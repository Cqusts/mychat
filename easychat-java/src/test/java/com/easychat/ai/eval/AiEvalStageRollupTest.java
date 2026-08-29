package com.easychat.ai.eval;

import com.easychat.controller.AiEvalTextReport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败按阶段归并 + 简历口径那句话的生成。
 *
 * 这两个数（失败集中在哪个阶段、占比多少）是要直接写进简历的，
 * 归错类比算错还糟——面试时说"失败集中在编码"，人家一看明细全是评审打回
 */
class AiEvalStageRollupTest {

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

    private AiEvalRecord fail(String requirement, String reason) {
        AiEvalRecord r = new AiEvalRecord();
        r.setTaskId("t" + (seq++));
        r.setRequirement(requirement);
        r.setStage("FAILED");
        r.setRetryCount(0);
        r.setEnteredCoding(true);
        r.setFirstCompilePass(false);
        r.setFailReason(reason);
        r.setCostMs(60_000L);
        return r;
    }

    private AiEvalRecord done(String requirement) {
        AiEvalRecord r = new AiEvalRecord();
        r.setTaskId("t" + (seq++));
        r.setRequirement(requirement);
        r.setStage("DONE");
        r.setRetryCount(0);
        r.setEnteredCoding(true);
        r.setFirstCompilePass(true);
        r.setCostMs(60_000L);
        return r;
    }

    @Test
    void 各种编码期失败都归到编码实现() {
        List<AiEvalRecord> data = List.of(
                fail("A", "编码零改动"),
                fail("B", "编译不通过"),
                fail("C", "推送失败"),
                fail("D", "编码被熔断(超出工具调用次数上限)"),
                fail("E", "编码环境不可用"),
                fail("F", "准备代码工作区失败：clone超时"),
                fail("G", "编码阶段调用失败"));

        AiEvalReport report = reportOf(data);

        assertEquals(7, report.getStageFailures().get("编码实现"));
        assertEquals(100.0, report.getCodingFailureRate());
    }

    @Test
    void 评审和用户停止不算编码失败() {
        List<AiEvalRecord> data = List.of(
                fail("A", "编码零改动"),
                fail("B", "编码零改动"),
                fail("C", "方案未通过评审"),
                fail("D", "用户停止(编码实现)"));

        AiEvalReport report = reportOf(data);

        assertEquals(2, report.getStageFailures().get("编码实现"));
        assertEquals(1, report.getStageFailures().get("方案评审"));
        //停在编码那一步但原因是人点了停止，不该算成系统能力问题
        assertEquals(1, report.getStageFailures().get("用户停止"));
        assertEquals(50.0, report.getCodingFailureRate());
    }

    @Test
    void 占比的分母是失败数不是任务总数() {
        List<AiEvalRecord> data = List.of(
                done("A"), done("B"), done("C"),
                fail("D", "编码零改动"),
                fail("E", "方案未通过评审"));

        AiEvalReport report = reportOf(data);

        assertEquals(5, report.getTotal());
        //1个编码失败 / 2个失败 = 50%，不是 1/5
        assertEquals(50.0, report.getCodingFailureRate());
    }

    @Test
    void 需求条数和运行次数能反推出来() {
        List<AiEvalRecord> data = List.of(
                done("需求一"), fail("需求一", "编码零改动"),
                done("需求二"), done("需求二"),
                fail("需求三", "编译不通过"), fail("需求三", "编译不通过"));

        AiEvalReport report = reportOf(data);

        assertEquals(3, report.getRequirementCount());
        assertEquals(2, report.getRepeat());
    }

    @Test
    void 简历那句话把数字都填进去了() {
        List<AiEvalRecord> data = new ArrayList<>();
        data.add(done("需求一"));
        data.add(fail("需求一", "编码零改动"));

        String text = AiEvalTextReport.render(reportOf(data), 20.0);

        assertTrue(text.contains("构建 1 条难度分层的需求评测集（每条运行 2 次，共 2 个任务）"), text);
        assertTrue(text.contains("完成率 50.0%"), text);
        assertTrue(text.contains("失败集中在编码实现阶段（100.0%）"), text);
        //20元 / 2个任务 = 10元
        assertTrue(text.contains("token 成本约 10.00 元"), text);
        //结论那格必须留空，它是判断不是数据
        assertTrue(text.contains("据此定位瓶颈为 __"), text);
    }

    @Test
    void 没传成本时那一格留空并给出算法() {
        String text = AiEvalTextReport.render(reportOf(List.of(done("需求一"))), null);

        assertTrue(text.contains("token 成本约 __ 元"), text);
        assertTrue(text.contains("totalCostYuan"), text);
    }

    @Test
    void 零失败时不报错也不瞎给结论() {
        String text = AiEvalTextReport.render(reportOf(List.of(done("A"), done("B"))), null);

        assertTrue(text.contains("（无失败）"), text);
        assertTrue(text.contains("任务集太简单"), text);
    }
}
