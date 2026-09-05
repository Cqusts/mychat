package com.mychat.service.impl;

import com.mychat.entity.dto.TokenUserInfoDto;
import com.mychat.entity.po.ChatMessage;
import com.mychat.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 saveMessage/saveWorkflowMessage 入口的 messageContent 长度限制。
 *
 * 本次改动在 ChatMessageServiceImpl 里新增了 checkMessageContentLength：
 * 内容超过 message.max-length（默认5000）时抛 BusinessException 并给出明确文案。
 * 该方法是 private 的，且被私有 saveMessage(chatMessage, token, agentDepth) 在方法
 * 第一行调用，所以：
 *  - 测「超长抛异常」走公开 saveMessage 即可，异常在开头抛出，不触碰 DB/Redis；
 *  - 测「边界值/正常值不抛」通过反射直接调 private checkMessageContentLength，
 *    避免把整条落库链路（Redis好友校验、Mapper落库、WebSocket广播）全部打桩。
 */
class ChatMessageServiceImplTest {

    private ChatMessageServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new ChatMessageServiceImpl();
        // 通过反射塞入配置值，模拟 @Value("${message.max-length:5000}")
        Field field = ChatMessageServiceImpl.class.getDeclaredField("maxMessageLength");
        field.setAccessible(true);
        field.set(service, 5000);
    }

    /** 反射调用 private void checkMessageContentLength(String content) */
    private void invokeCheck(String content) throws Exception {
        Method method = ChatMessageServiceImpl.class.getDeclaredMethod("checkMessageContentLength", String.class);
        method.setAccessible(true);
        method.invoke(service, content);
    }

    // ============ 边界与正常值：不抛异常 ============

    @Test
    void 内容恰好等于上限5000不抛异常() throws Exception {
        invokeCheck("x".repeat(5000));
    }

    @Test
    void 内容接近上限4999不抛异常() throws Exception {
        invokeCheck("x".repeat(4999));
    }

    @Test
    void 内容为空串不抛异常() throws Exception {
        invokeCheck("");
    }

    @Test
    void 内容为null不抛异常_NPE防护() throws Exception {
        invokeCheck(null);
    }

    @Test
    void 中文内容按字符数计算不按字节() throws Exception {
        // 5000个中文字符，UTF-8下是15000字节，但校验用 length() 按字符数算，不该超
        invokeCheck("中".repeat(5000));
    }

    // ============ 超长：抛 BusinessException ============

    @Test
    void 内容超长抛BusinessException且提示清晰() {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageContent("x".repeat(5001));
        chatMessage.setContactId("U123");
        chatMessage.setMessageType(2);
        TokenUserInfoDto token = new TokenUserInfoDto();
        token.setUserId("U999");
        token.setNickName("测试用户");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveMessage(chatMessage, token));

        String msg = ex.getMessage();
        assertTrue(msg.contains("messageContent长度超过限制"), "提示应点明是messageContent超长，实际: " + msg);
        assertTrue(msg.contains("5000"), "提示应包含上限值，实际: " + msg);
        assertTrue(msg.contains("字符"), "提示应说明单位是字符，实际: " + msg);
    }

    @Test
    void 内容超长在落库前就被拦截_不触碰任何依赖() {
        // 只设置 messageContent 超长，contactId/messageType/token 全是脏数据，
        // 如果校验没在最开头触发，方法会一路走到好友校验/落库必然NPE或抛别的错。
        // 只要抛出的是"长度超限"的 BusinessException 就说明拦截点正确。
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageContent("超".repeat(5001));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveMessage(chatMessage, new TokenUserInfoDto()));

        assertTrue(ex.getMessage().contains("messageContent长度超过限制"),
                "应该在长度校验处被拦截，实际报错: " + ex.getMessage());
    }

    /** 配置值可被覆盖（模拟不同 message.max-length 环境） */
    @Test
    void 配置上限可被调低() throws Exception {
        Field field = ChatMessageServiceImpl.class.getDeclaredField("maxMessageLength");
        field.setAccessible(true);
        field.set(service, 100);

        // 200字符在默认5000下合法，但在100上限下超长
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveMessage(buildMessage("y".repeat(200)), buildToken()));
        assertTrue(ex.getMessage().contains("100"), "提示应反映当前配置上限100，实际: " + ex.getMessage());
    }

    private ChatMessage buildMessage(String content) {
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setMessageContent(content);
        chatMessage.setContactId("U123");
        chatMessage.setMessageType(2);
        return chatMessage;
    }

    private TokenUserInfoDto buildToken() {
        TokenUserInfoDto token = new TokenUserInfoDto();
        token.setUserId("U999");
        token.setNickName("测试用户");
        return token;
    }
}
