package com.easychat.ai;

import com.easychat.entity.po.ChatMessage;
import com.easychat.entity.po.UserContact;
import com.easychat.entity.query.ChatMessageQuery;
import com.easychat.entity.query.UserContactQuery;
import com.easychat.mappers.ChatMessageMapper;
import com.easychat.mappers.UserContactMapper;
import com.easychat.service.AiStreamCallback;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 按次构造Agent工具集。
 *
 * 工具对象必须是每次对话新建的：它携带了当前用户身份和本次的流式回调，
 * 做成Spring单例会导致用户之间串数据。
 */
@Component("aiToolFactory")
public class AiToolFactory {

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    /**
     * @param userId   当前登录用户
     * @param callback 流式回调，用于推送工具调用状态，非流式场景传null
     */
    public ChatAgentTools create(String userId, AiStreamCallback callback) {
        return new ChatAgentTools(userId, callback, userContactMapper, chatMessageMapper);
    }
}
