package com.mychat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * StringTools.desensitizePhone 手机号脱敏的单元测试。
 */
class StringToolsTest {

    @Test
    void 正常11位手机号中间四位替换为星号() {
        assertEquals("138****1234", StringTools.desensitizePhone("13812341234"));
    }

    @Test
    void null入参原样返回() {
        assertNull(StringTools.desensitizePhone(null));
    }

    @Test
    void 空串入参原样返回() {
        assertEquals("", StringTools.desensitizePhone(""));
    }

    @Test
    void 10位入参原样返回() {
        assertEquals("1381234123", StringTools.desensitizePhone("1381234123"));
    }

    @Test
    void 12位入参原样返回() {
        assertEquals("138123412345", StringTools.desensitizePhone("138123412345"));
    }
}
