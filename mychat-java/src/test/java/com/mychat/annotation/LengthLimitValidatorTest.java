package com.mychat.annotation;

import com.mychat.entity.enums.ResponseCodeEnum;
import com.mychat.exception.BusinessException;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LengthLimitValidator} 的单元测试。
 *
 * 校验器是纯逻辑、不依赖 Spring/容器，可直接实例化验证。
 * 覆盖：默认上限 5000 / 自定义上限 / null 放行 / 空串放行 /
 * 恰好等于上限放行 / 超限抛异常（错误码与 message 内容）/
 * 注解默认值与 message 模板声明。
 */
class LengthLimitValidatorTest {

    private LengthLimitValidator validator;
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        validator = new LengthLimitValidator();
        context = null; // 实现里没有使用 context，传 null 即可
    }

    // ---------- 默认上限 5000 ----------

    @Test
    void null值直接放行() {
        validator.initialize(annotationOf(5000));
        assertTrue(validator.isValid(null, context), "null 应放行，不抛异常");
    }

    @Test
    void 空串直接放行() {
        validator.initialize(annotationOf(5000));
        assertTrue(validator.isValid("", context), "空串应放行，不抛异常");
    }

    @Test
    void 恰好等于上限放行() {
        validator.initialize(annotationOf(5));
        assertTrue(validator.isValid("12345", context), "长度恰好等于上限应放行");
    }

    @Test
    void 未超限放行() {
        validator.initialize(annotationOf(5000));
        assertTrue(validator.isValid("hello", context), "长度未超限应放行");
    }

    @Test
    void 超限抛业务异常且错误码为40010() {
        validator.initialize(annotationOf(5));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.isValid("123456", context));
        assertEquals(ResponseCodeEnum.PARAM_TOO_LONG.getCode(), ex.getCode(),
                "超限应抛 PARAM_TOO_LONG(40010)");
    }

    @Test
    void 超限异常message包含当前长度与上限() {
        validator.initialize(annotationOf(5));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.isValid("123456", context));
        assertTrue(ex.getMessage().contains("内容长度6"), "message 应含当前长度，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("超过上限5"), "message 应含上限值，实际: " + ex.getMessage());
    }

    // ---------- 自定义上限 ----------

    @Test
    void 自定义上限生效() {
        validator.initialize(annotationOf(3));
        assertTrue(validator.isValid("abc", context), "自定义上限 3，长度 3 应放行");
        assertThrows(BusinessException.class, () -> validator.isValid("abcd", context),
                "自定义上限 3，长度 4 应抛异常");
    }

    @Test
    void 默认上限为5000() {
        LengthLimit annotation = annotationOf(0); // 占位，实际检查注解默认值
        // 直接读注解的默认 maxLength
        assertEquals(5000L, defaultMaxLength(), "注解 maxLength 默认值应为 5000");
    }

    // ---------- UTF-16 字符长度（中文等） ----------

    @Test
    void 中文按UTF16计长() {
        // "你好" 在 Java String.length() 下是 2 个 code unit
        validator.initialize(annotationOf(1));
        assertThrows(BusinessException.class, () -> validator.isValid("你好", context),
                "两个中文字符 length=2 超上限 1，应抛异常");
    }

    @Test
    void 刚好等于上限的中文放行() {
        validator.initialize(annotationOf(2));
        assertTrue(validator.isValid("你好", context), "两个中文字符 length=2 等于上限 2 应放行");
    }

    // ---------- 辅助方法：用反射造注解实例 ----------

    private LengthLimit annotationOf(long maxLength) {
        return new LengthLimit() {
            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return LengthLimit.class;
            }

            @Override
            public long maxLength() {
                return maxLength;
            }

            @Override
            public String message() {
                return "内容长度{value}，超过上限{maxLength}";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @Override
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return new Class[0];
            }
        };
    }

    private long defaultMaxLength() {
        try {
            Method m = LengthLimit.class.getMethod("maxLength");
            return (long) m.getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
