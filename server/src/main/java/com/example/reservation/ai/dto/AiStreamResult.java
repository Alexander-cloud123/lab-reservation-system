package com.example.reservation.ai.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 结构化流式 SSE · result 事件载荷（R13-5）。
 *
 * <p>线上必须与非流式接口的 {@code data} 同构（同一个 VO），前端流式/非流式两路共用一套落地逻辑；
 * {@link JsonValue} 使本包装在 Jackson 序列化时直接展开为内部 VO，
 * 即 {@code event:result} 的 data 就是 VO 本身，而非 {"data":{...}}。
 *
 * @param data 结构化结果 VO（AiRecommendVO / AiParseVO / AiComplianceVO）
 */
public record AiStreamResult<T>(@JsonValue T data) {

    public static <T> AiStreamResult<T> of(T data) {
        return new AiStreamResult<>(data);
    }
}
