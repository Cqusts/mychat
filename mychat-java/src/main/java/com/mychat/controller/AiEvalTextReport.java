package com.mychat.controller;

import com.mychat.ai.eval.AiEvalRecord;
import com.mychat.ai.eval.AiEvalReport;

import java.util.Map;

/**
 * 把评测报告渲染成能直接看、直接抄的纯文本。
 * JSON适合程序读，人对着一堆花括号数指标太累
 */
public class AiEvalTextReport {

    private AiEvalTextReport() {
    }

    public static String render(AiEvalReport report) {
        return render(report, null);
    }

    /**
     * @param totalCostYuan 跑批期间大模型控制台上的消费总额（元）。
     *                      传了就换算成单需求成本，没传那一格留空
     */
    public static String render(AiEvalReport report, Double totalCostYuan) {
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

        sb.append("\n---------- 失败按阶段归并 ----------\n");
        if (report.getStageFailures().isEmpty()) {
            sb.append("无失败\n");
        } else {
            for (Map.Entry<String, Integer> entry : report.getStageFailures().entrySet()) {
                sb.append(String.format("%-12s %d 条%n", entry.getKey(), entry.getValue()));
            }
            sb.append(String.format("其中编码阶段占全部失败的 %.1f%%%n", report.getCodingFailureRate()));
        }

        sb.append("\n---------- 失败原因明细 ----------\n");
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

        sb.append(resumeLine(report, totalCostYuan));
        return sb.toString();
    }

    /**
     * 把指标拼成一句能直接抄进简历的话。
     *
     * 唯一不自动填的是最后那句结论——那是判断不是数据，
     * 得看着失败分布自己下，写错了面试时圆不回来
     */
    private static String resumeLine(AiEvalReport report, Double totalCostYuan) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n---------- 简历口径（核对后可直接抄）----------\n");
        sb.append(String.format(
                "构建 %d 条难度分层的需求评测集（每条运行 %d 次，共 %d 个任务），实测端到端任务完成率 "
                        + "%.1f%%、平均评审返工 %.1f 轮、编译一次通过率 %.1f%%、单需求中位耗时 %.1f 分钟",
                report.getRequirementCount(), report.getRepeat(), report.getTotal(),
                report.getCompletionRate(), report.getAvgRetryCount(),
                report.getFirstCompilePassRate(), report.getMedianCostMs() / 60000.0));
        if (totalCostYuan != null && report.getTotal() > 0) {
            sb.append(String.format(" / token 成本约 %.2f 元",
                    totalCostYuan / report.getTotal()));
        } else {
            sb.append(" / token 成本约 __ 元");
        }
        sb.append("。\n");
        sb.append(String.format("失败集中在%s阶段（%.1f%%），据此定位瓶颈为 __。\n",
                dominantStage(report), report.getCodingFailureRate()));

        sb.append("\n最后那个空自己填，别照抄——它是结论不是数据。参考对照：\n");
        sb.append("  最多的失败原因是：").append(
                report.getTopFailReason() == null ? "无失败" : report.getTopFailReason()).append("\n");
        sb.append("  → ").append(suggestion(report.getTopFailReason())).append("\n");
        if (totalCostYuan == null) {
            sb.append("\ntoken 成本：跑批前后各看一眼大模型控制台的消费额，差值填进\n");
            sb.append("  /eval/reportText?totalCostYuan=12.34   会自动除以任务数\n");
        }
        return sb.toString();
    }

    /**
     * 失败最多的是哪个阶段。这个词直接进简历，不能拍脑袋写"编码"
     */
    private static String dominantStage(AiEvalReport report) {
        String stage = null;
        int max = 0;
        for (Map.Entry<String, Integer> entry : report.getStageFailures().entrySet()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                stage = entry.getKey();
            }
        }
        return stage == null ? "（无失败）" : stage;
    }

    private static String suggestion(String topReason) {
        if (topReason == null) {
            return "没有失败样本。多半是任务集太简单了，加几条难题重测。";
        }
        if (topReason.startsWith("编码零改动")) {
            return "模型定位不到该改的文件 → 瓶颈是缺乏代码检索增强（RAG）";
        }
        if (topReason.startsWith("编译不通过")) {
            return "改出来编不过 → 先把 ai.coder.max-fix-rounds 调大再测一轮，对比两组数据";
        }
        if (topReason.startsWith("编码被熔断")) {
            return "卡在解不开的问题上 → 看日志里的 [EVAL] 行定位它在反复做什么";
        }
        if (topReason.startsWith("方案未通过评审")) {
            return "方案过不了评审 → 评审标准过严或需求描述太模糊，调 review 的 prompt";
        }
        if (topReason.startsWith("编码环境不可用") || topReason.startsWith("准备代码工作区")) {
            return "这是环境问题不是系统能力问题 → 修好环境重测，这批数据不可用";
        }
        if (topReason.startsWith("用户停止")) {
            return "有人中途点了停止 → 这些样本要剔除，否则完成率被拉低";
        }
        return "看失败明细定位";
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
