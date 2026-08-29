package com.easychat.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * replaceInFile 的匹配行为测试。
 *
 * 这里全是踩过的坑：项目在Windows上检出，磁盘上是CRLF，
 * 而模型吐出来的 oldText 永远是LF，早先的 indexOf 精确匹配一次都对不上，
 * 程序员助手一整轮十几次调用全部失败，一个文件都改不动。
 */
class CoderToolsReplaceTest {

    @TempDir
    Path workspace;

    private CoderWorkspace coderWorkspace;

    private CoderTools tools;

    @BeforeEach
    void setUp() {
        coderWorkspace = new CoderWorkspace();
        //工作区路径是@Value注入的，测试里不起Spring容器，直接塞进去
        ReflectionTestUtils.setField(coderWorkspace, "workspacePath", workspace.toString());
        tools = new CoderTools(coderWorkspace, null);
    }

    private void write(String name, String content) throws Exception {
        Files.write(workspace.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private String read(String name) throws Exception {
        return new String(Files.readAllBytes(workspace.resolve(name)), StandardCharsets.UTF_8);
    }

    @Test
    void 模型给LF也能改动CRLF文件并保持原换行符() throws Exception {
        write("A.java", "class A {\r\n    int a = 1;\r\n    int b = 2;\r\n}\r\n");

        String result = tools.replaceInFile("A.java", "    int a = 1;", "    int a = 42;");

        assertTrue(result.startsWith("已修改"), result);
        assertEquals("class A {\r\n    int a = 42;\r\n    int b = 2;\r\n}\r\n", read("A.java"));
        assertEquals(1, tools.getChangedFileCount());
        assertEquals("A.java", tools.getTouchedFiles().get(0));
    }

    @Test
    void 跨多行的CRLF片段也能匹配() throws Exception {
        write("B.java", "class B {\r\n    void run() {\r\n        step();\r\n    }\r\n}\r\n");

        String result = tools.replaceInFile("B.java",
                "    void run() {\n        step();\n    }",
                "    void run() {\n        before();\n        step();\n    }");

        assertTrue(result.startsWith("已修改"), result);
        assertEquals("class B {\r\n    void run() {\r\n        before();\r\n        step();\r\n    }\r\n}\r\n",
                read("B.java"));
    }

    @Test
    void 缩进对不上时按行宽松匹配并把新内容摆正() throws Exception {
        write("C.java", "class C {\n        int deep = 1;\n}\n");

        //模型少写了4个空格
        String result = tools.replaceInFile("C.java", "    int deep = 1;", "    int deep = 2;");

        assertTrue(result.startsWith("已修改"), result);
        assertEquals("class C {\n        int deep = 2;\n}\n", read("C.java"));
    }

    @Test
    void 行尾多余空格不影响匹配() throws Exception {
        write("D.java", "class D {\r\n    int a = 1;   \r\n}\r\n");

        String result = tools.replaceInFile("D.java", "    int a = 1;", "    int a = 9;");

        assertTrue(result.startsWith("已修改"), result);
        assertTrue(read("D.java").contains("int a = 9;"), read("D.java"));
    }

    @Test
    void 出现多次时拒绝替换而不是改错地方() throws Exception {
        write("E.java", "class E {\r\n    int a = 1;\r\n    int a = 1;\r\n}\r\n");

        String result = tools.replaceInFile("E.java", "    int a = 1;", "    int a = 2;");

        assertTrue(result.contains("多次"), result);
        assertEquals(0, tools.getChangedFileCount());
    }

    @Test
    void 匹配不上时回显文件真实内容供模型照抄() throws Exception {
        write("F.java", "class F {\r\n    int alpha = 1;\r\n}\r\n");

        String result = tools.replaceInFile("F.java", "    int alpha = 999;", "    int alpha = 2;");

        assertTrue(result.startsWith("没找到"), result);
        //锚点行(class F {)能对上，所以要把真实原文带回去，而不是只说一句没找到
        assertTrue(result.contains("int alpha = 1;"), result);
        assertEquals(0, tools.getChangedFileCount());
    }

    @Test
    void 超过读取上限的长文件不会被截断() throws Exception {
        StringBuilder sb = new StringBuilder("class G {\r\n");
        for (int i = 0; i < 3000; i++) {
            sb.append("    // filler line ").append(i).append("\r\n");
        }
        sb.append("    int tail = 1;\r\n}\r\n");
        String original = sb.toString();
        //必须显著超过 CoderWorkspace.MAX_READ_CHARS(20000)，否则测不到这个case
        assertTrue(original.length() > 20000, "构造的样例不够长");
        write("G.java", original);

        String result = tools.replaceInFile("G.java", "    int tail = 1;", "    int tail = 7;");

        assertTrue(result.startsWith("已修改"), result);
        String after = read("G.java");
        assertTrue(after.contains("int tail = 7;"), "改动没生效");
        assertTrue(after.contains("// filler line 2999"), "文件被截断了");
        assertEquals(original.length(), after.length(), "长度不该有变化");
    }

    @Test
    void 纯LF文件保持LF不被改成CRLF() throws Exception {
        write("H.java", "class H {\n    int a = 1;\n}\n");

        tools.replaceInFile("H.java", "    int a = 1;", "    int a = 2;");

        assertFalse(read("H.java").contains("\r"), "LF文件不该被改成CRLF");
    }

    @Test
    void 新建文件也会记入改动清单() throws Exception {
        String result = tools.createFile("new/I.java", "class I {\n}\n");

        assertTrue(result.startsWith("已创建"), result);
        assertEquals(1, tools.getChangedFileCount());
        assertEquals("new/I.java", tools.getTouchedFiles().get(0));
    }
}
