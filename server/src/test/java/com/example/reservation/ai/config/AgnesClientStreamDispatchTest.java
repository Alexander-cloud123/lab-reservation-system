package com.example.reservation.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AgnesClient 流式响应分发单测：上游 SSE 文本 → delta 片段回调 + 累积全文 + finish_reason。
 * 只测包可见的 dispatchStream（HTTP 交换层与限流层复用 chat() 已验证逻辑，不在本类覆盖）。
 */
class AgnesClientStreamDispatchTest {

    private AgnesClient client;

    @BeforeEach
    void setUp() {
        client = new AgnesClient();
        ReflectionTestUtils.setField(client, "objectMapper", new ObjectMapper());
    }

    private InputStream sse(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("多 chunk 正常流：delta 按序回调、全文累积、finish_reason=stop；[DONE] 后内容忽略")
    void dispatchNormalStream() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"},\"finish_reason\":null}]}\n\n"
                + ": comment line\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"AFTER_DONE\"}}]}\n\n";
        List<String> pieces = new ArrayList<>();
        Consumer<String> consumer = pieces::add;

        AgnesClient.DispatchResult result = client.dispatchStream(sse(body), consumer);

        assertEquals("你好，世界", result.content());
        assertEquals("stop", result.finishReason());
        assertEquals(List.of("你好", "，世界"), pieces);
    }

    @Test
    @DisplayName("空 delta / 无 choices 的 chunk 跳过，不回调")
    void dispatchEmptyDeltaSkipped() throws Exception {
        String body = "data: {\"choices\":[]}\n\n"
                + "data: {\"choices\":[{\"delta\":{}}]}\n\n"
                + "data: [DONE]\n\n";
        List<String> pieces = new ArrayList<>();
        AgnesClient.DispatchResult result = client.dispatchStream(sse(body), pieces::add);
        assertEquals("", result.content());
        assertEquals(0, pieces.size());
    }

    @Test
    @DisplayName("脏帧（非 JSON）跳过不中断；缺 finish_reason 时为 null")
    void dispatchDirtyFrameSkipped() throws Exception {
        String body = "data: not-a-json\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: [DONE]\n\n";
        List<String> pieces = new ArrayList<>();
        AgnesClient.DispatchResult result = client.dispatchStream(sse(body), pieces::add);
        assertEquals("ok", result.content());
        assertNull(result.finishReason());
    }

    @Test
    @DisplayName("deltaConsumer 抛出的异常不被脏帧 catch 吞掉（客户端中断需原样上抛）")
    void dispatchConsumerExceptionPropagates() {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n";
        UncheckedIOException boom = new UncheckedIOException(new IOException("client gone"));
        Consumer<String> bad = s -> {
            throw boom;
        };
        assertSame(boom, assertThrows(UncheckedIOException.class,
                () -> client.dispatchStream(sse(body), bad)));
    }
}
