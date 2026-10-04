package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiMetricsVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 运行指标纯单测（本次改动：token 累计、finish_reason 分桶、缓存命中计数）
 *
 * AiMetrics 无外部依赖，直接 new 即可断言；不涉及 Spring 上下文。
 *
 * @author reservation-team
 */
class AiMetricsTest {

    @Test
    @DisplayName("token 用量累计：多次记录按输入/输出/合计分别累加")
    void recordUsageAccumulates() {
        AiMetrics metrics = new AiMetrics();
        metrics.recordUsage(new AiUsage(100L, 20L, 120L, AiConstants.FINISH_REASON_STOP));
        metrics.recordUsage(new AiUsage(50L, 5L, 55L, AiConstants.FINISH_REASON_STOP));

        AiMetricsVO vo = metrics.snapshot();
        assertEquals(150L, vo.getTotalPromptTokens());
        assertEquals(25L, vo.getTotalCompletionTokens());
        assertEquals(175L, vo.getTotalTokens());
    }

    @Test
    @DisplayName("token 用量：上游未返回 usage 时保持为 0（不伪造数据）")
    void recordUsageEmptyKeepsZero() {
        AiMetrics metrics = new AiMetrics();
        metrics.recordUsage(AiUsage.EMPTY);
        metrics.recordUsage(null);

        AiMetricsVO vo = metrics.snapshot();
        assertEquals(0L, vo.getTotalTokens());
    }

    @Test
    @DisplayName("finish_reason 分桶：已知三桶各归其位，未知取值与 null 归 unknown")
    void recordFinishReasonBuckets() {
        AiMetrics metrics = new AiMetrics();
        metrics.recordFinishReason(AiConstants.FINISH_REASON_STOP);
        metrics.recordFinishReason(AiConstants.FINISH_REASON_LENGTH);
        metrics.recordFinishReason(AiConstants.FINISH_REASON_CONTENT_FILTER);
        metrics.recordFinishReason("tool_calls");
        metrics.recordFinishReason(null);

        AiMetricsVO vo = metrics.snapshot();
        assertEquals(1L, vo.getFinishReasonByReason().get(AiConstants.FINISH_REASON_STOP));
        assertEquals(1L, vo.getFinishReasonByReason().get(AiConstants.FINISH_REASON_LENGTH));
        assertEquals(1L, vo.getFinishReasonByReason().get(AiConstants.FINISH_REASON_CONTENT_FILTER));
        // tool_calls 与 null 均归入 unknown
        assertEquals(2L, vo.getFinishReasonByReason().get(AiConstants.FINISH_REASON_UNKNOWN));
        // 分桶固定 4 个，结构稳定
        assertEquals(4, vo.getFinishReasonByReason().size());
    }

    @Test
    @DisplayName("模型调用耗时：平均与最大值计算正确，零调用时平均为 0（不除零）")
    void recordModelCallAvgAndMax() {
        AiMetrics empty = new AiMetrics();
        assertEquals(0L, empty.snapshot().getModelAvgMs());

        AiMetrics metrics = new AiMetrics();
        metrics.recordModelCall(100L);
        metrics.recordModelCall(300L);
        AiMetricsVO vo = metrics.snapshot();
        assertEquals(2L, vo.getModelCallCount());
        assertEquals(200L, vo.getModelAvgMs());
        assertEquals(300L, vo.getModelMaxMs());
    }

    @Test
    @DisplayName("降级分桶与限流计数：本地限流拒绝同时计入 RATE_LIMITED，并汇总为 degradeTotal")
    void recordDegradeAndRateLimit() {
        AiMetrics metrics = new AiMetrics();
        metrics.recordRateLimitRejected();
        metrics.recordUpstream429();
        metrics.recordDegrade(AiDegradeReason.NO_KEY);

        AiMetricsVO vo = metrics.snapshot();
        // rateLimitRejected 只统计「本地限流拒绝」；上游 429 单独计数，二者同属 RATE_LIMITED 桶
        assertEquals(1L, vo.getRateLimitRejected());
        assertEquals(1L, vo.getUpstream429());
        assertEquals(2L, vo.getDegradeByReason().get(AiDegradeReason.RATE_LIMITED.name()));
        assertEquals(1L, vo.getDegradeByReason().get(AiDegradeReason.NO_KEY.name()));
        assertEquals(3L, vo.getDegradeTotal());
        // 降级桶固定 4 个，结构稳定
        assertEquals(4, vo.getDegradeByReason().size());
    }

    @Test
    @DisplayName("缓存命中/未命中计数：命中不改变模型调用次数")
    void recordCacheHitAndMiss() {
        AiMetrics metrics = new AiMetrics();
        metrics.recordCacheMiss();
        metrics.recordCacheHit();
        metrics.recordCacheHit();

        AiMetricsVO vo = metrics.snapshot();
        assertEquals(2L, vo.getCacheHitCount());
        assertEquals(1L, vo.getCacheMissCount());
        assertEquals(0L, vo.getModelCallCount());
        assertTrue(vo.getStartedAt() != null && !vo.getStartedAt().isBlank());
    }
}