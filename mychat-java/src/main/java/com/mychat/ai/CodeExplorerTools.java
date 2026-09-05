package com.mychat.ai;

import com.mychat.ai.index.CodeIndex;
import com.mychat.service.AiStreamCallback;
import com.mychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 只读的代码勘察工具，给方案设计和评审阶段用。
 *
 * 为什么需要：实测 20 个任务里 11 条失败，100% 在编码阶段，
 * 而且全都是在"定位该改哪个文件"上把预算耗光的——没有一条是代码写错了编不过。
 * 追根到底是架构师从没看过一行代码，输出的方案是凭需求文本想象出来的，
 * 程序员拿到一段文字之后还得从零重新定位。
 *
 * 这个类把检索能力提前给到方案阶段，让方案里能直接写清楚"改哪些文件"。
 * 刻意不提供任何写入能力：方案阶段动代码会让职责彻底混乱，
 * 而且评审还没过就改文件，被打回时收不回来
 */
public class CodeExplorerTools {

    private static final Logger logger = LoggerFactory.getLogger(CodeExplorerTools.class);

    private static final int MAX_FILES = 8;

    private static final int MAX_READS = 4;

    private static final int MAX_READ_CHARS = 24000;

    private final CoderWorkspace workspace;

    private final ToolBudget budget;

    private AiStreamCallback callback;

    public CodeExplorerTools(CoderWorkspace workspace, ToolBudget budget) {
        this.workspace = workspace;
        this.budget = budget;
    }

    public void bindCallback(AiStreamCallback callback) {
        this.callback = callback;
    }

    private void notify(String hint) {
        if (callback != null) {
            callback.onToolCall(hint);
        }
    }

    private String guard(String signature) {
        String verdict = budget.check(signature);
        if (verdict != null) {
            logger.warn("勘察工具被熔断: {} ({})", signature, verdict);
        }
        return verdict;
    }

    @Tool(description = "把需求原话丢进来，找出最可能要改的文件，并列出每个文件里有哪些类和方法。"
            + "写技术方案之前先用它，方案里要写清楚具体改哪些文件，不要凭空想象。")
    public String findFiles(
            @ToolParam(description = "需求描述，直接写中文原话") String requirement) {
        String blocked = guard("findFiles|" + requirement);
        if (blocked != null) {
            return blocked;
        }
        notify("正在勘察相关代码…");
        try {
            if (StringTools.isEmpty(requirement)) {
                return "需求描述不能为空。";
            }
            List<CodeIndex.Hit> hits = workspace.getCodeIndex().search(requirement, MAX_FILES);
            if (hits.isEmpty()) {
                return "没有定位到相关文件。换个说法再试一次。";
            }
            StringBuilder sb = new StringBuilder("按相关度排序，最可能要改的文件：\n");
            int index = 1;
            for (CodeIndex.Hit hit : hits) {
                sb.append(index++).append(". ").append(hit.getDocument().getPath()).append('\n');
                String outline = hit.getDocument().getOutline();
                if (!outline.isEmpty()) {
                    sb.append("     包含：").append(outline).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("findFiles执行失败, requirement:{}", requirement, e);
            return "勘察失败：" + e.getMessage();
        }
    }

    @Tool(description = "只看文件骨架（有哪些类、方法、字段），不返回方法体。确认某个文件是不是要找的那个时用它。")
    public String outline(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path) {
        String blocked = guard("outline|" + path);
        if (blocked != null) {
            return blocked;
        }
        notify("正在查看结构…");
        try {
            var document = workspace.getCodeIndex().getDocument(path);
            if (document == null) {
                return "索引里没有这个文件：" + path;
            }
            List<String> symbols = document.getSymbolList();
            return symbols.isEmpty()
                    ? path + " 里没有解析出类或方法"
                    : path + " 包含：\n  " + String.join("\n  ", symbols);
        } catch (Exception e) {
            logger.error("outline执行失败, path:{}", path, e);
            return "查看失败：" + e.getMessage();
        }
    }

    @Tool(description = "读取若干个文件的内容，确认现有实现细节。方案阶段尽量少用，"
            + "多数时候 outline 就够了——读全文很贵。")
    public String readFiles(
            @ToolParam(description = "文件路径列表，最多4个") List<String> paths) {
        String blocked = guard("readFiles|" + (paths == null ? "" : String.join(",", paths)));
        if (blocked != null) {
            return blocked;
        }
        if (paths == null || paths.isEmpty()) {
            return "路径列表是空的。";
        }
        if (paths.size() > MAX_READS) {
            return "方案阶段一次最多读 " + MAX_READS + " 个文件，挑最关键的。";
        }
        notify("正在阅读现有实现…");
        try {
            StringBuilder sb = new StringBuilder();
            int budgetChars = MAX_READ_CHARS;
            for (String path : paths) {
                String content = workspace.readFile(path);
                if (content == null) {
                    sb.append("===== ").append(path).append(" =====\n（文件不存在）\n\n");
                    continue;
                }
                if (content.length() > budgetChars) {
                    content = content.substring(0, Math.max(0, budgetChars)) + "\n……（已截断）";
                }
                budgetChars -= content.length();
                sb.append("===== ").append(path).append(" =====\n").append(content).append("\n\n");
                if (budgetChars <= 0) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("readFiles执行失败", e);
            return "读取失败：" + e.getMessage();
        }
    }
}
