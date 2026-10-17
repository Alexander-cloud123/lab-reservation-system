package com.example.reservation.ai.config;

import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.ai.support.StreamExchange;
import com.example.reservation.ai.support.StreamTransport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgnesClient.chatStream 编排单测（R13-4）。
 *
 * <p>HTTP 传输用 {@link FakeStreamTransport} 模拟 {@code WebClientStreamTransport} 的桥接契约
 *（2xx 回调 onReady + 逐帧 onFrame；帧回调抛 UncheckedIOException → CANCELLED），
 * 覆盖终态映射（成功/429/5xx/传输异常/中断/空内容）与帧处理（[DONE]、脏帧、请求体 stream=true），
 * 不为 WebClient 本身引 MockWebServer/WireMock。
 */
class AgnesClientStreamOrchestrationTest {

    private AgnesClient client;
    private FakeStreamTransport transport;
    private AiConfigService aiConfigService;

    @BeforeEach
    void setUp() {
        client = new AgnesClient();
        AiProperties props = new AiProperties();
        props.setBaseUrl("https://agnes.test/v1");
        props.setApiKey("test-key");
        props.setTimeoutSeconds(60);
        aiConfigService = mock(AiConfigService.class);
        when(aiConfigService.getEffectiveModel()).thenReturn("agnes-3.0-flash");
        transport = new FakeStreamTransport();

        ReflectionTestUtils.setField(client, "aiProperties", props);
        ReflectionTestUtils.setField(client, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(client, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(client, "aiMetrics", new AiMetrics());
        ReflectionTestUtils.setField(client, "streamTransport", transport);
    }

    private static String chunk(String content, String finishReason) {
        String delta = content == null ? "{}" : "{\"content\":\"" + content + "\"}";
        String finish = finishReason == null ? "null" : "\"" + finishReason + "\"";
        return "{\"choices\":[{\"delta\":" + delta + ",\"finish_reason\":" + finish + "}]}";
    }

    @Test
    @DisplayName("正常流：ready 恰好一次、delta 按序、[DONE] 后帧忽略，返回 ok(stop)")
    void normalStream() {
        transport.frames = List.of(chunk("你好", null), chunk("，世界", null), chunk(null, "stop"),
                "[DONE]", chunk("AFTER_DONE", null));
        List<String> pieces = new ArrayList<>();

        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "你好", transport.readyRecorder, pieces::add);

        assertTrue(handle.ok());
        assertEquals("stop", handle.finishReason());
        assertNull(handle.reason());
        assertEquals(List.of("你好", "，世界"), pieces);
        assertEquals(1, transport.readyCount, "onReady 应只在 2xx 后回调一次");
        assertTrue(transport.called, "transport 必须被调用");
        // 请求体：stream=true、model 取配置服务、消息顺序 system→user
        assertEquals(true, transport.lastBody.path("stream").asBoolean());
        assertEquals("agnes-3.0-flash", transport.lastBody.path("model").asText());
        assertEquals("sys", transport.lastBody.path("messages").get(0).path("content").asText());
        assertEquals("你好", transport.lastBody.path("messages").get(1).path("content").asText());
    }

    @Test
    @DisplayName("脏帧（非 JSON/空 choices）跳过不中断；有效帧照常累积，缺失 finish_reason 时为 null")
    void dirtyFrameSkipped() {
        transport.frames = List.of("not-a-json", "{\"choices\":[]}", chunk("ok", null), "[DONE]");
        List<String> pieces = new ArrayList<>();

        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, pieces::add);

        assertTrue(handle.ok());
        assertEquals(List.of("ok"), pieces);
        assertNull(handle.finishReason());
    }

    @Test
    @DisplayName("全流无任何文本：返回服务异常降级")
    void emptyContentDegrades() {
        transport.frames = List.of("{\"choices\":[]}", chunk(null, null), "[DONE]");
        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, s -> {
                });
        assertFalse(handle.ok());
        assertEquals(AiConstants.AI_SERVICE_ERROR_MESSAGE, handle.reason());
    }

    @Test
    @DisplayName("上游 429：限流降级话术，不回调 onReady")
    void http429RateLimited() {
        transport.httpStatus = 429;
        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, s -> {
                });
        assertFalse(handle.ok());
        assertEquals(AiConstants.AI_RATE_LIMITED_MESSAGE, handle.reason());
        assertEquals(0, transport.readyCount);
    }

    @Test
    @DisplayName("上游 500：服务异常降级")
    void http500ServiceError() {
        transport.httpStatus = 500;
        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, s -> {
                });
        assertFalse(handle.ok());
        assertEquals(AiConstants.AI_SERVICE_ERROR_MESSAGE, handle.reason());
    }

    @Test
    @DisplayName("传输异常（超时/连接失败）：服务异常降级")
    void transportErrorDegrades() {
        transport.transportError = true;
        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, s -> {
                });
        assertFalse(handle.ok());
        assertEquals(AiConstants.AI_SERVICE_ERROR_MESSAGE, handle.reason());
    }

    @Test
    @DisplayName("delta 回调抛 UncheckedIOException（浏览器断开）：transport 取消上游，handle=aborted，已发内容不回滚")
    void consumerAbortReturnsAborted() {
        transport.frames = List.of(chunk("半截内容", null), chunk("不会到达", null));
        Consumer<String> failing = new Consumer<>() {
            private boolean first = true;

            @Override
            public void accept(String s) {
                if (first) {
                    first = false;
                    return;
                }
                throw new UncheckedIOException(new IOException("client gone"));
            }
        };

        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, failing);

        assertFalse(handle.ok());
        assertNull(handle.reason(), "中断不是降级：reason 必须为 null");
        assertNull(handle.finishReason());
        assertEquals(StreamExchange.State.CANCELLED, transport.lastOutcome.state());
    }

    @Test
    @DisplayName("onReady 抛 UncheckedIOException（meta 写失败）：同样判定为中断")
    void readyAbortReturnsAborted() {
        transport.readyThrows = true;
        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, s -> {
                });
        assertFalse(handle.ok());
        assertNull(handle.reason());
        assertEquals(StreamExchange.State.CANCELLED, transport.lastOutcome.state());
    }

    @Test
    @DisplayName("密钥缺失：直接降级且不发起传输")
    void noKeySkipsTransport() {
        AiProperties noKeyProps = new AiProperties();
        noKeyProps.setBaseUrl("https://agnes.test/v1");
        noKeyProps.setApiKey("");
        noKeyProps.setTimeoutSeconds(60);
        ReflectionTestUtils.setField(client, "aiProperties", noKeyProps);
        AgnesClient.StreamChatHandle handle =
                client.chatStream(7L, "sys", List.of(), "q", transport.readyRecorder, s -> {
                });
        assertFalse(handle.ok());
        assertEquals(AiConstants.AI_NO_KEY_MESSAGE, handle.reason());
        assertFalse(transport.called);
    }

    /**
     * 假传输层：同步回放预设帧，严格复刻 WebClientStreamTransport 对下游异常的契约
     * （onFrame/onReady 抛 UncheckedIOException → CANCELLED）
     */
    private static class FakeStreamTransport implements StreamTransport {
        private List<String> frames = List.of();
        private int httpStatus;
        private boolean transportError;
        private boolean readyThrows;

        private boolean called;
        private int readyCount;
        private JsonNode lastBody;
        private StreamExchange lastOutcome;

        /** 传给 AgnesClient 的 onReady 实现：记录次数；readyThrows 时模拟 meta 写失败断连 */
        private final Runnable readyRecorder = () -> {
            if (readyThrows) {
                throw new UncheckedIOException(new IOException("meta write failed"));
            }
            readyCount++;
        };

        @Override
        public StreamExchange postStream(String url, String bearerToken, JsonNode body,
                                         Duration frameTimeout,
                                         Runnable onReady, Consumer<String> onFrame) {
            called = true;
            lastBody = body;
            if (httpStatus != 0) {
                lastOutcome = StreamExchange.httpError(httpStatus);
                return lastOutcome;
            }
            if (transportError) {
                lastOutcome = StreamExchange.transportError();
                return lastOutcome;
            }
            try {
                onReady.run();
                for (String frame : frames) {
                    onFrame.accept(frame);
                }
            } catch (UncheckedIOException e) {
                lastOutcome = StreamExchange.cancelled();
                return lastOutcome;
            }
            lastOutcome = StreamExchange.completed();
            return lastOutcome;
        }
    }
}
