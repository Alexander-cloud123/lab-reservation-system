package com.example.reservation.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回结果封装
 * 约定：{ "code": 200, "message": "操作成功", "data": {} }
 * code=200 成功；4xx 参数/鉴权错误；5xx 系统异常
 *
 * @author reservation-team
 */
@Data
public class Result<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private Integer code;

    /** 提示信息 */
    private String message;

    /** 业务数据 */
    private T data;

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /** 成功（无数据） */
    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    /** 成功（带数据） */
    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    /** 成功（自定义提示 + 数据） */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /** 失败（语义化错误码 + 自定义提示） */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    /** 失败（默认 400 参数/业务错误） */
    public static <T> Result<T> error(String message) {
        return new Result<>(ResultCode.PARAM_ERROR.getCode(), message, null);
    }
}
