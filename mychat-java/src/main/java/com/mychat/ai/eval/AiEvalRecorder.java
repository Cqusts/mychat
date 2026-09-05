package com.mychat.ai.eval;

import com.mychat.entity.constants.Constants;
import com.mychat.entity.dto.AiWorkflowTaskDto;
import com.mychat.entity.enums.AiWorkflowStageEnum;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 流水线评测记录器。
 *
 * 存在的理由：这套多Agent系统最要紧的问题是"它到底好不好用"，
 * 而在有这个类之前，唯一的答案是"我跑过一次，成了"——这不叫指标，叫轶事。
 *
 * 记录写在Redis的一个List里，不建表：评测是一次性的活儿，
 * 跑完把数抄走就行，没必要为它动数据库结构
 */
@Component("aiEvalRecorder")
public class AiEvalRecorder {

    private static final Logger logger = LoggerFactory.getLogger(AiEvalRecorder.class);

    /**
     * 最多保留多少条，防止忘了关开关之后无限堆积
     */
    private static final int MAX_RECORDS = 500;

    private static final String STAGE_CODING = "编码实现";

    @Value("${ai.eval.enabled:false}")
    private Boolean evalEnabled;

    @Resource
    private RedissonClient redissonClient;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(evalEnabled);
    }

    /**
     * 任务走到终态时调一次。没开评测开关就什么都不做
     */
    public void record(AiWorkflowTaskDto task) {
        if (!isEnabled() || task == null) {
            return;
        }
        try {
            AiEvalRecord record = new AiEvalRecord();
            record.setTaskId(task.getTaskId());
            record.setRequirement(task.getRequirement());
            record.setStage(task.getStage());
            record.setRetryCount(task.getRetryCount() == null ? 0 : task.getRetryCount());
            //能拿到分支名就说明至少准备过工作区，也就是走到了编码这一步
            record.setEnteredCoding(task.getCodeBranch() != null);
            record.setFirstCompilePass(task.getFirstCompilePass());
            record.setCodePushed(task.getCodePushed());
            record.setTestsPassed(task.getTestsPassed());
            record.setRedGatePassed(task.getRedGatePassed());
            record.setAcceptancePassed(task.getAcceptancePassed());
            record.setFailReason(task.getFailReason());
            record.setCreateTime(task.getCreateTime());
            long end = task.getEndTime() == null ? System.currentTimeMillis() : task.getEndTime();
            record.setCostMs(task.getCreateTime() == null ? 0 : end - task.getCreateTime());

            RList<AiEvalRecord> list = redissonClient.getList(Constants.REDIS_KEY_AI_EVAL);
            list.add(record);
            int size = list.size();
            if (size > MAX_RECORDS) {
                list.trim(size - MAX_RECORDS, size - 1);
            }
            logger.info("[EVAL] 任务归档 taskId={} 终态={} 返工={} 编译一次过={} 推送={} 耗时秒={}",
                    record.getTaskId(), record.getStage(), record.getRetryCount(),
                    record.getFirstCompilePass(), record.getCodePushed(), record.getCostMs() / 1000);
        } catch (Exception e) {
            //评测记录失败绝不能影响主流程
            logger.error("写评测记录失败, taskId:{}", task.getTaskId(), e);
        }
    }

    public List<AiEvalRecord> loadAll() {
        RList<AiEvalRecord> list = redissonClient.getList(Constants.REDIS_KEY_AI_EVAL);
        return new ArrayList<>(list.readAll());
    }

    public void clear() {
        redissonClient.getList(Constants.REDIS_KEY_AI_EVAL).delete();
    }

    /**
     * 把记录汇总成报告
     */
    public AiEvalReport report() {
        List<AiEvalRecord> records = loadAll();
        AiEvalReport report = new AiEvalReport();
        report.setRecords(records);
        report.setTotal(records.size());
        if (records.isEmpty()) {
            return report;
        }

        int completed = 0;
        int enteredCoding = 0;
        int firstPass = 0;
        int pushed = 0;
        int testsOk = 0;
        int redGate = 0;
        int accepted = 0;
        long retrySum = 0;
        List<Long> costs = new ArrayList<>(records.size());
        Map<String, Integer> failReasons = new LinkedHashMap<>();
        //用 int[]{通过数, 总数} 记每条需求的战绩
        Map<String, int[]> perRequirement = new LinkedHashMap<>();

        for (AiEvalRecord record : records) {
            boolean success = record.isSuccess();
            if (success) {
                completed++;
            } else {
                String reason = record.getFailReason() == null
                        ? descOf(record.getStage()) : record.getFailReason();
                failReasons.merge(reason, 1, Integer::sum);
            }
            if (Boolean.TRUE.equals(record.getEnteredCoding())) {
                enteredCoding++;
                if (Boolean.TRUE.equals(record.getFirstCompilePass())) {
                    firstPass++;
                }
            }
            if (Boolean.TRUE.equals(record.getCodePushed())) {
                pushed++;
            }
            if (Boolean.TRUE.equals(record.getTestsPassed())) {
                testsOk++;
            }
            if (Boolean.TRUE.equals(record.getRedGatePassed())) {
                redGate++;
            }
            if (record.isAccepted()) {
                accepted++;
            }
            retrySum += record.getRetryCount() == null ? 0 : record.getRetryCount();
            costs.add(record.getCostMs() == null ? 0 : record.getCostMs());

            String key = record.getRequirement() == null ? "(空)" : record.getRequirement();
            int[] score = perRequirement.computeIfAbsent(key, k -> new int[2]);
            score[1]++;
            if (success) {
                score[0]++;
            }
        }

        report.setCompleted(completed);
        report.setCompletionRate(percent(completed, records.size()));
        report.setAvgRetryCount(round1((double) retrySum / records.size()));
        report.setEnteredCoding(enteredCoding);
        report.setFirstCompilePassRate(percent(firstPass, enteredCoding));
        report.setCodePushed(pushed);
        report.setTestsPassed(testsOk);
        report.setRedGatePassed(redGate);
        report.setAccepted(accepted);
        //分母是全部任务，不是过了红灯的那些：
        //红灯就没过说明测试都没写对，那也是没达成，不能从分母里摘出去
        report.setAcceptanceRate(percent(accepted, records.size()));

        Collections.sort(costs);
        report.setMedianCostMs(quantile(costs, 0.5));
        report.setP90CostMs(quantile(costs, 0.9));
        report.setFailReasons(failReasons);

        //按阶段归并。简历上写"失败集中在X阶段"要的是这个口径，
        //光给细分原因还得自己心算，容易算错
        Map<String, Integer> stageFailures = new LinkedHashMap<>();
        int codingFailures = 0;
        int totalFailures = 0;
        String topReason = null;
        int topCount = 0;
        for (Map.Entry<String, Integer> entry : failReasons.entrySet()) {
            String stage = stageOfFailure(entry.getKey());
            stageFailures.merge(stage, entry.getValue(), Integer::sum);
            totalFailures += entry.getValue();
            if (STAGE_CODING.equals(stage)) {
                codingFailures += entry.getValue();
            }
            if (entry.getValue() > topCount) {
                topCount = entry.getValue();
                topReason = entry.getKey();
            }
        }
        report.setStageFailures(stageFailures);
        report.setCodingFailureRate(percent(codingFailures, totalFailures));
        report.setTopFailReason(topReason);

        report.setRequirementCount(perRequirement.size());
        report.setRepeat(perRequirement.isEmpty() ? 0 : records.size() / perRequirement.size());

        Map<String, String> perView = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : perRequirement.entrySet()) {
            perView.put(entry.getKey(), entry.getValue()[0] + "/" + entry.getValue()[1]);
        }
        report.setPerRequirement(perView);
        return report;
    }

    /**
     * 取分位数。列表必须已排好序
     */
    private long quantile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private double percent(int part, int whole) {
        return whole == 0 ? 0 : round1(part * 100.0 / whole);
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /**
     * 把细分失败原因归到阶段上。
     *
     * 用前缀/关键词匹配而不是枚举：失败原因里带了动态内容
     * （"用户停止(编码实现)"、"编码被熔断(超出工具调用次数上限)"），
     * 精确相等匹配不上
     */
    private String stageOfFailure(String reason) {
        if (reason == null) {
            return "未知";
        }
        if (reason.startsWith("用户停止")) {
            return "用户停止";
        }
        if (reason.startsWith("需求分析") || reason.contains("停在需求分析")) {
            return "需求分析";
        }
        if (reason.startsWith("方案设计") || reason.contains("停在方案设计")) {
            return "方案设计";
        }
        if (reason.startsWith("方案评审") || reason.equals("方案未通过评审")
                || reason.contains("停在方案评审")) {
            return "方案评审";
        }
        //测试先行的失败要归到自己头上。放在编码/测试验证之前判：
        //"测试先行阶段调用失败"以"测试"开头，会被后面那条错认成测试验证
        if (reason.contains("测试先行") || reason.startsWith("红灯门禁")) {
            return "测试先行";
        }
        if (reason.startsWith("编码") || reason.startsWith("编译")
                || reason.startsWith("推送") || reason.startsWith("准备代码工作区")
                || reason.contains("停在编码实现")) {
            return STAGE_CODING;
        }
        if (reason.startsWith("测试") || reason.contains("停在测试验证")) {
            return "测试验证";
        }
        return "其他";
    }

    private String descOf(String stage) {
        try {
            return "停在" + AiWorkflowStageEnum.valueOf(stage).getDesc();
        } catch (Exception e) {
            return "未知(" + stage + ")";
        }
    }
}
