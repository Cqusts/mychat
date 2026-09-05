package com.mychat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringToolsTest {

    @Test
    void testMaskMobile_正常11位手机号() {
        assertEquals("138****5678", StringTools.maskMobile("13812345678"));
    }

    @Test
    void testMaskMobile_短号原样返回() {
        assertEquals("12345", StringTools.maskMobile("12345"));
    }

    @Test
    void testMaskMobile_null原样返回() {
        assertEquals(null, StringTools.maskMobile(null));
    }

    @Test
    void testMaskMobile_11位非数字原样返回() {
        assertEquals("abcdefghijk", StringTools.maskMobile("abcdefghijk"));
    }

    @Test
    void testMaskMobile_11位含字母数字混合原样返回() {
        assertEquals("138abc45678", StringTools.maskMobile("138abc45678"));
    }

    @Test
    void testMaskMobile_空白字符串原样返回() {
        assertEquals("   ", StringTools.maskMobile("   "));
    }
}
