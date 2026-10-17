package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AgnesClient;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AiInvoker 调用前回调（beforeUpstreamCall，R13-5）单测：
 * 缓存未命中 → 先回调再调上游；缓存命中 → 不回调不调上游；回调抛异常 → 中止且不调上游。
 */
@ExtendWith(MockitoExtension.class)
class AiInvokerBeforeCallTest {

    @Mock
    private AgnesClient agnesClient;
    @Mock
    private AiMetrics aiMetrics;
    @Mock
    private AiResultCache aiResultCache;

    private AiInvoker invoker;

    private final AiInvoker.AiCacheSpec<String> cacheSpec =
            new AiInvoker.AiCacheSpec<>("k", 60L, new TypeReference<String>() {
            });

    @BeforeEach
    void setUp() {
        invoker = new AiInvoker();
        ReflectionTestUtils.setField(invoker, "agnesClient", agnesClient);
        ReflectionTestUtils.setField(invoker, "aiMetrics", aiMetrics);
        ReflectionTestUtils.setField(invoker, "aiResultCache", aiResultCache);
    }

    @Test
    @DisplayName("缓存未命中：beforeUpstreamCall 恰好执行 1 次且早于上游调用，成功结果写缓存")
    void missInvokesCallbackBeforeUpstreamAndCaches() {
        when(aiResultCache.get(eq("k"), any(TypeReference.class))).thenReturn(null);
        // 记录「回调执行」与「上游调用」的相对次序
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenAnswer(inv -> {
                    order.add("chat");
                    return AgnesClient.AgnesResponse.ok("{}", AiUsage.EMPTY);
                });
        AtomicInteger calls = new AtomicInteger();

        String result = invoker.invoke("测试接口", 7L, "sys", List.of(), "user",
                content -> "parsed",
                parsed -> "SUCCESS:" + parsed,
                reason -> "FAIL",
                cacheSpec,
                () -> {
                    calls.incrementAndGet();
                    order.add("beforeCall");
                });

        assertEquals("SUCCESS:parsed", result);
        assertEquals(1, calls.get(), "调用前回调必须恰好执行 1 次");
        assertEquals(List.of("beforeCall", "chat"), order, "回调必须先于上游调用");
        verify(aiResultCache).put(eq("k"), eq("SUCCESS:parsed"), eq(60L));
        verify(aiMetrics).recordCacheMiss();
    }

    @Test
    @DisplayName("缓存命中：直接返回缓存值，不执行 beforeUpstreamCall、不调上游、不写缓存")
    void hitSkipsCallbackAndUpstream() {
        when(aiResultCache.get(eq("k"), any(TypeReference.class))).thenReturn("CACHED");
        AtomicInteger calls = new AtomicInteger();

        String result = invoker.invoke("测试接口", 7L, "sys", List.of(), "user",
                content -> "parsed",
                parsed -> "SUCCESS",
                reason -> "FAIL",
                cacheSpec,
                calls::incrementAndGet);

        assertEquals("CACHED", result);
        assertEquals(0, calls.get(), "缓存命中不得执行调用前回调");
        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
        verify(aiResultCache, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
        verify(aiMetrics).recordCacheHit();
    }

    @Test
    @DisplayName("回调抛异常：立即中止，不调用上游、不写缓存（客户端断开不空耗配额）")
    void callbackThrowingAbortsUpstream() {
        when(aiResultCache.get(eq("k"), any(TypeReference.class))).thenReturn(null);

        RuntimeException abort = assertThrows(RuntimeException.class, () -> invoker.invoke(
                "测试接口", 7L, "sys", List.of(), "user",
                content -> "parsed",
                parsed -> "SUCCESS",
                reason -> "FAIL",
                cacheSpec,
                () -> {
                    throw new IllegalStateException("client disconnected");
                }));
        assertTrue(abort.getMessage().contains("client disconnected"));
        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
        verify(aiResultCache, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("无缓存规格（cacheSpec=null）：回调仍在调用上游前执行（解析接口无缓存也必须发 phase）")
    void noCacheStillInvokesCallback() {
        AtomicInteger calls = new AtomicInteger();
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.ok("{}", AiUsage.EMPTY));

        String result = invoker.invoke("解析", 7L, "sys", List.of(), "user",
                content -> "parsed",
                parsed -> "OK",
                reason -> "FAIL",
                null,
                calls::incrementAndGet);

        assertEquals("OK", result);
        assertEquals(1, calls.get());
        verify(aiResultCache, never()).get(anyString(), any(TypeReference.class));
        // 无缓存规格：成功也不触发 put
        verify(aiResultCache, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("既有 9 参签名（不传回调）行为不变：未命中正常调上游（回归保护）")
    void legacySignatureStillWorks() {
        when(aiResultCache.get(eq("k"), any(TypeReference.class))).thenReturn(null);
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.ok("{}", AiUsage.EMPTY));

        String result = invoker.invoke("旧接口", 7L, "sys", List.of(), "user",
                content -> "parsed",
                parsed -> "OK",
                reason -> "FAIL",
                cacheSpec);

        assertEquals("OK", result);
        verify(agnesClient).chat(anyLong(), anyString(), anyList(), anyString(), eq(true));
    }
}
