package com.example.reservation.ai.support;

/**
 * AI 降级原因分桶（AiMetrics 计数用，禁止用裸字符串做分桶键）
 *
 * 分桶职责（避免同一事件被重复计数）：
 *  - AgnesClient 负责记录「未拿到模型输出」的失败类桶：限流、无密钥、服务异常；
 *  - 4 个业务 Service 负责记录「模型已返回但结构不合法」的 {@link #OUTPUT_INVALID}。
 *
 * @author reservation-team
 */
public enum AiDegradeReason {

    /** 限流：本地限流器拒绝（用户/全站窗口超限）或上游返回 429 */
    RATE_LIMITED("限流（本地限流拒绝或上游 429）"),

    /** 密钥缺失：环境变量 AGNES_API_KEY 未配置，直接降级、不触发对外调用 */
    NO_KEY("未配置密钥"),

    /** 服务异常：超时/网络异常/上游非 429 错误/响应内容为空 */
    SERVICE_ERROR("服务异常（超时/网络/上游错误/空响应）"),

    /** 输出结构不合法：模型有返回内容，但不符合 Prompt 约定的 JSON 形态 */
    OUTPUT_INVALID("模型输出结构不合法");

    /** 中文说明（仅用于文档/日志可读性，不参与前端展示） */
    private final String description;

    AiDegradeReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}