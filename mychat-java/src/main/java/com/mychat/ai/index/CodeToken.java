package com.mychat.ai.index;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码检索的分词器。
 *
 * 要同时吃下三种输入，这是这套检索能用的前提：
 *
 *   1. 英文标识符：ChatSessionUser 必须能被 "session" 命中，
 *      所以除了整词还要按驼峰和下划线拆开
 *   2. 中文：需求是中文写的（"会话列表支持模糊搜索"），
 *      而这个项目的注释也是中文的，所以中文查询本来就能命中注释。
 *      中文没有空格，按二元组切
 *   3. 数字和符号：直接丢掉，全是噪音
 *
 * 需求里说"会话列表"，代码里叫 ChatSession——中间这道坎，
 * 一半靠中文注释里的"会话"，一半靠驼峰拆出来的 "session"
 */
public final class CodeToken {

    private static final Pattern WORD = Pattern.compile("[A-Za-z]+|[\\u4e00-\\u9fa5]+");

    /**
     * 驼峰边界：aB 或 ABb 处切开，能把 ChatSessionUser 和 HTTPRequest 都拆对
     */
    private static final Pattern CAMEL = Pattern.compile(
            "(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * 太短的中文词整体保留（比如"群"），长的只切二元组
     */
    private static final int CHINESE_KEEP_WHOLE_MAX = 4;

    private CodeToken() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            if (isChinese(word.charAt(0))) {
                addChinese(tokens, word);
            } else {
                addLatin(tokens, word);
            }
        }
        return tokens;
    }

    private static void addChinese(List<String> tokens, String word) {
        if (word.length() <= CHINESE_KEEP_WHOLE_MAX) {
            tokens.add(word);
        }
        //二元组：会话列表 -> 会话 话列 列表。
        //查询"会话"能命中"会话列表"，查询"列表"也能，比整词匹配宽容得多
        for (int i = 0; i + 2 <= word.length(); i++) {
            tokens.add(word.substring(i, i + 2));
        }
        if (word.length() == 1) {
            tokens.add(word);
        }
    }

    private static void addLatin(List<String> tokens, String word) {
        String lower = word.toLowerCase();
        tokens.add(lower);
        //驼峰拆出来的片段单独入库，ChatSessionUser 才能被 session 命中
        String[] parts = CAMEL.split(word);
        if (parts.length > 1) {
            for (String part : parts) {
                if (part.length() > 1) {
                    tokens.add(part.toLowerCase());
                }
            }
        }
    }

    private static boolean isChinese(char c) {
        return c >= '一' && c <= '龥';
    }
}
