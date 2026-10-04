package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * AI 调用模板（收敛 4 个 AI 接口重复的「调用 → 解析 → 降级」样板）
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
 *  - OUTPUT_INVALID 桶（模型有返回但结构不合法）在此统一计数，与 AgnesClient 记录的失败类桶（限流/无密钥/服务异常）互不重复。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AiInvoker {

    @Resource
    private AgnesClient agnesClient;

    /** 运行指标收集（本类只负责 OUTPUT_INVALID 桶，失败类桶由 AgnesClient 负责） */
    @Resource
    private AiMetrics aiMetrics;

    /**
     * 执行一次「AI 调用 + 解析 + 降级」
     *
     * @param label     接口中文名（仅用于日志定位）
     * @param userId    当前登录用户 ID（限流按用户维度隔离）
     * @param system    System Prompt
     * @param user      User 消息
     * @param parser    模型内容 → 解析结果（失败返回 null）
     * @param onSuccess 解析结果 → 成功返回体
     * @param onDegrade 降级原因 → 降级返回体（本地规则兜底 + message 填充）
     * @param <R>       解析结果类型
     * @param <V>       返回体类型
     * @return 成功返回体或降级返回体
     */
    public <R, V> V invoke(String label, Long userId, String system, String user,
                           Function<String, R> parser,
                           Function<R, V> onSuccess,
                           Function<String, V> onDegrade) {
        // 调用结果 ok=false 时 reason 为失败类降级原因（限流/密钥缺失/服务异常）
        AgnesClient.AgnesResponse resp = agnesClient.chat(userId, system, user, true);
        if (resp.ok()) {
            R parsed = parser.apply(resp.content());
            if (parsed != null) {
                return onSuccess.apply(parsed);
            }
            log.warn("{}模型输出不合法，切换降级：{}", label, resp.content());
            // 模型有返回但结构不合法：与「服务不可用」区分（resp.ok()=true 时 reason 为空，须显式给话术，否则降级不可观测）
            aiMetrics.recordDegrade(AiDegradeReason.OUTPUT_INVALID);
            return onDegrade.apply(AiConstants.AI_OUTPUT_INVALID_MESSAGE);
        }
        return onDegrade.apply(resp.reason());
    }
}