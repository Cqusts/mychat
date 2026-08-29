package com.easychat.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在跑的流水线任务登记处，负责"能把它停下来"。
 *
 * 为什么需要：模型陷在工具循环里是会真的停不下来的。
 * 线上遇到过一次——机器上没装 mvn，compile 工具返回"命令无法执行"，
 * 模型看不懂这不是它能修的问题，于是反复 searchCode("mvn") 找编译方式，
 * 同一个调用刷了一千七百多次，只能靠重启服务端才收得住。
 *
 * 停止是两层的：
 *   1. 打标记。工具方法每次进来先看标记，看到就不干活并让模型收尾。
 *      正常情况下一次工具调用之内就停了，代价最小。
 *   2. 打断线程。卡在网络读或者 mvn 上时标记是轮询不到的，
 *      这时候直接 interrupt 跑任务的那个线程。
 *
 * 只存在于单个节点的内存里。集群部署时停止请求要打到跑任务的那个节点，
 * 目前是单机跑，先不引入额外的协调开销。
 */
@Component("aiTaskControl")
public class AiTaskControl {

    private static final Logger logger = LoggerFactory.getLogger(AiTaskControl.class);

    private final Map<String, Handle> running = new ConcurrentHashMap<>();

    public static class Handle {
        private final String taskId;
        private final String groupId;
        private final String requesterId;
        private final long startTime = System.currentTimeMillis();
        private volatile boolean cancelled;
        /**
         * 跑这个任务的线程。runTask 真正开跑之后才绑得上
         */
        private volatile Thread worker;

        Handle(String taskId, String groupId, String requesterId) {
            this.taskId = taskId;
            this.groupId = groupId;
            this.requesterId = requesterId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getRequesterId() {
            return requesterId;
        }

        public long getStartTime() {
            return startTime;
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    public void register(String taskId, String groupId, String requesterId) {
        running.put(taskId, new Handle(taskId, groupId, requesterId));
    }

    /**
     * 任务线程开跑时把自己绑上去，供硬中断使用
     */
    public void bindWorker(String taskId) {
        Handle handle = running.get(taskId);
        if (handle != null) {
            handle.worker = Thread.currentThread();
        }
    }

    public void finish(String taskId) {
        running.remove(taskId);
    }

    public boolean isCancelled(String taskId) {
        Handle handle = running.get(taskId);
        return handle != null && handle.cancelled;
    }

    public Handle get(String taskId) {
        return running.get(taskId);
    }

    public List<Handle> runningInGroup(String groupId) {
        List<Handle> result = new ArrayList<>();
        for (Handle handle : running.values()) {
            if (handle.groupId.equals(groupId) && !handle.cancelled) {
                result.add(handle);
            }
        }
        return result;
    }

    /**
     * 停掉一个群里正在跑的所有任务
     *
     * @return 实际停掉了几个
     */
    public int cancelByGroup(String groupId, String operatorId) {
        int count = 0;
        for (Handle handle : runningInGroup(groupId)) {
            if (cancel(handle.taskId, operatorId)) {
                count++;
            }
        }
        return count;
    }

    public boolean cancel(String taskId, String operatorId) {
        Handle handle = running.get(taskId);
        if (handle == null || handle.cancelled) {
            return false;
        }
        handle.cancelled = true;
        logger.info("用户终止流水线任务, taskId:{}, groupId:{}, 操作人:{}, 已运行{}秒",
                taskId, handle.groupId, operatorId,
                (System.currentTimeMillis() - handle.startTime) / 1000);

        //标记之后再补一刀中断：卡在HTTP读或者mvn上时是轮询不到标记的。
        //中断可能落在Redis调用上，所以引擎那边收尾前要先把中断状态清掉
        Thread worker = handle.worker;
        if (worker != null) {
            worker.interrupt();
        }
        return true;
    }
}
