package com.example.reservation.ai.service.impl;

import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiChatVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiChatFallback;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.ai.support.ChatStreamSink;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式问答编排单测（R12）：成功透传 + 写缓存 / 缓存命中重放 / 降级 FAQ 分段 / AI 关闭。
 * Sink 用录制假实现，不依赖 Servlet。
 */
@ExtendWith(MockitoExtension.class)
class AiChatStreamServiceTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private AgnesClient agnesClient;
    @Mock
    private AiChatFallback chatFallback;
    @Mock
    private AiResultCache aiResultCache;
    @Mock
    private AiMetrics aiMetrics;

    private AiChatServiceImpl service;

    /** 录制事件的假 sink（事件扁平为字符串便于断言顺序） */
    private static class RecordingSink implements ChatStreamSink {
        final List<String> events = new ArrayList<>();
        final StringBuilder text = new StringBuilder();

        @Override
        public void meta(boolean enabled, boolean degraded, String message) {
            events.add("meta(" + enabled + "," + degraded + "," + message + ")");
        }

        @Override
        public void delta(String text) {
            events.add("delta");
            this.text.append(text);
        }

        @Override
        public void done(String finishReason) {
            events.add("done(" + finishReason + ")");
        }

        @Override
        public void error(String message) {
            events.add("error(" + message + ")");
        }
    }

    @BeforeEach
    void setUp() {
        service = new AiChatServiceImpl();
        ReflectionTestUtils.setField(service, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(service, "agnesClient", agnesClient);
        ReflectionTestUtils.setField(service, "chatFallback", chatFallback);
        ReflectionTestUtils.setField(service, "aiResultCache", aiResultCache);
        ReflectionTestUtils.setField(service, "aiMetrics", aiMetrics);
        // buildContext 用到的两个 mapper（具体打桩放在需要走到 buildContext 的用例中，
        // MockitoExtension 为 STRICT_STUBS，disabled/cacheHit 用例不走 buildContext，不能提前桩）
        ReflectionTestUtils.setField(service, "reservationMapper",
                org.mockito.Mockito.mock(com.example.reservation.mapper.ReservationMapper.class));
        ReflectionTestUtils.setField(service, "classroomMapper",
                org.mockito.Mockito.mock(com.example.reservation.mapper.ClassroomMapper.class));
    }

    /**
     * 缓存未命中用例公用打桩：buildContext 检索「最近 5 条预约」返回空列表、总数 0
     * （未命中缓存的成功/降级用例都会构建用户上下文；Mockito 对 List 返回类型默认给 null，必须显式桩）
     */
    @SuppressWarnings("unchecked")
    private void stubEmptyUserContext() {
        com.example.reservation.mapper.ReservationMapper reservationMapper =
                (com.example.reservation.mapper.ReservationMapper)
                        ReflectionTestUtils.getField(service, "reservationMapper");
        org.mockito.Mockito.when(reservationMapper.selectList(any())).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(reservationMapper.selectCount(any())).thenReturn(0L);
    }

    /**
     * aiResultCache 为 Mock，buildKey 默认返回 null；而 Mockito 5 的 anyString() 不匹配 null，
     * 故走到缓存分支的用例需把 buildKey 桩为非 null 固定 Key（disabled / 校验用例不触达，不能提前桩）
     */
    private void stubCacheKey() {
        when(aiResultCache.buildKey(anyString(), anyString(), anyString())).thenReturn("chat-cache-key");
    }

    @Test
    @DisplayName("成功：meta(enabled=true,degraded=false) → delta 透传 → done(stop)，全文写缓存")
    void successStreamsAndCaches() {
        stubEmptyUserContext();
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(null);
        when(agnesClient.chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(3, Runnable.class).run(); // onReady
                    inv.getArgument(4, java.util.function.Consumer.class).accept("你好");
                    inv.getArgument(4, java.util.function.Consumer.class).accept("，世界");
                    return new AgnesClient.StreamChatHandle(true, null, "stop");
                });
        RecordingSink sink = new RecordingSink();

        service.streamChat(7L, "怎么预约", sink);

        // 精确事件序列：恰好 1 个 meta、2 个 delta、1 个 done，且顺序固定
        assertEquals(List.of("meta(true,false,null)", "delta", "delta", "done(stop)"), sink.events);
        assertEquals("你好，世界", sink.text.toString());
        // 写缓存值断言：enabled=true、answer 为累积全文（成功路径写缓存前的 stripCodeFence 不改写纯文本）
        ArgumentCaptor<AiChatVO> voCaptor = ArgumentCaptor.forClass(AiChatVO.class);
        verify(aiResultCache).put(anyString(), voCaptor.capture(), eq(AiConstants.AI_CACHE_TTL_CHAT_SECONDS));
        AiChatVO cachedVo = voCaptor.getValue();
        assertEquals("你好，世界", cachedVo.getAnswer());
        assertTrue(cachedVo.isEnabled());
    }

    @Test
    @DisplayName("缓存命中：不调上游，答案按 20 字分段重放，done(stop)")
    void cacheHitReplaysWithoutUpstream() {
        AiChatVO cached = new AiChatVO();
        cached.setEnabled(true);
        cached.setAnswer("0123456789012345678901234"); // 25 字 → 20 + 5 两段
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(cached);
        RecordingSink sink = new RecordingSink();

        service.streamChat(7L, "怎么预约", sink);

        // 精确事件序列：meta → 恰好 2 个 delta（20+5）→ done，无任何多余事件
        assertEquals(List.of("meta(true,false,null)", "delta", "delta", "done(stop)"), sink.events);
        assertEquals("0123456789012345678901234", sink.text.toString());
        verify(agnesClient, never()).chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any());
        verify(aiResultCache, never()).put(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("降级：meta(degraded,原因) → FAQ 分段 → done(fallback)，不写缓存")
    void degradedStreamsFallback() {
        stubEmptyUserContext();
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(null);
        when(agnesClient.chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any()))
                .thenReturn(new AgnesClient.StreamChatHandle(false, AiConstants.AI_RATE_LIMITED_MESSAGE, null));
        when(chatFallback.chatFallback("怎么取消")).thenReturn("FAQ答案");
        RecordingSink sink = new RecordingSink();

        service.streamChat(7L, "怎么取消", sink);

        // 精确事件序列：FAQ 答案 5 字（<20 步长）只有 1 个 delta
        assertEquals(List.of("meta(true,true," + AiConstants.AI_RATE_LIMITED_MESSAGE + ")",
                "delta", "done(fallback)"), sink.events);
        assertEquals("FAQ答案", sink.text.toString());
        verify(aiResultCache, never()).put(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("AI 关闭：meta(enabled=false,degraded=true) 后直接 done(disabled)，不查缓存不调上游")
    void disabledShortCircuit() {
        when(aiConfigService.isAiEnabled()).thenReturn(false);
        RecordingSink sink = new RecordingSink();

        service.streamChat(7L, "在吗", sink);

        // 精确事件序列：仅 meta + done，无 delta
        assertEquals(List.of("meta(false,true," + AiConstants.AI_DISABLED_MESSAGE + ")",
                "done(disabled)"), sink.events);
        verify(aiResultCache, never()).get(anyString(), any(TypeReference.class));
        verify(agnesClient, never()).chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any());
    }

    @Test
    @DisplayName("入参校验：空问题抛 BusinessException（与非流式 chat 同口径）")
    void validateBlankRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.reservation.common.BusinessException.class,
                () -> service.validateQuestion("   "));
    }

    @Test
    @DisplayName("入参校验：超过 200 字抛 BusinessException，200 字边界不抛")
    void validateLengthBoundary() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.reservation.common.BusinessException.class,
                () -> service.validateQuestion("字".repeat(201)));
        // 200 字整为合法边界：validateQuestion 仅做 blank/length 判定，不触碰任何 Mock 依赖，正常返回
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.validateQuestion("字".repeat(200)));
    }

    @Test
    @DisplayName("客户端中断：delta 写入失败后静默终止，不发降级/FAQ/done，不写缓存")
    void clientAbortSilentlyTerminates() {
        stubEmptyUserContext();
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(null);
        when(agnesClient.chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(3, Runnable.class).run(); // onReady：meta 发送成功
                    java.util.function.Consumer<String> c = inv.getArgument(4);
                    try {
                        c.accept("第一段"); // sink 在此抛 IOException → service 应包装 UncheckedIOException 上抛
                    } catch (java.io.UncheckedIOException expected) {
                        // 模拟真实 AgnesClient：中断 → aborted 句柄（ok=false, reason=null）
                        return new AgnesClient.StreamChatHandle(false, null, null);
                    }
                    return new AgnesClient.StreamChatHandle(true, null, "stop");
                });
        ChatStreamSink abortingSink = new ChatStreamSink() {
            @Override
            public void meta(boolean enabled, boolean degraded, String message) {
                // meta 已成功发出（onReady 阶段），空实现
            }

            @Override
            public void delta(String text) throws java.io.IOException {
                throw new java.io.IOException("client gone");
            }

            @Override
            public void done(String finishReason) {
                throw new AssertionError("aborted 流不应再发 done");
            }

            @Override
            public void error(String message) {
                throw new AssertionError("aborted 流不应再发 error");
            }
        };

        service.streamChat(7L, "怎么预约", abortingSink);

        verify(aiResultCache, never()).put(anyString(), any(), anyLong());
        verify(chatFallback, never()).chatFallback(anyString());
    }

    @Test
    @DisplayName("客户端中断：onReady(meta) 写入失败即静默终止，不读 delta、不发 done/error，不写缓存")
    void clientAbortOnReadySilentlyTerminates() {
        stubEmptyUserContext();
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(null);
        when(agnesClient.chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any()))
                .thenAnswer(inv -> {
                    try {
                        // onReady 中 meta 写入失败 → service 应包装 UncheckedIOException 上抛，且此后不再读 delta
                        inv.getArgument(3, Runnable.class).run();
                    } catch (java.io.UncheckedIOException expected) {
                        // 模拟真实 AgnesClient：onReady 中断 → aborted 句柄（ok=false, reason=null）
                        return new AgnesClient.StreamChatHandle(false, null, null);
                    }
                    throw new AssertionError("onReady 写入失败应抛出 UncheckedIOException");
                });
        ChatStreamSink metaAbortingSink = new ChatStreamSink() {
            @Override
            public void meta(boolean enabled, boolean degraded, String message) throws java.io.IOException {
                throw new java.io.IOException("meta write failed");
            }

            @Override
            public void delta(String text) {
                throw new AssertionError("aborted 流不应再发 delta");
            }

            @Override
            public void done(String finishReason) {
                throw new AssertionError("aborted 流不应再发 done");
            }

            @Override
            public void error(String message) {
                throw new AssertionError("aborted 流不应再发 error");
            }
        };

        // 中断为静默终止：service 正常返回，不外抛
        service.streamChat(7L, "怎么预约", metaAbortingSink);

        verify(aiResultCache, never()).put(anyString(), any(), anyLong());
        verify(chatFallback, never()).chatFallback(anyString());
    }

    @Test
    @DisplayName("建链后降级(200 后空内容)：meta 仅 1 个(enabled=true,degraded=false)，FAQ 分段后 done(fallback)")
    void readyThenEmptyContentFallbackWithoutSecondMeta() {
        stubEmptyUserContext();
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(null);
        // 45 字 FAQ → 按 20 字步长切为 20+20+5 三段
        String faq = "0123456789".repeat(4) + "01234";
        when(chatFallback.chatFallback("怎么预约")).thenReturn(faq);
        when(agnesClient.chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(3, Runnable.class).run(); // onReady：唯一 meta 已发出
                    // 不回调任何 onDelta：模拟 200 后空内容/全脏帧，AgnesClient 返回 degraded 句柄
                    return AgnesClient.StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                });
        RecordingSink sink = new RecordingSink();

        service.streamChat(7L, "怎么预约", sink);

        // 精确事件序列：1 个 meta(enabled=true,degraded=false) + 3 个 FAQ delta(20/20/5) + done(fallback)
        assertEquals(List.of("meta(true,false,null)", "delta", "delta", "delta",
                "done(fallback)"), sink.events);
        assertEquals(faq, sink.text.toString());
        // 全流 meta 恰好 1 个：建链后降级原因不再通过第 2 个 degraded meta 透出
        assertEquals(1, sink.events.stream().filter(e -> e.startsWith("meta(")).count());
        verify(aiResultCache, never()).put(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("建链后读体中断(已有半截文本)：meta 仅 1 个，保留半截不拼 FAQ，done(unknown)")
    void readyThenInterruptedKeepsPartialAndDoneUnknown() {
        stubEmptyUserContext();
        stubCacheKey();
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiResultCache.get(anyString(), any(TypeReference.class))).thenReturn(null);
        when(agnesClient.chatStream(anyLong(), anyString(), anyString(), any(Runnable.class), any()))
                .thenAnswer(inv -> {
                    inv.getArgument(3, Runnable.class).run(); // onReady：唯一 meta 已发出
                    inv.getArgument(4, java.util.function.Consumer.class).accept("半截回答");
                    // 模拟读体中途 IOException：AgnesClient 返回 degraded 句柄（finishReason 为 null）
                    return AgnesClient.StreamChatHandle.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE);
                });
        RecordingSink sink = new RecordingSink();

        service.streamChat(7L, "怎么预约", sink);

        // 精确事件序列：1 个 meta + 1 个 delta("半截回答") + done(unknown)，无第 2 个 meta、无 FAQ
        assertEquals(List.of("meta(true,false,null)", "delta", "done(unknown)"), sink.events);
        assertEquals("半截回答", sink.text.toString());
        // 全部 delta 拼接不含 FAQ 文本，且兜底根本未被调用（半截真实文本不与 FAQ 拼接）
        assertFalse(sink.text.toString().contains("FAQ"));
        verify(chatFallback, never()).chatFallback(anyString());
        assertEquals(1, sink.events.stream().filter(e -> e.startsWith("meta(")).count());
        verify(aiResultCache, never()).put(anyString(), any(), anyLong());
    }
}
