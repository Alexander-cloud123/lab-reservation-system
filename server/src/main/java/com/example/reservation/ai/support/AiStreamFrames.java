package com.example.reservation.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * 上游 SSE data 帧解析纯函数（R13-4）。
 *
 * <p>R13-4 起 SSE 行分帧由 WebClient 的 {@code ServerSentEventHttpMessageReader} 完成，
 * 本类只处理「单个 data payload → OpenAI chunk 字段提取」，无 IO、无状态、可独立单测。
 * 帧协议（OpenAI v1 兼容）：
 * <ul>
 *   <li>终止哨兵帧 {@code data: [DONE]}：非 JSON，由 {@link #isDone(String)} 单独识别；</li>
 *   <li>数据帧 {@code data: {"choices":[{"delta":{"content":"..."},"finish_reason":"stop"}]}}。</li>
 * </ul>
 */
public final class AiStreamFrames {

    private AiStreamFrames() {
    }

    /** 上游终止哨兵（允许两侧空白，防御网关改写） */
    public static boolean isDone(String payload) {
        return payload != null && "[DONE]".equals(payload.trim());
    }

    /**
     * 提取首 choice 的增量内容与结束原因。
     *
     * <p>脏帧（非 JSON / 无 choices）返回 empty，由调用方记日志后跳过，不中断整条流；
     * 字段缺失时 {@link ChunkDelta} 对应字段为 null（与 R12 dispatchStream 容错口径一致）。
     */
    public static Optional<ChunkDelta> tryExtract(String payload, ObjectMapper mapper) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        final JsonNode chunk;
        try {
            chunk = mapper.readTree(payload);
        } catch (Exception parseEx) {
            // 非 JSON 脏帧：不中断整条流
            return Optional.empty();
        }
        JsonNode choices = chunk.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = choices.get(0);
        String content = null;
        JsonNode contentNode = first.path("delta").path("content");
        if (contentNode.isTextual() && !contentNode.asText().isEmpty()) {
            content = contentNode.asText();
        }
        String finishReason = null;
        JsonNode finishNode = first.path("finish_reason");
        // 记录非空 finish_reason（上游 SSE 协议下该值仅出现一次，与「最后一个非空」等价）
        if (finishNode.isTextual() && !finishNode.asText().isEmpty()) {
            finishReason = finishNode.asText();
        }
        return Optional.of(new ChunkDelta(content, finishReason));
    }

    /**
     * 单个 chunk 的提取结果：content 为增量文本（无增量时 null），finishReason 为结束原因（缺失时 null）
     */
    public record ChunkDelta(String content, String finishReason) {
    }
}
