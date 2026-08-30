package com.mychat.entity.dto;

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

    /**
     * 代码推送到的分支，只会是 ai/ 前缀
     */
    private String codeBranch;

    /**
     * 改动概览（git diff --stat）
     */
    private String codeDiffStat;

    /**
     * 代码是否已经推送成功
     */
    private Boolean codePushed;

    /**
     * 单元测试是否通过
     */
    private Boolean testsPassed;

    /**
     * 首次编译是否直接通过（没让模型返工修）。
     * 评测用：这个数直接反映编码Agent的实际可用性
     */
    private Boolean firstCompilePass;

    /**
     * 失败原因，只在非DONE时有值。评测时用来出失败分布，
     * 知道"败在哪一环"比知道"完成率多少"更能指导下一步优化
     */
    private String failReason;

    private Long createTime;

    private Long endTime;

    public AiWorkflowTaskDto() {
        this.retryCount = 0;
        this.reviewHistory = new ArrayList<>();
    }

    public Boolean getFirstCompilePass() {
        return firstCompilePass;
    }

    public void setFirstCompilePass(Boolean firstCompilePass) {
        this.firstCompilePass = firstCompilePass;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Long getEndTime() {
        return endTime;
    }

    public void setEndTime(Long endTime) {
        this.endTime = endTime;
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

    public String getCodeBranch() {
        return codeBranch;
    }

    public void setCodeBranch(String codeBranch) {
        this.codeBranch = codeBranch;
    }

    public String getCodeDiffStat() {
        return codeDiffStat;
    }

    public void setCodeDiffStat(String codeDiffStat) {
        this.codeDiffStat = codeDiffStat;
    }

    public Boolean getCodePushed() {
        return codePushed;
    }

    public void setCodePushed(Boolean codePushed) {
        this.codePushed = codePushed;
    }

    public Boolean getTestsPassed() {
        return testsPassed;
    }

    public void setTestsPassed(Boolean testsPassed) {
        this.testsPassed = testsPassed;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
