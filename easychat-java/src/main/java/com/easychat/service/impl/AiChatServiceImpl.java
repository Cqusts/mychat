package com.easychat.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.easychat.service.AiChatService;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI大模型对话服务实现
 * 基于OkHttp调用OpenAI兼容协议的大模型API（兼容DeepSeek、通义千问、智谱等）
 */
@Service("aiChatService")
public class AiChatServiceImpl implements AiChatService {

    private static final Logger logger = LoggerFactory.getLogger(AiChatServiceImpl.class);

    @Value("${ai.chat.api-key:}")
    private String apiKey;

    @Value("${ai.chat.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${ai.chat.model:gpt-3.5-turbo}")
    private String model;

    @Value("${ai.chat.temperature:0.7}")
    private Double temperature;

    @Value("${ai.chat.system-prompt:你是EasyChat的智能助手，请用简洁友好的中文回答用户的问题。}")
    private String systemPrompt;

    @Value("${ai.chat.max-history:20}")
    private Integer maxHistory;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * 用户多轮对话历史（内存缓存，生产环境建议改为Redis）
     * key: userId, value: 最近N轮对话消息列表
     */
    private final Map<String, LinkedList<Map<String, String>>> conversationHistory = new ConcurrentHashMap<>();

    @Override
    public String chat(String userId, String message) {
        try {
            // 构建messages数组
            List<Map<String, String>> messages = new ArrayList<>();

            // 系统提示
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            // 历史对话
            LinkedList<Map<String, String>> history = conversationHistory.computeIfAbsent(userId, k -> new LinkedList<>());
            messages.addAll(history);

            // 当前用户消息
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            messages.add(userMsg);

            // 构建请求体
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", temperature);

            // 发送HTTP请求
            String url = baseUrl.endsWith("/") ? baseUrl + "v1/chat/completions" : baseUrl + "/v1/chat/completions";
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(JSON_MEDIA_TYPE, requestBody.toJSONString()))
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            if (!response.isSuccessful()) {
                logger.error("AI接口调用失败, status: {}, body: {}", response.code(), responseBody);
                return "AI助手暂时无法回复，请稍后再试。";
            }

            // 解析回复
            JSONObject jsonResponse = JSON.parseObject(responseBody);
            JSONArray choices = jsonResponse.getJSONArray("choices");
            String reply = choices.getJSONObject(0).getJSONObject("message").getString("content");

            // 保存本轮对话到历史
            Map<String, String> assistantMsg = new HashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", reply);
            history.add(userMsg);
            history.add(assistantMsg);

            // 超过最大轮数，移除最早的一轮（一问一答 = 2条）
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
