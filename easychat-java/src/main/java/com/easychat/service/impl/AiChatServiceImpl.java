package com.easychat.service.impl;

import com.easychat.ai.AiToolFactory;
import com.easychat.service.AiChatMemory;
import com.easychat.service.AiChatService;
import com.easychat.service.AiStreamCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
import java.util.List;

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

    @Resource
    private AiChatMemory aiChatMemory;

    @Resource
    private AiToolFactory aiToolFactory;

    @Value("${ai.chat.system-prompt:你是EasyChat的智能助手，请用简洁友好的中文回答用户的问题。}")
    private String systemPrompt;

    /**
     * 是否开放业务工具给模型调用。
     * 留开关是因为工具调用依赖模型本身的Function Calling能力，
     * 换到不支持的模型上可以直接关掉退回纯对话。
     */
    @Value("${ai.chat.tools.enabled:true}")
    private Boolean toolsEnabled;

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

    @Override
    public String chat(String userId, String message) {
        try {
            Prompt prompt = new Prompt(buildMessages(userId, message));
            //非流式场景没有回调，工具调用状态无处可推，传null
            String reply = withTools(chatClient.prompt(prompt), userId, null).call().content();
            aiChatMemory.append(userId, message, reply);
            return reply;
        } catch (Exception e) {
            logger.error("AI对话异常, userId: {}, message: {}", userId, message, e);
            return FALLBACK_REPLY;
        }
    }

    @Override
    public void chatStream(String userId, String message, AiStreamCallback callback) {
        //完整回复内容
        StringBuilder full = new StringBuilder();
        //尚未推送的缓冲区
        StringBuilder pending = new StringBuilder();
        try {
            Prompt prompt = new Prompt(buildMessages(userId, message));
            Flux<String> flux = withTools(chatClient.prompt(prompt), userId, callback)
                    .stream().content()
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
            aiChatMemory.append(userId, message, reply);
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
            aiChatMemory.append(userId, message, reply);
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
     * 给本次请求挂上业务工具。
     * 工具实例是每次新建的，里面绑好了当前用户身份——
     * userId不作为工具参数暴露给模型，模型没法伪造身份去读别人的数据。
     */
    private ChatClient.ChatClientRequestSpec withTools(ChatClient.ChatClientRequestSpec spec,
                                                       String userId, AiStreamCallback callback) {
        if (!Boolean.TRUE.equals(toolsEnabled)) {
            return spec;
        }
        return spec.tools(aiToolFactory.create(userId, callback));
    }

    /**
     * 组装送给大模型的消息列表：系统提示 + 历史对话 + 当前消息
     */
    private List<Message> buildMessages(String userId, String message) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(systemPrompt));
        messages.addAll(aiChatMemory.load(userId));
        messages.add(new UserMessage(message));
        return messages;
    }
}
