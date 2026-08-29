package com.easychat.controller;

import com.easychat.ai.eval.AiEvalRecord;
import com.easychat.ai.eval.AiEvalReport;

import java.util.Map;

/**
 * 把评测报告渲染成能直接看、直接抄的纯文本。
 * JSON适合程序读，人对着一堆花括号数指标太累
 */
public class AiEvalTextReport {

    private AiEvalTextReport() {
    }

    public static String render(AiEvalReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("========== 需求流水线评测报告 ==========\n");
        if (report.getTotal() == 0) {
            sb.append("还没有任何记录。先跑 /eval/batch。\n");
            return sb.toString();
        }
        sb.append(String.format("样本总数        %d 个任务%n", report.getTotal()));
        sb.append(String.format("任务完成率      %.1f%%  (%d/%d 走到DONE)%n",
                report.getCompletionRate(), report.getCompleted(), report.getTotal()));
        sb.append(String.format("平均返工轮次    %.1f 轮%n", report.getAvgRetryCount()));
        sb.append(String.format("进入编码阶段    %d 个%n", report.getEnteredCoding()));
        sb.append(String.format("编译一次通过率  %.1f%%  (分母是进入编码的%d个)%n",
                report.getFirstCompilePassRate(), report.getEnteredCoding()));
        sb.append(String.format("成功推送分支    %d 个%n", report.getCodePushed()));
        sb.append(String.format("单测全部通过    %d 个%n", report.getTestsPassed()));
        sb.append(String.format("单需求耗时      中位 %s / P90 %s%n",
                duration(report.getMedianCostMs()), duration(report.getP90CostMs())));

        sb.append("\n---------- 失败原因分布 ----------\n");
        if (report.getFailReasons().isEmpty()) {
            sb.append("无失败\n");
        } else {
            for (Map.Entry<String, Integer> entry : report.getFailReasons().entrySet()) {
                sb.append(String.format("%-28s %d 条%n", entry.getKey(), entry.getValue()));
            }
        }

        sb.append("\n---------- 逐条需求通过情况 ----------\n");
        sb.append("(同一条需求跑多次结果不一致，说明系统不稳定，这比完成率本身更值得看)\n");
        for (Map.Entry<String, String> entry : report.getPerRequirement().entrySet()) {
            sb.append(String.format("%-8s %s%n", entry.getValue(), abbreviate(entry.getKey())));
        }

        sb.append("\n---------- 明细 ----------\n");
        for (AiEvalRecord record : report.getRecords()) {
            sb.append(String.format("%-8s 返工%d 一次编译过=%s 推送=%s 耗时%s  %s%s%n",
                    record.getStage(),
                    record.getRetryCount() == null ? 0 : record.getRetryCount(),
                    yesNo(record.getFirstCompilePass()),
                    yesNo(record.getCodePushed()),
                    duration(record.getCostMs()),
                    abbreviate(record.getRequirement()),
                    record.getFailReason() == null ? "" : "  [" + record.getFailReason() + "]"));
        }

        sb.append("\n提示：token成本用跑批前后 DeepSeek 控制台的用量差值除以任务数，\n");
        sb.append("      比在代码里统计准，也不用改任何东西。\n");
        return sb.toString();
    }

    private static String duration(Long ms) {
        if (ms == null || ms <= 0) {
            return "-";
        }
        long seconds = ms / 1000;
        return seconds < 60 ? seconds + "秒" : (seconds / 60) + "分" + (seconds % 60) + "秒";
    }

    private static String yesNo(Boolean value) {
        return value == null ? "-" : (value ? "是" : "否");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 30 ? text : text.substring(0, 30) + "…";
    }
}
