package com.easychat.ai.eval;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标计算的正确性。
 *
 * 这些数字是要写进简历、拿去面试被追问的，算错比不算更糟，
 * 所以每条统计口径都钉一个用例
 */
class AiEvalReportTest {

    private int seq = 0;

    /**
     * 不起Redis，直接把 loadAll 要的数据塞进去
     */
    private AiEvalReport reportOf(List<AiEvalRecord> data) {
        AiEvalRecorder recorder = new AiEvalRecorder() {
            @Override
            public List<AiEvalRecord> loadAll() {
                return data;
            }
        };
        ReflectionTestUtils.setField(recorder, "evalEnabled", true);
        return recorder.report();
    }

    private AiEvalRecord record(String requirement, String stage, int retry,
                                Boolean enteredCoding, Boolean firstCompile, long costMs) {
        AiEvalRecord r = new AiEvalRecord();
        r.setTaskId("t" + (seq++));
        r.setRequirement(requirement);
        r.setStage(stage);
        r.setRetryCount(retry);
        r.setEnteredCoding(enteredCoding);
        r.setFirstCompilePass(firstCompile);
        r.setCostMs(costMs);
        return r;
    }

    @Test
    void 没有记录时不炸() {
        AiEvalReport report = reportOf(new ArrayList<>());

        assertEquals(0, report.getTotal());
        assertEquals(0.0, report.getCompletionRate());
        assertTrue(report.getFailReasons().isEmpty());
    }

    @Test
    void 完成率按DONE算() {
        List<AiEvalRecord> data = List.of(
                record("A", "DONE", 0, true, true, 60_000),
                record("B", "DONE", 1, true, false, 120_000),
                record("C", "FAILED", 2, true, false, 180_000),
                record("D", "CANCELLED", 0, false, null, 30_000));

        AiEvalReport report = reportOf(data);

        assertEquals(4, report.getTotal());
        assertEquals(2, report.getCompleted());
        assertEquals(50.0, report.getCompletionRate());
    }

    @Test
    void 编译一次通过率的分母只算进入编码阶段的() {
        List<AiEvalRecord> data = List.of(
                record("A", "DONE", 0, true, true, 1000),
                record("B", "FAILED", 0, true, false, 1000),
                //下面两条连编码都没走到，不该稀释这个比率
                record("C", "FAILED", 2, false, null, 1000),
                record("D", "FAILED", 2, false, null, 1000));

        AiEvalReport report = reportOf(data);

        assertEquals(2, report.getEnteredCoding());
        assertEquals(50.0, report.getFirstCompilePassRate(), "分母应该是2不是4");
    }

    @Test
    void 一个都没进编码时通过率是0而不是除零() {
        AiEvalReport report = reportOf(List.of(
                record("A", "FAILED", 2, false, null, 1000)));

        assertEquals(0, report.getEnteredCoding());
        assertEquals(0.0, report.getFirstCompilePassRate());
    }

    @Test
    void 平均返工轮次保留一位小数() {
        List<AiEvalRecord> data = List.of(
                record("A", "DONE", 0, true, true, 1000),
                record("B", "DONE", 1, true, true, 1000),
                record("C", "DONE", 2, true, true, 1000));

        assertEquals(1.0, reportOf(data).getAvgRetryCount());
    }

    @Test
    void 中位数和P90取排序后的分位() {
        List<AiEvalRecord> data = new ArrayList<>();
        //故意乱序，验证内部确实排过序
        long[] costs = {100_000, 10_000, 50_000, 20_000, 90_000,
                30_000, 80_000, 40_000, 70_000, 60_000};
        for (long cost : costs) {
            data.add(record("R" + cost, "DONE", 0, true, true, cost));
        }

        AiEvalReport report = reportOf(data);

        //10个样本：0.5分位取第5个(50000)，0.9分位取第9个(90000)
        assertEquals(50_000, report.getMedianCostMs());
        assertEquals(90_000, report.getP90CostMs());
    }

    @Test
    void 失败原因按次数汇总且成功的不计入() {
        AiEvalRecord ok = record("A", "DONE", 0, true, true, 1000);
        AiEvalRecord f1 = record("B", "FAILED", 0, true, false, 1000);
        f1.setFailReason("编译不通过");
        AiEvalRecord f2 = record("C", "FAILED", 0, true, false, 1000);
        f2.setFailReason("编译不通过");
        AiEvalRecord f3 = record("D", "FAILED", 0, false, null, 1000);
        f3.setFailReason("编码零改动");

        AiEvalReport report = reportOf(List.of(ok, f1, f2, f3));

        assertEquals(2, report.getFailReasons().size());
        assertEquals(2, report.getFailReasons().get("编译不通过"));
        assertEquals(1, report.getFailReasons().get("编码零改动"));
    }

    @Test
    void 同一需求多次运行分开统计() {
        List<AiEvalRecord> data = List.of(
                record("加个字段", "DONE", 0, true, true, 1000),
                record("加个字段", "FAILED", 0, true, false, 1000),
                record("消息撤回", "FAILED", 2, false, null, 1000),
                record("消息撤回", "FAILED", 2, false, null, 1000));

        AiEvalReport report = reportOf(data);

        //同一条需求一次成一次败，说明系统不稳定，这比总完成率更值得看
        assertEquals("1/2", report.getPerRequirement().get("加个字段"));
        assertEquals("0/2", report.getPerRequirement().get("消息撤回"));
    }

    @Test
    void 没写失败原因时用停在哪个阶段兜底() {
        AiEvalReport report = reportOf(List.of(
                record("A", "REVIEW", 2, false, null, 1000)));

        assertEquals(1, report.getFailReasons().get("停在方案评审"));
    }

    @Test
    void 文本报告能正常渲染() {
        String text = com.easychat.controller.AiEvalTextReport.render(reportOf(
                List.of(record("给消息表加字段", "DONE", 1, true, true, 125_000))));

        assertTrue(text.contains("100.0%"), text);
        assertTrue(text.contains("2分5秒"), text);
    }
}
