package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.config.RedisCache;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI 结果缓存单测（本次改动：命中即免上游调用；降级结果不入缓存）
 *
 * 本类覆盖两层：
 *  1. {@link AiResultCache} 自身——Key 组装格式与对既有 {@link RedisCache} 的委托；
 *  2. {@link AiInvoker} 中的缓存编排——命中即短路（不触上游）、仅成功分支写缓存、降级分支不写缓存。
 * 全程 Mockito 打桩，不起 Spring 上下文、不消耗上游额度。
 *
 * @author reservation-team
 */
class AiResultCacheTest {

    private static final String KEY = "cache:ai:compliance:v1:abc";
    private static final long TTL_SECONDS = 300L;
    private static final TypeReference<AiComplianceVO> VO_TYPE = new TypeReference<>() {
    };

    /* ==================== 1. AiResultCache 自身 ==================== */

    @Test
    @DisplayName("Key 组装：cache:ai:{namespace}:{version}:{fingerprint}")
    void buildKeyFormat() {
        AiResultCache cache = new AiResultCache();
        assertEquals("cache:ai:compliance:v1:abc", cache.buildKey("compliance", "v1", "abc"));
    }

    @Test
    @DisplayName("读写委托既有 RedisCache（不重复实现 Redis 逻辑）")
    void getAndPutDelegateToRedisCache() {
        AiResultCache cache = new AiResultCache();
        RedisCache redisCache = mock(RedisCache.class);
        ReflectionTestUtils.setField(cache, "redisCache", redisCache);

        AiComplianceVO cached = new AiComplianceVO();
        cached.setEnabled(true);
        when(redisCache.getObject(eq(KEY), any())).thenReturn(cached);

        assertSame(cached, cache.get(KEY, VO_TYPE));
        cache.put(KEY, cached, TTL_SECONDS);
        verify(redisCache).setObject(KEY, cached, TTL_SECONDS);
    }

    /* ==================== 2. AiInvoker 中的缓存编排 ==================== */

    @Test
    @DisplayName("缓存命中：直接返回缓存结果，完全不触发上游调用（modelCallCount 不增）")
    void cacheHitShortCircuitsUpstream() {
        AiResultCache cache = mock(AiResultCache.class);
        AgnesClient agnes = mock(AgnesClient.class);
        AiMetrics metrics = new AiMetrics();
        AiInvoker invoker = buildInvoker(cache, agnes, metrics);

        AiComplianceVO cached = new AiComplianceVO();
        cached.setEnabled(true);
        cached.setCompliant(true);
        when(cache.get(eq(KEY), any())).thenReturn(cached);

        AiComplianceVO result = invoker.invoke("合规", 1L, "sys", "purpose",
                c -> new AiComplianceVO(), r -> r, r -> null, spec());

        assertSame(cached, result);
        verifyNoInteractions(agnes);
        verify(cache, never()).put(anyString(), any(), anyLong());
        assertEquals(1L, metrics.snapshot().getCacheHitCount());
        assertEquals(0L, metrics.snapshot().getModelCallCount());
    }

    @Test
    @DisplayName("未命中且模型调用成功：回写缓存（下次即可命中）")
    void successWritesCache() {
        AiResultCache cache = mock(AiResultCache.class);
        AgnesClient agnes = mock(AgnesClient.class);
        AiMetrics metrics = new AiMetrics();
        AiInvoker invoker = buildInvoker(cache, agnes, metrics);

        when(cache.get(eq(KEY), any())).thenReturn(null);
        when(agnes.chat(any(), any(), any(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.ok("{\"compliant\":true}", AiUsage.EMPTY));

        // onSuccess 与生产口径一致：成功分支把 enabled 置真（前端据此显示入口）
        AiComplianceVO result = invoker.invoke("合规", 1L, "sys", "purpose",
                c -> new AiComplianceVO(),
                vo -> {
                    vo.setEnabled(true);
                    return vo;
                },
                r -> null, spec());

        assertTrue(result.isEnabled());
        verify(cache).put(eq(KEY), any(AiComplianceVO.class), eq(TTL_SECONDS));
        assertEquals(1L, metrics.snapshot().getCacheMissCount());
    }

    @Test
    @DisplayName("降级结果不入缓存：命中失败后模型降级，缓存不得被写入")
    void degradeDoesNotWriteCache() {
        AiResultCache cache = mock(AiResultCache.class);
        AgnesClient agnes = mock(AgnesClient.class);
        AiMetrics metrics = new AiMetrics();
        AiInvoker invoker = buildInvoker(cache, agnes, metrics);

        when(cache.get(eq(KEY), any())).thenReturn(null);
        when(agnes.chat(any(), any(), any(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.degraded("AI 服务暂时不可用，已自动切换为本地规则模式"));

        AiComplianceVO degraded = new AiComplianceVO();
        degraded.setEnabled(true);
        degraded.setMessage("AI 服务暂时不可用，已自动切换为本地规则模式");
        AiComplianceVO result = invoker.invoke("合规", 1L, "sys", "purpose",
                c -> new AiComplianceVO(), r -> r, r -> degraded, spec());

        assertSame(degraded, result);
        // 关键断言：降级分支绝不写缓存，避免把本地规则结果固化成「模型结果」
        verify(cache, never()).put(anyString(), any(), anyLong());
        assertEquals(1L, metrics.snapshot().getCacheMissCount());
    }

    @Test
    @DisplayName("模型输出结构不合法：记 OUTPUT_INVALID 降级桶，且不写缓存")
    void outputInvalidDoesNotWriteCache() {
        AiResultCache cache = mock(AiResultCache.class);
        AgnesClient agnes = mock(AgnesClient.class);
        AiMetrics metrics = new AiMetrics();
        AiInvoker invoker = buildInvoker(cache, agnes, metrics);

        when(cache.get(eq(KEY), any())).thenReturn(null);
        when(agnes.chat(any(), any(), any(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.ok("not-a-json-object", AiUsage.EMPTY));

        AiComplianceVO result = invoker.invoke("合规", 1L, "sys", "purpose",
                c -> (AiComplianceVO) null, r -> r, r -> new AiComplianceVO(), spec());

        assertFalse(result.isEnabled());
        verify(cache, never()).put(anyString(), any(), anyLong());
        assertEquals(1L, metrics.snapshot().getDegradeByReason().get(AiDegradeReason.OUTPUT_INVALID.name()));
    }

    /** 组装被测 AiInvoker（三个依赖均由构造方注入，避免起 Spring 上下文） */
    private AiInvoker buildInvoker(AiResultCache cache, AgnesClient agnes, AiMetrics metrics) {
        AiInvoker invoker = new AiInvoker();
        ReflectionTestUtils.setField(invoker, "agnesClient", agnes);
        ReflectionTestUtils.setField(invoker, "aiResultCache", cache);
        ReflectionTestUtils.setField(invoker, "aiMetrics", metrics);
        return invoker;
    }

    /** 缓存规格（与生产合规场景同构：指定 Key / TTL / 反序列化类型） */
    private AiInvoker.AiCacheSpec<AiComplianceVO> spec() {
        return new AiInvoker.AiCacheSpec<>(KEY, TTL_SECONDS, VO_TYPE);
    }
}