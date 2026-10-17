package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiPrompts;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * AI 调用模板（收敛 4 个 AI 接口重复的「缓存 → 调用 → 解析 → 降级」样板）
 *
 * 4 个接口（推荐/解析/问答/合规）此前各自维护一份结构完全相同的降级块：
 * 调用 AgnesClient → resp.ok() 时解析模型输出 → 解析失败记录 OUTPUT_INVALID 并降级 →
 * resp 失败时取 resp.reason() 降级 → 用降级原因填充返回体的 message。
 * 本类把这段公共骨架收敛为一处，各接口只需提供 3 个 Func：
 *  - parser：模型原始内容 → 业务解析结果（无法解析返回 null，由调用方保证不抛异常）；
 *  - onSuccess：解析结果 → 成功返回体；
 *  - onDegrade：降级原因 → 降级返回体（内部用本地规则兜底并把原因写入 message）。
 *
 * 说明：
 *  - 4 个接口均要求 JSON 结构化输出（response_format={"type":"json_object"}），故 jsonMode 固定传 true；
 *  - OUTPUT_INVALID 桶（模型有返回但结构不合法）在此统一计数，与 AgnesClient 记录的失败类桶（限流/无密钥/服务异常）互不重复；
 *  - 结果缓存由本模板统一编排：命中即直接返回（不产生上游调用），**仅成功分支写入缓存**，
 *    降级分支永不写——从模板层杜绝「把本地规则结果固化成模型结果」。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AiInvoker {

    @Resource
    private AgnesClient agnesClient;

    /** 运行指标收集（本类负责 OUTPUT_INVALID 桶与缓存命中计数，失败类桶由 AgnesClient 负责） */
    @Resource
    private AiMetrics aiMetrics;

    /** AI 结果缓存（复用既有 RedisCache） */
    @Resource
    private AiResultCache aiResultCache;

    /**
     * 执行一次「AI 调用 + 解析 + 降级」（不启用结果缓存，供输入唯一、无复用价值的接口使用）
     *
     * @see #invoke(String, Long, String, String, Function, Function, Function, AiCacheSpec)
     */
    public <R, V> V invoke(String label, Long userId, String system, String user,
                           Function<String, R> parser,
                           Function<R, V> onSuccess,
                           Function<String, V> onDegrade) {
        return invoke(label, userId, system, List.of(), user, parser, onSuccess, onDegrade, null);
    }

    /**
     * 执行一次「缓存 → AI 调用 → 解析 → 降级」
     *
     * @param label     接口中文名（仅用于日志定位）
     * @param userId    当前登录用户 ID（限流按用户维度隔离）
     * @param system    System Prompt
     * @param user      User 消息
     * @param parser    模型内容 → 解析结果（失败返回 null）
     * @param onSuccess 解析结果 → 成功返回体
     * @param onDegrade 降级原因 → 降级返回体（本地规则兜底 + message 填充）
     * @param cacheSpec 结果缓存规格；传 null 表示不启用缓存
     * @param <R>       解析结果类型
     * @param <V>       返回体类型
     * @return 缓存命中结果、成功返回体或降级返回体
     */
    public <R, V> V invoke(String label, Long userId, String system, String user,
                           Function<String, R> parser,
                           Function<R, V> onSuccess,
                           Function<String, V> onDegrade,
                           AiCacheSpec<V> cacheSpec) {
        return invoke(label, userId, system, List.of(), user, parser, onSuccess, onDegrade, cacheSpec);
    }

    /**
     * 执行一次「缓存 → AI 调用（R13-3：可带多轮历史）→ 解析 → 降级」
     *
     * @param history 已归一化的历史消息（system 之后、当前 user 之前注入）；空列表为单轮
     * @see #invoke(String, Long, String, String, Function, Function, Function, AiCacheSpec)
     */
    public <R, V> V invoke(String label, Long userId, String system,
                           List<AgnesClient.ChatMessage> history, String user,
                           Function<String, R> parser,
                           Function<R, V> onSuccess,
                           Function<String, V> onDegrade,
                           AiCacheSpec<V> cacheSpec) {
        // R13-5：结构化流式以外的调用方无「调用前进度」需求，传空操作保持既有行为
        return invoke(label, userId, system, history, user, parser, onSuccess, onDegrade, cacheSpec, () -> {
        });
    }

    /**
     * 执行一次「缓存 → AI 调用（R13-3：可带多轮历史）→ 解析 → 降级」，并支持调用前回调（R13-5）。
     *
     * @param beforeUpstreamCall 缓存未命中、真正发起上游调用前的回调（结构化流式用于发 phase 进度帧）；
     *                           缓存命中时【不执行】。回调抛异常（如客户端断开包装的 UncheckedIOException）
     *                           直接向上中止，不发起上游调用
     * @see #invoke(String, Long, String, java.util.List, String, Function, Function, Function, AiCacheSpec)
     */
    public <R, V> V invoke(String label, Long userId, String system,
                           List<AgnesClient.ChatMessage> history, String user,
                           Function<String, R> parser,
                           Function<R, V> onSuccess,
                           Function<String, V> onDegrade,
                           AiCacheSpec<V> cacheSpec,
                           Runnable beforeUpstreamCall) {
        // 1. 缓存查询：命中即返回，不产生上游调用（故 modelCallCount 不增长，beforeUpstreamCall 也不执行）
        if (cacheSpec != null) {
            V cached = aiResultCache.get(cacheSpec.key(), cacheSpec.type());
            if (cached != null) {
                aiMetrics.recordCacheHit();
                return cached;
            }
            aiMetrics.recordCacheMiss();
        }

        // 2. 缓存未命中：先执行调用前回调（phase 进度帧）；回调失败（客户端已断开）则中止，不空耗配额
        beforeUpstreamCall.run();

        // 3. 调用模型；ok=false 时 reason 为失败类降级原因（限流/密钥缺失/服务异常）
        AgnesClient.AgnesResponse resp = agnesClient.chat(userId, system, history, user, true);
        if (resp.ok()) {
            R parsed = parser.apply(resp.content());
            if (parsed != null) {
                V vo = onSuccess.apply(parsed);
                // 3. 仅成功分支写入缓存：降级结果永不入缓存
                if (cacheSpec != null) {
                    aiResultCache.put(cacheSpec.key(), vo, cacheSpec.ttlSeconds());
                }
                return vo;
            }
            // 日志附 Prompt 版本号，便于按版本归因「改 Prompt 导致的质量波动」
            log.warn("[{}|v{}]模型输出不合法，切换降级：{}", label, AiPrompts.versionOf(system), resp.content());
            // 模型有返回但结构不合法：与「服务不可用」区分（resp.ok()=true 时 reason 为空，须显式给话术，否则降级不可观测）
            aiMetrics.recordDegrade(AiDegradeReason.OUTPUT_INVALID);
            return onDegrade.apply(AiConstants.AI_OUTPUT_INVALID_MESSAGE);
        }
        return onDegrade.apply(resp.reason());
    }

    /**
     * 结果缓存规格
     *
     * @param key        缓存 Key（由 {@link AiResultCache#buildKey} 组装）
     * @param ttlSeconds TTL（秒）
     * @param type       反序列化类型（供 RedisCache 以 TypeReference 还原泛型）
     * @param <V>        返回体类型
     */
    public record AiCacheSpec<V>(String key, long ttlSeconds, TypeReference<V> type) {
    }
}