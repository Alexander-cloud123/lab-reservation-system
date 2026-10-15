package com.example.reservation.ai.dto;

/**
 * SSE 流式问答 · meta 事件（每个流仅 1 个，先于一切 delta）
 *
 * @param enabled  AI 是否启用（false 时前端不展示答案区）
 * @param degraded 是否为本地规则降级
 * @param message  降级原因文案（非降级为 null）
 */
public record AiStreamMeta(boolean enabled, boolean degraded, String message) {
}
