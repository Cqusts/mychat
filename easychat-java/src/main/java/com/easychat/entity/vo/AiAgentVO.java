package com.easychat.entity.vo;

/**
 * 可拉进群的AI助手。
 * 字段名对齐前端选人组件的contactId/contactName约定，
 * 这样拉助手进群可以直接复用现有的添加群成员流程。
 */
public class AiAgentVO {

    private String contactId;

    private String contactName;

    private String signature;

    public String getContactId() {
        return contactId;
    }

    public void setContactId(String contactId) {
        this.contactId = contactId;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }
}
