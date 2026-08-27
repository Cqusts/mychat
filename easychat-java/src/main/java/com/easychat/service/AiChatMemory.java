package com.easychat.service;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * AI多轮对话记忆。
 * 抽成接口是为了让存储实现可替换：单机可以用内存，多实例部署必须用共享存储，
 * 否则同一个用户的请求打到不同节点上，上下文就断了。
 */
public interface AiChatMemory {

    /**
     * 读取某个用户与某个助手之间的历史对话。
     * 按(用户,助手)两个维度隔离：同一个人可以同时和多个助手私聊，
     * 各自的上下文不能混在一起。
     */
    List<Message> load(String userId, String agentId);

    /**
     * 追加一轮完整对话（一问一答）
     */
    void append(String userId, String agentId, String userContent, String assistantContent);

    /**
     * 清空某个用户与某个助手的对话历史
     */
    void clear(String userId, String agentId);
}
