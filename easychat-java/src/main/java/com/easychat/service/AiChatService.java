package com.easychat.service;

/**
 * AI大模型对话服务接口
 */
public interface AiChatService {

    /**
     * 调用AI大模型获取回复（阻塞式，一次性返回完整内容）
     *
     * @param userId  发送消息的用户ID
     * @param message 用户发送的消息内容
     * @return AI生成的回复内容
     */
    String chat(String userId, String message);

    /**
     * 流式调用AI大模型，片段通过回调逐步返回。
     * 本方法会阻塞到整轮回复结束，调用方需自行放到线程池中执行。
     *
     * @param userId   发送消息的用户ID
     * @param message  用户发送的消息内容
     * @param callback 片段回调
     */
    void chatStream(String userId, String message, AiStreamCallback callback);
}
