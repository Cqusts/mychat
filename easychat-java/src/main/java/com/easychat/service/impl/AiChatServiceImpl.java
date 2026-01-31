package com.easychat.service.impl;

import com.easychat.service.AiChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedList;

/**
 * AI大模型对话服务实现
 * 基于Spring AI框架，通过ChatClient调用大模型API
 */
@Service("aiChatService")
public class AiChatServiceImpl implements AiChatService {

    private static final Logger logger = LoggerFactory.getLogger(AiChatServiceImpl.class);

    @Resource
    private ChatClient chatClient;

    @Value("${ai.chat.system-prompt:你是EasyChat的智能助手，请用简洁友好的中文回答用户的问题。}")
    private String systemPrompt;

    @Value("${ai.chat.max-history:20}")
    private Integer maxHistory;

    /**
     * 用户多轮对话历史（内存缓存，生产环境建议改为Redis）
     * key: userId, value: 最近N轮对话消息列表
     */
    private final Map<String, LinkedList<Message>> conversationHistory = new ConcurrentHashMap<>();

    @Override
    public String chat(String userId, String message) {
        try {
            // 构建消息列表：系统提示 + 历史对话 + 当前用户消息
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(systemPrompt));

            // 获取该用户的历史对话
            LinkedList<Message> history = conversationHistory.computeIfAbsent(userId, k -> new LinkedList<>());
            messages.addAll(history);

            // 添加当前用户消息
            UserMessage userMessage = new UserMessage(message);
            messages.add(userMessage);

            // 调用大模型
            Prompt prompt = new Prompt(messages);
            String reply = chatClient.prompt(prompt).call().content();

            // 保存本轮对话到历史记录
            history.add(userMessage);
            history.add(new AssistantMessage(reply));

            // 超过最大轮数，移除最早的一轮（一问一答 = 2条消息）
            while (history.size() > maxHistory * 2) {
                history.removeFirst();
                history.removeFirst();
            }

            return reply;
        } catch (Exception e) {
            logger.error("AI对话异常, userId: {}, message: {}", userId, message, e);
            return "AI助手暂时无法回复，请稍后再试。";
        }
    }
}
