package com.mychat.ai.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码检索的排序质量。
 *
 * 这套东西是为了解决一个实测出来的问题：评测里 7 条真实失败 100% 是
 * 编码 Agent 在工具预算内找不到该改的文件。所以这里的用例全部围绕
 * 「中文需求能不能排出正确的文件」写，而不是测 BM25 公式本身
 */
class CodeIndexTest {

    @TempDir
    Path repo;

    private CodeIndex index;

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 造一个缩微版的项目：分层结构、中文注释、命名风格都照着真实项目来
     */
    @BeforeEach
    void setUp() throws Exception {
        write("app/src/main/java/com/demo/service/impl/ChatSessionServiceImpl.java",
                "package com.demo.service.impl;\n"
                        + "/** 会话业务实现 */\n"
                        + "public class ChatSessionServiceImpl implements ChatSessionService {\n"
                        + "    public void updateSessionInfo(String sessionId) { }\n"
                        + "    public List<ChatSession> loadSessionList(String userId) { }\n"
                        + "}\n");
        write("app/src/main/java/com/demo/service/impl/ChatMessageServiceImpl.java",
                "package com.demo.service.impl;\n"
                        + "/** 消息业务实现，发消息和撤回都在这里 */\n"
                        + "public class ChatMessageServiceImpl implements ChatMessageService {\n"
                        + "    public void saveMessage(ChatMessage message) { }\n"
                        + "}\n");
        write("app/src/main/java/com/demo/entity/po/GroupInfo.java",
                "package com.demo.entity.po;\n"
                        + "/** 群信息 */\n"
                        + "public class GroupInfo {\n"
                        + "    private String groupOwnerId;\n"
                        + "    private String groupNotice;\n"
                        + "}\n");
        write("app/src/main/resources/com/demo/mappers/ChatSessionMapper.xml",
                "<mapper namespace=\"com.demo.mappers.ChatSessionMapper\">\n"
                        + "  <select id=\"selectSessionList\">select * from chat_session</select>\n"
                        + "</mapper>\n");
        //干扰项：文档和测试把领域词写了个遍，但永远不是要改的文件
        write("README.md",
                "# 项目说明\n本项目实现了会话、消息、群聊、撤回、群公告等完整功能，"
                        + "会话列表支持搜索，消息支持撤回。\n");
        write("app/src/test/java/com/demo/ChatSessionServiceImplTest.java",
                "class ChatSessionServiceImplTest {\n"
                        + "  /** 测试会话列表加载 */\n"
                        + "  void testLoadSessionList() { }\n"
                        + "}\n");
        //应该被完全跳过的目录
        write("app/target/classes/Ghost.java", "class Ghost { /* 会话 消息 群聊 */ }\n");
        write("app/node_modules/pkg/index.js", "// 会话 消息 群聊 撤回\n");

        index = CodeIndex.build(repo);
    }

    private List<String> paths(String query, int topK) {
        List<String> result = new ArrayList<>();
        for (CodeIndex.Hit hit : index.search(query, topK)) {
            result.add(hit.getDocument().getPath());
        }
        return result;
    }

    @Test
    void 构建产物和依赖目录不进索引() {
        assertTrue(index.size() > 0);
        for (String noise : List.of("app/target/classes/Ghost.java", "app/node_modules/pkg/index.js")) {
            assertTrue(index.getDocument(noise) == null, noise + " 不该被索引");
        }
    }

    @Test
    void 中文需求能排出对应的实现类() {
        List<String> top = paths("会话列表支持按昵称模糊搜索", 3);

        assertTrue(top.get(0).contains("ChatSession"),
                "第一名应该是会话相关的文件，实际是 " + top.get(0));
    }

    @Test
    void 文档和测试不该排在实现代码前面() {
        List<String> top = paths("会话列表支持按昵称模糊搜索", 3);

        assertFalse(top.get(0).equals("README.md"), "README 不该是第一名");
        assertFalse(top.get(0).contains("/src/test/"), "测试文件不该是第一名");
    }

    @Test
    void 中文概念能命中英文标识符() {
        //"撤回"在代码里没有对应的中文字样，只能靠词典映射到 revoke/recall，
        //以及"消息"->message 定位到 ChatMessageServiceImpl
        List<String> top = paths("实现消息撤回功能", 3);

        assertTrue(top.stream().anyMatch(p -> p.contains("ChatMessage")),
                "应该定位到消息相关文件，实际是 " + top);
    }

    @Test
    void 需求里点名类名时该文件必须排第一() {
        List<String> top = paths("给 GroupInfo 增加一个字段", 3);

        assertTrue(top.get(0).endsWith("GroupInfo.java"),
                "点名了类名就该排第一，实际是 " + top.get(0));
    }

    @Test
    void 套话不影响排序() {
        //"增加、支持、提供、返回、功能"这些词在每个文件里都可能出现，
        //不过滤掉的话会把排序冲乱
        List<String> withNoise = paths("需要增加支持提供返回功能：群公告", 3);
        List<String> clean = paths("群公告", 3);

        assertEquals(clean.get(0), withNoise.get(0), "加一堆套话不该改变第一名");
    }

    @Test
    void 符号表解析出了类名和方法名() {
        CodeDocument document = index.getDocument(
                "app/src/main/java/com/demo/service/impl/ChatSessionServiceImpl.java");

        assertTrue(document.getSymbols().contains("ChatSessionServiceImpl"));
        assertTrue(document.getSymbols().contains("updateSessionInfo"));
        assertTrue(document.getSymbols().contains("loadSessionList"));
        //if/for 这类关键字不该被方法正则误伤进来
        assertFalse(document.getSymbols().contains("if"));
    }

    @Test
    void mapper的namespace和语句id都能索引到() {
        CodeDocument document = index.getDocument(
                "app/src/main/resources/com/demo/mappers/ChatSessionMapper.xml");

        assertTrue(document.getSymbols().contains("selectSessionList"));
    }

    @Test
    void 空查询和无结果查询都不炸() {
        assertTrue(index.search("", 5).isEmpty());
        assertTrue(index.search(null, 5).isEmpty());
        assertTrue(index.search("zzzznotexist", 5).isEmpty());
    }
}
