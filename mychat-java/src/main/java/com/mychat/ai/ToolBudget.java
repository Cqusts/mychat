package com.mychat.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 一轮 Agent 调用的工具预算。
 *
 * 带工具的模型调用没有天然的终点：它可以一直调下去。
 * 而 Flux.timeout 管的是"两个分片之间的间隔"，只要工具一直有产出就永远不触发，
 * 挡不住原地打转。所以次数、墙钟时间、重复调用这三条线得自己划。
 *
 * 每轮一个实例，不是共享的，不需要考虑跨任务的竞争，
 * 但同一轮里工具可能被并行调用，所以 check 上了锁。
 */
public class ToolBudget {

    private static final int MAX_SAME_CALL = 3;

    /**
     * 连续多少次调用没改动任何文件就判定失速。
     *
     * 20 是照着实测数据定的：成功的任务通常20~40次调用就收工，
     * 而定位阶段（findFiles + 几次outline + readFiles）撑死十来次。
     * 到第20次还一个字没改，基本可以断定它找不到北了
     */
    private static final int DEFAULT_STALL_LIMIT = 20;

    private final AiTaskControl control;

    private final String taskId;

    private final int maxCalls;

    private final int stallLimit;

    private final long deadline;

    private final Map<String, Integer> repeats = new HashMap<>();

    private int calls;

    /**
     * 距离上一次真正改动文件，已经空转了多少次调用
     */
    private int callsSinceProgress;

    /**
     * 被熔断的原因，null表示这一轮是正常跑完的。引擎据此决定怎么跟用户交代
     */
    private String stopReason;

    public ToolBudget(AiTaskControl control, String taskId, int maxCalls, int deadlineMinutes) {
        this(control, taskId, maxCalls, deadlineMinutes, DEFAULT_STALL_LIMIT);
    }

    public ToolBudget(AiTaskControl control, String taskId, int maxCalls,
                      int deadlineMinutes, int stallLimit) {
        this.control = control;
        this.taskId = taskId;
        this.maxCalls = maxCalls;
        this.deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(deadlineMinutes);
        this.stallLimit = stallLimit;
    }

    /**
     * 有文件真的被改动了，失速计数清零。
     * 只有写文件才算数——搜索和读文件再多也不代表在推进
     */
    public synchronized void recordProgress() {
        callsSinceProgress = 0;
    }

    /**
     * 每次工具调用前过一道。
     *
     * @param signature 工具名+参数，用来识别原地打转
     * @return null 表示放行；否则是要回给模型的收尾指令
     */
    public synchronized String check(String signature) {
        if (control != null && taskId != null && control.isCancelled(taskId)) {
            return stop("用户已停止",
                    "用户点了停止。请立刻停止调用任何工具，用一句中文说明你做到哪一步，然后结束。");
        }
        //用 >= ：deadline 是"到点"而不是"超过点"，
        //时限配成0时应当立刻拒绝，而不是等下一毫秒
        if (System.currentTimeMillis() >= deadline) {
            return stop("超出单轮时限",
                    "本轮已超时。请立刻停止调用工具，用中文简要说明你已经完成的改动，然后结束。");
        }
        calls++;
        if (calls > maxCalls) {
            return stop("超出工具调用次数上限",
                    "本轮工具调用已达上限（" + maxCalls + "次）。请立刻停止调用工具，"
                            + "用中文说明你已经完成了什么、还差什么，然后结束。");
        }
        //失速检测：一直在搜、在读，但一个文件都没动，说明已经迷路了。
        //实测失败任务占55%且全都烧满预算，而按平方增长的成本关系，
        //这部分支出占了大头——早点收手比让它耗到上限便宜得多
        callsSinceProgress++;
        if (callsSinceProgress > stallLimit) {
            return stop("连续多次调用无实际进展",
                    "你已经连续" + stallLimit + "次调用工具但一个文件都没改动，说明当前思路走不通。"
                            + "请停止继续搜索，用中文说明你想改什么、卡在哪里、需要什么信息，然后结束。");
        }

        int same = repeats.merge(signature, 1, Integer::sum);
        if (same > MAX_SAME_CALL) {
            //同样的参数调同样的工具，结果一定也一样，再试多少次都不会变
            return stop("同一调用重复过多",
                    "你已经用完全相同的参数调用过这个工具" + (same - 1) + "次，结果不会变。"
                            + "换个思路，或者直接用中文说明当前卡在哪里、需要什么前提条件，然后结束。");
        }
        return null;
    }

    private String stop(String reason, String hint) {
        if (stopReason == null) {
            stopReason = reason;
        }
        return hint;
    }

    public boolean isStopped() {
        return stopReason != null;
    }

    public String getStopReason() {
        return stopReason;
    }

    public int getCalls() {
        return calls;
    }

    public int getCallsSinceProgress() {
        return callsSinceProgress;
    }
}
