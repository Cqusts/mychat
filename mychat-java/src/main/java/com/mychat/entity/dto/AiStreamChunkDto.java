package com.mychat.entity.dto;

import java.io.Serializable;

/**
 * AI流式回复片段，作为MessageSendDto的extendData下发给前端。
 * 同一次回复的所有片段共享一个streamId，前端据此把片段拼接到同一个消息气泡中。
 */
public class AiStreamChunkDto implements Serializable {

    private static final long serialVersionUID = 3721068894112354701L;

    /**
     * 本次流式回复的唯一标识
     */
    private String streamId;

    /**
     * 片段内容。
     * AI_STREAM：本次新增的增量文本；
     * AI_STREAM_END：完整回复内容，供前端做最终校准；
     * AI_TOOL_CALL：工具调用提示语，如"正在搜索聊天记录…"
     */
    private String content;

    /**
     * 片段序号，从0开始递增，前端可用于检测乱序或丢片
     */
    private Integer index;

    public AiStreamChunkDto() {
    }

    public AiStreamChunkDto(String streamId, String content, Integer index) {
        this.streamId = streamId;
        this.content = content;
        this.index = index;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getIndex() {
        return index;
    }

    public void setIndex(Integer index) {
        this.index = index;
    }
}
