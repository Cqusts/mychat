package com.mychat.annotation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 校验聊天消息内容长度，上限由配置 chat.message.max-length 控制（默认 5000）。
 * 仅处理非空内容的长度，null/空白交给 @NotBlank 处理。
 */
@Target({ElementType.PARAMETER, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = MessageContentValidator.class)
public @interface ValidMessageContent {

    String message() default "消息内容长度超出限制";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
