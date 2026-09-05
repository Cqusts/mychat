package com.mychat.ai;

import com.mychat.ai.index.CodeIndex;
import com.mychat.service.AiStreamCallback;
import com.mychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 暴露给程序员助手的代码工具。
 *
 * 和ChatAgentTools一样是每次任务新建的实例，不是Spring单例。
 * 所有文件操作都经过CoderWorkspace的路径校验，模型给的路径出不了工作区。
 *
 * 特意没有提供"提交/推送"工具：什么时候可以推由引擎在编译通过后决定，
 * 不交给模型判断。
 */
public class CoderTools {

    private static final Logger logger = LoggerFactory.getLogger(CoderTools.class);

    private static final int MAX_SEARCH_HITS = 40;

    /**
     * findFiles 返回多少个候选。给太多等于没给——模型会挨个读，轮次全耗在这
     */
    private static final int MAX_RELEVANT_FILES = 8;

    /**
     * 一次批量提交的改动上限
     */
    private static final int MAX_BATCH_EDITS = 20;

    private static final int MAX_BATCH_READS = 6;

    /**
     * 批量读的总字符上限。几个大文件全塞进来会把上下文占满，
     * 后面每一轮都要带着它们重发一遍，反而更贵
     */
    private static final int MAX_BATCH_READ_CHARS = 40000;

    /**
     * 匹配失败时回显原文的最大行数，别把整个文件塞回上下文
     */
    private static final int MAX_ECHO_LINES = 60;

    /**
     * 模糊定位时至少要有这么多字符前缀相同，太短了只会指到毫不相干的行
     */
    private static final int MIN_ANCHOR_PREFIX = 8;

    /**
     * 单轮最多调多少次工具。踩过的坑：机器上没有mvn，compile返回"命令无法执行"，
     * 模型看不懂这不是它能修的，反复 searchCode("mvn") 刷了一千七百多次
     */
    private static final int DEFAULT_MAX_TOOL_CALLS = 60;

    /**
     * 单轮的墙钟时限。流的 timeout 是"两个分片之间的间隔"，
     * 只要工具一直在产出内容它就永远不触发，挡不住这种空转
     */
    private static final int DEFAULT_DEADLINE_MINUTES = 20;

    /**
     * 同一个调用（工具名+参数完全一样）重复到第几次就叫停
     */
    private static final int MAX_SAME_CALL = 3;

    private final CoderWorkspace workspace;

    /**
     * 本轮的熔断闸门。工具循环失控时靠它收场，见 guard()
     */
    private final ToolBudget budget;

    /**
     * 把"正在搜索代码…""正在编译…"推给前端，让干活过程可见
     */
    private AiStreamCallback callback;

    /**
     * 本次任务实际改过的文件数，用来判断模型到底动手了没有
     */
    private int changedFileCount = 0;

    /**
     * 改过的文件清单，按顺序去重。用来在群里说清楚"到底动了哪些文件"
     */
    private final Set<String> touchedFiles = new LinkedHashSet<>();

    /**
     * 只读路径片段。TDD 模式下测试文件就是需求本身的形式化描述，
     * 允许程序员改测试的话，最省事的"让测试变绿"办法就是把断言删掉，
     * 红绿门禁也就没有意义了。所以这里从工具层直接封死，不靠提示词自觉
     */
    private List<String> protectedSegments = new ArrayList<>();

    public CoderTools(CoderWorkspace workspace, AiStreamCallback callback) {
        this(workspace, callback, new ToolBudget(null, null, DEFAULT_MAX_TOOL_CALLS, DEFAULT_DEADLINE_MINUTES));
    }

    public CoderTools(CoderWorkspace workspace, AiStreamCallback callback, ToolBudget budget) {
        this.workspace = workspace;
        this.callback = callback;
        this.budget = budget;
    }

    /**
     * 本轮工具调用的闸门：用户点了停止、调用次数超预算、超时、原地打转，
     * 四种情况都在这里拦下并给模型一句明确的收尾指令。
     *
     * 之所以做成"返回一句话"而不是抛异常：抛异常会被 Spring AI 包成错误再喂回去，
     * 模型多半会换个参数接着试；给一句明确的"停止调用工具、直接总结"反而收得住。
     */
    private String guard(String signature) {
        String verdict = budget.check(signature);
        if (verdict != null) {
            logger.warn("工具调用被熔断: {} ({})", signature, verdict);
        }
        return verdict;
    }

    /**
     * 回调要等引擎把流式管道建好才有，所以允许构造后再绑
     */
    public void bindCallback(AiStreamCallback callback) {
        this.callback = callback;
    }

    /**
     * @param protectedSegments 路径片段，命中即只读。传目录片段而不是前缀，
     *                          是因为仓库是多模块的，测试目录真实路径是
     *                          mychat-java/src/test/java/…，写死前缀会漏
     */
    public void setProtectedPaths(List<String> protectedSegments) {
        this.protectedSegments = protectedSegments == null ? new ArrayList<>() : protectedSegments;
    }

    /**
     * @return 该路径被保护时返回给模型的说明，可写则返回null
     */
    private String protectedBlock(String path) {
        if (path == null || protectedSegments.isEmpty()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        for (String segment : protectedSegments) {
            if (normalized.contains(segment)) {
                return "不能改 " + path + "：测试文件是这次需求的验收标准，本轮只读。"
                        + "请改业务代码让测试通过，不要改测试本身。";
            }
        }
        return null;
    }

    public int getChangedFileCount() {
        return changedFileCount;
    }

    public List<String> getTouchedFiles() {
        return new ArrayList<>(touchedFiles);
    }

    public ToolBudget getBudget() {
        return budget;
    }

    @Tool(description = "把需求原话丢进来，直接找出最可能要改的文件，并列出每个文件里有哪些类和方法。"
            + "这是改代码的第一步，比 searchCode 好用得多——它能处理中文需求，"
            + "不需要你先猜代码里用的是哪个英文单词。")
    public String findFiles(
            @ToolParam(description = "需求描述，直接写中文原话即可，比如「会话列表支持按昵称模糊搜索」") String requirement) {
        String blocked = guard("findFiles|" + requirement);
        if (blocked != null) {
            return blocked;
        }
        notify("正在定位相关文件…");
        try {
            if (StringTools.isEmpty(requirement)) {
                return "需求描述不能为空。";
            }
            List<CodeIndex.Hit> hits = workspace.getCodeIndex().search(requirement, MAX_RELEVANT_FILES);
            if (hits.isEmpty()) {
                return "没有定位到相关文件。换个说法再试一次，或者用 searchCode 搜具体的类名。";
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
            sb.append("\n接下来用 readFile 或 outline 看清楚再动手改。"
                    + "注意这个项目是分层的：改一个功能通常要动 Controller、Service、Mapper 接口和 Mapper.xml 四处。");
            return sb.toString();
        } catch (Exception e) {
            logger.error("findFiles执行失败, requirement:{}", requirement, e);
            return "定位失败：" + e.getMessage();
        }
    }

    @Tool(description = "只看一个文件的骨架（有哪些类、方法、字段），不返回方法体。"
            + "想确认某个文件是不是要找的那个、或者想知道该调哪个方法时用它，比 readFile 省得多。")
    public String outline(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path) {
        String blocked = guard("outline|" + path);
        if (blocked != null) {
            return blocked;
        }
        notify("正在查看 " + shortName(path) + " 的结构…");
        try {
            var document = workspace.getCodeIndex().getDocument(path);
            if (document == null) {
                return "索引里没有这个文件：" + path + "。用 findFiles 重新定位，或者用 readFile 直接读。";
            }
            List<String> symbols = document.getSymbolList();
            if (symbols.isEmpty()) {
                return path + " 里没有解析出类或方法（可能是配置文件），直接用 readFile 读。";
            }
            return path + " 包含：\n  " + String.join("\n  ", symbols);
        } catch (Exception e) {
            logger.error("outline执行失败, path:{}", path, e);
            return "查看失败：" + e.getMessage();
        }
    }

    @Tool(description = "按关键词搜索项目代码，返回匹配的文件路径、行号和该行内容。"
            + "已知具体类名/方法名时用它；只有中文需求描述时先用 findFiles。")
    public String searchCode(
            @ToolParam(description = "要搜索的关键词，比如类名、方法名、字段名") String keyword,
            @ToolParam(required = false, description = "限定文件后缀，比如 .java 或 .vue，不填则搜全部") String extension) {
        String blocked = guard("searchCode|" + keyword + "|" + extension);
        if (blocked != null) {
            return blocked;
        }
        notify("正在搜索代码：" + keyword + "…");
        try {
            if (StringTools.isEmpty(keyword)) {
                return "关键词不能为空。";
            }
            List<String> hits = workspace.searchCode(keyword, extension, MAX_SEARCH_HITS);
            if (hits.isEmpty()) {
                return "没有搜到包含「" + keyword + "」的代码。";
            }
            return "搜到" + hits.size() + "处：\n" + String.join("\n", hits);
        } catch (Exception e) {
            logger.error("searchCode执行失败, keyword:{}", keyword, e);
            return "搜索失败：" + e.getMessage();
        }
    }

    @Tool(description = "读取项目里某个文件的完整内容。路径是相对于仓库根目录的，比如 mychat-java/src/main/java/com/mychat/controller/GroupController.java")
    public String readFile(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path) {
        String blocked = guard("readFile|" + path);
        if (blocked != null) {
            return blocked;
        }
        notify("正在读取 " + shortName(path) + "…");
        try {
            String content = workspace.readFile(path);
            if (content == null) {
                return "文件不存在：" + path;
            }
            return content;
        } catch (Exception e) {
            logger.error("readFile执行失败, path:{}", path, e);
            return "读取失败：" + e.getMessage();
        }
    }

    @Tool(description = "把文件里的一段代码替换成新的代码。oldText 要能在文件里唯一定位到一段连续内容；"
            + "换行符(CRLF/LF)、行尾空格和整体缩进都已自动兼容，不用为这些反复重试。"
            + "这是修改已有文件的唯一方式。")
    public String replaceInFile(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path,
            @ToolParam(description = "要被替换掉的原内容，要能在文件里唯一定位到") String oldText,
            @ToolParam(description = "替换后的新内容") String newText) {
        String blocked = guard("replaceInFile|" + path + "|" + oldText);
        if (blocked != null) {
            return blocked;
        }
        String readOnly = protectedBlock(path);
        if (readOnly != null) {
            return readOnly;
        }
        notify("正在修改 " + shortName(path) + "…");
        try {
            //这里必须读全文。用给模型看的截断版改完再写回去，
            //会把超过MAX_READ_CHARS的文件从两万字符处齐根砍断
            String raw = workspace.readFileRaw(path);
            if (raw == null) {
                return "文件不存在：" + path;
            }
            EditResult result = applyOne(raw, oldText, newText, path);
            if (result.error != null) {
                return result.error;
            }
            workspace.writeFile(path, result.content);
            changedFileCount++;
            touchedFiles.add(path);
            budget.recordProgress();
            return "已修改 " + path;
        } catch (Exception e) {
            logger.error("replaceInFile执行失败, path:{}", path, e);
            return "修改失败：" + e.getMessage();
        }
    }

    /**
     * 一次替换的结果：要么给出新内容，要么给出该怎么改的说明
     */
    private static final class EditResult {
        String content;
        String error;

        static EditResult ok(String content) {
            EditResult result = new EditResult();
            result.content = content;
            return result;
        }

        static EditResult fail(String error) {
            EditResult result = new EditResult();
            result.error = error;
            return result;
        }
    }

    /**
     * 在一段内容上执行一处替换，不落盘。
     *
     * 抽出来是为了让批量修改能在同一个内存缓冲上连续改多处，
     * 全部校验通过之后再统一写文件
     */
    private EditResult applyOne(String raw, String oldText, String newText, String path) {
        if (StringTools.isEmpty(oldText)) {
            return EditResult.fail(path + "：oldText 不能为空，新建文件请用 createFile 或在 applyEdits 里留空 oldText。");
        }
        //项目是在Windows上检出的，磁盘上是CRLF，而模型吐出来的oldText永远是LF，
        //直接indexOf必然找不到——表现就是助手一整轮都在"没找到→再试一次"里空转，
        //一个文件都改不动。统一成LF比较，写回时只把新内容按原换行符还原，
        //没动到的部分保持原样，避免整个文件的换行符被改掉、diff面目全非
        Normalized file = normalize(raw);
        String content = file.text;
        String target = toLf(oldText);
        String replacement = toLf(newText == null ? "" : newText);

        Match match = locate(content, target);
        if (match == null) {
            return EditResult.fail(notFound(content, target, path));
        }
        if (match.ambiguous) {
            return EditResult.fail(path + "：要替换的内容在文件里出现了多次，无法确定改哪一处。"
                    + "请把 oldText 写得更长一些，带上足够的上下文使其唯一。");
        }

        String eol = dominantEol(raw);
        return EditResult.ok(raw.substring(0, file.rawOffset[match.start])
                + restoreEol(reindent(replacement, match.fileIndent, match.targetIndent), eol)
                + raw.substring(file.rawOffset[match.end]));
    }

    /**
     * 一处待提交的改动。oldText 留空表示新建文件
     */
    public static class FileEdit {

        private String path;

        private String oldText;

        private String newText;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getOldText() {
            return oldText;
        }

        public void setOldText(String oldText) {
            this.oldText = oldText;
        }

        public String getNewText() {
            return newText;
        }

        public void setNewText(String newText) {
            this.newText = newText;
        }
    }

    @Tool(description = "一次性提交多处改动，可以跨多个文件。改一个功能通常要同时动 Controller、Service、"
            + "Mapper 接口和 Mapper.xml，用这个一次提交完，不要一处一处调 replaceInFile。"
            + "每一项：path 是文件路径，oldText 是要被替换的原内容（留空表示新建文件），newText 是替换后的内容。"
            + "要么全部成功，要么全部不改——有任何一处对不上会把所有问题一次性告诉你，不会改到一半。")
    public String applyEdits(
            @ToolParam(description = "改动清单，每项包含 path、oldText、newText") List<FileEdit> edits) {
        String blocked = guard("applyEdits|" + (edits == null ? 0 : edits.size()));
        if (blocked != null) {
            return blocked;
        }
        if (edits == null || edits.isEmpty()) {
            return "改动清单是空的。";
        }
        if (edits.size() > MAX_BATCH_EDITS) {
            return "一次最多提交 " + MAX_BATCH_EDITS + " 处改动，请分批。";
        }
        notify("正在提交 " + edits.size() + " 处改动…");

        try {
            //按文件分组：同一个文件的多处改动要落在同一个内存缓冲上连续改，
            //否则第二处会基于旧内容定位，位置全错
            Map<String, List<FileEdit>> byFile = new LinkedHashMap<>();
            for (FileEdit edit : edits) {
                if (edit == null || StringTools.isEmpty(edit.getPath())) {
                    return "有一项没填 path。";
                }
                String readOnly = protectedBlock(edit.getPath());
                if (readOnly != null) {
                    return readOnly;
                }
                byFile.computeIfAbsent(edit.getPath(), key -> new ArrayList<>()).add(edit);
            }

            //先全部试算，一处都不落盘。
            //这样模型能在一轮里拿到所有问题，而不是改一处失败一次、来回好几轮——
            //每多一轮都要重发全部历史上下文，token成本是平方级涨的
            Map<String, String> pending = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();
            List<String> created = new ArrayList<>();

            for (Map.Entry<String, List<FileEdit>> entry : byFile.entrySet()) {
                String path = entry.getKey();
                String raw = workspace.readFileRaw(path);
                boolean isNew = raw == null;
                if (isNew) {
                    raw = "";
                }
                for (FileEdit edit : entry.getValue()) {
                    if (StringTools.isEmpty(edit.getOldText())) {
                        if (!isNew && !raw.isEmpty()) {
                            errors.add(path + "：这个文件已经存在，新增内容也要给出 oldText 定位插在哪里。");
                            break;
                        }
                        raw = edit.getNewText() == null ? "" : edit.getNewText();
                        created.add(path);
                        continue;
                    }
                    if (isNew) {
                        errors.add(path + "：文件不存在，无法替换。新建文件请把 oldText 留空。");
                        break;
                    }
                    EditResult result = applyOne(raw, edit.getOldText(), edit.getNewText(), path);
                    if (result.error != null) {
                        errors.add(result.error);
                        break;
                    }
                    raw = result.content;
                }
                pending.put(path, raw);
            }

            if (!errors.isEmpty()) {
                return "这批改动没有提交，一个文件都没动。以下 " + errors.size() + " 处需要修正：\n\n"
                        + String.join("\n\n", errors)
                        + "\n\n改好之后重新提交整批。";
            }

            for (Map.Entry<String, String> entry : pending.entrySet()) {
                workspace.writeFile(entry.getKey(), entry.getValue());
                changedFileCount++;
                touchedFiles.add(entry.getKey());
            }
            budget.recordProgress();
            StringBuilder sb = new StringBuilder("已提交 " + edits.size() + " 处改动，涉及 "
                    + pending.size() + " 个文件：\n");
            for (String path : pending.keySet()) {
                sb.append("  ").append(created.contains(path) ? "新建 " : "修改 ").append(path).append('\n');
            }
            sb.append("接下来调 compile 验证。");
            return sb.toString();
        } catch (Exception e) {
            logger.error("applyEdits执行失败", e);
            return "批量修改失败：" + e.getMessage();
        }
    }

    @Tool(description = "一次读多个文件的完整内容。要改的文件确定之后用它一次性读完，"
            + "不要一个一个调 readFile——每多一轮交互都要重发全部历史，很贵。")
    public String readFiles(
            @ToolParam(description = "文件路径列表，相对于仓库根目录") List<String> paths) {
        String blocked = guard("readFiles|" + (paths == null ? "" : String.join(",", paths)));
        if (blocked != null) {
            return blocked;
        }
        if (paths == null || paths.isEmpty()) {
            return "路径列表是空的。";
        }
        if (paths.size() > MAX_BATCH_READS) {
            return "一次最多读 " + MAX_BATCH_READS + " 个文件，挑最相关的几个。";
        }
        notify("正在读取 " + paths.size() + " 个文件…");
        try {
            StringBuilder sb = new StringBuilder();
            int budget = MAX_BATCH_READ_CHARS;
            for (String path : paths) {
                String content = workspace.readFile(path);
                if (content == null) {
                    sb.append("===== ").append(path).append(" =====\n（文件不存在）\n\n");
                    continue;
                }
                if (content.length() > budget) {
                    //总量有上限：几个大文件全塞进来会把上下文占满，
                    //后面的轮次反而更贵
                    content = content.substring(0, Math.max(0, budget))
                            + "\n……（总量超限已截断，需要看剩下的部分请单独 readFile）";
                }
                budget -= content.length();
                sb.append("===== ").append(path).append(" =====\n").append(content).append("\n\n");
                if (budget <= 0) {
                    sb.append("（剩余文件未读取，总量已达上限）\n");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            logger.error("readFiles执行失败", e);
            return "读取失败：" + e.getMessage();
        }
    }

    @Tool(description = "新建一个文件并写入内容。只用于创建新文件，修改已有文件请用 replaceInFile。")
    public String createFile(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path,
            @ToolParam(description = "文件的完整内容") String content) {
        String blocked = guard("createFile|" + path);
        if (blocked != null) {
            return blocked;
        }
        String readOnly = protectedBlock(path);
        if (readOnly != null) {
            return readOnly;
        }
        notify("正在创建 " + shortName(path) + "…");
        try {
            if (workspace.exists(path)) {
                return "文件已存在：" + path + "，请改用 replaceInFile。";
            }
            workspace.writeFile(path, content == null ? "" : content);
            changedFileCount++;
            touchedFiles.add(path);
            budget.recordProgress();
            return "已创建 " + path;
        } catch (Exception e) {
            logger.error("createFile执行失败, path:{}", path, e);
            return "创建失败：" + e.getMessage();
        }
    }

    @Tool(description = "编译后端项目，返回编译结果。改完代码一定要调用它验证；如果编译失败，根据报错继续修，直到编译通过为止。")
    public String compile() {
        String blocked = guard("compile");
        if (blocked != null) {
            return blocked;
        }
        notify("正在编译…");
        try {
            CoderWorkspace.ExecResult result = workspace.compile();
            if (result.success()) {
                notify("编译通过");
                return "编译通过。";
            }
            notify("编译失败，正在修复…");
            return "编译失败，报错如下：\n" + result.output;
        } catch (Exception e) {
            //命令本身起不来（比如mvn不在PATH）不是模型能修的，
            //不说清楚它会以为是自己的问题，接着搜"怎么编译"，一直搜下去
            logger.error("compile执行失败", e);
            return "编译命令无法执行：" + e.getMessage()
                    + "。这是服务端环境问题（多半是 mvn 不在 PATH），不是代码问题，"
                    + "你无法通过改代码或换工具解决。请停止调用工具，直接说明这一点然后结束。";
        }
    }

    @Tool(description = "运行项目的单元测试，返回测试结果。写完测试用例后调用它验证；测试不通过就根据报错修，直到通过为止。")
    public String runTests() {
        String blocked = guard("runTests");
        if (blocked != null) {
            return blocked;
        }
        notify("正在运行单元测试…");
        try {
            CoderWorkspace.ExecResult result = workspace.runTests();
            if (result.success()) {
                notify("测试通过");
                return "测试全部通过。\n" + result.output;
            }
            notify("测试未通过，正在修复…");
            return "测试未通过，输出如下：\n" + result.output;
        } catch (Exception e) {
            logger.error("runTests执行失败", e);
            return "测试命令无法执行：" + e.getMessage()
                    + "。这是服务端环境问题，不是代码问题，你无法通过改代码解决。"
                    + "请停止调用工具，直接说明这一点然后结束。";
        }
    }

    // ==================== 文本定位：容忍换行符和缩进的差异 ====================

    /**
     * LF归一化后的文本，以及每个字符在原始文本里的下标。
     * 有了这个映射，替换时可以只重写命中的那一段，
     * 文件其余部分连一个字节都不动
     */
    private static final class Normalized {
        final String text;
        /** 长度 = text.length() + 1，最后一位是原文长度，方便取右边界 */
        final int[] rawOffset;

        Normalized(String text, int[] rawOffset) {
            this.text = text;
            this.rawOffset = rawOffset;
        }
    }

    /**
     * 一次定位的结果。fileIndent/targetIndent 用来把新内容的缩进摆正
     */
    private static final class Match {
        int start;
        int end;
        String fileIndent = "";
        String targetIndent = "";
        boolean ambiguous;
    }

    private Normalized normalize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        int[] map = new int[raw.length() + 1];
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            map[sb.length()] = i;
            if (c == '\r') {
                sb.append('\n');
                i += (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') ? 2 : 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        map[sb.length()] = raw.length();
        return new Normalized(sb.toString(), map);
    }

    private String toLf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String dominantEol(String raw) {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) != '\n') {
                continue;
            }
            if (i > 0 && raw.charAt(i - 1) == '\r') {
                crlf++;
            } else {
                lf++;
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    private String restoreEol(String lfText, String eol) {
        return "\n".equals(eol) ? lfText : lfText.replace("\n", eol);
    }

    private Match locate(String content, String target) {
        Match exact = locateExact(content, target);
        if (exact != null) {
            return exact;
        }
        //严格匹配对不上时退一步按行比，忽略每行首尾空白。
        //模型最常见的偏差就是缩进少几个空格、行尾多个空格，
        //为这种差异让它反复重试纯属浪费轮次
        return locateByLines(content, target);
    }

    private Match locateExact(String content, String target) {
        int first = content.indexOf(target);
        if (first < 0) {
            return null;
        }
        Match match = new Match();
        match.start = first;
        match.end = first + target.length();
        match.ambiguous = content.indexOf(target, first + 1) >= 0;
        return match;
    }

    private Match locateByLines(String content, String target) {
        String[] fileLines = content.split("\n", -1);
        String[] targetLines = target.split("\n", -1);
        //模型常在oldText前后多带一个空行，先掐掉再比
        int from = 0;
        int to = targetLines.length - 1;
        while (from <= to && targetLines[from].isBlank()) {
            from++;
        }
        while (to >= from && targetLines[to].isBlank()) {
            to--;
        }
        if (from > to) {
            return null;
        }
        int span = to - from + 1;

        int hit = -1;
        boolean ambiguous = false;
        for (int i = 0; i + span <= fileLines.length; i++) {
            boolean matched = true;
            for (int j = 0; j < span; j++) {
                if (!fileLines[i + j].strip().equals(targetLines[from + j].strip())) {
                    matched = false;
                    break;
                }
            }
            if (!matched) {
                continue;
            }
            if (hit >= 0) {
                ambiguous = true;
                break;
            }
            hit = i;
        }
        if (hit < 0) {
            return null;
        }

        Match match = new Match();
        match.ambiguous = ambiguous;
        if (ambiguous) {
            return match;
        }
        int start = 0;
        for (int i = 0; i < hit; i++) {
            start += fileLines[i].length() + 1;
        }
        int length = span - 1;
        for (int j = 0; j < span; j++) {
            length += fileLines[hit + j].length();
        }
        match.start = start;
        match.end = start + length;
        match.fileIndent = indentOf(fileLines[hit]);
        match.targetIndent = indentOf(targetLines[from]);
        return match;
    }

    private String indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    /**
     * 按行匹配时，模型给的缩进可能和文件里差几格。
     * 命中区间是从行首开始整行替换的，所以新内容也要整体挪到文件的真实缩进上，
     * 否则代码能编过但缩进是乱的，diff很难看
     */
    private String reindent(String replacement, String fileIndent, String targetIndent) {
        if (fileIndent.equals(targetIndent) || replacement.isEmpty()) {
            return replacement;
        }
        String add = fileIndent.startsWith(targetIndent)
                ? fileIndent.substring(targetIndent.length()) : null;
        String strip = add == null && targetIndent.startsWith(fileIndent)
                ? targetIndent.substring(fileIndent.length()) : null;
        if (add == null && strip == null) {
            //空格和Tab混着用，判断不了差多少，原样写回去，交给编译和人来看
            return replacement;
        }
        String[] lines = replacement.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isBlank()) {
                if (add != null) {
                    line = add + line;
                } else if (line.startsWith(strip)) {
                    line = line.substring(strip.length());
                }
            }
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    /**
     * 匹配不上时别只说一句"没找到"，把文件里对应位置的真实内容回显出去，
     * 让模型能直接照抄一段重试，而不是凭记忆猜
     */
    private String notFound(String content, String target, String path) {
        String anchor = null;
        for (String line : target.split("\n", -1)) {
            if (!line.isBlank()) {
                anchor = line.strip();
                break;
            }
        }
        String[] fileLines = content.split("\n", -1);
        int hit = anchor == null ? -1 : locateAnchor(fileLines, anchor);
        if (hit >= 0) {
            int span = Math.min(fileLines.length - hit,
                    Math.min(MAX_ECHO_LINES, target.split("\n", -1).length + 3));
            StringBuilder sb = new StringBuilder();
            sb.append("没找到要替换的内容。文件 ").append(path).append(" 第").append(hit + 1)
                    .append(" 行起的真实内容是：\n");
            for (int j = 0; j < span; j++) {
                sb.append(fileLines[hit + j]).append('\n');
            }
            sb.append("请从上面原样挑一段作为 oldText 重试。");
            return sb.toString();
        }
        //路径一定要带上：批量提交时一次会回好几条错误，
        //不写清楚是哪个文件的，模型根本对不上号
        return "没找到要替换的内容。文件：" + path + "。请先用 readFile 确认原文。"
                + "换行符和行尾空格已经自动兼容，不用为这些反复重试；"
                + "如果文件太长被截断了，请把改动落在能看到的部分。";
    }

    /**
     * 找到最像 oldText 首行的那一行。
     * 整行对得上最好；对不上就退而求其次找开头最像的一行——
     * 模型多半只是把某个字面量、参数或类型记错了，真正需要的原文就在那附近
     */
    private int locateAnchor(String[] fileLines, String anchor) {
        for (int i = 0; i < fileLines.length; i++) {
            if (fileLines[i].strip().equals(anchor)) {
                return i;
            }
        }
        int best = -1;
        int bestLength = MIN_ANCHOR_PREFIX - 1;
        for (int i = 0; i < fileLines.length; i++) {
            int length = commonPrefixLength(fileLines[i].strip(), anchor);
            if (length > bestLength) {
                bestLength = length;
                best = i;
            }
        }
        return best;
    }

    private int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private String shortName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private void notify(String hint) {
        if (callback == null) {
            return;
        }
        try {
            callback.onToolCall(hint);
        } catch (Exception e) {
            logger.warn("推送工具调用提示失败", e);
        }
    }
}
