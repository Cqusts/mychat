package com.mychat.entity.vo;

/**
 * 可拉进群的AI助手。
 * 字段名对齐前端选人组件的contactId/contactName约定，
 * 这样拉助手进群可以直接复用现有的添加群成员流程。
 */
public class AiAgentVO {

    private String contactId;

    private String contactName;

    private String signature;

    /**
     * 能力说明，多条用 | 分隔
     */
    private String description;

    /**
     * 当前用户是否已经把它加为联系人
     */
    private Boolean inContact;

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getInContact() {
        return inContact;
    }

    public void setInContact(Boolean inContact) {
        this.inContact = inContact;
    }
}
