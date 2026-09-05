package com.mychat.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * 字符串长度上限校验注解。
 *
 * null/空串直接放行（保持现有行为不变），仅对非空内容执行长度检查。
 * message 里的占位符 {value} 会由 Hibernate Validator 替换成注解上的 maxLength 值。
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = LengthLimitValidator.class)
@Documented
public @interface LengthLimit {

    /**
     * 允许的最大长度（字符数，按 Java String.length() 即 UTF-16 code unit 计算）。
     */
    long maxLength() default 5000;

    String message() default "内容长度{value}，超过上限{maxLength}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
