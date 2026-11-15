package com.example.reservation.ai.dto;

/**
 * SSE 流式问答 · thinking 事件（回答开始前展示的处理步骤与耗时）
 *
 * <p>R14 交互优化：按真实执行流水线逐步发出，不伪造思维链。
 * begin 时 status=running、elapsedMs=null；结束时同一 id 再发一次 done/failed 并带该步真实耗时。
 * summary 事件 id 固定为 summary，label 为可选摘要文案（命中缓存/已降级等），elapsedMs 为「入口到首个回答」的总用时。
 *
 * @param id        步骤标识（scene/retrieve/generate/verify/cache/fallback/summary）
 * @param label     直接展示的步骤文案（用户视角、主动语态）；summary 时可为 null
 * @param status    步骤状态：running/done/failed
 * @param elapsedMs 该步骤真实耗时毫秒数（running 时为 null）
 */
public record AiStreamThinking(String id, String label, String status, Long elapsedMs) {
}
