package com.example.reservation.ai.config;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiDegradeReason;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.ai.support.AiResponseParser;
import com.example.reservation.ai.support.AiSseLineParser;
import com.example.reservation.ai.support.AiUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Agnes AI 客户端封装（R7，ai 包内低耦合，spec.md 6.1 接入规格）
 * 调用兼容 OpenAI v1 规范的 POST {base-url}/chat/completions：
 *  - 请求头：Authorization: Bearer {API_KEY}、Content-Type: application/json
 *  - 请求体：model（取 ai_config.ai_model，缺省回退 application.yml ai.model）、messages（system/user）、
 *    temperature、response_format={"type":"json_object"}（结构化输出）
 * 调用保护（AGENTS 4.4 / spec.md 6.1）：
 *  - 超时控制：读 AiProperties.timeoutSeconds（默认 60s，连接超时固定 10s），超时/报错自动降级
 *  - 限流保护：RateLimiter 双层固定窗口（N2 修复：用户维度 rpm-limit/min + 全站维度 global-rpm-limit/min），
 *    限流时直接返回降级，不触发对外调用
 *  - 429 不做请求内重试（R10 修正）：实测该账号硬上限为 10 次/分钟，官方处置建议是「等待 1 分钟限制窗口恢复」，
 *    2~4s 级退避无法跨越 60s 窗口，重试只会把 1 次用户操作放大成 3 次上游调用、更快耗尽额度；
 *    改由本地限流器把上游调用速率压在账号额度之内，429 直接降级，不放大额度消耗
 *  - 密钥缺失：apiKey 为空直接降级，前端零接触密钥
 *
 * @author reservation-team
 */
@Slf4j
@Component
public class AgnesClient {

    /** HTTP 429：上游限流（Agnes 官方错误码表：超过 RPM 或订阅配额） */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    @Resource
    private AiProperties aiProperties;

    /** AI 配置读取（生效模型取 ai_config.ai_model 优先，与 application.yml ai.model 构成「DB 可覆盖」口径） */
    @Resource
    private AiConfigService aiConfigService;

    /** 主 ObjectMapper（Spring 统一配置实例，避免各组件自行 new 造成配置分叉） */
    @Resource
    private ObjectMapper objectMapper;

    /** 运行指标收集（本次改动：把失败类降级按原因分桶，供 /api/stats/ai/metrics 观测） */
    @Resource
    private AiMetrics aiMetrics;

    /** 模型响应体解析（一次 readTree 同时取内容、usage 与 finish_reason，供 token 用量与截断观测） */
    @Resource
    private AiResponseParser aiResponseParser;

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
            aiMetrics.recordRateLimitRejected();
            return AgnesResponse.degraded(AiConstants.AI_RATE_LIMITED_MESSAGE);
        }
        // 2. 密钥缺失保护（环境变量 AGNES_API_KEY 未配置）
        if (StrUtil.isBlank(aiProperties.getApiKey())) {
            log.warn("AGNES_API_KEY 未配置，切换降级");
            aiMetrics.recordDegrade(AiDegradeReason.NO_KEY);
            return AgnesResponse.degraded(AiConstants.AI_NO_KEY_MESSAGE);
        }

        // 耗时统计起点：仅统计真实发往上游的调用（限流/密钥缺失在此时已返回，不计入）
        long startNs = System.nanoTime();
        try {
            // 3. 真实调用（超时/连接异常/服务异常统一捕获 → 降级，不阻断业务；429 不重试，避免放大额度消耗）
            RestClient client = buildClient();
            ObjectNode body = buildRequestBody(system, user, jsonMode);
            String responseBody = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            // 一次 readTree 取全：内容 + usage（token 用量）+ finish_reason（结束原因）
            AiResponseParser.ParsedResponse parsed = aiResponseParser.parse(responseBody);
            // 拿到 HTTP 200 即记录用量与结束原因——内容为空/被 max_tokens 截断/被内容策略拦截，同样需要可观测
            aiMetrics.recordUsage(parsed.usage());
            aiMetrics.recordFinishReason(parsed.usage().finishReason());
            String content = parsed.content();
            if (StrUtil.isBlank(content)) {
                log.warn("Agnes 返回内容为空，切换降级");
                aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
                return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
            }
            return AgnesResponse.ok(content, parsed.usage());
        } catch (HttpClientErrorException e) {
            // 4xx：429 为上游限流，其余（401/402/404 等）重试同样无意义，统一直接降级
            log.warn("Agnes 调用服务异常：{}", e.getMessage());
            if (e.getStatusCode().value() == HTTP_TOO_MANY_REQUESTS) {
                // 429 单独计数（同时计入 RATE_LIMITED 桶），便于区分「撞上游限」与「服务故障」
                aiMetrics.recordUpstream429();
                return AgnesResponse.degraded(AiConstants.AI_RATE_LIMITED_MESSAGE);
            }
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (ResourceAccessException e) {
            log.warn("Agnes 调用超时或网络异常：{}", e.getMessage());
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (RestClientException e) {
            // 非 429 的 4xx / 5xx / 其他 HTTP 异常
            log.warn("Agnes 调用服务异常：{}", e.getMessage());
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("Agnes 调用未知异常：", e);
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } finally {
            // 无论成功/失败，真实发起的上游调用都要计入次数与耗时
            aiMetrics.recordModelCall((System.nanoTime() - startNs) / 1_000_000);
        }
    }

    /**
     * 流式对话补全（R12，POST /api/ai/chat?stream=true 专用）
     *
     * 与 chat 同口径：双层限流、密钥保护、指标计数、429/超时/异常直接降级；
     * 区别：请求体带 stream=true 且不带 response_format，上游 SSE 逐帧到达，
     * 内容片段经 onDelta 实时回调（打字机效果），onReady 在拿到 HTTP 200、
     * 即将开始读流时回调一次（供调用方在「成功/降级」确定后再发 meta 事件）。
     *
     * @param onReady       上游 200 建链成功回调（失败/限流/无密钥时不回调）
     * @param deltaConsumer 内容片段回调（按上游 chunk 粒度，可能多次）
     * @return ok=true 携带 finishReason；ok=false 携带降级原因（调用方改推本地 FAQ）
     */
    public StreamChatHandle chatStream(Long userId, String system, String user,
                                       Runnable onReady, Consumer<String> deltaConsumer) {
        // 1. 限流保护（与 chat() 同口径，触发即降级、不发对外请求）
        if (!limiter().tryAcquire(userId)) {
            log.warn("Agnes 流式调用触发限流（rpm-limit={}，global-rpm-limit={}，userId={}），切换降级",
                    aiProperties.getRpmLimit(), aiProperties.getGlobalRpmLimit(), userId);
            aiMetrics.recordRateLimitRejected();
            return StreamChatHandle.degraded(AiConstants.AI_RATE_LIMITED_MESSAGE);
        }
        // 2. 密钥缺失保护
        if (StrUtil.isBlank(aiProperties.getApiKey())) {
            log.warn("AGNES_API_KEY 未配置，流式切换降级");
            aiMetrics.recordDegrade(AiDegradeReason.NO_KEY);
            return StreamChatHandle.degraded(AiConstants.AI_NO_KEY_MESSAGE);
        }

        long startNs = System.nanoTime();
        try {
            RestClient client = buildClient();
            ObjectNode body = buildStreamRequestBody(system, user);
            // exchange 不应用 retrieve 的默认状态处理，4xx/5xx 需在此自行判定（SSE 不能再走全局异常 JSON）
            return client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.isError()) {
                            log.warn("Agnes 流式调用 HTTP 异常：{}", status.value());
                            if (status.value() == HTTP_TOO_MANY_REQUESTS) {
                                aiMetrics.recordUpstream429();
                                return StreamChatHandle.degraded(AiConstants.AI_RATE_LIMITED_MESSAGE);
                            }
                            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
                            return StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                        }
                        onReady.run();
                        DispatchResult dispatched;
                        try (InputStream upstream = response.getBody()) {
                            dispatched = dispatchStream(upstream, deltaConsumer);
                        } catch (IOException e) {
                            log.warn("Agnes 流式读取中断：{}", e.getMessage());
                            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
                            return StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                        }
                        if (StrUtil.isBlank(dispatched.content())) {
                            log.warn("Agnes 流式返回内容为空，切换降级");
                            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
                            return StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                        }
                        // 流式分片不回传 usage（未请求 stream_options.include_usage），token 计数如实保持 0；结束原因照常分桶
                        aiMetrics.recordFinishReason(dispatched.finishReason());
                        return StreamChatHandle.ok(dispatched.finishReason());
                    });
        } catch (UncheckedIOException e) {
            // 仅可能来自 onReady / deltaConsumer（上游自身读取 IOException 已在 exchange 内转降级）：客户端中断，静默终止
            log.warn("Agnes 流式因客户端中断而终止：{}", e.getMessage());
            return StreamChatHandle.aborted();
        } catch (ResourceAccessException e) {
            log.warn("Agnes 流式调用超时或网络异常：{}", e.getMessage());
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (RestClientException e) {
            log.warn("Agnes 流式调用服务异常：{}", e.getMessage());
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } catch (Exception e) {
            log.error("Agnes 流式调用未知异常：", e);
            aiMetrics.recordDegrade(AiDegradeReason.SERVICE_ERROR);
            return StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
        } finally {
            aiMetrics.recordModelCall((System.nanoTime() - startNs) / 1_000_000);
        }
    }

    /**
     * 构建流式请求体：复用 buildRequestBody（jsonMode=false → 不带 response_format），追加 stream=true
     */
    private ObjectNode buildStreamRequestBody(String system, String user) {
        ObjectNode root = buildRequestBody(system, user, false);
        root.put("stream", true);
        return root;
    }

    /**
     * 分发上游 SSE 字节流：逐帧解析 → 提取 choices[0].delta.content 回调；记录最后一个非空 finish_reason（上游 SSE 协议下该值仅出现一次，与首个等价）。
     * 脏帧（非 JSON/结构缺失）跳过不中断整条流；遇到 [DONE] 即结束（其后内容忽略）。
     * 包可见以便纯单测（不发起真实 HTTP）。
     *
     * 关键：readLine 对 SSE 空行（帧边界）返回空串 ""，parser 据此完成派发并可能返回帧；
     * 因此必须先判 DONE、再判 isEmpty（非空 data 行返回 empty 才继续），顺序不可颠倒。
     */
    DispatchResult dispatchStream(InputStream body, Consumer<String> deltaConsumer) throws IOException {
        Objects.requireNonNull(body, "upstream stream");
        Objects.requireNonNull(deltaConsumer, "deltaConsumer");
        AiSseLineParser parser = new AiSseLineParser();
        StringBuilder full = new StringBuilder();
        String finishReason = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = reader.readLine()) != null) {
                Optional<AiSseLineParser.Frame> frameOpt = parser.feedLine(raw);
                if (frameOpt.isPresent() && frameOpt.get().kind() == AiSseLineParser.Frame.Kind.DONE) {
                    break;
                }
                if (frameOpt.isEmpty()) {
                    // 非空 data/event 行：帧未结束，继续读；空行但无 data：parser 已返回 empty
                    continue;
                }
                AiSseLineParser.Frame frame = frameOpt.get();
                String piece = null;
                try {
                    JsonNode chunk = objectMapper.readTree(frame.payload());
                    JsonNode choices = chunk.path("choices");
                    if (choices.isArray() && !choices.isEmpty()) {
                        JsonNode first = choices.get(0);
                        JsonNode contentNode = first.path("delta").path("content");
                        if (contentNode.isTextual() && !contentNode.asText().isEmpty()) {
                            piece = contentNode.asText();
                        }
                        JsonNode finishNode = first.path("finish_reason");
                        // 记录最后一个非空 finish_reason（上游 SSE 协议下该值仅出现一次，与首个等价）
                        if (finishNode.isTextual() && !finishNode.asText().isEmpty()) {
                            finishReason = finishNode.asText();
                        }
                    }
                } catch (Exception parseEx) {
                    // 脏帧不中断整条流（与 AiResponseParser 的容错原则一致）；注意：deltaConsumer 不在此 try 内，
                    // 客户端中断（UncheckedIOException）必须原样上抛，由 chatStream 识别为 aborted
                    log.warn("Agnes 流式脏帧已跳过：{}", parseEx.getMessage());
                }
                // 回调置于脏帧 try 之外：消费者异常（如 SSE 写入失败→客户端断连）不得被吞掉
                if (piece != null) {
                    full.append(piece);
                    deltaConsumer.accept(piece);
                }
            }
        }
        return new DispatchResult(full.toString(), finishReason);
    }

    /**
     * 构建 Chat Completions 请求体（OpenAI v1 规范）
     */
    private ObjectNode buildRequestBody(String system, String user, boolean jsonMode) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", aiConfigService.getEffectiveModel());
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
     *
     * @param ok      是否拿到模型内容
     * @param content 模型输出内容（降级时为 null）
     * @param reason  降级原因话术（成功时为 null）
     * @param usage   本次调用的 token 用量与结束原因（未走到解析链路时为 {@link AiUsage#EMPTY}）
     */
    public record AgnesResponse(boolean ok, String content, String reason, AiUsage usage) {

        /** 成功结果（携带上游返回的用量与结束原因） */
        public static AgnesResponse ok(String content, AiUsage usage) {
            return new AgnesResponse(true, content, null, usage == null ? AiUsage.EMPTY : usage);
        }

        /** 降级结果（未产生用量信息） */
        public static AgnesResponse degraded(String reason) {
            return new AgnesResponse(false, null, reason, AiUsage.EMPTY);
        }
    }

    /**
     * 流式调用结果载体：ok=true 携带结束原因；ok=false 携带降级原因（无内容产出）
     */
    public record StreamChatHandle(boolean ok, String reason, String finishReason) {

        public static StreamChatHandle ok(String finishReason) {
            return new StreamChatHandle(true, null, finishReason);
        }

        public static StreamChatHandle degraded(String reason) {
            return new StreamChatHandle(false, reason, null);
        }

        /**
         * 客户端已中断（SSE 写入失败）：非上游故障，不记降级指标；调用方应静默结束、不再发任何事件
         */
        public static StreamChatHandle aborted() {
            return new StreamChatHandle(false, null, null);
        }
    }

    /**
     * 上游流分发结果（内部使用）：累积全文 + 最后一个非空 finish_reason（上游 SSE 协议下该值仅出现一次，与首个等价）
     */
    public record DispatchResult(String content, String finishReason) {
    }
}
