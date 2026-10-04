package com.example.reservation.ai.dto;

import lombok.Data;

import java.util.Map;

/**
 * AI 运行指标快照 VO（GET /api/stats/ai/metrics，管理员专属，只读）
 *
 * 仅回传原始计数，不提供任何派生百分比（样本量小时百分比易误导）；
 * 计数器为进程内累加，应用重启即清零（startedAt 标明本次统计起点）。
 *
 * @author reservation-team
 */
@Data
public class AiMetricsVO {

    /** 统计起始时刻（本次进程启动/Bean 创建时刻，yyyy-MM-dd HH:mm:ss） */
    private String startedAt;

    /** 上游模型调用次数（已通过限流与密钥检查的真实调用，含失败） */
    private long modelCallCount;

    /** 上游模型调用平均耗时（毫秒，无调用时为 0） */
    private long modelAvgMs;

    /** 上游模型调用最大耗时（毫秒） */
    private long modelMaxMs;

    /** 输入 token 累计（上游未返回 usage 时保持为 0，表示不可得） */
    private long totalPromptTokens;

    /** 输出 token 累计（上游未返回 usage 时保持为 0） */
    private long totalCompletionTokens;

    /** 合计 token 累计（上游未返回 usage 时保持为 0） */
    private long totalTokens;

    /** 结束原因分桶（key=stop/length/content_filter/unknown，value=次数；仅统计拿到 HTTP 200 的调用） */
    private Map<String, Long> finishReasonByReason;

    /** 降级总次数（各分桶之和） */
    private long degradeTotal;

    /** 降级次数分桶（key=AiDegradeReason 名称，value=次数） */
    private Map<String, Long> degradeByReason;

    /** 本地限流拒绝次数（RATE_LIMITED 桶的子集） */
    private long rateLimitRejected;

    /** 上游 429 次数（RATE_LIMITED 桶的子集） */
    private long upstream429;

    /** 结果缓存命中次数（命中即未产生上游调用） */
    private long cacheHitCount;

    /** 结果缓存未命中次数 */
    private long cacheMissCount;
}