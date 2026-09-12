package com.example.reservation.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器
 * 统一拦截业务异常与系统异常，转换为 Result 结构，禁止将异常堆栈直接返回前端
 *
 * @author reservation-team
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：携带语义化错误码与提示信息
     */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 请求体解析失败（非法 JSON / 类型不匹配）：按参数错误处理
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.error(ResultCode.PARAM_ERROR.getCode(), "请求体格式错误");
    }

    /**
     * 缺少必填请求参数（R3 起：@RequestParam 必填参数缺失统一返回 400）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必要参数：{}", e.getParameterName());
        return Result.error(ResultCode.PARAM_ERROR.getCode(), "缺少必要参数：" + e.getParameterName());
    }

    /**
     * 请求参数类型不匹配（如 classroomId=abc 无法转为 Long，R6 边界补全：由 500 降级为 400 友好提示）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配：参数 {} 期望类型 {}", e.getName(), e.getRequiredType() == null ? "未知" : e.getRequiredType().getSimpleName());
        return Result.error(ResultCode.PARAM_ERROR.getCode(), "参数类型不正确：" + e.getName());
    }

    /**
     * 兜底异常：隐藏堆栈细节，统一返回 500
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常：", e);
        return Result.error(ResultCode.SERVER_ERROR.getCode(), ResultCode.SERVER_ERROR.getMessage());
    }
}
