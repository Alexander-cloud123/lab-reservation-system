package com.example.reservation.ai.config;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agnes AI 客户端封装（R7，ai 包内低耦合，spec.md 6.1 接入规格）
 * 调用兼容 OpenAI v1 规范的 POST {base-url}/chat/completions：
 *  - 请求头：Authorization: Bearer {API_KEY}、Content-Type: application/json
 *  - 请求体：model、messages（system/user）、temperature、response_format={"type":"json_object"}（结构化输出）
 * 调用保护（AGENTS 4.4 / spec.md 6.1）：
 *  - 超时控制：读 AiProperties.timeoutSeconds（默认 60s，连接超时固定 10s），超时/报错自动降级
 *  - 限流保护：固定窗口 1 分钟 RPM 计数（rpmLimit），触发限流返回降级
 *  - 密钥缺失：apiKey 为空直接降级，前端零接触密钥
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AgnesClient {

    /** JSON 序列化/解析（Spring Boot 自带 Jackson） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AiProperties aiProperties;

    /** 限流计数：当前窗口内调用次数 */
    private final AtomicInteger windowCount = new AtomicInteger(0);

    /** 限流窗口开始时间戳（毫秒） */
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    /**
     * 调用 Agnes 大模型对话补全
     *
     * @param system   System 角色消息（Prompt 限定场景与结构化输出，AGENTS 4.4 第 4 条）
     * @param user      User 角色消息（实际业务内容）
     * @param jsonMode 是否要求 JSON 结构化输出（response_format={"type":"json_object"}）
     * @return 调用结果：ok=true 携带模型输出 content；ok=false 携带降级原因 reason（调用方据此切换本地规则模拟）
     */
    public AgnesResponse chat(String system, String user, boolean jsonMode) {
        // 1. 限流保护（任何调用均计数，RPM≈20；触发返回降级）
        if (!tryAcquire()) {
            log.warn("Agnes 调用触发限流（rpm-limit={}），切换降级", aiProperties.getRpmLimit());
            return AgnesResponse.degraded(AiConstants.AI_RATE_LIMITED_MESSAGE);
        }
        // 2. 密钥缺失保护（环境变量 AGNES_API_KEY 未配置）
        if (StrUtil.isBlank(aiProperties.getApiKey())) {
            log.warn("AGNES_API_KEY 未配置，切换降级");
            return AgnesResponse.degraded(AiConstants.AI_NO_KEY_MESSAGE);
        }

        try {
            // 3. 真实调用（超时/连接异常/服务异常统一捕获 → 降级，不阻断业务）
            RestClient client = buildClient();
            ObjectNode body = buildRequestBody(system, user, jsonMode);
            String responseBody = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            String content = extractContent(responseBody);
            if (StrUtil.isBlank(content)) {
                log.warn("Agnes 返回内容为空，切换降级");
                return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
            }
            return AgnesResponse.ok(content);
        } catch (ResourceAccessException e) {
            // 连接超时/读取超时/网络不可达
            log.warn("Agnes 调用超时或网络异常：{}", e.getMessage());
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (RestClientException e) {
            // 4xx（含限流 429 / 额度异常）/ 5xx / 其他 HTTP 异常
            log.warn("Agnes 调用服务异常：{}", e.getMessage());
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("Agnes 调用未知异常：", e);
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        }
    }

    /**
     * 构建 Chat Completions 请求体（OpenAI v1 规范）
     */
    private ObjectNode buildRequestBody(String system, String user, boolean jsonMode) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", aiProperties.getModel());
        ArrayNode messages = root.putArray("messages");
        if (StrUtil.isNotBlank(system)) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", system);
        }
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", user);
        root.put("temperature", 0.3);
        int maxTokens = aiProperties.getMaxTokens();
        if (maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }
        if (jsonMode) {
            ObjectNode format = root.putObject("response_format");
            format.put("type", "json_object");
        }
        return root;
    }

    /**
     * 解析响应：choices[0].message.content
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode content = choices.get(0).path("message").path("content");
                if (content.isTextual()) {
                    return content.asText();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Agnes 响应解析失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 构建 RestClient（连接/读取超时 = timeoutSeconds，默认 3s）
     * 每次调用构建成本可忽略；保持超时参数来自配置，便于演示时调整
     */
    private RestClient buildClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int readTimeoutMs = Math.max(aiProperties.getTimeoutSeconds(), 1) * 1000;
        factory.setConnectTimeout(10_000);          // 连接超时固定 10s，网络不可达时快速失败
        factory.setReadTimeout(readTimeoutMs);      // 读取超时 = timeout-seconds（实测模型响应可达数十秒）
        return RestClient.builder()
                .baseUrl(aiProperties.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + aiProperties.getApiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    /**
     * 限流窗口计数（固定窗口 1 分钟，窗口滑动后重置计数）
     *
     * @return true=允许调用；false=触发限流
     */
    private synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= AiConstants.RATE_LIMIT_WINDOW_MS) {
            windowStart.set(now);
            windowCount.set(0);
        }
        int limit = Math.max(aiProperties.getRpmLimit(), 1);
        return windowCount.incrementAndGet() <= limit;
    }

    /**
     * Agnes 调用结果载体：ok=true 表示拿到模型内容；ok=false 表示已降级（携带降级原因）
     */
    public record AgnesResponse(boolean ok, String content, String reason) {

        /** 成功结果 */
        public static AgnesResponse ok(String content) {
            return new AgnesResponse(true, content, null);
        }

        /** 降级结果 */
        public static AgnesResponse degraded(String reason) {
            return new AgnesResponse(false, null, reason);
        }
    }
}
