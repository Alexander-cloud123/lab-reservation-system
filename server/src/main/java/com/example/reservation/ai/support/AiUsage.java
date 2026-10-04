package com.example.reservation.ai.support;

/**
 * 单次模型调用的用量与结束原因（OpenAI 兼容响应体 usage / choices[0].finish_reason 的载体）
 *
 * 说明：免费档上游未必每条响应都带 usage，故各字段缺失时统一取安全默认（0 / null），
 * 由调用方决定如何呈现，不在本类内抛异常。
 *
 * @param promptTokens     输入 token 数（缺失为 0）
 * @param completionTokens 输出 token 数（缺失为 0）
 * @param totalTokens      合计 token 数（上游未给出时按输入+输出兜底）
 * @param finishReason     结束原因：stop（正常结束）/ length（触达 max_tokens 被截断）/
 *                         content_filter（内容策略拦截），缺失为 null
 * @author reservation-team
 */
public record AiUsage(long promptTokens, long completionTokens, long totalTokens, String finishReason) {

    /** 安全默认值：无用量信息（限流/无密钥/服务异常等未走到解析链路的场景） */
    public static final AiUsage EMPTY = new AiUsage(0L, 0L, 0L, null);
}