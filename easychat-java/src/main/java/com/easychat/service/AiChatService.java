package com.easychat.service;

/**
 * AI大模型对话服务接口
 */
public interface AiChatService {

    /**
     * 调用AI大模型获取回复
     *
     * @param userId  发送消息的用户ID
     * @param message 用户发送的消息内容
     * @return AI生成的回复内容
     */
    String chat(String userId, String message);
}
