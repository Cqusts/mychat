package com.mychat.service;

/**
 * AI流式回复回调。
 * AiChatService只负责调用大模型并按片段回调，
 * 具体如何推送、如何落库由调用方决定，避免AI层耦合IM业务。
 */
public interface AiStreamCallback {

    /**
     * 收到一个聚合后的增量片段
     *
     * @param delta 本次新增的文本
     */
    void onChunk(String delta);

    /**
     * AI正在调用工具（为后续Function Calling预留）
     *
     * @param toolHint 展示给用户的提示语，如"正在查询好友列表…"
     */
    default void onToolCall(String toolHint) {
    }

    /**
     * 回复正常结束
     *
     * @param fullContent 完整回复内容
     */
    void onComplete(String fullContent);

    /**
     * 回复异常结束
     *
     * @param errorMessage 展示给用户的兜底文案
     */
    void onError(String errorMessage);
}
