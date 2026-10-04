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

    /** 降级总次数（各分桶之和） */
    private long degradeTotal;

    /** 降级次数分桶（key=AiDegradeReason 名称，value=次数） */
    private Map<String, Long> degradeByReason;

    /** 本地限流拒绝次数（RATE_LIMITED 桶的子集） */
    private long rateLimitRejected;

    /** 上游 429 次数（RATE_LIMITED 桶的子集） */
    private long upstream429;
}