package com.mychat.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringToolsTest {

    @Test
    void 正常手机号中间四位脱敏() {
        assertEquals("138****5678", StringMasker.maskPhone("13812345678"));
    }

    @Test
    void null原样返回() {
        assertNull(StringMasker.maskPhone(null));
    }

    @Test
    void 长度不足原样返回() {
        assertEquals("123", StringMasker.maskPhone("123"));
    }

    @Test
    void 带空格原样返回() {
        assertEquals("138 1234 5678", StringMasker.maskPhone("138 1234 5678"));
    }

    @Test
    void 超长数字原样返回() {
        assertEquals("138123456789", StringMasker.maskPhone("138123456789"));
    }

    @Test
    void 空字符串原样返回() {
        assertEquals("", StringMasker.maskPhone(""));
    }

    @Test
    void 非1开头的11位数字原样返回() {
        assertEquals("23812345678", StringMasker.maskPhone("23812345678"));
    }

    @Test
    void 带连字符原样返回() {
        assertEquals("138-1234-5678", StringMasker.maskPhone("138-1234-5678"));
    }

    @Test
    void 长度11但含字母原样返回() {
        assertEquals("13812345a78", StringMasker.maskPhone("13812345a78"));
    }

    @Test
    void 已脱敏字符串再次调用原样返回() {
        assertEquals("138****5678", StringMasker.maskPhone("138****5678"));
    }

    @Test
    void maskMobile正常手机号中间四位脱敏() {
        assertEquals("138****5678", StringTools.maskMobile("13812345678"));
    }

    @Test
    void maskMobile_null原样返回() {
        assertNull(StringTools.maskMobile(null));
    }

    @Test
    void maskMobile长度不足原样返回() {
        assertEquals("123", StringTools.maskMobile("123"));
    }

    @Test
    void maskMobile超长数字原样返回() {
        assertEquals("138123456789", StringTools.maskMobile("138123456789"));
    }

    @Test
    void maskMobile空字符串原样返回() {
        assertEquals("", StringTools.maskMobile(""));
    }

    @Test
    void maskMobile长度11但含字母原样返回() {
        assertEquals("13812345a78", StringTools.maskMobile("13812345a78"));
    }

    @Test
    void maskMobile含非数字符号原样返回() {
        assertEquals("138-1234-5678", StringTools.maskMobile("138-1234-5678"));
    }

    @Test
    void maskMobile非1开头的11位数字也脱敏() {
        assertEquals("238****5678", StringTools.maskMobile("23812345678"));
    }

    @Test
    void maskMobile前导0的11位数字也脱敏() {
        assertEquals("012****7890", StringTools.maskMobile("01234567890"));
    }

    @Test
    void maskMobile全0的11位数字脱敏() {
        assertEquals("000****0000", StringTools.maskMobile("00000000000"));
    }

    @Test
    void maskMobile已脱敏字符串原样返回() {
        assertEquals("138****5678", StringTools.maskMobile("138****5678"));
    }

    @Test
    void maskMobile长度11但含空格原样返回() {
        assertEquals("138 1234 5678", StringTools.maskMobile("138 1234 5678"));
    }
}
