package com.mychat.ai;

import com.mychat.entity.enums.MessageTypeEnum;
import com.mychat.entity.enums.UserContactStatusEnum;
import com.mychat.entity.enums.UserContactTypeEnum;
import com.mychat.entity.po.ChatMessage;
import com.mychat.entity.po.UserContact;
import com.mychat.entity.query.ChatMessageQuery;
import com.mychat.entity.query.SimplePage;
import com.mychat.entity.query.UserContactQuery;
import com.mychat.mappers.ChatMessageMapper;
import com.mychat.mappers.UserContactMapper;
import com.mychat.service.AiStreamCallback;
import com.mychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 暴露给大模型的业务工具集。
 *
 * 注意这个类不是Spring单例，而是每次对话新建一个实例，userId在构造时由服务端注入。
 * 这一点是安全边界的关键：userId不是工具参数，模型没有任何办法伪造它去读别人的数据。
 * 所有工具的查询范围都被硬性限制在这个userId名下。
 */
public class ChatAgentTools {

    private static final Logger logger = LoggerFactory.getLogger(ChatAgentTools.class);

    /**
     * 单个工具最多返回多少条，既是为了保护数据库也是为了控制塞给模型的token量
     */
    private static final int MAX_RESULT = 20;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String[] WEEK_DAYS =
            {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    /**
     * 当前登录用户，由服务端注入，不来自模型输出
     */
    private final String userId;

    /**
     * 用于把"正在调用工具"的状态推给前端，可能为null（非流式调用时）
     */
    private final AiStreamCallback callback;

    private final UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    private final ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    public ChatAgentTools(String userId,
                          AiStreamCallback callback,
                          UserContactMapper<UserContact, UserContactQuery> userContactMapper,
                          ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper) {
        this.userId = userId;
        this.callback = callback;
        this.userContactMapper = userContactMapper;
        this.chatMessageMapper = chatMessageMapper;
    }

    @Tool(description = "获取当前的日期、时间和星期几。当用户的问题里出现'今天''昨天''上周''这个月'这类相对时间时，先调用本工具确定当前时间。")
    public String getCurrentTime() {
        notifyToolCall("正在查询当前时间…");
        LocalDateTime now = LocalDateTime.now();
        return now.format(TIME_FORMATTER) + " " + WEEK_DAYS[now.getDayOfWeek().getValue() - 1];
    }

    @Tool(description = "查询当前用户的好友列表，返回每个好友的昵称和联系人ID。需要针对某个好友做进一步操作时，先用本工具拿到他的联系人ID。")
    public String listFriends() {
        notifyToolCall("正在查询好友列表…");
        try {
            UserContactQuery query = new UserContactQuery();
            query.setUserId(userId);
            query.setContactType(UserContactTypeEnum.USER.getType());
            query.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            query.setQueryContactUserInfo(true);
            query.setOrderBy("last_update_time desc");
            query.setSimplePage(new SimplePage(0, MAX_RESULT));
            List<UserContact> list = userContactMapper.selectList(query);
            if (list == null || list.isEmpty()) {
                return "该用户还没有添加任何好友。";
            }
            StringBuilder sb = new StringBuilder("好友列表：\n");
            for (UserContact item : list) {
                sb.append("- ").append(displayName(item)).append("（联系人ID：")
                        .append(item.getContactId()).append("）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("工具listFriends执行失败, userId:{}", userId, e);
            return "查询好友列表失败。";
        }
    }

    @Tool(description = "查询当前用户加入的群聊列表，返回每个群的名称和群ID。")
    public String listGroups() {
        notifyToolCall("正在查询群聊列表…");
        try {
            UserContactQuery query = new UserContactQuery();
            query.setUserId(userId);
            query.setContactType(UserContactTypeEnum.GROUP.getType());
            query.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            query.setQueryGroupInfo(true);
            query.setOrderBy("last_update_time desc");
            query.setSimplePage(new SimplePage(0, MAX_RESULT));
            List<UserContact> list = userContactMapper.selectList(query);
            if (list == null || list.isEmpty()) {
                return "该用户还没有加入任何群聊。";
            }
            StringBuilder sb = new StringBuilder("群聊列表：\n");
            for (UserContact item : list) {
                sb.append("- ").append(displayName(item)).append("（群ID：")
                        .append(item.getContactId()).append("）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("工具listGroups执行失败, userId:{}", userId, e);
            return "查询群聊列表失败。";
        }
    }

    @Tool(description = "在当前用户与某个好友或某个群聊的会话里，按关键词搜索历史聊天记录，按时间从新到旧返回。")
    public String searchChatHistory(
            @ToolParam(description = "好友的联系人ID或群ID，必须来自listFriends或listGroups的返回结果") String contactId,
            @ToolParam(description = "要搜索的关键词") String keyword) {
        notifyToolCall("正在搜索聊天记录…");
        try {
            if (StringTools.isEmpty(contactId) || StringTools.isEmpty(keyword)) {
                return "需要同时提供联系人ID和搜索关键词。";
            }
            //鉴权：只允许搜索当前用户自己的会话。
            //模型可能给出任意contactId，这里必须回到数据库确认归属，不能相信模型的输出
            if (!isMyContact(contactId)) {
                logger.warn("工具searchChatHistory越权访问被拦截, userId:{}, contactId:{}", userId, contactId);
                return "当前用户的联系人里没有这个ID，无法搜索。";
            }
            UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
            String sessionId = UserContactTypeEnum.GROUP == contactTypeEnum
                    ? StringTools.getChatSessionId4Group(contactId)
                    : StringTools.getChatSessionId4User(new String[]{userId, contactId});

            ChatMessageQuery query = new ChatMessageQuery();
            query.setSessionId(sessionId);
            query.setMessageContentFuzzy(keyword);
            //只搜文字消息，系统通知和媒体消息对关键词搜索没有意义
            query.setMessageType(MessageTypeEnum.CHAT.getType());
            query.setOrderBy("send_time desc");
            query.setSimplePage(new SimplePage(0, MAX_RESULT));
            List<ChatMessage> list = chatMessageMapper.selectList(query);
            if (list == null || list.isEmpty()) {
                return "没有搜到包含「" + keyword + "」的聊天记录。";
            }
            StringBuilder sb = new StringBuilder("搜到" + list.size() + "条聊天记录：\n");
            for (ChatMessage item : list) {
                sb.append("- [").append(formatTime(item.getSendTime())).append("] ")
                        .append(item.getSendUserNickName()).append("：")
                        .append(item.getMessageContent()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("工具searchChatHistory执行失败, userId:{}, contactId:{}", userId, contactId, e);
            return "搜索聊天记录失败。";
        }
    }

    /**
     * 确认contactId确实在当前用户的联系人里
     */
    private boolean isMyContact(String contactId) {
        UserContactQuery query = new UserContactQuery();
        query.setUserId(userId);
        query.setContactId(contactId);
        query.setStatus(UserContactStatusEnum.FRIEND.getStatus());
        Integer count = userContactMapper.selectCount(query);
        return count != null && count > 0;
    }

    private String displayName(UserContact contact) {
        return StringTools.isEmpty(contact.getContactName()) ? "未知" : contact.getContactName();
    }

    private String formatTime(Long timestamp) {
        if (timestamp == null) {
            return "未知时间";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                .format(TIME_FORMATTER);
    }

    /**
     * 把工具调用状态推给前端，让用户看得见AI在做什么。
     * 这一步不能影响主流程，失败了就算了。
     */
    private void notifyToolCall(String hint) {
        if (callback == null) {
            return;
        }
        try {
            callback.onToolCall(hint);
        } catch (Exception e) {
            logger.warn("推送工具调用提示失败", e);
        }
    }
}
