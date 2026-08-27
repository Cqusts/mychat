package com.easychat.service.impl;

import com.easychat.service.AiChatService;
import com.easychat.service.AiStreamCallback;
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
import reactor.core.publisher.Flux;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI大模型对话服务实现
 * 基于Spring AI框架，通过ChatClient调用大模型API
 */
@Service("aiChatService")
public class AiChatServiceImpl implements AiChatService {

    private static final Logger logger = LoggerFactory.getLogger(AiChatServiceImpl.class);

    /**
     * 回复生成中途失败时追加的提示，前面已经推给用户的内容予以保留
     */
    private static final String INTERRUPTED_TIP = "\n\n（回复生成中断，请重试）";

    private static final String FALLBACK_REPLY = "AI助手暂时无法回复，请稍后再试。";

    @Resource
    private ChatClient chatClient;

    @Value("${ai.chat.system-prompt:你是EasyChat的智能助手，请用简洁友好的中文回答用户的问题。}")
    private String systemPrompt;

    @Value("${ai.chat.max-history:20}")
    private Integer maxHistory;

    /**
     * 片段聚合阈值：缓冲区达到该字符数就推送一次
     */
    @Value("${ai.chat.stream.flush-chars:20}")
    private Integer flushChars;

    /**
     * 片段聚合阈值：距上次推送超过该毫秒数就推送一次。
     * 与flushChars共同作用，避免每个token都过一次Redis广播。
     */
    @Value("${ai.chat.stream.flush-interval-ms:80}")
    private Long flushIntervalMs;

    /**
     * 单次流式回复的整体超时。
     * toIterable()会阻塞等待，模型迟迟不返回就会永久占住一个线程池线程，必须设上限。
     */
    @Value("${ai.chat.stream.timeout-seconds:120}")
    private Long timeoutSeconds;

    /**
     * 用户多轮对话历史（内存缓存）
     * key: userId, value: 最近N轮对话消息列表
     * TODO 多实例部署时此处会导致上下文分裂，下一步替换为Redis实现
     */
    private final Map<String, LinkedList<Message>> conversationHistory = new ConcurrentHashMap<>();

    @Override
    public String chat(String userId, String message) {
        UserMessage userMessage = new UserMessage(message);
        try {
            Prompt prompt = new Prompt(buildMessages(userId, userMessage));
            String reply = chatClient.prompt(prompt).call().content();
            appendHistory(userId, userMessage, reply);
            return reply;
        } catch (Exception e) {
            logger.error("AI对话异常, userId: {}, message: {}", userId, message, e);
            return FALLBACK_REPLY;
        }
    }

    @Override
    public void chatStream(String userId, String message, AiStreamCallback callback) {
        UserMessage userMessage = new UserMessage(message);
        //完整回复内容
        StringBuilder full = new StringBuilder();
        //尚未推送的缓冲区
        StringBuilder pending = new StringBuilder();
        try {
            Prompt prompt = new Prompt(buildMessages(userId, userMessage));
            Flux<String> flux = chatClient.prompt(prompt).stream().content()
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            long lastFlushAt = System.currentTimeMillis();
            //toIterable会阻塞直到流结束，本方法约定由调用方放到线程池中执行
            for (String delta : flux.toIterable()) {
                if (delta == null || delta.isEmpty()) {
                    continue;
                }
                full.append(delta);
                pending.append(delta);
                long now = System.currentTimeMillis();
                if (pending.length() >= flushChars || now - lastFlushAt >= flushIntervalMs) {
                    if (flush(pending, callback)) {
                        lastFlushAt = now;
                    }
                }
            }
            //推送缓冲区里剩余的内容
            if (pending.length() > 0) {
                callback.onChunk(pending.toString());
                pending.setLength(0);
            }

            String reply = full.toString();
            appendHistory(userId, userMessage, reply);
            callback.onComplete(reply);
        } catch (Exception e) {
            logger.error("AI流式对话异常, userId: {}, message: {}", userId, message, e);
            if (full.length() == 0) {
                callback.onError(FALLBACK_REPLY);
                return;
            }
            //已经推了部分内容给用户，补一句中断提示后正常收尾，保留这半截回复
            if (pending.length() > 0) {
                callback.onChunk(pending.toString());
            }
            callback.onChunk(INTERRUPTED_TIP);
            full.append(INTERRUPTED_TIP);
            String reply = full.toString();
            appendHistory(userId, userMessage, reply);
            callback.onComplete(reply);
        }
    }

    /**
     * 把缓冲区推送出去。
     * 末尾的\r会被留到下一片段，保证\r\n不会被拆散——
     * 否则前端按片段做HTML转义时，换行符会还原不出来。
     *
     * @return 是否真的推送了内容
     */
    private boolean flush(StringBuilder pending, AiStreamCallback callback) {
        int len = pending.length();
        if (len > 0 && pending.charAt(len - 1) == '\r') {
            len--;
        }
        if (len == 0) {
            return false;
        }
        callback.onChunk(pending.substring(0, len));
        pending.delete(0, len);
        return true;
    }

    /**
     * 组装送给大模型的消息列表：系统提示 + 历史对话 + 当前消息
     */
    private List<Message> buildMessages(String userId, UserMessage userMessage) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        LinkedList<Message> history = conversationHistory.computeIfAbsent(userId, k -> new LinkedList<>());
        synchronized (history) {
            messages.addAll(history);
        }
        messages.add(userMessage);
        return messages;
    }

    /**
     * 记录本轮对话，并按最大轮数淘汰最早的一问一答
     */
    private void appendHistory(String userId, UserMessage userMessage, String reply) {
        LinkedList<Message> history = conversationHistory.computeIfAbsent(userId, k -> new LinkedList<>());
        synchronized (history) {
            history.add(userMessage);
            history.add(new AssistantMessage(reply));
            while (history.size() > maxHistory * 2) {
                history.removeFirst();
                history.removeFirst();
            }
        }
    }
}
