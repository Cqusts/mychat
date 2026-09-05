package com.mychat.controller;

import com.mychat.entity.enums.ResponseCodeEnum;
import com.mychat.entity.vo.ResponseVO;
import com.mychat.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import jakarta.validation.ConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AGlobalExceptionHandlerControllerTest {

    private final AGlobalExceptionHandlerController handler = new AGlobalExceptionHandlerController();

    private HttpServletRequest mockRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://localhost/chat/sendMessage"));
        return request;
    }

    @Test
    void 参数校验失败映射为40001() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        ResponseVO vo = (ResponseVO) handler.handleException(ex, mockRequest());
        assertEquals(ResponseCodeEnum.CODE_40001.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40001.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void HandlerMethodValidationException映射为40001() throws Exception {
        HandlerMethodValidationException ex = mock(HandlerMethodValidationException.class);
        ResponseVO vo = (ResponseVO) handler.handleException(ex, mockRequest());
        assertEquals(ResponseCodeEnum.CODE_40001.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40001.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void ConstraintViolationException映射为40001() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new ConstraintViolationException("参数校验失败", java.util.Collections.emptySet()),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_40001.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40001.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void BindException映射为40001() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new BindException(new org.springframework.validation.BeanPropertyBindingResult(new Object(), "obj")),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_40001.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40001.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void MethodArgumentTypeMismatchException映射为40001() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new MethodArgumentTypeMismatchException("abc", Integer.class, "messageType", null, null),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_40001.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40001.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void HttpMessageNotReadableException映射为40002() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new HttpMessageNotReadableException("请求体格式错误"),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_40002.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_40002.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void NoHandlerFoundException映射为404() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new NoHandlerFoundException("GET", "/chat/xxx", null),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_404.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_404.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void BusinessException返回业务错误码() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new BusinessException(ResponseCodeEnum.CODE_600),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_600.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_600.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void 无code的BusinessException回退到600() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new BusinessException("自定义业务错误"),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_600.getCode(), vo.getCode());
        assertEquals("自定义业务错误", vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void DuplicateKeyException映射为601() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new DuplicateKeyException("主键冲突"),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_601.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_601.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }

    @Test
    void 未知异常走500兜底() {
        ResponseVO vo = (ResponseVO) handler.handleException(
                new IllegalStateException("未知错误"),
                mockRequest());
        assertEquals(ResponseCodeEnum.CODE_500.getCode(), vo.getCode());
        assertEquals(ResponseCodeEnum.CODE_500.getMsg(), vo.getInfo());
        assertEquals("error", vo.getStatus());
    }
}
