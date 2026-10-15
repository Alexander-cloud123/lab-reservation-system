package com.example.reservation.ai.dto;

/**
 * SSE 流式问答 · done 事件（正常收尾）
 *
 * @param finishReason stop / length / content_filter / unknown（模型）；fallback（本地降级）；disabled（AI 关闭）
 */
public record AiStreamDone(String finishReason) {
}
