package com.example.reservation.ai.dto;

/**
 * SSE 流式问答 · delta 事件（一个回答由多个片段顺序组成）
 *
 * @param text 文本片段（直接追加即完整答案）
 */
public record AiStreamDelta(String text) {
}
