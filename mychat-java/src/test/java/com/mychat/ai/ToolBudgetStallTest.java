package com.mychat.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失速检测。
 *
 * 存在的理由是成本：实测失败任务占55%且全都烧满预算，
 * 而 token 开销随轮次平方增长，所以这部分支出占了大头。
 * 一直在搜在读却一个文件都没动，就是走不通了，早点收手比耗到上限便宜得多
 */
class ToolBudgetStallTest {

    @Test
    void 连续空转到上限就叫停() {
        ToolBudget budget = new ToolBudget(null, null, 100, 20, 5);

        for (int i = 0; i < 5; i++) {
            assertNull(budget.check("readFile|File" + i), "第" + (i + 1) + "次不该被拦");
        }
        String verdict = budget.check("readFile|Another");

        assertNotNull(verdict, "连续6次没改动就该叫停");
        assertTrue(verdict.contains("一个文件都没改动"), verdict);
        assertEquals("连续多次调用无实际进展", budget.getStopReason());
    }

    @Test
    void 改动文件后计数清零() {
        ToolBudget budget = new ToolBudget(null, null, 100, 20, 5);

        for (int i = 0; i < 5; i++) {
            budget.check("readFile|File" + i);
        }
        assertEquals(5, budget.getCallsSinceProgress());

        //真改了文件，重新给它5次探索的机会
        budget.recordProgress();
        assertEquals(0, budget.getCallsSinceProgress());

        for (int i = 0; i < 5; i++) {
            assertNull(budget.check("outline|File" + i), "清零后应该重新放行");
        }
    }

    @Test
    void 边搜边改的正常节奏不会被误伤() {
        ToolBudget budget = new ToolBudget(null, null, 100, 20, 5);

        //模拟真实工作节奏：找几次、改一次、再找几次、再改一次
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 4; i++) {
                assertNull(budget.check("readFile|r" + round + "f" + i));
            }
            budget.recordProgress();
        }
        assertNull(budget.getStopReason(), "正常节奏不该触发失速");
    }

    @Test
    void 失速优先于次数上限被触发() {
        //次数还很富裕，但已经空转很久了——应该报失速而不是报超预算
        ToolBudget budget = new ToolBudget(null, null, 100, 20, 3);

        budget.check("a");
        budget.check("b");
        budget.check("c");
        budget.check("d");

        assertEquals("连续多次调用无实际进展", budget.getStopReason());
        assertTrue(budget.getCalls() < 100, "远没到次数上限");
    }
}
