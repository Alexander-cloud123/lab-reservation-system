package com.example.reservation.ai.dto;

/**
 * SSE 流式问答 · reasoning 事件（模型真实推理片段透传）
 *
 * <p>仅当上游 delta 带 reasoning_content（或兼容字段 reasoning）时发出，逐帧追加；
 * 与 delta（回答正文）互不混入。前端按纯文本展示并标注「AI 生成，仅供参考」。
 *
 * @param text 推理片段
 */
public record AiStreamReasoning(String text) {
}
