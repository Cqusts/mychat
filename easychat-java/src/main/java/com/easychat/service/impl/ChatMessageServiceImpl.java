package com.easychat.service.impl;

import com.easychat.entity.config.AppConfig;
import com.easychat.entity.constants.Constants;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.SysSettingDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.*;
import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.ChatSession;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.ChatSessionQuery;
import com.easychat.entity.query.SimplePage;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.entity.vo.PaginationResultVO;
import com.easychat.exception.BusinessException;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.ChatSessionMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.redis.RedisComponet;
import com.easychat.service.ChatMessageService;
import com.easychat.utils.CopyTools;
import com.easychat.utils.DateUtil;
import com.easychat.utils.StringTools;
import com.easychat.ai.AiAgentDefinition;
import com.easychat.ai.AiAgentRegistry;
import com.easychat.ai.AiStreamPusher;
import com.easychat.ai.AiWorkflowEngine;
import com.easychat.service.AiChatService;
import com.easychat.service.AiStreamCallback;
import com.easychat.entity.dto.AiStreamChunkDto;
import com.easychat.websocket.MessageHandler;
import jodd.util.ArraysUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 聊天消息表 业务接口实现
 */
@Service("chatMessageService")
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    /**
     * AI线程池被打满时回给用户的兜底文案
     */
    private static final String ROBOT_BUSY_TIP = "助手当前有点忙，请稍后再问我一次～";

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Resource
    private MessageHandler messageHandler;

    @Resource(name = "aiTaskExecutor")
    private ThreadPoolTaskExecutor aiTaskExecutor;

    @Resource
    private AppConfig appConfig;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private RedisComponet redisComponet;

    @Resource
    private AiChatService aiChatService;

    @Resource
    private AiAgentRegistry aiAgentRegistry;

    @Resource
    private AiStreamPusher aiStreamPusher;

    @Resource
    private AiWorkflowEngine aiWorkflowEngine;

    /**
     * 群里助手之间互相接话的最大轮数。
     * 助手可以@别的助手，所以必须有硬上限，否则两个助手能一直聊下去把额度烧光。
     */
    @Value("${ai.chat.group.max-depth:3}")
    private Integer maxAgentDepth;

    /**
     * 拼给助手看的群聊上下文条数
     */
    @Value("${ai.chat.group.context-size:15}")
    private Integer groupContextSize;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<ChatMessage> findListByParam(ChatMessageQuery param) {
        return this.chatMessageMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(ChatMessageQuery param) {
        return this.chatMessageMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<ChatMessage> findListByPage(ChatMessageQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<ChatMessage> list = this.findListByParam(param);
        PaginationResultVO<ChatMessage> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(ChatMessage bean) {
        return this.chatMessageMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<ChatMessage> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatMessageMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<ChatMessage> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatMessageMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(ChatMessage bean, ChatMessageQuery param) {
        StringTools.checkParam(param);
        return this.chatMessageMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(ChatMessageQuery param) {
        StringTools.checkParam(param);
        return this.chatMessageMapper.deleteByParam(param);
    }

    /**
     * 根据MessageId获取对象
     */
    @Override
    public ChatMessage getChatMessageByMessageId(Long messageId) {
        return this.chatMessageMapper.selectByMessageId(messageId);
    }

    /**
     * 根据MessageId修改
     */
    @Override
    public Integer updateChatMessageByMessageId(ChatMessage bean, Long messageId) {
        return this.chatMessageMapper.updateByMessageId(bean, messageId);
    }

    /**
     * 根据MessageId删除
     */
    @Override
    public Integer deleteChatMessageByMessageId(Long messageId) {
        return this.chatMessageMapper.deleteByMessageId(messageId);
    }


    @Override
    public MessageSendDto saveMessage(ChatMessage chatMessage, TokenUserInfoDto tokenUserInfoDto) {
        //外部调用一律从第0轮开始
        return saveMessage(chatMessage, tokenUserInfoDto, 0);
    }

    /**
     * @param agentDepth 当前是助手接话的第几轮。真人发言为0，助手的回复递增，
     *                   用来给助手之间的互相接话封顶
     */
    private MessageSendDto saveMessage(ChatMessage chatMessage, TokenUserInfoDto tokenUserInfoDto, int agentDepth) {
        //AI助手不是真人，不需要先建立好友关系才能收发消息，跳过这一步校验
        if (!aiAgentRegistry.isAgent(tokenUserInfoDto.getUserId())) {
            List<String> contactList = redisComponet.getUserContactList(tokenUserInfoDto.getUserId());
            if (!contactList.contains(chatMessage.getContactId())) {
                UserContactTypeEnum userContactTypeEnum = UserContactTypeEnum.getByPrefix(chatMessage.getContactId());
                if (UserContactTypeEnum.USER == userContactTypeEnum) {
                    throw new BusinessException(ResponseCodeEnum.CODE_902);
                } else {
                    throw new BusinessException(ResponseCodeEnum.CODE_903);
                }
            }
        }
        String sessionId = null;
        String sendUserId = tokenUserInfoDto.getUserId();
        String contactId = chatMessage.getContactId();
        Long curTime = System.currentTimeMillis();
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getByType(chatMessage.getMessageType());
        String lastMessage = chatMessage.getMessageContent();
        String messageContent = StringTools.resetMessageContent(chatMessage.getMessageContent());
        chatMessage.setMessageContent(messageContent);
        Integer status = MessageTypeEnum.MEDIA_CHAT == messageTypeEnum ? MessageStatusEnum.SENDING.getStatus() : MessageStatusEnum.SENDED.getStatus();
        if (ArraysUtil.contains(new Integer[]{
                MessageTypeEnum.CHAT.getType(),
                MessageTypeEnum.GROUP_CREATE.getType(),
                MessageTypeEnum.ADD_FRIEND.getType(),
                MessageTypeEnum.MEDIA_CHAT.getType()
        }, messageTypeEnum.getType())) {
            if (UserContactTypeEnum.USER == contactTypeEnum) {
                sessionId = StringTools.getChatSessionId4User(new String[]{sendUserId, contactId});
            } else {
                sessionId = StringTools.getChatSessionId4Group(contactId);
            }
            //更新会话消息
            ChatSession chatSession = new ChatSession();
            chatSession.setLastMessage(messageContent);
            if (UserContactTypeEnum.GROUP == contactTypeEnum && !MessageTypeEnum.GROUP_CREATE.getType().equals(messageTypeEnum.getType())) {
                chatSession.setLastMessage(tokenUserInfoDto.getNickName() + "：" + messageContent);
            }
            lastMessage = chatSession.getLastMessage();
            //如果是媒体文件
            chatSession.setLastReceiveTime(curTime);
            chatSessionMapper.updateBySessionId(chatSession, sessionId);
            //记录消息消息表
            chatMessage.setSessionId(sessionId);
            chatMessage.setSendUserId(sendUserId);
            chatMessage.setSendUserNickName(tokenUserInfoDto.getNickName());
            chatMessage.setSendTime(curTime);
            chatMessage.setContactType(contactTypeEnum.getType());
            chatMessage.setStatus(status);
            chatMessageMapper.insert(chatMessage);
        }
        MessageSendDto messageSend = CopyTools.copy(chatMessage, MessageSendDto.class);
        if (isSingleChatAgent(contactId)) {
            //用户消息已经落库，AI回复交给独立线程池异步生成：
            //生成过程中的片段直接走WebSocket推送，HTTP请求线程立即返回，不再被大模型的秒级耗时拖住
            dispatchAgentReply(contactId, sendUserId, chatMessage.getMessageContent(), sessionId);
        } else {
            messageHandler.sendMessage(messageSend);
            if (UserContactTypeEnum.GROUP == contactTypeEnum) {
                dispatchGroupAgentReplies(contactId, sessionId, messageContent, tokenUserInfoDto, agentDepth);
            }
        }
        return messageSend;
    }

    /**
     * 群消息落库广播之后，看看有没有助手被@到，有就让它们各自回复。
     */
    private void dispatchGroupAgentReplies(String groupId, String sessionId, String content,
                                           TokenUserInfoDto sender, int agentDepth) {
        //绝大多数群消息都不含@，先做一次零成本的短路，避免每条消息都去查一次群成员
        if (content == null || content.indexOf('@') < 0) {
            return;
        }
        if (agentDepth >= maxAgentDepth) {
            logger.info("群助手接话已达最大轮数{}，不再触发, groupId:{}", maxAgentDepth, groupId);
            return;
        }
        List<String> groupAgentIds = findGroupAgentIds(groupId);
        if (groupAgentIds.isEmpty()) {
            //消息里有@但群里一个助手都没有，最常见的原因是助手压根没被拉进群
            logger.info("群里没有AI助手，跳过@解析, groupId:{}", groupId);
            return;
        }
        List<AiAgentDefinition> mentioned = aiAgentRegistry.matchMentions(content, groupAgentIds);
        if (mentioned.isEmpty()) {
            //能走到这说明群里有助手、消息里也有@，但昵称对不上。
            //把两边都打出来，一眼能看出是不是昵称不一致（比如数据库里还是旧的乱码昵称）
            List<String> agentNames = new ArrayList<>();
            for (String agentId : groupAgentIds) {
                AiAgentDefinition agent = aiAgentRegistry.getById(agentId);
                if (agent != null) {
                    agentNames.add(agent.getName());
                }
            }
            logger.info("消息里有@但没匹配到任何助手, groupId:{}, 群内助手昵称:{}, 消息内容:{}",
                    groupId, agentNames, content);
            return;
        }
        //真人@到流水线入口助手，就是在提需求，交给编排引擎按状态机跑，
        //不再走下面的自由对话分支。助手之间的转交不触发流程，避免流程被套娃启动
        boolean fromHuman = !aiAgentRegistry.isAgent(sender.getUserId());
        if (fromHuman && aiWorkflowEngine.isEnabled()) {
            for (AiAgentDefinition agent : mentioned) {
                if (!aiWorkflowEngine.isEntryAgent(agent.getId())) {
                    continue;
                }
                String requirement = stripMentions(content, groupAgentIds);
                logger.info("识别为需求流水线, groupId:{}, 需求:{}", groupId, requirement);
                aiWorkflowEngine.startTask(groupId, sessionId, sender, requirement, groupAgentIds);
                return;
            }
        }

        //真人可以一次@多个助手（比如拉几个角色一起评审），
        //但助手转交时只认第一个：否则每层扇出2个的话，8层就是几百次模型调用，
        //额度会被指数级烧光。助手之间保持单线传递，链条长度就只受max-depth约束
        boolean senderIsAgent = aiAgentRegistry.isAgent(sender.getUserId());
        for (AiAgentDefinition agent : mentioned) {
            //助手@到自己不触发，否则它会无限自问自答
            if (agent.getId().equals(sender.getUserId())) {
                continue;
            }
            logger.info("触发群助手回复, groupId:{}, agent:{}({}), 第{}轮",
                    groupId, agent.getName(), agent.getId(), agentDepth + 1);
            dispatchOneGroupAgent(agent, groupId, sessionId, groupAgentIds, agentDepth);
            if (senderIsAgent) {
                if (mentioned.size() > 1) {
                    logger.info("发言者是助手，本条只转交给第一个被@的助手，忽略其余{}个",
                            mentioned.size() - 1);
                }
                break;
            }
        }
    }

    private void dispatchOneGroupAgent(AiAgentDefinition agent, String groupId, String sessionId,
                                       List<String> groupAgentIds, int agentDepth) {
        String streamId = UUID.randomUUID().toString().replace("-", "");
        TokenUserInfoDto agentToken = new TokenUserInfoDto();
        agentToken.setUserId(agent.getId());
        agentToken.setNickName(agent.getName());
        try {
            aiTaskExecutor.execute(() ->
                    streamGroupAgentReply(agent, agentToken, groupId, sessionId, groupAgentIds, streamId, agentDepth));
        } catch (TaskRejectedException e) {
            //群聊里线程池被打满时直接放弃这次回复，不像单聊那样回一条"繁忙"——
            //群里塞一堆机器人道歉消息比不回复更吵
            logger.warn("AI线程池已满，跳过群助手回复, groupId:{}, agentId:{}", groupId, agent.getId());
        }
    }

    private void streamGroupAgentReply(AiAgentDefinition agent, TokenUserInfoDto agentToken, String groupId,
                                       String sessionId, List<String> groupAgentIds, String streamId, int agentDepth) {
        AtomicInteger index = new AtomicInteger(0);
        String systemPrompt = buildGroupSystemPrompt(agent, groupAgentIds);
        String userPrompt = buildGroupContext(agent, sessionId);
        aiChatService.chatStreamOnce(systemPrompt, userPrompt, new AiStreamCallback() {
            @Override
            public void onChunk(String delta) {
                aiStreamPusher.push(agentToken, groupId, sessionId, MessageTypeEnum.AI_STREAM, streamId,
                        StringTools.resetMessageContent(delta), index.getAndIncrement());
            }

            @Override
            public void onComplete(String fullContent) {
                aiStreamPusher.push(agentToken, groupId, sessionId, MessageTypeEnum.AI_STREAM_END, streamId,
                        StringTools.resetMessageContent(fullContent), index.getAndIncrement());
                //助手的回复也走正常落库链路，所以它自己也可能@到别的助手，
                //depth+1把这条接话链的长度记下来
                saveAgentGroupMessage(agentToken, groupId, fullContent, agentDepth + 1);
            }

            @Override
            public void onError(String errorMessage) {
                //群里出错就安静地不发言，不用错误消息刷屏
                aiStreamPusher.push(agentToken, groupId, sessionId, MessageTypeEnum.AI_STREAM_END, streamId,
                        "", index.getAndIncrement());
                //群里不发错误消息避免刷屏，但日志必须能定位问题，
                //否则用户看到的就是"@了没反应"，完全无从查起
                logger.error("群助手回复失败（群里不发消息，只记日志）, groupId:{}, agent:{}({}), reason:{}",
                        groupId, agentToken.getNickName(), agentToken.getUserId(), errorMessage);
            }
        });
    }

    /**
     * 助手在群里的人设。
     * 除了配置里的角色设定，还要告诉它群里都有谁、可以怎么把问题转给别的助手——
     * 助手之间的接力就是靠这句话跑起来的。
     */
    private String buildGroupSystemPrompt(AiAgentDefinition agent, List<String> groupAgentIds) {
        StringBuilder sb = new StringBuilder(agent.getPrompt() == null ? "" : agent.getPrompt());
        sb.append("\n你现在在一个群聊里，成员有真人也有其他AI助手。");
        sb.append("发言要简短口语化，像真人在群里说话，不要长篇大论、不要分点罗列。");
        List<String> peers = new ArrayList<>();
        for (String id : groupAgentIds) {
            AiAgentDefinition peer = aiAgentRegistry.getById(id);
            if (peer != null && !peer.getId().equals(agent.getId())) {
                peers.add(peer.getName());
            }
        }
        if (!peers.isEmpty()) {
            sb.append("群里的其他助手有：").append(String.join("、", peers)).append("。");
            sb.append("如果某个问题明显更适合他们中的某一位回答，你可以在回复里用@加对方昵称把话题交给他；");
            sb.append("但不要为了客套而@人，没必要时就自己答完。");
        }
        return sb.toString();
    }

    /**
     * 把群聊最近的对话渲染成一段文本给助手看。
     * 群里有多个说话人，用user/assistant交替的消息列表表达不了谁是谁，
     * 直接渲染成带昵称的记录反而更清楚。
     */
    private String buildGroupContext(AiAgentDefinition agent, String sessionId) {
        ChatMessageQuery query = new ChatMessageQuery();
        query.setSessionId(sessionId);
        query.setMessageType(MessageTypeEnum.CHAT.getType());
        query.setOrderBy("send_time desc");
        query.setSimplePage(new SimplePage(0, groupContextSize));
        List<ChatMessage> list = chatMessageMapper.selectList(query);
        StringBuilder sb = new StringBuilder("以下是群聊最近的对话记录，按时间从早到晚：\n");
        if (list != null && !list.isEmpty()) {
            //查出来是倒序，翻回正序才符合阅读顺序
            List<ChatMessage> ordered = new ArrayList<>(list);
            Collections.reverse(ordered);
            for (ChatMessage item : ordered) {
                sb.append(item.getSendUserNickName()).append("：")
                        .append(item.getMessageContent()).append("\n");
            }
        }
        sb.append("\n你是「").append(agent.getName()).append("」，刚刚有人在群里@了你。");
        sb.append("请针对最后这条消息作出回应。直接说你要说的话，不要加昵称前缀。");
        return sb.toString();
    }

    /**
     * 助手在群里发言落库
     */
    private void saveAgentGroupMessage(TokenUserInfoDto agentToken, String groupId, String content, int agentDepth) {
        try {
            ChatMessage message = new ChatMessage();
            message.setContactId(groupId);
            message.setMessageContent(content);
            message.setMessageType(MessageTypeEnum.CHAT.getType());
            saveMessage(message, agentToken, agentDepth);
        } catch (Exception e) {
            logger.error("群助手发言落库失败, groupId:{}, agentId:{}", groupId, agentToken.getUserId(), e);
        }
    }

    /**
     * 找出这个群里有哪些AI助手
     */
    /**
     * 把消息里的"@助手昵称"去掉，剩下的才是需求本身。
     * 不去掉的话，需求文本会带着@前缀传给模型，容易被它当成要转交的指令
     */
    private String stripMentions(String content, List<String> groupAgentIds) {
        String result = content == null ? "" : content;
        for (String agentId : groupAgentIds) {
            AiAgentDefinition agent = aiAgentRegistry.getById(agentId);
            if (agent != null && !StringTools.isEmpty(agent.getName())) {
                result = result.replace("@" + agent.getName(), " ");
            }
        }
        return result.trim();
    }

    private List<String> findGroupAgentIds(String groupId) {
        UserContactQuery query = new UserContactQuery();
        query.setContactId(groupId);
        query.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        List<UserContact> members = userContactMapper.selectList(query);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> agentIds = new ArrayList<>();
        for (UserContact member : members) {
            if (aiAgentRegistry.getById(member.getUserId()) != null) {
                agentIds.add(member.getUserId());
            }
        }
        return agentIds;
    }

    @Override
    public MessageSendDto saveWorkflowMessage(ChatMessage chatMessage, TokenUserInfoDto agentToken) {
        //深度直接给到上限：dispatchGroupAgentReplies里会因为达到上限提前返回，
        //从而不解析这条消息里的@。复用已有的深度判断，不用再加一个开关
        return saveMessage(chatMessage, agentToken, maxAgentDepth);
    }

    /**
     * 判断私聊的对象是不是AI助手。
     * 内置的单聊机器人和配置出来的助手都算——助手被加为好友之后就能直接私聊。
     */
    private boolean isSingleChatAgent(String contactId) {
        return Constants.ROBOT_UID.equals(contactId) || aiAgentRegistry.getById(contactId) != null;
    }

    /**
     * 投递一次私聊AI回复任务到AI线程池
     *
     * @param agentId     被私聊的助手ID
     * @param userId      提问的用户
     * @param userMessage 用户的提问内容
     * @param sessionId   两者的会话ID
     */
    private void dispatchAgentReply(String agentId, String userId, String userMessage, String sessionId) {
        TokenUserInfoDto agentToken = new TokenUserInfoDto();
        String systemPrompt = null;
        AiAgentDefinition agent = aiAgentRegistry.getById(agentId);
        if (agent != null) {
            //配置出来的助手，用它自己的人设
            agentToken.setUserId(agent.getId());
            agentToken.setNickName(agent.getName());
            systemPrompt = buildSingleChatSystemPrompt(agent);
        } else {
            //内置的单聊机器人，身份来自系统设置，人设走ai.chat.system-prompt默认值
            SysSettingDto sysSettingDto = redisComponet.getSysSetting();
            agentToken.setUserId(sysSettingDto.getRobotUid());
            agentToken.setNickName(sysSettingDto.getRobotNickName());
        }
        String streamId = UUID.randomUUID().toString().replace("-", "");
        String prompt = systemPrompt;
        try {
            aiTaskExecutor.execute(() ->
                    streamAgentReply(agentToken, prompt, userId, userMessage, sessionId, streamId));
        } catch (TaskRejectedException e) {
            //线程和队列都满了，快速失败并给用户一个明确答复，而不是让请求一直挂着。
            //注意这里必须捕获Spring的TaskRejectedException：
            //ThreadPoolTaskExecutor会把JDK的RejectedExecutionException包装后再抛出
            logger.warn("AI线程池已满，拒绝本次请求, userId:{}, agentId:{}", userId, agentId);
            saveAgentSingleMessage(agentToken, userId, ROBOT_BUSY_TIP);
        }
    }

    /**
     * 助手私聊时的人设。
     * 在配置的角色设定之外补上工具使用说明——私聊场景下工具以提问者的身份执行，
     * "查谁的数据"是明确的，所以可以放心开放。
     */
    private String buildSingleChatSystemPrompt(AiAgentDefinition agent) {
        StringBuilder sb = new StringBuilder(agent.getPrompt() == null ? "" : agent.getPrompt());
        sb.append("\n你正在和用户一对一私聊，回答简洁自然，不要长篇大论。");
        sb.append("你可以调用工具查询这位用户的好友列表、群聊列表和聊天记录：");
        sb.append("需要这些信息时直接调用工具，不要凭空编造，也不要向用户索要联系人ID。");
        sb.append("遇到“上周”“昨天”这类相对时间，先调getCurrentTime确认今天是哪天。");
        return sb.toString();
    }

    /**
     * 在AI线程池中执行：流式取回复 -> 逐片段推送 -> 结束后落库
     */
    private void streamAgentReply(TokenUserInfoDto agentToken, String systemPrompt, String userId,
                                  String userMessage, String sessionId, String streamId) {
        AtomicInteger index = new AtomicInteger(0);
        aiChatService.chatStream(agentToken.getUserId(), systemPrompt, userId, userMessage, new AiStreamCallback() {
            @Override
            public void onChunk(String delta) {
                aiStreamPusher.push(agentToken, userId, sessionId, MessageTypeEnum.AI_STREAM, streamId,
                        StringTools.resetMessageContent(delta), index.getAndIncrement());
            }

            @Override
            public void onToolCall(String toolHint) {
                aiStreamPusher.push(agentToken, userId, sessionId, MessageTypeEnum.AI_TOOL_CALL, streamId,
                        toolHint, index.getAndIncrement());
            }

            @Override
            public void onComplete(String fullContent) {
                //先告知前端流已结束，再落库；
                //落库后的正式消息会顺着原有链路推给前端，用来替换掉临时的流式气泡
                aiStreamPusher.push(agentToken, userId, sessionId, MessageTypeEnum.AI_STREAM_END, streamId,
                        StringTools.resetMessageContent(fullContent), index.getAndIncrement());
                saveAgentSingleMessage(agentToken, userId, fullContent);
            }

            @Override
            public void onError(String errorMessage) {
                aiStreamPusher.push(agentToken, userId, sessionId, MessageTypeEnum.AI_STREAM_END, streamId,
                        StringTools.resetMessageContent(errorMessage), index.getAndIncrement());
                saveAgentSingleMessage(agentToken, userId, errorMessage);
            }
        });
    }

    /**
     * 助手私聊回复落库，复用原有saveMessage链路（更新会话、推送正式消息）。
     * 运行在AI线程池中，异常不能往外抛，否则会打挂线程池里的任务。
     */
    private void saveAgentSingleMessage(TokenUserInfoDto agentToken, String userId, String content) {
        try {
            ChatMessage agentMessage = new ChatMessage();
            agentMessage.setContactId(userId);
            agentMessage.setMessageContent(content);
            agentMessage.setMessageType(MessageTypeEnum.CHAT.getType());
            saveMessage(agentMessage, agentToken);
        } catch (Exception e) {
            logger.error("助手私聊回复落库失败, userId:{}, agentId:{}", userId, agentToken.getUserId(), e);
        }
    }

    @Override
    public void saveMessageFile(String userId, Long messageId, MultipartFile file, MultipartFile cover) {
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        if (null == message) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (!message.getSendUserId().equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        SysSettingDto sysSettingDto = redisComponet.getSysSetting();
        String fileSuffix = StringTools.getFileSuffix(file.getOriginalFilename());
        if (!StringTools.isEmpty(fileSuffix) && ArraysUtil.contains(Constants.IMAGE_SUFFIX_LIST, fileSuffix.toLowerCase())
                && file.getSize() > Constants.FILE_SIZE_MB * sysSettingDto.getMaxImageSize()) {
            return;
        } else if (!StringTools.isEmpty(fileSuffix) && ArraysUtil.contains(Constants.VIDEO_SUFFIX_LIST, fileSuffix.toLowerCase())
                && file.getSize() > Constants.FILE_SIZE_MB * sysSettingDto.getMaxVideoSize()) {
            return;
        } else if (!StringTools.isEmpty(fileSuffix) &&
                !ArraysUtil.contains(Constants.VIDEO_SUFFIX_LIST, fileSuffix.toLowerCase()) &&
                !ArraysUtil.contains(Constants.IMAGE_SUFFIX_LIST, fileSuffix.toLowerCase()) &&
                file.getSize() > Constants.FILE_SIZE_MB * sysSettingDto.getMaxFileSize()) {
            return;
        }
        String fileName = file.getOriginalFilename();
        String fileExtName = StringTools.getFileSuffix(fileName);
        String fileRealName = messageId + fileExtName;
        String month = DateUtil.format(new Date(message.getSendTime()), DateTimePatternEnum.YYYYMM.getPattern());
        File folder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + month);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File uploadFile = new File(folder.getPath() + "/" + fileRealName);
        try {
            file.transferTo(uploadFile);
            if (cover != null) {
                cover.transferTo(new File(uploadFile.getPath() + Constants.COVER_IMAGE_SUFFIX));
            }
        } catch (Exception e) {
            logger.error("上传文件失败", e);
            throw new BusinessException("文件上传失败");
        }
        ChatMessage updateInfo = new ChatMessage();
        updateInfo.setStatus(MessageStatusEnum.SENDED.getStatus());
        ChatMessageQuery messageQuery = new ChatMessageQuery();
        messageQuery.setMessageId(messageId);
        chatMessageMapper.updateByParam(updateInfo, messageQuery);

        MessageSendDto messageSend = new MessageSendDto();
        messageSend.setStatus(MessageStatusEnum.SENDED.getStatus());
        messageSend.setMessageId(message.getMessageId());
        messageSend.setMessageType(MessageTypeEnum.FILE_UPLOAD.getType());
        messageSend.setContactId(message.getContactId());
        messageHandler.sendMessage(messageSend);
    }

    @Override
    public File downloadFile(TokenUserInfoDto userInfoDto, Long messageId, Boolean cover) {
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        String contactId = message.getContactId();
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
        if (UserContactTypeEnum.USER.getType().equals(contactTypeEnum) && !userInfoDto.getUserId().equals(message.getContactId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)) {
            UserContactQuery userContactQuery = new UserContactQuery();
            userContactQuery.setUserId(userInfoDto.getUserId());
            userContactQuery.setContactType(UserContactTypeEnum.GROUP.getType());
            userContactQuery.setContactId(contactId);
            userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            Integer contactCount = userContactMapper.selectCount(userContactQuery);
            if (contactCount == 0) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
        }
        String month = DateUtil.format(new Date(message.getSendTime()), DateTimePatternEnum.YYYYMM.getPattern());
        File folder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + month);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        String fileName = message.getFileName();
        String fileExtName = StringTools.getFileSuffix(fileName);
        String fileRealName = messageId + fileExtName;

        if (cover != null && cover) {
            fileRealName = fileRealName + Constants.COVER_IMAGE_SUFFIX;
        }
        File file = new File(folder.getPath() + "/" + fileRealName);
        if (!file.exists()) {
            logger.info("文件不存在");
            throw new BusinessException(ResponseCodeEnum.CODE_602);
        }
        return file;
    }
}