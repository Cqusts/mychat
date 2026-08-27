package com.easychat.service;

/**
 * AI大模型对话服务接口
 */
public interface AiChatService {

    /**
     * 调用AI大模型获取回复（阻塞式，一次性返回完整内容）
     *
     * @param agentId      回复的助手ID，决定用哪份对话记忆
     * @param systemPrompt 助手人设，传null则用配置里的默认人设
     * @param userId       发送消息的用户ID
     * @param message      用户发送的消息内容
     * @return AI生成的回复内容
     */
    String chat(String agentId, String systemPrompt, String userId, String message);

    /**
     * 流式调用AI大模型，片段通过回调逐步返回。
     * 本方法会阻塞到整轮回复结束，调用方需自行放到线程池中执行。
     *
     * @param agentId      回复的助手ID，决定用哪份对话记忆
     * @param systemPrompt 助手人设，传null则用配置里的默认人设
     * @param userId       发送消息的用户ID
     * @param message      用户发送的消息内容
     * @param callback     片段回调
     */
    void chatStream(String agentId, String systemPrompt, String userId, String message, AiStreamCallback callback);

    /**
     * 无记忆的一次性流式对话，上下文完全由调用方拼好。
     * 群聊场景走这个：群里有多个说话人，用单一的user/assistant交替历史表达不了，
     * 把群聊记录直接渲染成一段文本反而更可靠。
     * 同样会阻塞到整轮结束，需要放到线程池里执行。
     *
     * @param systemPrompt 助手人设
     * @param userPrompt   已经拼好的上下文（群聊记录 + 本次要回应的内容）
     * @param callback     片段回调
     */
    void chatStreamOnce(String systemPrompt, String userPrompt, AiStreamCallback callback);
}
