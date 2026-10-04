package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiMetricsVO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 运行指标收集器（只读观测，不改变任何业务行为）
 *
 * 目的：把「模型调用频率/耗时、token 消耗、结束原因分布、降级原因分布、限流触发、缓存命中」变成可观测数字，
 * 答辩时可直观说明 AI 模块的运行健康度与额度消耗，而非仅靠日志翻查。
 *
 * 计数口径：
 *  - {@link #modelCallCount}：真正发往上游的 HTTP 调用次数（已通过本地限流与密钥检查），
 *    含成功与失败；限流拒绝/密钥缺失不产生对外调用，故不计入；
 *  - {@link #modelTotalMs} / {@link #modelMaxMs}：上游调用（含失败）的耗时累计与最大值；
 *  - {@link #promptTokens} / {@link #completionTokens} / {@link #totalTokens}：上游返回 usage 的用量累计
 *    （上游未返回 usage 时保持为 0，不会伪造数据）；
 *  - {@link #finishReasonCounters}：结束原因分桶，**仅统计拿到 HTTP 200 响应的调用**（4 个桶，其余归 unknown）；
 *  - {@link #degradeByReason}：按 {@link AiDegradeReason} 分桶的降级次数；
 *  - {@link #rateLimitRejected}：本地限流器拒绝次数（是 RATE_LIMITED 桶的子集）；
 *  - {@link #upstream429}：上游返回 429 的次数（同是 RATE_LIMITED 桶的子集）；
 *  - {@link #cacheHitCount} / {@link #cacheMissCount}：结果缓存命中/未命中次数（命中即不产生上游调用）。
 *
 * 说明：计数器为「进程内累加」，应用重启即清零；只提供快照，不提供重置接口（避免被误当作业务数据篡改）。
 *
 * @author reservation-team
 */
@Component
public class AiMetrics {

    /** 统计起始时刻格式（本地时区，便于人工阅读） */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 统计起始时刻（Bean 创建即视为本次进程统计起点） */
    private final LocalDateTime startedAt = LocalDateTime.now();

    /** 上游模型调用次数 */
    private final AtomicLong modelCallCount = new AtomicLong();

    /** 上游模型调用耗时累计（毫秒） */
    private final AtomicLong modelTotalMs = new AtomicLong();

    /** 上游模型调用耗时最大值（毫秒） */
    private final AtomicLong modelMaxMs = new AtomicLong();

    /** 输入 token 累计 */
    private final AtomicLong promptTokens = new AtomicLong();

    /** 输出 token 累计 */
    private final AtomicLong completionTokens = new AtomicLong();

    /** 合计 token 累计 */
    private final AtomicLong totalTokens = new AtomicLong();

    /** 结束原因分桶（LinkedHashMap 固定 4 桶 + 稳定输出顺序） */
    private final Map<String, AtomicLong> finishReasonCounters = new LinkedHashMap<>();

    /** 降级次数分桶（EnumMap 保证固定桶、便于快照输出） */
    private final Map<AiDegradeReason, AtomicLong> degradeByReason = new EnumMap<>(AiDegradeReason.class);

    /** 本地限流拒绝次数 */
    private final AtomicLong rateLimitRejected = new AtomicLong();

    /** 上游 429 次数 */
    private final AtomicLong upstream429 = new AtomicLong();

    /** 结果缓存命中次数 */
    private final AtomicLong cacheHitCount = new AtomicLong();

    /** 结果缓存未命中次数 */
    private final AtomicLong cacheMissCount = new AtomicLong();

    public AiMetrics() {
        // 预置全部降级桶，保证快照结构稳定（缺桶也返回 0，而非缺失字段）
        for (AiDegradeReason reason : AiDegradeReason.values()) {
            degradeByReason.put(reason, new AtomicLong());
        }
        // 预置结束原因 4 桶（其余取值统一归入 unknown）
        for (String reason : new String[]{
                AiConstants.FINISH_REASON_STOP,
                AiConstants.FINISH_REASON_LENGTH,
                AiConstants.FINISH_REASON_CONTENT_FILTER,
                AiConstants.FINISH_REASON_UNKNOWN}) {
            finishReasonCounters.put(reason, new AtomicLong());
        }
    }

    /**
     * 记录一次真实的上游模型调用及其耗时（含失败调用）
     *
     * @param elapsedMs 本次调用耗时（毫秒，负值按 0 处理）
     */
    public void recordModelCall(long elapsedMs) {
        long ms = Math.max(elapsedMs, 0L);
        modelCallCount.incrementAndGet();
        modelTotalMs.addAndGet(ms);
        modelMaxMs.accumulateAndGet(ms, Math::max);
    }

    /**
     * 记录上游返回的 token 用量（上游未返回 usage 时为全 0，保持为 0 即为「不可得」的如实体现）
     */
    public void recordUsage(AiUsage usage) {
        if (usage == null) {
            return;
        }
        promptTokens.addAndGet(Math.max(usage.promptTokens(), 0L));
        completionTokens.addAndGet(Math.max(usage.completionTokens(), 0L));
        totalTokens.addAndGet(Math.max(usage.totalTokens(), 0L));
    }

    /**
     * 记录一次结束原因（仅限拿到 HTTP 200 响应的调用；未知/缺失归入 unknown 桶）
     */
    public void recordFinishReason(String finishReason) {
        finishReasonCounters.get(normalizeFinishReason(finishReason)).incrementAndGet();
    }

    /**
     * 记录一次结果缓存命中（命中即未产生上游调用，故 modelCallCount 不会增长）
     */
    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }

    /**
     * 记录一次结果缓存未命中
     */
    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }

    /**
     * 记录一次降级（按原因分桶）
     */
    public void recordDegrade(AiDegradeReason reason) {
        degradeByReason.get(reason).incrementAndGet();
    }

    /**
     * 记录一次本地限流拒绝（同时计入 RATE_LIMITED 降级桶）
     */
    public void recordRateLimitRejected() {
        rateLimitRejected.incrementAndGet();
        recordDegrade(AiDegradeReason.RATE_LIMITED);
    }

    /**
     * 记录一次上游 429（同时计入 RATE_LIMITED 降级桶）
     */
    public void recordUpstream429() {
        upstream429.incrementAndGet();
        recordDegrade(AiDegradeReason.RATE_LIMITED);
    }

    /**
     * 生成当前指标快照（只读，不改变任何计数）
     */
    public AiMetricsVO snapshot() {
        AiMetricsVO vo = new AiMetricsVO();
        vo.setStartedAt(startedAt.format(TIME_FORMATTER));

        long callCount = modelCallCount.get();
        long totalMs = modelTotalMs.get();
        vo.setModelCallCount(callCount);
        // 平均耗时：无调用时返回 0，避免除零
        vo.setModelAvgMs(callCount == 0 ? 0L : totalMs / callCount);
        vo.setModelMaxMs(modelMaxMs.get());

        vo.setTotalPromptTokens(promptTokens.get());
        vo.setTotalCompletionTokens(completionTokens.get());
        vo.setTotalTokens(totalTokens.get());

        Map<String, Long> finishMap = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : finishReasonCounters.entrySet()) {
            finishMap.put(entry.getKey(), entry.getValue().get());
        }
        vo.setFinishReasonByReason(finishMap);

        long total = 0L;
        Map<String, Long> reasonMap = new LinkedHashMap<>();
        // 按枚举声明顺序输出，保证快照可读且顺序稳定
        for (AiDegradeReason reason : AiDegradeReason.values()) {
            long count = degradeByReason.get(reason).get();
            reasonMap.put(reason.name(), count);
            total += count;
        }
        vo.setDegradeTotal(total);
        vo.setDegradeByReason(reasonMap);

        vo.setRateLimitRejected(rateLimitRejected.get());
        vo.setUpstream429(upstream429.get());
        vo.setCacheHitCount(cacheHitCount.get());
        vo.setCacheMissCount(cacheMissCount.get());
        return vo;
    }

    /** 结束原因归桶：仅保留 stop/length/content_filter，其余（含 null、上游新取值）统一归 unknown */
    private String normalizeFinishReason(String finishReason) {
        if (finishReason == null) {
            return AiConstants.FINISH_REASON_UNKNOWN;
        }
        return switch (finishReason) {
            case AiConstants.FINISH_REASON_STOP -> AiConstants.FINISH_REASON_STOP;
            case AiConstants.FINISH_REASON_LENGTH -> AiConstants.FINISH_REASON_LENGTH;
            case AiConstants.FINISH_REASON_CONTENT_FILTER -> AiConstants.FINISH_REASON_CONTENT_FILTER;
            default -> AiConstants.FINISH_REASON_UNKNOWN;
        };
    }
}