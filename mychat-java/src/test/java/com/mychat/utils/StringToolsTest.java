package com.mychat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StringToolsTest {

    @Test
    void 正常11位手机号脱敏() {
        assertEquals("138****5678", StringTools.maskMobile("13812345678"));
    }

    @Test
    void 首位为0的11位号码也正常脱敏() {
        assertEquals("000****0001", StringTools.maskMobile("00012340001"));
    }

    @Test
    void null入参抛异常() {
        assertThrows(IllegalArgumentException.class, () -> StringTools.maskMobile(null));
    }

    @Test
    void 位数不足为10位的号码抛异常() {
        assertThrows(IllegalArgumentException.class, () -> StringTools.maskMobile("1381234567"));
    }

    @Test
    void 位数超过为12位的号码抛异常() {
        assertThrows(IllegalArgumentException.class, () -> StringTools.maskMobile("138123456789"));
    }

    @Test
    void 含非数字字符抛异常() {
        assertThrows(IllegalArgumentException.class, () -> StringTools.maskMobile("1381234567a"));
    }
}
