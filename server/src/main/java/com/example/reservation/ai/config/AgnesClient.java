package com.example.reservation.ai.config;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Agnes AI 客户端封装（R7，ai 包内低耦合，spec.md 6.1 接入规格）
 * 调用兼容 OpenAI v1 规范的 POST {base-url}/chat/completions：
 *  - 请求头：Authorization: Bearer {API_KEY}、Content-Type: application/json
 *  - 请求体：model、messages（system/user）、temperature、response_format={"type":"json_object"}（结构化输出）
 * 调用保护（AGENTS 4.4 / spec.md 6.1）：
 *  - 超时控制：读 AiProperties.timeoutSeconds（默认 60s，连接超时固定 10s），超时/报错自动降级
 *  - 限流保护：RateLimiter 双层固定窗口（N2 修复：用户维度 rpm-limit/min + 全站维度 global-rpm-limit/min），
 *    限流时直接返回降级，不触发对外调用
 *  - 429 退避重试（R10 修复）：免费档文本模型有效 RPM 仅约 10/min，瞬时连发会撞限，
 *    按 Agnes 官方错误码指引「降低并发、等待限制窗口恢复」重试（优先 Retry-After，缺省退避 2s → 4s，最多 2 次），
 *    而非立刻降级出本地规则话术；重试仍失败或超出耗时预算才降级
 *  - 密钥缺失：apiKey 为空直接降级，前端零接触密钥
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AgnesClient {

    /** HTTP 429：上游限流（Agnes 官方错误码表：超过 RPM 或订阅配额） */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** 429 退避序列（毫秒）：长度即最大重试次数（2 次 → 最多 3 次尝试），官方指引「等待限制窗口恢复」 */
    private static final long[] RETRY_BACKOFF_MS = {2_000L, 4_000L};

    /** 单次重试等待上限（毫秒）：上游若给出过长的 Retry-After，按此截断 */
    private static final long MAX_RETRY_WAIT_MS = 5_000L;

    /** 重试总耗时预算（毫秒）：含重试的总耗时超过此值即直接降级，保证落在前端 AI 接口 60s 超时内 */
    private static final long RETRY_BUDGET_MS = 15_000L;

    @Resource
    private AiProperties aiProperties;

    /** 主 ObjectMapper（Spring 统一配置实例，避免各组件自行 new 造成配置分叉） */
    @Resource
    private ObjectMapper objectMapper;

    /** 限流器（N2：懒初始化，参数来自 AiProperties；无 Spring 依赖，纯 JDK） */
    private volatile RateLimiter rateLimiter;

    /**
     * 调用 Agnes 大模型对话补全
     *
     * @param userId   当前登录用户 ID（M7：按用户限流维度；null 时退化为共享窗口）
     * @param system   System 角色消息（Prompt 限定场景与结构化输出，AGENTS 4.4 第 4 条）
     * @param user      User 角色消息（实际业务内容）
     * @param jsonMode 是否要求 JSON 结构化输出（response_format={"type":"json_object"}）
     * @return 调用结果：ok=true 携带模型输出 content；ok=false 携带降级原因 reason（调用方据此切换本地规则模拟）
     */
    public AgnesResponse chat(Long userId, String system, String user, boolean jsonMode) {
        // 1. 限流保护（N2：双层——用户维度 rpm-limit/min + 全站维度 global-rpm-limit/min；触发返回降级，不触发对外调用）
        if (!limiter().tryAcquire(userId)) {
            log.warn("Agnes 调用触发限流（rpm-limit={}，global-rpm-limit={}，userId={}），切换降级",
                    aiProperties.getRpmLimit(), aiProperties.getGlobalRpmLimit(), userId);
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
            long startAt = System.currentTimeMillis();
            // 3.1 429 专项：同上限流窗口被占满时按官方指引等待后重发，attempt 即「已重试次数」
            for (int attempt = 0; ; attempt++) {
                try {
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
                } catch (HttpClientErrorException e) {
                    // 4xx：429 为上游限流，其余（401/402/404 等）重试无意义，直接降级
                    if (!canRetryOn429(e, attempt, startAt)) {
                        log.warn("Agnes 调用服务异常：{}", e.getMessage());
                        // 429 用限流话术，便于前端区分「撞限」与「服务故障」
                        return AgnesResponse.degraded(e.getStatusCode().value() == HTTP_TOO_MANY_REQUESTS
                                ? AiConstants.AI_RATE_LIMITED_MESSAGE
                                : AiConstants.AI_SERVICE_ERROR_MESSAGE);
                    }
                    long waitMs = retryWaitMs(e, attempt);
                    log.warn("Agnes 触发 429（第 {} 次重试，等待 {}ms 后重发）：{}", attempt + 1, waitMs, e.getMessage());
                    if (!sleepQuietly(waitMs)) {
                        // 线程被中断（应用停止/请求取消）：不再重试，按服务异常降级
                        return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                    }
                } catch (ResourceAccessException e) {
                    // 连接超时/读取超时/网络不可达：重试会加倍耗时，直接降级
                    log.warn("Agnes 调用超时或网络异常：{}", e.getMessage());
                    return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                }
            }
        } catch (RestClientException e) {
            // 非 429 的 4xx / 5xx / 其他 HTTP 异常
            log.warn("Agnes 调用服务异常：{}", e.getMessage());
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("Agnes 调用未知异常：", e);
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        }
    }

    /**
     * 是否对本次失败再重试：仅限 429，且未超重试次数上限、总耗时仍在预算内
     * （预算用于保证「等待 + 重试 + 模型响应」总时长不超出前端 AI 接口 60s 超时）
     *
     * @param e       4xx 异常（携带上游状态码与响应头）
     * @param attempt 已重试次数（首次失败为 0）
     * @param startAt 本轮首次尝试的起始时刻
     */
    private boolean canRetryOn429(HttpClientErrorException e, int attempt, long startAt) {
        return e.getStatusCode().value() == HTTP_TOO_MANY_REQUESTS
                && attempt < RETRY_BACKOFF_MS.length
                && System.currentTimeMillis() - startAt < RETRY_BUDGET_MS;
    }

    /**
     * 计算本次重试等待时长：优先上游 Retry-After（秒），缺省按退避序列 2s → 4s，统一截断到上限
     */
    private long retryWaitMs(HttpClientErrorException e, int attempt) {
        long waitMs = RETRY_BACKOFF_MS[attempt];
        HttpHeaders headers = e.getResponseHeaders();
        String retryAfter = headers == null ? null : headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (StrUtil.isNotBlank(retryAfter)) {
            try {
                waitMs = Long.parseLong(retryAfter.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // Retry-After 也可能是 HTTP 日期格式：无法解析时沿用退避序列
                log.warn("Retry-After 无法解析，改用退避值 {}ms：{}", waitMs, retryAfter);
            }
        }
        return Math.max(0, Math.min(waitMs, MAX_RETRY_WAIT_MS));
    }

    /**
     * 等待重试间隔
     *
     * @return true=已等待完毕；false=等待期间被中断（已恢复中断标记，调用方应停止重试）
     */
    private boolean sleepQuietly(long waitMs) {
        try {
            Thread.sleep(waitMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 构建 Chat Completions 请求体（OpenAI v1 规范）
     */
    private ObjectNode buildRequestBody(String system, String user, boolean jsonMode) {
        ObjectNode root = objectMapper.createObjectNode();
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
            JsonNode root = objectMapper.readTree(responseBody);
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
     * 构建 RestClient（连接超时固定 10s；读取超时 = timeoutSeconds，默认 60s）
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
     * 限流器懒初始化（N2：构造参数来自 AiProperties——每用户 rpmLimit/min、全站 globalRpmLimit/min、窗口 60s；
     * global-rpm-limit 未配置（≤0）时默认取 rpmLimit × 5；保持按用户口径不变）。
     */
    private RateLimiter limiter() {
        RateLimiter r = rateLimiter;
        if (r == null) {
            synchronized (this) {
                r = rateLimiter;
                if (r == null) {
                    int perUser = Math.max(aiProperties.getRpmLimit(), 1);
                    int global = aiProperties.getGlobalRpmLimit() > 0
                            ? aiProperties.getGlobalRpmLimit()
                            : perUser * 5;
                    r = new RateLimiter(perUser, global, AiConstants.RATE_LIMIT_WINDOW_MS);
                    rateLimiter = r;
                }
            }
        }
        return r;
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
