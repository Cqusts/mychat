package com.mychat.ai.eval;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 需求达成率的口径。
 *
 * 和任务完成率是两回事：完成率只证明代码编得过、推得上去，
 * 达成率要求那套验收测试先红后绿——需求真的实现了。
 * 简历上这两个数是分开写的，算混了面试时会被问穿
 */
class AiEvalAcceptanceTest {

    private int seq = 0;

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

    private AiEvalRecord record(String stage, Boolean redGate, Boolean acceptance) {
        AiEvalRecord r = new AiEvalRecord();
        r.setTaskId("t" + (seq++));
        r.setRequirement("需求" + seq);
        r.setStage(stage);
        r.setRetryCount(0);
        r.setEnteredCoding(true);
        r.setCostMs(1000L);
        r.setRedGatePassed(redGate);
        r.setAcceptancePassed(acceptance);
        return r;
    }

    /**
     * 绿灯没过的任务照样是 DONE（代码编译通过也推送了），
     * 所以完成率100%而达成率只有50%。这个差值正是TDD想暴露的东西：
     * 一半的"完成"其实没实现需求
     */
    @Test
    void 完成率和达成率是两个口径() {
        List<AiEvalRecord> data = List.of(
                record("DONE", true, true),
                record("DONE", true, false));

        AiEvalReport report = reportOf(data);

        assertEquals(100.0, report.getCompletionRate());
        assertEquals(2, report.getRedGatePassed());
        assertEquals(1, report.getAccepted());
        assertEquals(50.0, report.getAcceptanceRate());
    }

    /**
     * 红灯就没过的（写出来的测试是假的）不能从分母里摘出去——
     * 测试都没写对，需求当然也谈不上达成
     */
    @Test
    void 红灯没过的算进达成率的分母() {
        List<AiEvalRecord> data = List.of(
                record("DONE", true, true),
                record("FAILED", false, null),
                record("FAILED", false, null),
                record("FAILED", false, null));

        AiEvalReport report = reportOf(data);

        assertEquals(1, report.getRedGatePassed());
        assertEquals(1, report.getAccepted());
        assertEquals(25.0, report.getAcceptanceRate());
    }

    /**
     * 只有绿灯没有红灯不算达成：这种组合意味着测试一开始就是通过的，
     * 也就是它根本没测到新行为
     */
    @Test
    void 只有绿灯没有红灯不算达成() {
        List<AiEvalRecord> data = List.of(record("DONE", false, true));

        AiEvalReport report = reportOf(data);

        assertEquals(0, report.getAccepted());
        assertEquals(0.0, report.getAcceptanceRate());
    }

    /**
     * TDD 关掉的批次两个门都是null，达成率恒为0，
     * 不能因此干扰完成率——报告里这几行也不会印出来
     */
    @Test
    void 没开TDD时不影响其他指标() {
        List<AiEvalRecord> data = List.of(
                record("DONE", null, null),
                record("FAILED", null, null));

        AiEvalReport report = reportOf(data);

        assertEquals(50.0, report.getCompletionRate());
        assertEquals(0, report.getRedGatePassed());
        assertEquals(0, report.getAccepted());
        assertEquals(0.0, report.getAcceptanceRate());
    }

    @Test
    void 没有记录时不炸() {
        AiEvalReport report = reportOf(new ArrayList<>());

        assertEquals(0, report.getAccepted());
        assertEquals(0.0, report.getAcceptanceRate());
    }
}
