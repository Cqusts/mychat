package com.easychat.ai;

import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.AiWorkflowTaskDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.AiWorkflowStageEnum;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.po.ChatMessage;
import com.easychat.service.AiChatService;
import com.easychat.service.AiStreamCallback;
import com.easychat.service.ChatMessageService;
import com.easychat.utils.StringTools;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 需求流水线编排引擎。
 *
 * 为什么需要它：早期版本让助手自己@下一个人来推进流程，实测第2棒就跳链——
 * 架构师无视了"写完@评审员"的指令，直接跳过评审和开发去@了测试。
 * 而且每个角色的上下文是"最近N条群消息"，会把上一个需求的内容串进来。
 *
 * 所以这里把两件事从模型手里拿走：
 *   1. 下一棒是谁 —— 由状态机决定，模型只负责产出内容
 *   2. 上下文是什么 —— 由任务对象精确提供，不再翻聊天记录
 *
 * 界面上看不出区别：助手照样一个个在群里发言，用户照样全程围观。
 */
@Component("aiWorkflowEngine")
public class AiWorkflowEngine {

    private static final Logger logger = LoggerFactory.getLogger(AiWorkflowEngine.class);

    /**
     * 评审结论的机器可读标记。让模型把结论放在开头，系统据此决定走向
     */
    private static final String REVIEW_PASS_MARK = "【通过】";

    private static final String REVIEW_REJECT_MARK = "【打回】";

    /**
     * 每个阶段都追加这句：流程由系统调度，模型不要自己@人，
     * 否则会把原有的自由对话链路也拉起来，两套调度打架
     */
    private static final String NO_MENTION_RULE =
            "\n注意：流程的下一棒由系统自动调度，你不需要、也不要@任何人。";

    @Value("${ai.workflow.enabled:true}")
    private Boolean workflowEnabled;

    @Value("${ai.workflow.agents.requirement:}")
    private String requirementAgentId;

    @Value("${ai.workflow.agents.design:}")
    private String designAgentId;

    @Value("${ai.workflow.agents.review:}")
    private String reviewAgentId;

    /**
     * 方案被打回后最多重做几次，超了就终止，避免评审和架构师无限拉锯
     */
    @Value("${ai.workflow.max-review-retry:2}")
    private Integer maxReviewRetry;

    @Value("${ai.workflow.task-expire-hours:24}")
    private Integer taskExpireHours;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private AiChatService aiChatService;

    @Resource
    private AiStreamPusher aiStreamPusher;

    @Resource(name = "aiTaskExecutor")
    private ThreadPoolTaskExecutor aiTaskExecutor;

    /**
     * ChatMessageService要用本引擎、本引擎又要用它，构成循环依赖，用@Lazy注入代理打破。
     *
     * 这里必须用@Autowired而不是@Resource：@Resource在字段名和bean名一致时
     * （这里正好都是chatMessageService）会直接按名getBean，绕过@Lazy代理，
     * 循环依赖就拦不住了——Spring Boot 2.6+默认禁止循环依赖，应用会直接起不来
     */
    @Lazy
    @Autowired
    private ChatMessageService chatMessageService;

    /**
     * 流水线是否可用：开关打开，且三个阶段的助手都配齐了
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(workflowEnabled)
                && !StringTools.isEmpty(requirementAgentId)
                && !StringTools.isEmpty(designAgentId)
                && !StringTools.isEmpty(reviewAgentId);
    }

    /**
     * 判断某个助手是不是流水线的入口。真人@他就等于提需求
     */
    public boolean isEntryAgent(String agentId) {
        return isEnabled() && requirementAgentId.equals(agentId);
    }

    /**
     * 启动一次需求流水线
     *
     * @param groupAgentIds 这个群里实际存在的助手，用来做缺席检查
     */
    public void startTask(String groupId, String sessionId, TokenUserInfoDto requester,
                          String requirement, List<String> groupAgentIds) {
        TokenUserInfoDto entryAgent = tokenOf(requirementAgentId);
        if (entryAgent == null) {
            logger.error("流水线入口助手未配置或不存在, agentId:{}", requirementAgentId);
            return;
        }

        //光@了人没说需求，问清楚再开始，不然模型会自己编一个需求出来
        if (StringTools.isEmpty(requirement)) {
            postAgentMessage(entryAgent, groupId,
                    "你想做什么需求？@我的时候把需求描述一起发过来，我这边接到就开始走流程。");
            return;
        }

        //缺席检查：少一个角色流程就走不完，与其跑到一半断掉，不如一开始就说清楚
        List<String> missing = findMissingAgents(groupAgentIds);
        if (!missing.isEmpty()) {
            String tip = "这个需求我接到了，但流程跑不完——群里还缺：" + String.join("、", missing)
                    + "。请群主到「群详情 → 助手」把他们拉进来，然后重新@我提一次。";
            logger.info("流水线助手缺席, groupId:{}, 缺少:{}", groupId, missing);
            postAgentMessage(entryAgent, groupId, tip);
            return;
        }

        AiWorkflowTaskDto task = new AiWorkflowTaskDto();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setGroupId(groupId);
        task.setSessionId(sessionId);
        task.setRequesterId(requester.getUserId());
        task.setRequesterNickName(requester.getNickName());
        task.setRequirement(requirement);
        task.setStage(AiWorkflowStageEnum.REQUIREMENT.name());
        task.setCreateTime(System.currentTimeMillis());
        saveTask(task);

        try {
            aiTaskExecutor.execute(() -> runTask(task.getTaskId()));
            logger.info("需求流水线已启动, taskId:{}, groupId:{}, 需求:{}",
                    task.getTaskId(), groupId, requirement);
        } catch (TaskRejectedException e) {
            logger.warn("AI线程池已满，流水线启动失败, groupId:{}", groupId);
            postAgentMessage(entryAgent, groupId, "现在活儿有点多，这个需求先没接住，过会儿再@我一次。");
        }
    }

    /**
     * 找出流水线需要、但不在这个群里的助手昵称
     */
    private List<String> findMissingAgents(List<String> groupAgentIds) {
        List<String> missing = new ArrayList<>();
        for (String agentId : new String[]{requirementAgentId, designAgentId, reviewAgentId}) {
            if (groupAgentIds != null && groupAgentIds.contains(agentId)) {
                continue;
            }
            AiAgentDefinition agent = aiAgentRegistry.getById(agentId);
            missing.add(agent == null ? agentId : agent.getName());
        }
        return missing;
    }

    /**
     * 状态机主循环。整个任务跑在一个线程池线程上，
     * 每个阶段都是一次阻塞的模型调用，所以一个任务可能占住线程好几分钟
     */
    private void runTask(String taskId) {
        AiWorkflowTaskDto task = loadTask(taskId);
        while (task != null) {
            AiWorkflowStageEnum stage;
            try {
                stage = AiWorkflowStageEnum.valueOf(task.getStage());
            } catch (Exception e) {
                logger.error("任务阶段非法, taskId:{}, stage:{}", taskId, task.getStage());
                return;
            }
            switch (stage) {
                case REQUIREMENT:
                    task = runRequirement(task);
                    break;
                case DESIGN:
                    task = runDesign(task);
                    break;
                case REVIEW:
                    task = runReview(task);
                    break;
                default:
                    //DONE / FAILED
                    return;
            }
        }
    }

    private AiWorkflowTaskDto runRequirement(AiWorkflowTaskDto task) {
        AiAgentDefinition agent = aiAgentRegistry.getById(requirementAgentId);
        String systemPrompt = personaOf(agent)
                + "\n你现在在一条需求流水线上工作，负责【需求分析】这一环。"
                + "请只输出需求分析本身：解决谁的什么问题、核心使用场景、优先级、边界（明确什么不做）。"
                + "控制在200字以内，用自然段落，不要罗列小标题。"
                + NO_MENTION_RULE;
        String userPrompt = "【原始需求】\n" + task.getRequirement() + "\n\n请输出你的需求分析。";

        String output = callAgent(agent, task, systemPrompt, userPrompt);
        if (output == null) {
            return failTask(task, "需求分析阶段调用失败");
        }
        task.setRequirementDoc(output);
        task.setStage(AiWorkflowStageEnum.DESIGN.name());
        saveTask(task);
        return task;
    }

    private AiWorkflowTaskDto runDesign(AiWorkflowTaskDto task) {
        AiAgentDefinition agent = aiAgentRegistry.getById(designAgentId);
        String systemPrompt = personaOf(agent)
                + "\n你现在在一条需求流水线上工作，负责【方案设计】这一环。"
                + "基于给出的需求分析，输出具体技术方案：涉及哪些模块和文件、数据结构怎么变、"
                + "接口怎么定、主要风险是什么。"
                + "项目技术栈是 Spring Boot 3 + Netty WebSocket + MySQL + MyBatis + Redis/Redisson"
                + " + Vue3/Electron，方案要落到这套栈上，不要泛泛而谈。"
                + "控制在300字以内。"
                + NO_MENTION_RULE;

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("【原始需求】\n").append(task.getRequirement()).append("\n\n");
        userPrompt.append("【需求分析】\n").append(task.getRequirementDoc()).append("\n\n");
        if (!StringTools.isEmpty(task.getReviewResult()) && Boolean.FALSE.equals(task.getReviewPassed())) {
            //被打回重做：把上一版方案和评审意见一起给它，否则它不知道要改什么
            userPrompt.append("【你上一版的方案】\n").append(task.getTechPlan()).append("\n\n");
            userPrompt.append("【评审意见，必须解决】\n").append(task.getReviewResult()).append("\n\n");
            userPrompt.append("请针对评审意见给出修改后的方案。");
        } else {
            userPrompt.append("请输出你的技术方案。");
        }

        String output = callAgent(agent, task, systemPrompt, userPrompt.toString());
        if (output == null) {
            return failTask(task, "方案设计阶段调用失败");
        }
        task.setTechPlan(output);
        task.setStage(AiWorkflowStageEnum.REVIEW.name());
        saveTask(task);
        return task;
    }

    private AiWorkflowTaskDto runReview(AiWorkflowTaskDto task) {
        AiAgentDefinition agent = aiAgentRegistry.getById(reviewAgentId);
        int round = task.getRetryCount() + 1;
        boolean firstRound = task.getReviewHistory() == null || task.getReviewHistory().isEmpty();

        StringBuilder systemPrompt = new StringBuilder(personaOf(agent));
        systemPrompt.append("\n你现在在一条需求流水线上工作，负责【方案评审】这一环。");
        systemPrompt.append("检查方案：技术上可行吗、有没有过度设计、漏了哪些场景、会不会和现有代码冲突。");
        systemPrompt.append("你的回复必须以").append(REVIEW_PASS_MARK).append("或").append(REVIEW_REJECT_MARK)
                .append("开头，这是系统判断流程走向的标记：以").append(REVIEW_PASS_MARK)
                .append("开头表示方案可以进入开发，以").append(REVIEW_REJECT_MARK)
                .append("开头表示必须返工，并明确写出要改哪里。");
        if (!firstRound) {
            //无界的评审天然总能挑出新毛病，不给收敛条件就永远通不过。
            //实测三轮全打回，而且第3轮把第2轮自己要求加的字段当成缺陷打了回去
            systemPrompt.append("这已经是第").append(round).append("轮评审了。");
            systemPrompt.append("你的判断重点是：前几轮你指出的问题，这一版有没有解决。");
            systemPrompt.append("只要前面的问题都改到位了就应该通过——");
            systemPrompt.append("即使你还能想到新的优化点，只要它不会导致功能不可用、数据出错或安全问题，");
            systemPrompt.append("就写成“后续建议”并给出").append(REVIEW_PASS_MARK).append("，不要再打回。");
            systemPrompt.append("另外不要推翻自己前几轮的意见，那会让方案来回改。");
            systemPrompt.append("评审的目的是把关，不是无限迭代。");
        }
        systemPrompt.append("不要和稀泥，方案有真正的硬伤就打回。控制在250字以内。");
        systemPrompt.append(NO_MENTION_RULE);

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("【原始需求】\n").append(task.getRequirement()).append("\n\n");
        userPrompt.append("【需求分析】\n").append(task.getRequirementDoc()).append("\n\n");
        if (!firstRound) {
            userPrompt.append("【你前几轮的评审意见】\n");
            List<String> history = task.getReviewHistory();
            for (int i = 0; i < history.size(); i++) {
                userPrompt.append("第").append(i + 1).append("轮：").append(history.get(i)).append("\n");
            }
            userPrompt.append("\n");
        }
        userPrompt.append("【待评审的技术方案】\n").append(task.getTechPlan()).append("\n\n");
        userPrompt.append(firstRound ? "请给出评审结论。"
                : "请判断上面的问题是否都已解决，并给出评审结论。");

        String output = callAgent(agent, task, systemPrompt.toString(), userPrompt.toString());
        if (output == null) {
            return failTask(task, "方案评审阶段调用失败");
        }
        task.setReviewResult(output);
        if (task.getReviewHistory() == null) {
            task.setReviewHistory(new ArrayList<>());
        }
        task.getReviewHistory().add(output);

        boolean passed = parseReviewVerdict(output);
        task.setReviewPassed(passed);

        if (passed) {
            task.setStage(AiWorkflowStageEnum.DONE.name());
            saveTask(task);
            postSummary(task, true);
            return null;
        }
        //打回：回到方案设计重做，超过次数就终止
        if (task.getRetryCount() >= maxReviewRetry) {
            logger.info("方案被打回{}次，达到上限，终止流程, taskId:{}", task.getRetryCount(), task.getTaskId());
            task.setStage(AiWorkflowStageEnum.FAILED.name());
            saveTask(task);
            postSummary(task, false);
            return null;
        }
        task.setRetryCount(task.getRetryCount() + 1);
        task.setStage(AiWorkflowStageEnum.DESIGN.name());
        saveTask(task);
        logger.info("方案被打回，第{}次返工, taskId:{}", task.getRetryCount(), task.getTaskId());
        return task;
    }

    /**
     * 解析评审结论。解析不出来时按通过处理并告警——
     * 按不通过处理会导致白白返工到上限，代价更大
     */
    private boolean parseReviewVerdict(String output) {
        if (output.contains(REVIEW_REJECT_MARK)) {
            return false;
        }
        if (output.contains(REVIEW_PASS_MARK)) {
            return true;
        }
        logger.warn("评审结论里没有找到{}或{}标记，按通过处理。原文开头：{}",
                REVIEW_PASS_MARK, REVIEW_REJECT_MARK,
                output.length() > 40 ? output.substring(0, 40) : output);
        return true;
    }

    /**
     * 调用某个助手：流式推给群里，同时把完整内容拿回来交给下一棒。
     * chatStreamOnce会阻塞到整轮结束，所以方法返回时holder里已经有结果了
     */
    private String callAgent(AiAgentDefinition agent, AiWorkflowTaskDto task,
                             String systemPrompt, String userPrompt) {
        if (agent == null) {
            return null;
        }
        TokenUserInfoDto agentToken = tokenOf(agent.getId());
        String streamId = UUID.randomUUID().toString().replace("-", "");
        AtomicInteger index = new AtomicInteger(0);
        String[] holder = new String[1];

        aiChatService.chatStreamOnce(systemPrompt, userPrompt, new AiStreamCallback() {
            @Override
            public void onChunk(String delta) {
                aiStreamPusher.push(agentToken, task.getGroupId(), task.getSessionId(),
                        MessageTypeEnum.AI_STREAM, streamId,
                        StringTools.resetMessageContent(delta), index.getAndIncrement());
            }

            @Override
            public void onComplete(String fullContent) {
                aiStreamPusher.push(agentToken, task.getGroupId(), task.getSessionId(),
                        MessageTypeEnum.AI_STREAM_END, streamId,
                        StringTools.resetMessageContent(fullContent), index.getAndIncrement());
                holder[0] = fullContent;
            }

            @Override
            public void onError(String errorMessage) {
                //群里不发错误消息刷屏，但日志要能定位
                aiStreamPusher.push(agentToken, task.getGroupId(), task.getSessionId(),
                        MessageTypeEnum.AI_STREAM_END, streamId, "", index.getAndIncrement());
                logger.error("流水线阶段调用失败, taskId:{}, agent:{}, reason:{}",
                        task.getTaskId(), agent.getName(), errorMessage);
                holder[0] = null;
            }
        });

        if (holder[0] != null) {
            postAgentMessage(agentToken, task.getGroupId(), holder[0]);
        }
        return holder[0];
    }

    /**
     * 流程收尾：由入口助手向提需求的人汇报
     */
    private void postSummary(AiWorkflowTaskDto task, boolean success) {
        TokenUserInfoDto entryAgent = tokenOf(requirementAgentId);
        if (entryAgent == null) {
            return;
        }
        String at = StringTools.isEmpty(task.getRequesterNickName())
                ? "" : "@" + task.getRequesterNickName() + " ";
        String summary;
        if (success) {
            summary = at + "需求「" + task.getRequirement() + "」已经走完需求分析和方案评审，方案评审通过。"
                    + (task.getRetryCount() > 0 ? "（中途返工了" + task.getRetryCount() + "次）" : "")
                    + "详细内容看上面几条发言。";
        } else {
            //retryCount是返工次数，评审实际跑了retryCount+1轮
            summary = at + "需求「" + task.getRequirement() + "」的方案连续" + (task.getRetryCount() + 1)
                    + "轮没通过评审，流程先停在这里。建议你看看评审意见，把需求或约束再明确一下重新提。";
        }
        postAgentMessage(entryAgent, task.getGroupId(), summary);
    }

    private AiWorkflowTaskDto failTask(AiWorkflowTaskDto task, String reason) {
        logger.error("流水线终止, taskId:{}, reason:{}", task.getTaskId(), reason);
        task.setStage(AiWorkflowStageEnum.FAILED.name());
        saveTask(task);
        TokenUserInfoDto entryAgent = tokenOf(requirementAgentId);
        if (entryAgent != null) {
            String at = StringTools.isEmpty(task.getRequesterNickName())
                    ? "" : "@" + task.getRequesterNickName() + " ";
            postAgentMessage(entryAgent, task.getGroupId(),
                    at + "抱歉，处理这个需求时出错了，流程中断。可以过一会儿重新@我试试。");
        }
        return null;
    }

    /**
     * 助手在群里发言并落库。
     * 走saveWorkflowMessage而不是saveMessage：流程的下一棒由状态机决定，
     * 不能让发言里可能出现的@把自由对话链路也拉起来
     */
    private void postAgentMessage(TokenUserInfoDto agentToken, String groupId, String content) {
        try {
            ChatMessage message = new ChatMessage();
            message.setContactId(groupId);
            message.setMessageContent(content);
            message.setMessageType(MessageTypeEnum.CHAT.getType());
            chatMessageService.saveWorkflowMessage(message, agentToken);
        } catch (Exception e) {
            //以前这里只记日志，结果表现是"群里凭空少了一条发言"，但流程还在往下走，
            //下一棒明明看过内容、用户却看不到，极难排查。现在同时在群里留一句可见的提示
            logger.error("流水线消息落库失败, groupId:{}, agentId:{}, 内容长度:{}",
                    groupId, agentToken.getUserId(), content == null ? 0 : content.length(), e);
            try {
                ChatMessage tip = new ChatMessage();
                tip.setContactId(groupId);
                tip.setMessageContent("（这条发言没能存下来，通常是内容超出了 chat_message.message_content 的长度限制，"
                        + "详见服务端日志）");
                tip.setMessageType(MessageTypeEnum.CHAT.getType());
                chatMessageService.saveWorkflowMessage(tip, agentToken);
            } catch (Exception ignore) {
                logger.error("连兜底提示也没能发出去, groupId:{}", groupId, ignore);
            }
        }
    }

    private String personaOf(AiAgentDefinition agent) {
        return agent == null || agent.getPrompt() == null ? "" : agent.getPrompt();
    }

    private TokenUserInfoDto tokenOf(String agentId) {
        AiAgentDefinition agent = aiAgentRegistry.getById(agentId);
        if (agent == null) {
            return null;
        }
        TokenUserInfoDto token = new TokenUserInfoDto();
        token.setUserId(agent.getId());
        token.setNickName(agent.getName());
        return token;
    }

    private void saveTask(AiWorkflowTaskDto task) {
        try {
            RBucket<AiWorkflowTaskDto> bucket =
                    redissonClient.getBucket(Constants.REDIS_KEY_AI_TASK + task.getTaskId());
            bucket.set(task, Duration.ofHours(taskExpireHours));
        } catch (Exception e) {
            logger.error("保存流水线任务失败, taskId:{}", task.getTaskId(), e);
        }
    }

    private AiWorkflowTaskDto loadTask(String taskId) {
        try {
            RBucket<AiWorkflowTaskDto> bucket =
                    redissonClient.getBucket(Constants.REDIS_KEY_AI_TASK + taskId);
            return bucket.get();
        } catch (Exception e) {
            logger.error("读取流水线任务失败, taskId:{}", taskId, e);
            return null;
        }
    }
}
