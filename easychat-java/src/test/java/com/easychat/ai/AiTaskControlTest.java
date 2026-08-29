package com.easychat.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiTaskControlTest {

    @Test
    void 按群停止只影响这个群() {
        AiTaskControl control = new AiTaskControl();
        control.register("t1", "G1", "U1");
        control.register("t2", "G1", "U1");
        control.register("t3", "G2", "U2");

        assertEquals(2, control.cancelByGroup("G1", "U1"));
        assertTrue(control.isCancelled("t1"));
        assertTrue(control.isCancelled("t2"));
        assertFalse(control.isCancelled("t3"));
    }

    @Test
    void 重复停止不会重复计数() {
        AiTaskControl control = new AiTaskControl();
        control.register("t1", "G1", "U1");

        assertTrue(control.cancel("t1", "U1"));
        assertFalse(control.cancel("t1", "U1"), "已经停过的不该再算一次");
        assertEquals(0, control.cancelByGroup("G1", "U1"));
    }

    @Test
    void 已停止的任务不再出现在运行列表里() {
        AiTaskControl control = new AiTaskControl();
        control.register("t1", "G1", "U1");
        assertEquals(1, control.runningInGroup("G1").size());

        control.cancel("t1", "U1");
        assertTrue(control.runningInGroup("G1").isEmpty());

        control.finish("t1");
        assertFalse(control.isCancelled("t1"), "注销之后查不到，也就谈不上已取消");
    }

    @Test
    void 停止会打断卡住的任务线程() throws Exception {
        AiTaskControl control = new AiTaskControl();
        control.register("t1", "G1", "U1");
        CountDownLatch bound = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean gotInterrupt = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            control.bindWorker("t1");
            bound.countDown();
            try {
                //模拟卡在网络读或者mvn上：这种时候轮询标记是没用的
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                gotInterrupt.set(true);
                interrupted.countDown();
            }
        });
        worker.start();
        assertTrue(bound.await(5, TimeUnit.SECONDS), "线程没能及时绑上");

        control.cancel("t1", "U1");

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "停止应该打断卡住的线程");
        assertTrue(gotInterrupt.get());
        worker.join(1000);
    }
}
