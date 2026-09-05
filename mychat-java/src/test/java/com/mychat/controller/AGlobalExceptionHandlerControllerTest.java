package com.mychat.controller;

import com.mychat.entity.enums.ResponseCodeEnum;
import com.mychat.entity.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常处理器对消息超长场景的响应码测试。
 *
 * 只验证 handleException 对 ConstraintViolationException 的分支判断：
 * 消息是"消息内容长度超出限制"时返回 40001，其它参数校验错误仍回 600。
 * 不拉起 Spring 容器，直接实例化处理器并 mock 请求。
 */
class AGlobalExceptionHandlerControllerTest {

    private final AGlobalExceptionHandlerController handler = new AGlobalExceptionHandlerController();

    private ConstraintViolationException buildConstraintViolation(String message) {
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn(message);
        return new ConstraintViolationException(Collections.singleton(violation));
    }

    @Test
    void 消息内容超长时返回40001() {
        ConstraintViolationException ex = buildConstraintViolation("消息内容长度超出限制");
        HttpServletRequest request = mock(HttpServletRequest.class);

        ResponseVO vo = (ResponseVO) handler.handleException(ex, request);

        assertEquals(ResponseCodeEnum.CODE_40001.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40001.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void 其它参数校验错误仍返回通用600() {
        ConstraintViolationException ex = buildConstraintViolation("不能为空");
        HttpServletRequest request = mock(HttpServletRequest.class);

        ResponseVO vo = (ResponseVO) handler.handleException(ex, request);

        assertEquals(ResponseCodeEnum.CODE_600.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_600.getMsg(), vo.getInfo());
    }
}
