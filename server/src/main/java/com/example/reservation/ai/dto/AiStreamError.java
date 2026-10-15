package com.example.reservation.ai.dto;

/**
 * SSE 流式问答 · error 事件（流已建立后的致命错误，如线程池拒绝）
 *
 * @param message 错误文案
 */
public record AiStreamError(String message) {
}
