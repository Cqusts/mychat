package com.mychat.ai;

import com.mychat.ai.index.CodeIndex;
import com.mychat.entity.dto.PlanChangeDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从方案文本里抽出结构化的改动清单和验收标准。
 *
 * 为什么不用 Spring AI 的结构化输出：方案阶段是流式推给群里的，
 * 流式和 entity() 映射凑不到一块儿。用固定格式加正则解析，
 * 代价是模型偶尔写歪，但解析失败时降级成纯文本方案即可，不会把流程卡死。
 *
 * 结构化之后能做三件纯文本做不到的事：
 *   1. 引擎能校验路径——方案里写的文件根本不存在，当场就能发现，
 *      而不是等编码阶段浪费几十次工具调用才撞出来
 *   2. 编码阶段直接拿到文件清单，跳过"定位"这个 100% 失败所在的环节
 *   3. 验收标准能交给测试阶段变成可执行的用例
 */
public final class TechPlanParser {

    private static final Logger logger = LoggerFactory.getLogger(TechPlanParser.class);

    /**
     * 【改动清单】和【验收标准】两个小节，取到下一个【】或文末为止
     */
    private static final Pattern CHANGES_SECTION = Pattern.compile(
            "【改动清单】\\s*\\n(.*?)(?=\\n【|$)", Pattern.DOTALL);

    private static final Pattern ACCEPTANCE_SECTION = Pattern.compile(
            "【验收标准】\\s*\\n(.*?)(?=\\n【|$)", Pattern.DOTALL);

    /**
     * 一行一项，形如：- 路径 | 动作 | 说明
     */
    private static final Pattern ITEM = Pattern.compile("^\\s*[-*]\\s*(.+)$");

    private TechPlanParser() {
    }

    public static List<PlanChangeDto> parseChanges(String plan, CodeIndex index) {
        List<PlanChangeDto> changes = new ArrayList<>();
        String section = section(CHANGES_SECTION, plan);
        if (section == null) {
            return changes;
        }
        for (String line : section.split("\n")) {
            Matcher matcher = ITEM.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String[] parts = matcher.group(1).split("\\|");
            String path = parts[0].trim().replace('\\', '/');
            if (path.isEmpty()) {
                continue;
            }
            PlanChangeDto change = new PlanChangeDto();
            change.setPath(path);
            change.setAction(parts.length > 1 ? parts[1].trim() : "");
            change.setDetail(parts.length > 2 ? parts[2].trim() : "");
            //索引里查不到的路径不一定是错的——也可能是要新建的文件。
            //标出来让编码阶段自己判断，不要直接判方案不合格
            change.setExists(index != null && index.getDocument(path) != null);
            changes.add(change);
        }
        return changes;
    }

    public static List<String> parseAcceptance(String plan) {
        List<String> acceptance = new ArrayList<>();
        String section = section(ACCEPTANCE_SECTION, plan);
        if (section == null) {
            return acceptance;
        }
        for (String line : section.split("\n")) {
            Matcher matcher = ITEM.matcher(line);
            if (matcher.find()) {
                String item = matcher.group(1).trim();
                if (!item.isEmpty()) {
                    acceptance.add(item);
                }
            }
        }
        return acceptance;
    }

    private static String section(Pattern pattern, String plan) {
        if (plan == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(plan);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * 把改动清单渲染成给编码阶段看的施工图
     */
    public static String renderForCoder(List<PlanChangeDto> changes) {
        if (changes == null || changes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【方案给出的改动清单，按这个动手，不用再自己找文件】\n");
        for (PlanChangeDto change : changes) {
            sb.append("- ").append(change.getPath());
            if (!change.getAction().isEmpty()) {
                sb.append("  【").append(change.getAction()).append("】");
            }
            if (!change.getDetail().isEmpty()) {
                sb.append("  ").append(change.getDetail());
            }
            if (!Boolean.TRUE.equals(change.getExists())) {
                sb.append("  （索引里没有这个文件，可能需要新建，动手前先确认一下）");
            }
            sb.append('\n');
        }
        sb.append("清单可能不完整，改的过程中发现遗漏可以补，但不要推翻重来。\n");
        return sb.toString();
    }

    /**
     * 记一笔解析质量，方便评测时判断是模型没按格式写、还是格式本身有问题
     */
    public static void logQuality(String taskId, List<PlanChangeDto> changes, List<String> acceptance) {
        long missing = changes.stream().filter(c -> !Boolean.TRUE.equals(c.getExists())).count();
        logger.info("方案结构化解析, taskId:{}, 改动{}项(其中{}项索引里没有), 验收标准{}条",
                taskId, changes.size(), missing, acceptance.size());
    }
}
