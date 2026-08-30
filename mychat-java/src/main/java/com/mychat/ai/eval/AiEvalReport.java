package com.mychat.ai.eval;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告。字段就是要写进简历的那几个数
 */
public class AiEvalReport implements Serializable {

    private static final long serialVersionUID = 991823741293847L;

    private int total;

    private int completed;

    /**
     * 任务完成率，百分数保留一位小数。Agent系统的总分
     */
    private double completionRate;

    /**
     * 平均评审返工轮次
     */
    private double avgRetryCount;

    private int enteredCoding;

    /**
     * 编译一次通过率。分母只算走到编码阶段的任务——
     * 把连方案都没过的也算进来是在稀释这个数，看不出编码Agent的真实水平
     */
    private double firstCompilePassRate;

    private int codePushed;

    private int testsPassed;

    private long medianCostMs;

    /**
     * 用P90不用最大值：最大值往往是某一次超时，代表不了体感
     */
    private long p90CostMs;

    /**
     * 失败原因分布。这张表比完成率更能指导下一步做什么
     */
    private Map<String, Integer> failReasons = new LinkedHashMap<>();

    /**
     * 失败按阶段归并：需求分析 / 方案设计 / 方案评审 / 编码实现 / 用户停止。
     * 简历上写"失败集中在X阶段"要的就是这个口径，
     * 光有细分原因还得自己心算
     */
    private Map<String, Integer> stageFailures = new LinkedHashMap<>();

    /**
     * 编码阶段失败数占全部失败数的比例
     */
    private double codingFailureRate;

    /**
     * 失败最多的那个具体原因，决定下一步优化什么
     */
    private String topFailReason;

    /**
     * 有几条不同的需求（同一条跑多次算一条）
     */
    private int requirementCount;

    /**
     * 每条需求跑了几次
     */
    private int repeat;

    /**
     * 按需求分组的通过情况，形如 "给消息表加字段" -> "2/2"。
     * temperature不为0时同一条需求多次结果可能不同，
     * 只看总完成率会掩盖掉这种不稳定
     */
    private Map<String, String> perRequirement = new LinkedHashMap<>();

    private List<AiEvalRecord> records;

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getCompleted() {
        return completed;
    }

    public void setCompleted(int completed) {
        this.completed = completed;
    }

    public double getCompletionRate() {
        return completionRate;
    }

    public void setCompletionRate(double completionRate) {
        this.completionRate = completionRate;
    }

    public double getAvgRetryCount() {
        return avgRetryCount;
    }

    public void setAvgRetryCount(double avgRetryCount) {
        this.avgRetryCount = avgRetryCount;
    }

    public int getEnteredCoding() {
        return enteredCoding;
    }

    public void setEnteredCoding(int enteredCoding) {
        this.enteredCoding = enteredCoding;
    }

    public double getFirstCompilePassRate() {
        return firstCompilePassRate;
    }

    public void setFirstCompilePassRate(double firstCompilePassRate) {
        this.firstCompilePassRate = firstCompilePassRate;
    }

    public int getCodePushed() {
        return codePushed;
    }

    public void setCodePushed(int codePushed) {
        this.codePushed = codePushed;
    }

    public int getTestsPassed() {
        return testsPassed;
    }

    public void setTestsPassed(int testsPassed) {
        this.testsPassed = testsPassed;
    }

    public long getMedianCostMs() {
        return medianCostMs;
    }

    public void setMedianCostMs(long medianCostMs) {
        this.medianCostMs = medianCostMs;
    }

    public long getP90CostMs() {
        return p90CostMs;
    }

    public void setP90CostMs(long p90CostMs) {
        this.p90CostMs = p90CostMs;
    }

    public Map<String, Integer> getFailReasons() {
        return failReasons;
    }

    public void setFailReasons(Map<String, Integer> failReasons) {
        this.failReasons = failReasons;
    }

    public Map<String, Integer> getStageFailures() {
        return stageFailures;
    }

    public void setStageFailures(Map<String, Integer> stageFailures) {
        this.stageFailures = stageFailures;
    }

    public double getCodingFailureRate() {
        return codingFailureRate;
    }

    public void setCodingFailureRate(double codingFailureRate) {
        this.codingFailureRate = codingFailureRate;
    }

    public String getTopFailReason() {
        return topFailReason;
    }

    public void setTopFailReason(String topFailReason) {
        this.topFailReason = topFailReason;
    }

    public int getRequirementCount() {
        return requirementCount;
    }

    public void setRequirementCount(int requirementCount) {
        this.requirementCount = requirementCount;
    }

    public int getRepeat() {
        return repeat;
    }

    public void setRepeat(int repeat) {
        this.repeat = repeat;
    }

    public Map<String, String> getPerRequirement() {
        return perRequirement;
    }

    public void setPerRequirement(Map<String, String> perRequirement) {
        this.perRequirement = perRequirement;
    }

    public List<AiEvalRecord> getRecords() {
        return records;
    }

    public void setRecords(List<AiEvalRecord> records) {
        this.records = records;
    }
}
