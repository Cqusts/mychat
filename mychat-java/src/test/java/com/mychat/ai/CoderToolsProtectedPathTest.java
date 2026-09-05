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
 * TDD 模式下测试目录的只读保护。
 *
 * 这是整个红绿门禁能不能立住的关键：如果程序员能改测试，
 * "让测试变绿"最省事的办法就是把断言删掉，门禁就成了摆设。
 * 靠提示词说"不要改测试"是不够的——它在预算快耗尽时一定会去改，
 * 所以这里从工具层直接封死
 */
class CoderToolsProtectedPathTest {

    private static final String TEST_ROOT = "src/test/java/";

    private static final String TEST_FILE = "mychat-java/src/test/java/com/mychat/FooTest.java";

    private static final String SRC_FILE = "mychat-java/src/main/java/com/mychat/Foo.java";

    @TempDir
    Path workspace;

    private CoderTools tools;

    @BeforeEach
    void setUp() throws Exception {
        CoderWorkspace coderWorkspace = new CoderWorkspace();
        ReflectionTestUtils.setField(coderWorkspace, "workspacePath", workspace.toString());
        tools = new CoderTools(coderWorkspace, null);
        tools.setProtectedPaths(List.of(TEST_ROOT));
        write(TEST_FILE, "assertEquals(2, foo.bar());\n");
        write(SRC_FILE, "int bar() { return 1; }\n");
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
    void 改测试文件会被挡住而且文件不变() throws Exception {
        String result = tools.replaceInFile(TEST_FILE, "assertEquals(2, foo.bar());", "");

        assertTrue(result.contains("不能改"), result);
        assertEquals("assertEquals(2, foo.bar());\n", read(TEST_FILE));
    }

    @Test
    void 改业务代码不受影响() throws Exception {
        String result = tools.replaceInFile(SRC_FILE, "return 1;", "return 2;");

        assertTrue(result.startsWith("已修改"), result);
        assertTrue(read(SRC_FILE).contains("return 2;"));
    }

    @Test
    void 新建测试文件也会被挡住() {
        String result = tools.createFile(
                "mychat-java/src/test/java/com/mychat/BarTest.java", "class BarTest {}");

        assertTrue(result.contains("不能改"), result);
        assertFalse(Files.exists(workspace.resolve("mychat-java/src/test/java/com/mychat/BarTest.java")));
    }

    /**
     * 批量里混一个测试文件，整批都不能落盘。
     *
     * 这条最要紧：applyEdits 本来就是全成功或全不改的，
     * 保护路径的检查必须在试算之前做完，否则会出现
     * "业务代码改了、测试没改成"的半截状态
     */
    @Test
    void 批量里夹带测试文件时整批都不改() throws Exception {
        String result = tools.applyEdits(List.of(
                edit(SRC_FILE, "return 1;", "return 2;"),
                edit(TEST_FILE, "assertEquals(2, foo.bar());", "assertTrue(true);")));

        assertTrue(result.contains("不能改"), result);
        assertEquals("int bar() { return 1; }\n", read(SRC_FILE));
        assertEquals("assertEquals(2, foo.bar());\n", read(TEST_FILE));
    }

    @Test
    void 反斜杠路径一样挡得住() throws Exception {
        String result = tools.replaceInFile(
                "mychat-java\\src\\test\\java\\com\\mychat\\FooTest.java",
                "assertEquals(2, foo.bar());", "");

        assertTrue(result.contains("不能改"), result);
        assertEquals("assertEquals(2, foo.bar());\n", read(TEST_FILE));
    }

    /**
     * 不设保护路径时(非TDD批次、测试先行阶段本身)所有写操作照常
     */
    @Test
    void 没设保护路径时测试文件可写() throws Exception {
        tools.setProtectedPaths(null);

        String result = tools.replaceInFile(TEST_FILE, "assertEquals(2, foo.bar());", "assertTrue(true);");

        assertTrue(result.startsWith("已修改"), result);
        assertTrue(read(TEST_FILE).contains("assertTrue(true);"));
    }
}
