package com.mychat.ai.eval;

import java.io.Serializable;

/**
 * 一次流水线任务跑完之后留下的评测记录。
 *
 * 只记结果，不记过程内容——过程在群聊里、在日志里都能看到，
 * 这里要的是能直接算成指标的字段
 */
public class AiEvalRecord implements Serializable {

    private static final long serialVersionUID = 8123421983412341L;

    private String taskId;

    /**
     * 同一条需求跑多次时用来分组。取需求文本本身
     */
    private String requirement;

    /**
     * 终态：DONE / FAILED / CANCELLED
     */
    private String stage;

    /**
     * 方案被打回重做的次数
     */
    private Integer retryCount;

    /**
     * 是否走到了编码阶段。没走到的话"编译一次通过率"不该把它算进分母
     */
    private Boolean enteredCoding;

    private Boolean firstCompilePass;

    private Boolean codePushed;

    private Boolean testsPassed;

    /**
     * 红灯门禁：测试先行阶段写出来的测试确实是失败的。
     * 一次就能跑通说明它没测到新行为，是假测试
     */
    private Boolean redGatePassed;

    /**
     * 绿灯门禁：那套测试在功能实现之后全部通过了。
     * 红绿都过才算需求真的达成——只有编译通过是证明不了的
     */
    private Boolean acceptancePassed;

    /**
     * 失败原因，成功时为空
     */
    private String failReason;

    private Long costMs;

    private Long createTime;

    public AiEvalRecord() {
    }

    public boolean isSuccess() {
        return "DONE".equals(stage);
    }

    /**
     * 需求达成：红绿两道门都过。比 isSuccess 严格得多
     */
    public boolean isAccepted() {
        return Boolean.TRUE.equals(redGatePassed) && Boolean.TRUE.equals(acceptancePassed);
    }

    public Boolean getRedGatePassed() {
        return redGatePassed;
    }

    public void setRedGatePassed(Boolean redGatePassed) {
        this.redGatePassed = redGatePassed;
    }

    public Boolean getAcceptancePassed() {
        return acceptancePassed;
    }

    public void setAcceptancePassed(Boolean acceptancePassed) {
        this.acceptancePassed = acceptancePassed;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
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

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public Boolean getEnteredCoding() {
        return enteredCoding;
    }

    public void setEnteredCoding(Boolean enteredCoding) {
        this.enteredCoding = enteredCoding;
    }

    public Boolean getFirstCompilePass() {
        return firstCompilePass;
    }

    public void setFirstCompilePass(Boolean firstCompilePass) {
        this.firstCompilePass = firstCompilePass;
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

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Long getCostMs() {
        return costMs;
    }

    public void setCostMs(Long costMs) {
        this.costMs = costMs;
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }
}
