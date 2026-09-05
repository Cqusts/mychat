package com.mychat.annotation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 聊天消息内容长度校验器。
 * 注册为 Spring Bean，通过 @Value 注入配置，避免前后端硬编码不一致。
 */
@Component
public class MessageContentValidator implements ConstraintValidator<ValidMessageContent, String> {

    @Value("${chat.message.max-length:5000}")
    private int maxLength;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null 交给 @NotBlank 处理，这里不重复拦截
        if (value == null) {
            return true;
        }
        return value.length() <= maxLength;
    }
}
