package com.mychat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringMaskerTest {

    @Test
    void 正常手机号中间四位脱敏() {
        assertEquals("138****5678", StringMasker.maskPhone("13812345678"));
    }

    @Test
    void 边界第二位为0原样返回() {
        assertEquals("10000000000", StringMasker.maskPhone("10000000000"));
    }

    @Test
    void 边界第二位为2原样返回() {
        assertEquals("12812345678", StringMasker.maskPhone("12812345678"));
    }

    @Test
    void null原样返回() {
        assertNull(StringMasker.maskPhone(null));
    }

    @Test
    void 空串原样返回() {
        assertEquals("", StringMasker.maskPhone(""));
    }

    @Test
    void 十位数字原样返回() {
        assertEquals("1381234567", StringMasker.maskPhone("1381234567"));
    }

    @Test
    void 十二位数字原样返回() {
        assertEquals("138123456789", StringMasker.maskPhone("138123456789"));
    }

    @Test
    void 含字母原样返回() {
        assertEquals("13812345a78", StringMasker.maskPhone("13812345a78"));
    }

    @Test
    void 含空格原样返回() {
        assertEquals("138 1234 5678", StringMasker.maskPhone("138 1234 5678"));
    }
}
