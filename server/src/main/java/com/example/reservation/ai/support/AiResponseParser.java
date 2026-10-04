package com.example.reservation.ai.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型响应体解析（一次 readTree 同时取出内容、用量与结束原因）
 *
 * 背景：OpenAI v1 兼容响应体形如
 * <pre>
 * {"choices":[{"message":{"content":"..."},"finish_reason":"stop"}],
 *  "usage":{"prompt_tokens":123,"completion_tokens":45,"total_tokens":168}}
 * </pre>
 * 此前仅取 content，导致「一次推荐花了多少 token」「降级是否由 max_tokens 截断引起」无法回答。
 * 本类把 usage 与 finish_reason 一并解析出来供指标采集；上游未返回时按安全默认处理。
 *
 * 容错原则（与降级链一致）：任何畸形/非 JSON 响应体一律返回「空内容 + 空用量」，绝不抛异常。
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AiResponseParser {

    /** 主 ObjectMapper（Spring 统一配置实例，避免各组件自行 new 造成配置分叉） */
    @Resource
    private ObjectMapper objectMapper;

    /**
     * 解析模型响应体
     *
     * @param responseBody 上游返回的原始 JSON 文本
     * @return 解析结果；无法解析时 content 为 null、usage 为 {@link AiUsage#EMPTY}
     */
    public ParsedResponse parse(String responseBody) {
        if (StrUtil.isBlank(responseBody)) {
            return ParsedResponse.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return ParsedResponse.empty();
            }
            JsonNode first = choices.get(0);
            JsonNode contentNode = first.path("message").path("content");
            String content = contentNode.isTextual() ? contentNode.asText() : null;
            JsonNode finishNode = first.path("finish_reason");
            String finishReason = finishNode.isTextual() ? finishNode.asText() : null;
            return new ParsedResponse(content, buildUsage(root.path("usage"), finishReason));
        } catch (Exception e) {
            log.warn("Agnes 响应解析失败：{}", e.getMessage());
            return ParsedResponse.empty();
        }
    }

    /**
     * 组装用量：usage 缺失/非对象时各计数取 0，total 缺失时按「输入+输出」兜底
     */
    private AiUsage buildUsage(JsonNode usageNode, String finishReason) {
        long promptTokens = 0L;
        long completionTokens = 0L;
        long totalTokens = 0L;
        if (usageNode != null && usageNode.isObject()) {
            promptTokens = usageNode.path("prompt_tokens").asLong(0L);
            completionTokens = usageNode.path("completion_tokens").asLong(0L);
            totalTokens = usageNode.path("total_tokens").asLong(0L);
            if (totalTokens <= 0L) {
                totalTokens = promptTokens + completionTokens;
            }
        }
        return new AiUsage(promptTokens, completionTokens, totalTokens, finishReason);
    }

    /**
     * 解析结果载体
     *
     * @param content 模型输出内容（无法解析为 null）
     * @param usage   用量与结束原因（无法解析为 {@link AiUsage#EMPTY}）
     */
    public record ParsedResponse(String content, AiUsage usage) {

        /** 空结果（响应体为空/畸形/无 choices） */
        public static ParsedResponse empty() {
            return new ParsedResponse(null, AiUsage.EMPTY);
        }
    }
}