package com.mychat.entity.dto;

import java.io.Serializable;

/**
 * AI对话历史中的一条消息。
 * 只存角色和文本，不直接序列化Spring AI的Message实现类——
 * 那些类的结构随框架版本变动，落到Redis里会导致升级后反序列化失败。
 */
public class AiHistoryMessageDto implements Serializable {

    private static final long serialVersionUID = 8823164905371882293L;

    public static final String ROLE_USER = "user";

    public static final String ROLE_ASSISTANT = "assistant";

    private String role;

    private String content;

    public AiHistoryMessageDto() {
    }

    public AiHistoryMessageDto(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
