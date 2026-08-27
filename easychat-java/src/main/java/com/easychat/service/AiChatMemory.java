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
     * 读取某个用户的历史对话
     */
    List<Message> load(String userId);

    /**
     * 追加一轮完整对话（一问一答）
     */
    void append(String userId, String userContent, String assistantContent);

    /**
     * 清空某个用户的对话历史
     */
    void clear(String userId);
}
