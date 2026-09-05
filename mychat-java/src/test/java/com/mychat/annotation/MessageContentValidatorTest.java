package com.mychat.annotation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天消息内容长度校验器的边界测试。
 *
 * 校验器是 @Component，maxLength 靠 @Value 从配置注入，
 * 这里不拉起 Spring 容器，直接用反射把配置值塞进私有字段，
 * 只测 isValid 的纯长度判断逻辑。
 */
class MessageContentValidatorTest {

    private MessageContentValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MessageContentValidator();
        // 模拟 application.properties 里的默认值 chat.message.max-length=5000
        ReflectionTestUtils.setField(validator, "maxLength", 5000);
    }

    @Test
    void 长度等于上限放行() {
        String value = "a".repeat(5000);
        assertTrue(validator.isValid(value, null), "长度恰好等于上限应该通过");
    }

    @Test
    void 长度小于上限放行() {
        assertTrue(validator.isValid("hello world", null));
        assertTrue(validator.isValid("", null));
    }

    @Test
    void 长度超过上限被拦截() {
        String value = "a".repeat(5001);
        assertFalse(validator.isValid(value, null), "超过上限1个字符就该被拦下");
    }

    @Test
    void null不触发长度校验交给NotBlank() {
        // 方案约定：null/空白由 @NotBlank 处理，长度校验不重复拦截
        assertTrue(validator.isValid(null, null), "null 不该被长度校验拦下");
    }

    @Test
    void 配置的上限值动态生效() {
        // 模拟 chat.message.max-length=10 的场景
        ReflectionTestUtils.setField(validator, "maxLength", 10);
        assertTrue(validator.isValid("1234567890", null), "等于新上限应通过");
        assertFalse(validator.isValid("12345678901", null), "超过新上限应被拦下");
    }

    @Test
    void 纯空白字符串按长度判断() {
        // 纯空白不是 null，会走长度逻辑；@NotBlank 会先拦下它，
        // 这里只验证长度逻辑本身不误伤
        assertTrue(validator.isValid("   ", null));
    }
}
