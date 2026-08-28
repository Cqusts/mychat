package com.easychat.ai;

import com.easychat.service.AiStreamCallback;
import com.easychat.utils.StringTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

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

    private final CoderWorkspace workspace;

    /**
     * 把"正在搜索代码…""正在编译…"推给前端，让干活过程可见
     */
    private AiStreamCallback callback;

    /**
     * 本次任务实际改过的文件数，用来判断模型到底动手了没有
     */
    private int changedFileCount = 0;

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

    @Tool(description = "把文件里的一段代码替换成新的代码。oldText 必须和文件里的内容一字不差（包括缩进），并且在文件中唯一出现。这是修改已有文件的唯一方式。")
    public String replaceInFile(
            @ToolParam(description = "相对于仓库根目录的文件路径") String path,
            @ToolParam(description = "要被替换掉的原内容，必须和文件里一字不差且唯一") String oldText,
            @ToolParam(description = "替换后的新内容") String newText) {
        notify("正在修改 " + shortName(path) + "…");
        try {
            if (StringTools.isEmpty(oldText)) {
                return "oldText 不能为空，新建文件请用 createFile。";
            }
            String content = workspace.readFile(path);
            if (content == null) {
                return "文件不存在：" + path;
            }
            int first = content.indexOf(oldText);
            if (first < 0) {
                //明确报错而不是静默跳过：不然模型会以为改成功了，继续往下走
                return "没找到要替换的内容，请先用 readFile 确认原文（注意缩进和换行必须完全一致）。";
            }
            if (content.indexOf(oldText, first + 1) >= 0) {
                return "要替换的内容在文件里出现了多次，无法确定改哪一处。请把 oldText 写得更长一些，带上足够的上下文使其唯一。";
            }
            workspace.writeFile(path, content.substring(0, first) + newText
                    + content.substring(first + oldText.length()));
            changedFileCount++;
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
