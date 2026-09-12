package com.example.reservation.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一返回码枚举（语义化错误码，spec.md 2.5）
 *
 * @author reservation-team
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),
    /** 参数错误 / 业务校验失败 */
    PARAM_ERROR(400, "参数错误"),
    /** 未登录或登录已过期 */
    UNAUTHORIZED(401, "未登录或登录已过期"),
    /** 无权限访问 */
    FORBIDDEN(403, "无权限访问"),
    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),
    /** 系统异常 */
    SERVER_ERROR(500, "系统异常，请稍后重试");

    private final int code;

    private final String message;
}
