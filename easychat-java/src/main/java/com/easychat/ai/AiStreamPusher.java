package com.easychat.ai;

import com.easychat.entity.dto.AiStreamChunkDto;
import com.easychat.entity.dto.MessageSendDto;
import com.easychat.entity.dto.TokenUserInfoDto;
import com.easychat.entity.enums.MessageStatusEnum;
import com.easychat.entity.enums.MessageTypeEnum;
import com.easychat.entity.enums.UserContactTypeEnum;
import com.easychat.websocket.MessageHandler;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 把AI的流式片段推给前端。
 * 单聊、群聊自由对话、流水线编排三条路都要用，抽出来避免各写一份导致行为不一致。
 */
@Component("aiStreamPusher")
public class AiStreamPusher {

    @Resource
    private MessageHandler messageHandler;

    /**
     * 推送一个流式片段。这类消息只走WebSocket，不落库、不更新会话，
     * 真正的消息记录以流结束后落库的那条CHAT消息为准。
     *
     * @param sender      以谁的身份发
     * @param contactId   推给谁：单聊填对方用户ID，群聊填群ID，
     *                    ChannelContextUtils会按前缀路由到send2User或sendMsg2Group
     * @param messageType AI_STREAM / AI_STREAM_END / AI_TOOL_CALL
     */
    public void push(TokenUserInfoDto sender, String contactId, String sessionId,
                     MessageTypeEnum messageType, String streamId, String content, Integer index) {
        MessageSendDto<AiStreamChunkDto> chunk = new MessageSendDto<>();
        chunk.setMessageType(messageType.getType());
        chunk.setContactId(contactId);
        chunk.setContactType(UserContactTypeEnum.getByPrefix(contactId).getType());
        chunk.setSendUserId(sender.getUserId());
        chunk.setSendUserNickName(sender.getNickName());
        chunk.setSessionId(sessionId);
        chunk.setSendTime(System.currentTimeMillis());
        chunk.setStatus(MessageStatusEnum.SENDED.getStatus());
        chunk.setExtendData(new AiStreamChunkDto(streamId, content, index));
        messageHandler.sendMessage(chunk);
    }
}
