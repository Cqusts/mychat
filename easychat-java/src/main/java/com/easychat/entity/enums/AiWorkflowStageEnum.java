package com.easychat.entity.enums;

/**
 * 需求流水线的阶段。
 *
 * 之前靠助手自己@下一个人来推进，实测第2棒就跳链（架构师直接跳过评审和开发去@测试），
 * 所以改成由状态机决定下一棒，模型只负责产出内容。
 */
public enum AiWorkflowStageEnum {

    REQUIREMENT("需求分析"),
    DESIGN("方案设计"),
    REVIEW("方案评审"),
    DONE("已完成"),
    FAILED("已终止");

    private final String desc;

    AiWorkflowStageEnum(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
