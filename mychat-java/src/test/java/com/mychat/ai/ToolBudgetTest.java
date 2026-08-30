package com.mychat.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具预算的熔断行为。
 *
 * 背景：机器上没装 mvn，compile 工具返回"命令无法执行"，
 * 模型看不懂这不是它能修的问题，反复 searchCode("mvn") 找编译方式，
 * 同一个调用刷了一千七百多次，只能重启服务端才停得下来。
 * 下面每条对应一道当时缺失的闸门。
 */
class ToolBudgetTest {

    @Test
    void 正常调用不受影响() {
        ToolBudget budget = new ToolBudget(null, null, 60, 20);

        assertNull(budget.check("readFile|A.java"));
        assertNull(budget.check("readFile|B.java"));
        assertFalse(budget.isStopped());
        assertEquals(2, budget.getCalls());
    }

    @Test
    void 同一个调用重复到第四次被拦下() {
        ToolBudget budget = new ToolBudget(null, null, 60, 20);

        assertNull(budget.check("searchCode|mvn|null"));
        assertNull(budget.check("searchCode|mvn|null"));
        assertNull(budget.check("searchCode|mvn|null"));
        String verdict = budget.check("searchCode|mvn|null");

        assertNotNull(verdict, "同参数第4次应该被拦下");
        assertTrue(verdict.contains("结果不会变"), verdict);
        assertEquals("同一调用重复过多", budget.getStopReason());
    }

    @Test
    void 参数不同的调用不算重复() {
        ToolBudget budget = new ToolBudget(null, null, 60, 20);

        for (int i = 0; i < 10; i++) {
            assertNull(budget.check("readFile|File" + i + ".java"));
        }
        assertFalse(budget.isStopped());
    }

    @Test
    void 超过次数上限后拒绝继续() {
        ToolBudget budget = new ToolBudget(null, null, 5, 20);

        for (int i = 0; i < 5; i++) {
            assertNull(budget.check("readFile|File" + i + ".java"));
        }
        String verdict = budget.check("readFile|File99.java");

        assertNotNull(verdict, "第6次应该超预算");
        assertTrue(verdict.contains("上限"), verdict);
        assertEquals("超出工具调用次数上限", budget.getStopReason());
    }

    @Test
    void 超过时限后拒绝继续() {
        //时限设成0分钟即"构造完就到点"，第一次check就该被拒
        ToolBudget budget = new ToolBudget(null, null, 60, 0);

        String verdict = budget.check("readFile|A.java");

        assertNotNull(verdict);
        assertTrue(verdict.contains("超时"), verdict);
        assertEquals("超出单轮时限", budget.getStopReason());
    }

    @Test
    void 用户点停止后立刻拦下并且不消耗预算() {
        AiTaskControl control = new AiTaskControl();
        control.register("task-1", "G123", "U1");
        ToolBudget budget = new ToolBudget(control, "task-1", 60, 20);

        assertNull(budget.check("readFile|A.java"));
        control.cancel("task-1", "U1");
        String verdict = budget.check("readFile|B.java");

        assertNotNull(verdict, "取消后应该立刻拦下");
        assertTrue(verdict.contains("停止"), verdict);
        assertEquals("用户已停止", budget.getStopReason());
        //取消的判断在计数之前，被拦下的这次不该记进已用次数
        assertEquals(1, budget.getCalls());
    }

    @Test
    void 停止原因只记第一个() {
        ToolBudget budget = new ToolBudget(null, null, 1, 20);

        budget.check("a");
        budget.check("b");
        assertEquals("超出工具调用次数上限", budget.getStopReason());

        budget.check("b");
        budget.check("b");
        budget.check("b");
        assertEquals("超出工具调用次数上限", budget.getStopReason(), "后续原因不该覆盖第一个");
    }
}
