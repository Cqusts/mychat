package com.mychat.ai.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分词。中文需求和英文代码之间那道坎，一半在这里跨
 */
class CodeTokenTest {

    @Test
    void 驼峰拆开后子词也能命中() {
        List<String> tokens = CodeToken.tokenize("ChatSessionUser");

        assertTrue(tokens.contains("chatsessionuser"), "整词要保留");
        assertTrue(tokens.contains("chat"));
        assertTrue(tokens.contains("session"));
        assertTrue(tokens.contains("user"));
    }

    @Test
    void 连续大写不会被拆碎() {
        List<String> tokens = CodeToken.tokenize("HTTPRequestHandler");

        assertTrue(tokens.contains("http"));
        assertTrue(tokens.contains("request"));
        assertTrue(tokens.contains("handler"));
    }

    @Test
    void 中文按二元组切以支持部分匹配() {
        List<String> tokens = CodeToken.tokenize("会话列表");

        assertTrue(tokens.contains("会话"), "查'会话'要能命中'会话列表'");
        assertTrue(tokens.contains("列表"), "查'列表'也要能命中");
    }

    @Test
    void 数字和符号被丢弃() {
        List<String> tokens = CodeToken.tokenize("user_id = 123; // 用户");

        assertTrue(tokens.contains("user"));
        assertTrue(tokens.contains("id"));
        assertTrue(tokens.contains("用户"));
        assertFalse(tokens.contains("123"));
    }

    @Test
    void 词典把中文概念映射成代码标识符() {
        List<String> expanded = CodeGlossary.expand("会话列表支持模糊搜索");

        assertTrue(expanded.contains("session"), "会话应该映射到 session");
        assertTrue(expanded.contains("search"), "搜索应该映射到 search");
        assertFalse(expanded.contains("支持"), "套话应该被过滤");
    }

    @Test
    void 需求套话被识别为停用词() {
        assertTrue(CodeGlossary.isStopWord("增加"));
        assertTrue(CodeGlossary.isStopWord("支持"));
        assertFalse(CodeGlossary.isStopWord("会话"));
    }
}
