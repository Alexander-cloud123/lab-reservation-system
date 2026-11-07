package com.example.reservation.ai.dto;

/**
 * 流式问答全量替换事件：上游已输出部分内容后失败，用完整本地降级答案替换半截文本。
 *
 * @param text    完整替换文本，不是需要追加的 delta
 * @param message 降级原因提示
 */
public record AiStreamReset(String text, String message) {
}
