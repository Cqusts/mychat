package com.mychat.ai;

import com.mychat.entity.constants.Constants;
import com.mychat.ai.eval.AiEvalRecorder;
import com.mychat.entity.dto.AiWorkflowTaskDto;
import com.mychat.entity.dto.PlanChangeDto;
import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.enums.AiWorkflowStageEnum;
import com.mychat.entity.enums.MessageTypeEnum;
import com.mychat.entity.po.ChatMessage;
import com.mychat.service.AiChatService;
import com.mychat.service.AiStreamCallback;
import com.mychat.service.ChatMessageService;
import com.mychat.utils.StringTools;
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
    /**
     * 项目结构地图，注入编码阶段的system prompt。
     *
     * 评测数据显示：需求里点名了类/表的4条通过3.5条，只描述行为、要自己定位的6条全灭。
     * 与其让模型从零摸索分层约定，不如开局就把地图给它——这几行的成本，
     * 比它用十几次工具调用去试出来便宜得多
     */
    private static final String PROJECT_MAP =
            "\n\n【项目结构】后端 mychat-java，前端 mychat-front，建表脚本 mychat.sql。"
                    + "后端是标准分层，加一个功能通常要一路动下来："
                    + "\n  controller/       接口入口和参数校验"
                    + "\n  service/ + service/impl/   业务逻辑"
                    + "\n  mappers/          Mapper接口"
                    + "\n  resources/com/mychat/mappers/*.xml   真正的SQL"
                    + "\n  entity/po/        数据库实体   entity/query/  查询条件"
                    + "\n  entity/dto/       传输对象     entity/vo/     返回给前端的对象"
                    + "\n  entity/enums/     枚举         entity/constants/Constants  常量"
                    + "\n  websocket/        Netty长连接与消息广播"
                    + "\n改数据库字段要同时改：entity/po 里的实体、对应的 Mapper.xml、以及 mychat.sql。"
                    + "\n命名是对应的：会话=ChatSession，消息=ChatMessage，好友/联系人=UserContact，"
                    + "群=GroupInfo，用户=UserInfo。";

    /**
     * 方案末尾必须带的两个结构化小节。
     *
     * 格式是给正则解析用的，所以要求写死。解析失败就降级成纯文本方案，
     * 不会把流程卡住——但那样编码阶段又得自己找文件，也就回到了老问题
     */
    private static final String PLAN_FORMAT =
            "\n\n正文之后必须附上两个小节，格式严格照抄，不要改标题也不要加别的符号：\n"
                    + "【改动清单】\n"
                    + "- 文件路径 | 动作 | 具体改什么\n"
                    + "- 文件路径 | 动作 | 具体改什么\n"
                    + "【验收标准】\n"
                    + "- 一句话描述一个可验证的行为\n"
                    + "- 一句话描述一个可验证的行为\n"
                    + "改动清单里的路径必须是相对仓库根目录的完整路径，"
                    + "比如 mychat-java/src/main/java/com/mychat/service/impl/ChatSessionServiceImpl.java；"
                    + "改一个后端功能通常要同时列出 Controller、Service、Mapper接口 和 Mapper.xml。"
                    + "验收标准要写成能用单元测试判定的行为，不要写「代码质量好」这种没法验证的话。";

    private static final String NO_MENTION_RULE =
            "\n注意：流程的下一棒由系统自动调度，你不需要、也不要@任何人。";

    /**
     * 带工具的阶段轮次多、上下文长，模型很容易滑到英文去
     * （程序员助手那一轮整篇都是英文，用户根本看不懂它在干什么）。
     * 这条约束要写死在每个阶段的system prompt里，光靠人设是中文压不住
     */
    private static final String CHINESE_RULE =
            "\n全程用中文表达：中间的思考、调用工具前后的说明、最后的总结，都必须是中文。"
                    + "只有代码本身、文件路径、报错原文保持原样。";

    /**
     * 每个阶段的system prompt末尾都要带上的通用约束
     */
    private static final String STAGE_RULES = NO_MENTION_RULE + CHINESE_RULE;

    /**
     * 发到群里的报错节选长度。chat_message虽然改成了text，但刷屏一样没法看
     */
    private static final int MAX_ERROR_EXCERPT = 800;

    @Value("${ai.coder.max-tool-calls:60}")
    private Integer maxToolCalls;

    @Value("${ai.coder.stage-deadline-minutes:20}")
    private Integer stageDeadlineMinutes;

    @Value("${ai.coder.stall-limit:20}")
    private Integer stallLimit;

    /**
     * 方案阶段是否允许查看真实代码。单独开关是为了能做A/B对照
     */
    @Value("${ai.workflow.plan-with-code:true}")
    private Boolean planWithCodeEnabled;

    @Value("${ai.workflow.plan-tool-calls:15}")
    private Integer planToolCalls;

    @Value("${ai.workflow.enabled:true}")
    private Boolean workflowEnabled;

    @Value("${ai.workflow.agents.requirement:}")
    private String requirementAgentId;

    @Value("${ai.workflow.agents.design:}")
    private String designAgentId;

    @Value("${ai.workflow.agents.review:}")
    private String reviewAgentId;

    @Value("${ai.workflow.agents.coding:}")
    private String codingAgentId;

    @Value("${ai.workflow.agents.testing:}")
    private String testingAgentId;

    /**
     * 写代码要反复搜索、读文件、编译，和聊天用同一个超时根本不够
     */
    @Value("${ai.coder.agent-timeout-seconds:900}")
    private Long coderTimeoutSeconds;

    /**
     * 引擎最终编译不过时，再给模型几轮带着报错去修的机会
     */
    @Value("${ai.coder.max-fix-rounds:1}")
    private Integer maxFixRounds;

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

    @Resource
    private CoderWorkspace coderWorkspace;

    //流水线用自己的池，不和秒级的聊天回复挤在一起，
    //否则一条跑几十分钟的编码任务会把普通用户的消息堵在队列后面
    @Resource(name = "aiWorkflowExecutor")
    private ThreadPoolTaskExecutor aiTaskExecutor;

    @Resource
    private AiTaskControl aiTaskControl;

    @Resource
    private AiEvalRecorder aiEvalRecorder;

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
    /**
     * @return 任务ID；没能启动（缺助手、需求为空、线程池满）时返回null
     */
    public String startTask(String groupId, String sessionId, TokenUserInfoDto requester,
                            String requirement, List<String> groupAgentIds) {
        TokenUserInfoDto entryAgent = tokenOf(requirementAgentId);
        if (entryAgent == null) {
            logger.error("流水线入口助手未配置或不存在, agentId:{}", requirementAgentId);
            return null;
        }

        //光@了人没说需求，问清楚再开始，不然模型会自己编一个需求出来
        if (StringTools.isEmpty(requirement)) {
            postAgentMessage(entryAgent, groupId,
                    "你想做什么需求？@我的时候把需求描述一起发过来，我这边接到就开始走流程。");
            return null;
        }

        //缺席检查：少一个角色流程就走不完，与其跑到一半断掉，不如一开始就说清楚
        List<String> missing = findMissingAgents(groupAgentIds);
        if (!missing.isEmpty()) {
            String tip = "这个需求我接到了，但流程跑不完——群里还缺：" + String.join("、", missing)
                    + "。请群主到「群详情 → 助手」把他们拉进来，然后重新@我提一次。";
            logger.info("流水线助手缺席, groupId:{}, 缺少:{}", groupId, missing);
            postAgentMessage(entryAgent, groupId, tip);
            return null;
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

        aiTaskControl.register(task.getTaskId(), groupId, requester.getUserId());
        try {
            aiTaskExecutor.execute(() -> runTask(task.getTaskId()));
            logger.info("需求流水线已启动, taskId:{}, groupId:{}, 需求:{}",
                    task.getTaskId(), groupId, requirement);
            return task.getTaskId();
        } catch (TaskRejectedException e) {
            aiTaskControl.finish(task.getTaskId());
            logger.warn("流水线线程池已满，启动失败, groupId:{}", groupId);
            postAgentMessage(entryAgent, groupId, "现在活儿有点多，这个需求先没接住，过会儿再@我一次。");
            return null;
        }
    }

    /**
     * 任务是否还在跑。评测跑批靠它串行等待，不然多条并发会互相干扰计时
     */
    public boolean isTaskRunning(String taskId) {
        return aiTaskControl.get(taskId) != null;
    }

    /**
     * 终态归档：记结束时间和失败原因，然后落一条评测记录。
     * 没开 ai.eval.enabled 时 recorder 内部直接返回，等于没有这一步
     */
    private void archive(AiWorkflowTaskDto task, String failReason) {
        task.setEndTime(System.currentTimeMillis());
        if (failReason != null) {
            task.setFailReason(failReason);
        }
        aiEvalRecorder.record(task);
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
     * 停掉某个群里正在跑的流水线。
     *
     * 真正的收尾（发消息、改状态）由任务线程自己在察觉到取消后完成，
     * 这里只负责置标记和中断——不然会出现两个线程同时往群里发收尾消息
     *
     * @return 停掉了几个任务
     */
    public int stopGroupTasks(String groupId, String operatorId) {
        return aiTaskControl.cancelByGroup(groupId, operatorId);
    }

    /**
     * 这个群里有没有正在跑的流水线，前端据此决定显不显示停止按钮
     */
    public boolean hasRunningTask(String groupId) {
        return !aiTaskControl.runningInGroup(groupId).isEmpty();
    }

    /**
     * 状态机主循环。整个任务跑在一个线程池线程上，
     * 每个阶段都是一次阻塞的模型调用，所以一个任务可能占住线程好几分钟
     */
    private void runTask(String taskId) {
        //把当前线程登记上去，用户点停止时才有东西可中断
        aiTaskControl.bindWorker(taskId);
        try {
            loopStages(taskId);
        } finally {
            aiTaskControl.finish(taskId);
        }
    }

    private void loopStages(String taskId) {
        AiWorkflowTaskDto task = loadTask(taskId);
        while (task != null) {
            if (aiTaskControl.isCancelled(taskId)) {
                abortByUser(task);
                return;
            }
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
                case CODING:
                    task = runCoding(task);
                    break;
                case TESTING:
                    task = runTesting(task);
                    break;
                default:
                    //DONE / FAILED
                    return;
            }
        }
    }

    /**
     * 每个阶段一套独立预算：这一轮调爆了不影响下一轮
     */
    private CoderTools newCoderTools(AiWorkflowTaskDto task) {
        return new CoderTools(coderWorkspace, null, new ToolBudget(aiTaskControl,
                task.getTaskId(), maxToolCalls, stageDeadlineMinutes, stallLimit));
    }

    private boolean cancelled(AiWorkflowTaskDto task) {
        return aiTaskControl.isCancelled(task.getTaskId());
    }

    /**
     * 用户点了停止之后的收尾。
     *
     * 第一件事是把线程的中断状态清掉：停止是靠 interrupt 兜底的，
     * 而中断可能正好落在Redis或HTTP调用上，不清掉的话连"已停止"这条消息都发不出去，
     * 用户会看到点了没反应
     */
    private void abortByUser(AiWorkflowTaskDto task) {
        Thread.interrupted();
        String stageDesc = stageDescOf(task.getStage());
        task.setStage(AiWorkflowStageEnum.CANCELLED.name());
        try {
            saveTask(task);
        } catch (Exception e) {
            logger.warn("停止后保存任务状态失败, taskId:{}", task.getTaskId(), e);
        }
        TokenUserInfoDto entryAgent = tokenOf(requirementAgentId);
        if (entryAgent != null) {
            postAgentMessage(entryAgent, task.getGroupId(),
                    atRequester(task) + "已停止。需求「" + task.getRequirement() + "」的流程停在【"
                            + stageDesc + "】，没有提交也没有推送任何代码。"
                            + "想继续的话重新@我提一次。");
        }
        archive(task, "用户停止(" + stageDesc + ")");
        logger.info("流水线被用户停止, taskId:{}, 停在:{}", task.getTaskId(), stageDesc);
    }

    private String stageDescOf(String stage) {
        try {
            return AiWorkflowStageEnum.valueOf(stage).getDesc();
        } catch (Exception e) {
            return stage;
        }
    }

    private AiWorkflowTaskDto runRequirement(AiWorkflowTaskDto task) {
        AiAgentDefinition agent = aiAgentRegistry.getById(requirementAgentId);
        String systemPrompt = personaOf(agent)
                + "\n你现在在一条需求流水线上工作，负责【需求分析】这一环。"
                + "请只输出需求分析本身：解决谁的什么问题、核心使用场景、优先级、边界（明确什么不做）。"
                + "控制在200字以内，用自然段落，不要罗列小标题。"
                + STAGE_RULES;
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
        boolean canExplore = planWithCodeEnabled && coderWorkspace.isEnabled();

        String systemPrompt = personaOf(agent)
                + "\n你现在在一条需求流水线上工作，负责【方案设计】这一环。"
                + (canExplore ? PROJECT_MAP
                        + "\n先用 findFiles 把需求丢进去看看真实代码，再用 outline 确认关键文件的结构，"
                        + "拿不准现有实现时才 readFiles。看过代码再写方案，不要凭想象。"
                        : "")
                + "\n输出具体技术方案：数据结构怎么变、接口怎么定、主要风险是什么。"
                + "项目技术栈是 Spring Boot 3 + Netty WebSocket + MySQL + MyBatis + Redis/Redisson"
                + " + Vue3/Electron，方案要落到这套栈上，不要泛泛而谈。"
                + "正文控制在300字以内。"
                + PLAN_FORMAT
                + STAGE_RULES;

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

        String output;
        if (canExplore) {
            //让架构师能看真实代码。工具是只读的：方案还没过评审就动文件，
            //被打回时收不回来，职责也乱了
            CodeExplorerTools explorer = new CodeExplorerTools(coderWorkspace,
                    new ToolBudget(aiTaskControl, task.getTaskId(),
                            planToolCalls, stageDeadlineMinutes, stallLimit));
            output = callAgent(agent, task, systemPrompt, userPrompt.toString(),
                    explorer, coderTimeoutSeconds);
        } else {
            output = callAgent(agent, task, systemPrompt, userPrompt.toString());
        }
        if (output == null) {
            return failTask(task, "方案设计阶段调用失败");
        }
        task.setTechPlan(output);
        //把方案里的改动清单和验收标准抽成结构化数据，
        //编码阶段直接拿文件清单动手，不用再自己找
        List<PlanChangeDto> changes = TechPlanParser.parseChanges(
                output, canExplore ? coderWorkspace.getCodeIndex() : null);
        List<String> acceptance = TechPlanParser.parseAcceptance(output);
        task.setPlanChanges(changes);
        task.setAcceptance(acceptance);
        TechPlanParser.logQuality(task.getTaskId(), changes, acceptance);
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
        systemPrompt.append(STAGE_RULES);

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
            //编码环节没启用（或没配好）就到此为止，前三步本身已经是完整的产出
            if (!coderWorkspace.isEnabled() || aiAgentRegistry.getById(codingAgentId) == null) {
                task.setStage(AiWorkflowStageEnum.DONE.name());
                saveTask(task);
                postSummary(task, true);
                return null;
            }
            task.setStage(AiWorkflowStageEnum.CODING.name());
            saveTask(task);
            return task;
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
     * 编码阶段：让程序员助手在独立工作区里真改代码，编译通过后推到 ai/ 分支。
     *
     * 和前三个阶段最大的不同是这里会落到磁盘和git上，所以：
     *   - 提交推送由引擎决定，模型没有这个工具，它只能改文件和编译
     *   - 编译不通过绝不推送
     */
    private AiWorkflowTaskDto runCoding(AiWorkflowTaskDto task) {
        AiAgentDefinition agent = aiAgentRegistry.getById(codingAgentId);

        //环境问题在环境这一层拦掉。交给模型去猜的代价是实打实踩过的：
        //机器上没有mvn，它反复搜"怎么编译"刷了一千七百多次工具调用才被人工掐断
        String toolchainProblem = coderWorkspace.checkToolchain();
        if (toolchainProblem != null) {
            logger.error("编码环境不可用, taskId:{}, 原因:{}", task.getTaskId(), toolchainProblem);
            task.setStage(AiWorkflowStageEnum.FAILED.name());
            saveTask(task);
            postAgentMessage(tokenOf(requirementAgentId), task.getGroupId(),
                    atRequester(task) + "方案评审通过了，但编码环节起不来：" + toolchainProblem
                            + "\n流程停在这里，没有动任何代码。");
            archive(task, "编码环境不可用");
            return null;
        }

        String branch = "ai/task-" + task.getTaskId().substring(0, 8);

        try {
            coderWorkspace.prepareBranch(branch);
            //工作区准备好了才算真的进了编码阶段。
            //评测里 enteredCoding 是靠 codeBranch 判断的，提前赋值会把
            //"clone 都没拉下来"的任务也算进"编译一次通过率"的分母，把这个指标稀释掉
            task.setCodeBranch(branch);
        } catch (Exception e) {
            logger.error("准备代码工作区失败, taskId:{}", task.getTaskId(), e);
            return failTask(task, "准备代码工作区失败：" + e.getMessage());
        }

        TokenUserInfoDto agentToken = tokenOf(codingAgentId);
        CoderTools tools = newCoderTools(task);
        boolean hasPlanChanges = task.getPlanChanges() != null && !task.getPlanChanges().isEmpty();

        //这一步要读文件、改文件、跑maven，动辄好几分钟，
        //群里不说一声的话看起来就像流程卡死了
        postAgentMessage(agentToken, task.getGroupId(),
                "方案通过了，我开始改代码。工作分支：" + branch + "，写完会编译验证再推。");

        String systemPrompt = personaOf(agent)
                + "\n你现在在一条需求流水线上工作，负责【编码实现】这一环，要真的改代码。"
                + PROJECT_MAP
                + (hasPlanChanges
                        ? "\n方案里已经给出了改动清单，直接照着做，不要再花工具调用去找文件："
                                + "\n1. 用 readFiles 一次把清单里的文件全读完；"
                                + "\n2. 想清楚全部改动之后，用 applyEdits 一次性提交；"
                                + "\n3. 调 compile 验证，不通过就根据报错继续修。"
                                + "\n只有清单明显遗漏了文件时才用 findFiles 补，不要一上来就重新找一遍。"
                        : "\n固定的工作顺序，别跳步："
                                + "\n1. 先调 findFiles，把需求原话直接丢进去，它会按相关度给出最该改的文件；"
                                + "\n2. 对候选文件调 outline 看骨架，确认是不是要找的那个（比 readFile 省很多）；"
                                + "\n3. 选定之后用 readFiles 一次把要改的几个文件全读完，不要一个一个读；"
                                + "\n4. 想清楚全部改动之后，用 applyEdits 一次性提交；"
                                + "\n5. 最后调 compile 验证，不通过就根据报错继续修。")
                + "\n为什么强调一次性：你每调一次工具，前面所有的对话历史都要重新发一遍，"
                + "第20轮的开销是第2轮的十倍。轮次翻倍，成本翻四倍。"
                + "跨文件的改动一次提交，比分十次提交便宜一个数量级，也更容易在预算内做完。"
                + "applyEdits 是全成功或全不改的，有对不上的地方会把所有问题一次性告诉你，"
                + "不会出现改到一半的中间状态。"
                + "\n注意几点：改动要小而准，只做方案要求的事，不要顺手重构无关代码；"
                + "oldText 只要能唯一定位就行，换行符和缩进工具会自动兼容，"
                + "如果提示没找到，按它回显的原文重试一次即可，不要在同一处反复试；"
                + "不要编造项目里不存在的类或方法，拿不准就先 outline 或 readFile 确认。"
                + "你只有60次工具调用的预算，别把它耗在漫无目的的搜索上——"
                + "findFiles 一次就该让你知道去哪，如果两次都没定位到，"
                + "直接说明卡在哪里并结束，不要硬试。"
                + "全部改完并编译通过后，用一段话说明你改了哪些文件、每个文件做了什么。"
                + STAGE_RULES;

        String userPrompt = "【原始需求】\n" + task.getRequirement() + "\n\n"
                + "【需求分析】\n" + task.getRequirementDoc() + "\n\n"
                + "【评审通过的技术方案】\n" + task.getTechPlan() + "\n\n"
                + TechPlanParser.renderForCoder(task.getPlanChanges())
                + "\n【评审意见，实现时要一并满足】\n" + task.getReviewResult() + "\n\n"
                + "请按方案改代码。";

        String output = callAgent(agent, task, systemPrompt, userPrompt, tools, coderTimeoutSeconds);
        //停止之后绝不能继续走到提交推送，所以这里要在编译和push之前拦一道
        if (cancelled(task)) {
            abortByUser(task);
            return null;
        }
        if (output == null) {
            return failTask(task, "编码阶段调用失败");
        }

        //模型可能一顿分析但一个文件都没动，这种情况必须识别出来，不能当成功。
        //判断依据只看git的真实状态，不看模型自己怎么说
        boolean hasChanges;
        try {
            hasChanges = coderWorkspace.hasChanges();
        } catch (Exception e) {
            logger.error("检查工作区改动失败, taskId:{}", task.getTaskId(), e);
            hasChanges = true;
        }
        if (!hasChanges) {
            task.setStage(AiWorkflowStageEnum.FAILED.name());
            task.setCodePushed(false);
            saveTask(task);
            String why = tools.getBudget().isStopped()
                    ? "本轮被熔断了（" + tools.getBudget().getStopReason() + "，共"
                            + tools.getBudget().getCalls() + "次工具调用），多半是它卡在了某个解不开的问题上。"
                    : "助手一共调用了" + tools.getChangedFileCount() + "次写文件工具，"
                            + "通常是方案还不够具体，或者它没定位到该改的文件。";
            postAgentMessage(tokenOf(requirementAgentId), task.getGroupId(),
                    atRequester(task) + "编码环节没有产生任何代码改动，流程停在这里。" + why);
            archive(task, tools.getBudget().isStopped()
                    ? "编码被熔断(" + tools.getBudget().getStopReason() + ")" : "编码零改动");
            return null;
        }

        //把"到底动了哪些文件"单独发一条。模型的自述可能含糊甚至虚构，
        //这条是从git统计出来的，用户可以直接拿它对账
        try {
            task.setCodeDiffStat(coderWorkspace.diffStat());
            if (!StringTools.isEmpty(task.getCodeDiffStat())) {
                postAgentMessage(agentToken, task.getGroupId(),
                        "本轮实际改动（git 统计）：\n" + task.getCodeDiffStat());
            }
        } catch (Exception e) {
            logger.error("取改动概览失败, taskId:{}", task.getTaskId(), e);
        }

        //引擎自己再编译一次把关：模型有可能说"编译通过"但其实没跑过compile
        String compileError = verifyCompile(agent, task, tools);
        if (compileError != null) {
            task.setStage(AiWorkflowStageEnum.FAILED.name());
            task.setCodePushed(false);
            saveTask(task);
            postAgentMessage(tokenOf(requirementAgentId), task.getGroupId(),
                    atRequester(task) + "代码改完了但编译不通过，已经放弃推送，避免把编不过的代码推上去。"
                            + "改动还留在工作区分支 " + branch + " 上，没有提交。\n报错节选：\n"
                            + tailOf(compileError, MAX_ERROR_EXCERPT));
            archive(task, "编译不通过");
            return null;
        }

        try {
            coderWorkspace.commitAndPush(branch,
                    "feat: " + task.getRequirement() + "\n\n由MyChat需求流水线自动生成，taskId:" + task.getTaskId());
            task.setCodePushed(true);
        } catch (Exception e) {
            logger.error("提交推送失败, taskId:{}, branch:{}", task.getTaskId(), branch, e);
            task.setCodePushed(false);
            saveTask(task);
            postAgentMessage(tokenOf(requirementAgentId), task.getGroupId(),
                    atRequester(task) + "代码写完也编译通过了，但推送分支失败：" + e.getMessage());
            archive(task, "推送失败");
            return null;
        }

        //代码已经推上去了，接着让测试工程师写用例验证
        if (aiAgentRegistry.getById(testingAgentId) == null) {
            task.setStage(AiWorkflowStageEnum.DONE.name());
            saveTask(task);
            postSummary(task, true);
            return null;
        }
        task.setStage(AiWorkflowStageEnum.TESTING.name());
        saveTask(task);
        return task;
    }

    /**
     * 测试阶段：让测试工程师看实际代码改动、补JUnit用例并真跑起来。
     *
     * 这一步失败不算整个任务失败——代码已经编译通过并推送了，
     * 测试没写好只是少了一层保障，不该把前面的成果一起判死
     */
    private AiWorkflowTaskDto runTesting(AiWorkflowTaskDto task) {
        AiAgentDefinition agent = aiAgentRegistry.getById(testingAgentId);
        CoderTools tools = newCoderTools(task);

        String systemPrompt = personaOf(agent)
                + "\n你现在在一条需求流水线上工作，负责【测试验证】这一环。"
                + "程序员刚改完代码并推到了分支，你要做的是："
                + "先用 findFiles 或 searchCode 配合 readFile 看清楚这次实际改了什么（看代码，不要只看方案）；"
                + "然后针对改动写JUnit测试，放在 mychat-java/src/test/java/ 下对应的包里；"
                + "写完调用 runTests 跑起来，不通过就修，直到通过。"
                + "重要约束：只测这次改动相关的逻辑，不要给整个项目补测试；"
                + "优先测不依赖外部环境的纯逻辑，需要数据库、Redis或Spring容器的地方用Mockito打桩，"
                + "不要写必须连上真实MySQL/Redis才能跑的测试——测试环境里没有这些。"
                + "最后用一段话说明：你测了哪些场景、结果如何、还有什么风险没覆盖到。"
                + STAGE_RULES;

        String userPrompt = "【原始需求】\n" + task.getRequirement() + "\n\n"
                + "【技术方案】\n" + task.getTechPlan() + "\n\n"
                + "【这次代码改动的文件】\n"
                + (StringTools.isEmpty(task.getCodeDiffStat()) ? "（未取到改动列表，请自己搜索定位）"
                        : task.getCodeDiffStat()) + "\n\n"
                + "请补充测试并验证。";

        String output = callAgent(agent, task, systemPrompt, userPrompt, tools, coderTimeoutSeconds);
        if (cancelled(task)) {
            abortByUser(task);
            return null;
        }
        boolean testsOk = false;
        try {
            CoderWorkspace.ExecResult result = coderWorkspace.runTests();
            testsOk = result.success();
            if (!testsOk) {
                logger.warn("测试未通过, taskId:{}, 输出:\n{}", task.getTaskId(), result.output);
            }
        } catch (Exception e) {
            logger.error("运行测试失败, taskId:{}", task.getTaskId(), e);
        }
        task.setTestsPassed(testsOk);

        //测试文件也要提交上去，否则白写了
        try {
            if (coderWorkspace.hasChanges()) {
                coderWorkspace.commitAndPush(task.getCodeBranch(),
                        "test: 为「" + task.getRequirement() + "」补充单元测试\n\n由MyChat需求流水线自动生成");
            }
        } catch (Exception e) {
            logger.error("推送测试代码失败, taskId:{}", task.getTaskId(), e);
        }

        task.setStage(AiWorkflowStageEnum.DONE.name());
        saveTask(task);
        postSummary(task, true);
        return null;
    }

    /**
     * 引擎侧的编译把关。不通过就把报错回传给模型再修几轮。
     *
     * @return 编译通过返回null，否则返回最后一次的报错内容，供群里如实告知
     */
    private String verifyCompile(AiAgentDefinition agent, AiWorkflowTaskDto task, CoderTools tools) {
        for (int round = 0; round <= maxFixRounds; round++) {
            CoderWorkspace.ExecResult result;
            try {
                result = coderWorkspace.compile();
            } catch (Exception e) {
                logger.error("编译执行失败, taskId:{}", task.getTaskId(), e);
                return "编译命令没能执行起来：" + e.getMessage();
            }
            if (round == 0) {
                //第0轮就过=模型自己写对了，这个数才是编码Agent的真实水平；
                //后面几轮是引擎把报错怼回去逼它改出来的，不能算
                task.setFirstCompilePass(result.success());
            }
            if (result.success()) {
                return null;
            }
            if (round == maxFixRounds) {
                logger.error("编译始终不通过，放弃推送, taskId:{}, 报错:\n{}", task.getTaskId(), result.output);
                return result.output;
            }
            logger.info("编译不通过，让程序员助手再修一轮, taskId:{}, 第{}轮", task.getTaskId(), round + 1);
            String fixPrompt = "你刚才的改动编译不通过，报错如下：\n" + result.output
                    + "\n\n请定位问题并修好，改完再调用 compile 确认。";
            callAgent(agent, task, personaOf(agent)
                    + "\n你在修复自己刚才改出来的编译错误。只改导致报错的地方，不要顺手改别的。"
                    + STAGE_RULES, fixPrompt, tools, coderTimeoutSeconds);
        }
        return "编译未通过";
    }

    /**
     * maven的报错往往几百行，真正有用的在末尾，掐尾巴发到群里就够定位了
     */
    private String tailOf(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed
                : "……\n" + trimmed.substring(trimmed.length() - maxChars);
    }

    private String atRequester(AiWorkflowTaskDto task) {
        return StringTools.isEmpty(task.getRequesterNickName())
                ? "" : "@" + task.getRequesterNickName() + " ";
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
        return callAgent(agent, task, systemPrompt, userPrompt, null, null);
    }

    private String callAgent(AiAgentDefinition agent, AiWorkflowTaskDto task, String systemPrompt,
                             String userPrompt, Object tools, Long timeoutSeconds) {
        if (agent == null) {
            return null;
        }
        TokenUserInfoDto agentToken = tokenOf(agent.getId());
        String streamId = UUID.randomUUID().toString().replace("-", "");
        AtomicInteger index = new AtomicInteger(0);
        String[] holder = new String[1];

        AiStreamCallback callback = new AiStreamCallback() {
            @Override
            public void onChunk(String delta) {
                aiStreamPusher.push(agentToken, task.getGroupId(), task.getSessionId(),
                        MessageTypeEnum.AI_STREAM, streamId,
                        StringTools.resetMessageContent(delta), index.getAndIncrement());
            }

            @Override
            public void onToolCall(String toolHint) {
                //接口里onToolCall是default空实现，这里以前没覆写，
                //结果CoderTools里"正在修改XXX.java…""正在编译…"全都推到了空气里，
                //用户在群里只能干等，完全不知道助手到底动没动代码
                aiStreamPusher.push(agentToken, task.getGroupId(), task.getSessionId(),
                        MessageTypeEnum.AI_TOOL_CALL, streamId,
                        toolHint, index.getAndIncrement());
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
        };
        if (tools == null) {
            aiChatService.chatStreamOnce(systemPrompt, userPrompt, callback);
        } else {
            //工具里的进度提示要能推到群里，所以callback建好之后再塞给CoderTools
            if (tools instanceof CoderTools) {
                ((CoderTools) tools).bindCallback(callback);
            }
            aiChatService.chatStreamAgent(systemPrompt, userPrompt, tools, timeoutSeconds, callback);
        }

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
            StringBuilder sb = new StringBuilder(at);
            sb.append("需求「").append(task.getRequirement()).append("」已完成。方案评审通过");
            if (task.getRetryCount() > 0) {
                sb.append("（中途返工").append(task.getRetryCount()).append("次）");
            }
            sb.append("。");
            if (Boolean.TRUE.equals(task.getCodePushed())) {
                sb.append("代码已编译通过并推送到分支 ").append(task.getCodeBranch()).append("。");
                if (Boolean.TRUE.equals(task.getTestsPassed())) {
                    sb.append("单元测试已补充并全部通过。");
                } else if (task.getTestsPassed() != null) {
                    sb.append("但单元测试没能全部跑通，合并前请自己确认一下。");
                }
                if (!StringTools.isEmpty(task.getCodeDiffStat())) {
                    sb.append("\n改动概览：\n").append(task.getCodeDiffStat());
                }
                sb.append("\n请到GitHub上看diff，确认无误再合并。");
            } else if (!coderWorkspace.isEnabled()) {
                //之前这里只说"详细内容看上面几条发言"，用户会以为流程漏了写代码那一步
                sb.append("流程到方案评审为止——编码环节还没启用（ai.coder.enabled=false）。");
                sb.append("想让它接着把代码写了，参考README打开这个开关并配好独立工作区。");
            } else {
                sb.append("详细内容看上面几条发言。");
            }
            summary = sb.toString();
        } else {
            //retryCount是返工次数，评审实际跑了retryCount+1轮
            summary = at + "需求「" + task.getRequirement() + "」的方案连续" + (task.getRetryCount() + 1)
                    + "轮没通过评审，流程先停在这里。建议你看看评审意见，把需求或约束再明确一下重新提。";
        }
        postAgentMessage(entryAgent, task.getGroupId(), summary);
        archive(task, success ? null : "方案未通过评审");
    }

    /**
     * 流程崩了的兜底出口。
     *
     * 群里必须说清楚停在哪一步、因为什么。之前这里只有一句
     * "处理这个需求时出错了"，用户看到它完全不知道是网络断了、还是Key没配、
     * 还是mvn起不来，只能去翻服务端日志——而真因其实早就拿在手里了
     */
    private AiWorkflowTaskDto failTask(AiWorkflowTaskDto task, String reason) {
        logger.error("流水线终止, taskId:{}, reason:{}", task.getTaskId(), reason);
        String stageDesc = stageDescOf(task.getStage());
        task.setStage(AiWorkflowStageEnum.FAILED.name());
        saveTask(task);
        TokenUserInfoDto entryAgent = tokenOf(requirementAgentId);
        if (entryAgent != null) {
            postAgentMessage(entryAgent, task.getGroupId(),
                    atRequester(task) + "这个需求没能处理完，流程停在【" + stageDesc + "】。\n原因："
                            + tailOf(reason, MAX_ERROR_EXCERPT)
                            + "\n没有提交也没有推送任何代码。修好之后重新@我提一次。");
        }
        archive(task, reason);
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
