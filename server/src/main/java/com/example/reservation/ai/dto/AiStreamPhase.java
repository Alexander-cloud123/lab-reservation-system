package com.example.reservation.ai.dto;

/**
 * 结构化流式 SSE · phase 进度事件（R13-5）
 *
 * <p>事件序列：{@code meta → phase*（0 或 1 个）→ result → done}；
 * 缓存命中 / 无候选短路时不发 phase，直接 result。
 *
 * @param phase 阶段机器码（recommend / parse / compliance，供前端识别与测试断言）
 * @param text  进度展示文案（直接面向用户，与后端 AiConstants 中 PHASE_TEXT_* 一致）
 */
public record AiStreamPhase(String phase, String text) {
}
