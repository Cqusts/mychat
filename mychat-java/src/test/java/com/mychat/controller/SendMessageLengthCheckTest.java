package com.mychat.controller;

import com.mychat.entity.constants.MessageConstants;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendMessageLengthCheckTest {

    @Test
    void 常量类上限为5000() {
        assertEquals(5000, MessageConstants.MAX_CONTENT_LENGTH);
    }

    @Test
    void sendMessage的messageContent注解引用常量上限() throws Exception {
        Method method = ChatController.class.getMethod(
                "sendMessage",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class, String.class, Integer.class,
                Long.class, String.class, Integer.class);
        Parameter[] params = method.getParameters();
        // messageContent 是第3个参数（下标2），且是 String 类型
        assertEquals(String.class, params[2].getType(), "第3个参数应为 messageContent(String)");
        Parameter messageContent = params[2];
        boolean hasNotEmpty = false;
        boolean hasSize = false;
        for (Annotation ann : messageContent.getAnnotations()) {
            if (ann instanceof jakarta.validation.constraints.NotEmpty) {
                hasNotEmpty = true;
            }
            if (ann instanceof Size) {
                hasSize = true;
                Size size = (Size) ann;
                // 断言上限值等于常量类中的定义，确保二者不脱节
                assertEquals(MessageConstants.MAX_CONTENT_LENGTH, size.max(),
                        "@Size.max 必须与 MessageConstants.MAX_CONTENT_LENGTH 保持一致");
            }
        }
        assertTrue(hasNotEmpty, "messageContent 应有 @NotEmpty");
        assertTrue(hasSize, "messageContent 应有 @Size");
    }

    @Test
    void 常量上限为正数() {
        assertTrue(MessageConstants.MAX_CONTENT_LENGTH > 0, "长度上限必须为正数");
    }
}
