package com.easychat.entity.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次需求流水线任务的完整状态。
 *
 * 这个对象的存在本身就是编排的核心：之前每个角色靠"最近N条群消息"推断上下文，
 * 结果是上一个需求的内容会串进来。现在每个阶段的产物精确存在这里，
 * 下一棒拿到的是确定的输入，而不是聊天记录的残留。
 */
public class AiWorkflowTaskDto implements Serializable {

    private static final long serialVersionUID = 6647210934418823311L;

    private String taskId;

    private String groupId;

    private String sessionId;

    /**
     * 提出需求的人，流程结束时要@回他
     */
    private String requesterId;

    private String requesterNickName;

    /**
     * 原始需求，全程不变，每个阶段都会带上——
     * 避免越靠后的角色越不知道最初要做什么
     */
    private String requirement;

    /**
     * 当前阶段，取值见 AiWorkflowStageEnum
     */
    private String stage;

    /**
     * 第1棒产物：需求分析
     */
    private String requirementDoc;

    /**
     * 第2棒产物：技术方案
     */
    private String techPlan;

    /**
     * 第3棒产物：评审意见
     */
    private String reviewResult;

    /**
     * 评审是否通过
     */
    private Boolean reviewPassed;

    /**
     * 历次评审意见。
     * 不记的话每轮评审都是从零开始，既不知道自己上轮说过什么、也判断不了这版有没有改好——
     * 实测出现过"第2轮要求加is_permanent字段、第3轮又说这个字段冗余要删掉"的自我矛盾
     */
    private List<String> reviewHistory;

    /**
     * 方案被打回重做的次数
     */
    private Integer retryCount;

    private Long createTime;

    public AiWorkflowTaskDto() {
        this.retryCount = 0;
        this.reviewHistory = new ArrayList<>();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(String requesterId) {
        this.requesterId = requesterId;
    }

    public String getRequesterNickName() {
        return requesterNickName;
    }

    public void setRequesterNickName(String requesterNickName) {
        this.requesterNickName = requesterNickName;
    }

    public String getRequirement() {
        return requirement;
    }

    public void setRequirement(String requirement) {
        this.requirement = requirement;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getRequirementDoc() {
        return requirementDoc;
    }

    public void setRequirementDoc(String requirementDoc) {
        this.requirementDoc = requirementDoc;
    }

    public String getTechPlan() {
        return techPlan;
    }

    public void setTechPlan(String techPlan) {
        this.techPlan = techPlan;
    }

    public String getReviewResult() {
        return reviewResult;
    }

    public void setReviewResult(String reviewResult) {
        this.reviewResult = reviewResult;
    }

    public List<String> getReviewHistory() {
        return reviewHistory;
    }

    public void setReviewHistory(List<String> reviewHistory) {
        this.reviewHistory = reviewHistory;
    }

    public Boolean getReviewPassed() {
        return reviewPassed;
    }

    public void setReviewPassed(Boolean reviewPassed) {
        this.reviewPassed = reviewPassed;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
