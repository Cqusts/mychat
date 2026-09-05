package com.mychat.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量修改。
 *
 * 存在的理由是成本：Agent 每调一次工具都要重发全部历史上下文，
 * token 开销随轮次平方增长——实测把预算从60提到120，单任务成本约4倍
 * 而通过率没变。所以正确的方向是压缩轮次，不是加预算。
 *
 * 这里最要紧的性质是原子性：一批里有任何一处对不上就一个文件都不改，
 * 并把所有问题一次性返回。改到一半的中间状态会让模型彻底懵，
 * 而分散的错误反馈会逼它多跑好几轮——每一轮都更贵
 */
class CoderToolsBatchTest {

    @TempDir
    Path workspace;

    private CoderTools tools;

    @BeforeEach
    void setUp() {
        CoderWorkspace coderWorkspace = new CoderWorkspace();
        ReflectionTestUtils.setField(coderWorkspace, "workspacePath", workspace.toString());
        tools = new CoderTools(coderWorkspace, null);
    }

    private void write(String name, String content) throws Exception {
        Path file = workspace.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(workspace.resolve(name)), StandardCharsets.UTF_8);
    }

    private CoderTools.FileEdit edit(String path, String oldText, String newText) {
        CoderTools.FileEdit fileEdit = new CoderTools.FileEdit();
        fileEdit.setPath(path);
        fileEdit.setOldText(oldText);
        fileEdit.setNewText(newText);
        return fileEdit;
    }

    @Test
    void 一次提交跨多个文件的改动() throws Exception {
        write("a/Controller.java", "class Controller {\r\n    void list() { }\r\n}\r\n");
        write("a/Service.java", "class Service {\n    void load() { }\n}\n");

        String result = tools.applyEdits(List.of(
                edit("a/Controller.java", "    void list() { }", "    void list(String keyword) { }"),
                edit("a/Service.java", "    void load() { }", "    void load(String keyword) { }")));

        assertTrue(result.startsWith("已提交"), result);
        assertTrue(read("a/Controller.java").contains("list(String keyword)"));
        assertTrue(read("a/Service.java").contains("load(String keyword)"));
        //各自的换行符要保住
        assertTrue(read("a/Controller.java").contains("\r\n"));
        assertFalse(read("a/Service.java").contains("\r"));
    }

    @Test
    void 同一个文件的多处改动依次生效() throws Exception {
        write("a/Multi.java", "class Multi {\n    int a = 1;\n    int b = 2;\n    int c = 3;\n}\n");

        String result = tools.applyEdits(List.of(
                edit("a/Multi.java", "    int a = 1;", "    int a = 11;"),
                edit("a/Multi.java", "    int b = 2;", "    int b = 22;"),
                edit("a/Multi.java", "    int c = 3;", "    int c = 33;")));

        assertTrue(result.startsWith("已提交"), result);
        String after = read("a/Multi.java");
        assertTrue(after.contains("int a = 11;"));
        assertTrue(after.contains("int b = 22;"));
        assertTrue(after.contains("int c = 33;"));
    }

    @Test
    void 有一处对不上就一个文件都不改() throws Exception {
        write("a/Ok.java", "class Ok {\n    int a = 1;\n}\n");
        write("a/Bad.java", "class Bad {\n    int b = 2;\n}\n");
        String okBefore = read("a/Ok.java");
        String badBefore = read("a/Bad.java");

        String result = tools.applyEdits(List.of(
                edit("a/Ok.java", "    int a = 1;", "    int a = 9;"),
                edit("a/Bad.java", "    int b = 999;", "    int b = 8;")));

        assertTrue(result.contains("一个文件都没动"), result);
        assertEquals(okBefore, read("a/Ok.java"), "校验失败时先前的改动也不该落盘");
        assertEquals(badBefore, read("a/Bad.java"));
        assertEquals(0, tools.getChangedFileCount());
    }

    @Test
    void 多处错误一次性全部返回() throws Exception {
        write("a/One.java", "class One {\n    int a = 1;\n}\n");
        write("a/Two.java", "class Two {\n    int b = 2;\n}\n");

        String result = tools.applyEdits(List.of(
                edit("a/One.java", "    int nope = 1;", "x"),
                edit("a/Two.java", "    int nope = 2;", "y")));

        //一轮里把所有问题都给出去，模型才不用来回试——每一轮都要重发全部历史
        assertTrue(result.contains("2 处需要修正"), result);
        assertTrue(result.contains("One.java"), result);
        assertTrue(result.contains("Two.java"), result);
    }

    @Test
    void oldText留空表示新建文件() throws Exception {
        String result = tools.applyEdits(List.of(
                edit("a/New.java", "", "class New {\n}\n")));

        assertTrue(result.contains("新建"), result);
        assertTrue(read("a/New.java").contains("class New"));
    }

    @Test
    void 对已存在的文件留空oldText会被拒绝() throws Exception {
        write("a/Exists.java", "class Exists {\n}\n");
        String before = read("a/Exists.java");

        String result = tools.applyEdits(List.of(edit("a/Exists.java", "", "整个覆盖掉")));

        assertTrue(result.contains("已经存在"), result);
        assertEquals(before, read("a/Exists.java"), "不能把已有文件整个盖掉");
    }

    @Test
    void 对不存在的文件做替换会被拒绝() {
        String result = tools.applyEdits(List.of(edit("a/Ghost.java", "something", "other")));

        assertTrue(result.contains("文件不存在"), result);
    }

    @Test
    void 空清单和超量都被挡住() {
        assertTrue(tools.applyEdits(null).contains("空的"));
        assertTrue(tools.applyEdits(List.of()).contains("空的"));

        List<CoderTools.FileEdit> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add(edit("a/F" + i + ".java", "x", "y"));
        }
        assertTrue(tools.applyEdits(tooMany).contains("最多"));
    }

    @Test
    void 批量读把多个文件拼在一次返回里() throws Exception {
        write("a/One.java", "class One {}\n");
        write("a/Two.java", "class Two {}\n");

        String result = tools.readFiles(List.of("a/One.java", "a/Two.java", "a/Missing.java"));

        assertTrue(result.contains("class One"));
        assertTrue(result.contains("class Two"));
        assertTrue(result.contains("文件不存在"), "缺失的文件要说明，不能静默跳过");
    }

    @Test
    void 批量读有总量上限() throws Exception {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            big.append("// 这是一行很长的填充内容，用来把文件撑大\n");
        }
        write("a/Big1.java", big.toString());
        write("a/Big2.java", big.toString());

        String result = tools.readFiles(List.of("a/Big1.java", "a/Big2.java"));

        assertTrue(result.contains("截断") || result.contains("未读取"),
                "总量超限要截断，否则上下文被占满、后面每轮都更贵");
    }
}
