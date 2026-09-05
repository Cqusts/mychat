package com.mychat.annotation;

import com.mychat.entity.enums.ResponseCodeEnum;
import com.mychat.exception.BusinessException;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link LengthLimit} 注解的校验实现。
 *
 * null/空串放行，仅对非空内容执行长度检查；超限时抛 {@link BusinessException}，
 * 错误码为 PARAM_TOO_LONG(40010)，message 含当前长度与上限值。
 */
public class LengthLimitValidator implements ConstraintValidator<LengthLimit, String> {

    private long maxLength;

    @Override
    public void initialize(LengthLimit constraintAnnotation) {
        this.maxLength = constraintAnnotation.maxLength();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // null/空串直接放行，保持现有行为不变，避免 NPE
        if (value == null || value.isEmpty()) {
            return true;
        }
        if (value.length() > maxLength) {
            throw new BusinessException(ResponseCodeEnum.PARAM_TOO_LONG.getCode(),
                    "内容长度" + value.length() + "，超过上限" + maxLength);
        }
        return true;
    }
}
