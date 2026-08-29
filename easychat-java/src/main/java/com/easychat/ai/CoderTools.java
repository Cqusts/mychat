package com.easychat.ai;

import com.easychat.service.AiStreamCallback;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
     * 匹配失败时回显原文的最大行数，别把整个文件塞回上下文
     */
    private static final int MAX_ECHO_LINES = 60;

    /**
     * 模糊定位时至少要有这么多字符前缀相同，太短了只会指到毫不相干的行
     */
    private static final int MIN_ANCHOR_PREFIX = 8;

    private final CoderWorkspace workspace;

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

    public CoderTools(CoderWorkspace workspace, AiStreamCallback callback) {
        this.workspace = workspace;
        this.callback = callback;
    }

    /**
     * 回调要等引擎把流式管道建好才有，所以允许构造后再绑
     */
    public void bindCallback(AiStreamCallback callback) {
        this.callback = callback;
    }

    public int getChangedFileCount() {
        return changedFileCount;
    }

    public List<String> getTouchedFiles() {
        return new ArrayList<>(touchedFiles);
    }

    @Tool(description = "按关键词搜索项目代码，返回匹配的文件路径、行号和该行内容。改代码前先用它定位相关文件。")
    public String searchCode(
            @ToolParam(description = "要搜索的关键词，比如类名、方法名、字段名") String keyword,
            @ToolParam(required = false, description = "限定文件后缀，比如 .java 或 .vue，不填则搜全部") String extension) {
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

    @Tool(description = "读取项目里某个文件的完整内容。路径是相对于仓库根目录的，比如 easychat-java/src/main/java/com/easychat/controller/GroupController.java")
    public String readFile(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path) {
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
        notify("正在修改 " + shortName(path) + "…");
        try {
            if (StringTools.isEmpty(oldText)) {
                return "oldText 不能为空，新建文件请用 createFile。";
            }
            //这里必须读全文。用给模型看的截断版改完再写回去，
            //会把超过MAX_READ_CHARS的文件从两万字符处齐根砍断
            String raw = workspace.readFileRaw(path);
            if (raw == null) {
                return "文件不存在：" + path;
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
                return notFound(content, target, path);
            }
            if (match.ambiguous) {
                return "要替换的内容在文件里出现了多次，无法确定改哪一处。"
                        + "请把 oldText 写得更长一些，带上足够的上下文使其唯一。";
            }

            String eol = dominantEol(raw);
            String merged = raw.substring(0, file.rawOffset[match.start])
                    + restoreEol(reindent(replacement, match.fileIndent, match.targetIndent), eol)
                    + raw.substring(file.rawOffset[match.end]);
            workspace.writeFile(path, merged);
            changedFileCount++;
            touchedFiles.add(path);
            return "已修改 " + path;
        } catch (Exception e) {
            logger.error("replaceInFile执行失败, path:{}", path, e);
            return "修改失败：" + e.getMessage();
        }
    }

    @Tool(description = "新建一个文件并写入内容。只用于创建新文件，修改已有文件请用 replaceInFile。")
    public String createFile(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path,
            @ToolParam(description = "文件的完整内容") String content) {
        notify("正在创建 " + shortName(path) + "…");
        try {
            if (workspace.exists(path)) {
                return "文件已存在：" + path + "，请改用 replaceInFile。";
            }
            workspace.writeFile(path, content == null ? "" : content);
            changedFileCount++;
            touchedFiles.add(path);
            return "已创建 " + path;
        } catch (Exception e) {
            logger.error("createFile执行失败, path:{}", path, e);
            return "创建失败：" + e.getMessage();
        }
    }

    @Tool(description = "编译后端项目，返回编译结果。改完代码一定要调用它验证；如果编译失败，根据报错继续修，直到编译通过为止。")
    public String compile() {
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
            logger.error("compile执行失败", e);
            return "编译执行失败：" + e.getMessage();
        }
    }

    @Tool(description = "运行项目的单元测试，返回测试结果。写完测试用例后调用它验证；测试不通过就根据报错修，直到通过为止。")
    public String runTests() {
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
            return "测试执行失败：" + e.getMessage();
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
        return "没找到要替换的内容，请先用 readFile 确认原文。"
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
