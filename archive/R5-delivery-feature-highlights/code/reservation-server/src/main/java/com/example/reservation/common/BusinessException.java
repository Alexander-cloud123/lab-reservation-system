package com.example.reservation.common;

import lombok.Getter;

/**
 * 业务异常
 * 业务校验失败时抛出，由全局异常处理器统一转换为 Result 返回，禁止将堆栈抛给前端
 *
 * @author reservation-team
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码（默认 400 参数/业务错误） */
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.PARAM_ERROR.getCode();
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
