package com.example.reservation.ai.service.impl;

import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiComplianceFallback;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.ai.support.AiUsage;
import com.example.reservation.ai.support.RecordingStructuredSink;
import com.example.reservation.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.UncheckedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * 流式合规校验编排单测（R13-5）：
 * 未命中 meta→phase→result→done 且写缓存；缓存命中无 phase 无上游；关闭短路；
 * 降级 message 透出且不写缓存；入参校验；phase 前断开中止上游。
 */
@ExtendWith(MockitoExtension.class)
class AiComplianceStreamServiceTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private AgnesClient agnesClient;
    @Mock
    private AiResultCache aiResultCache;
    @Mock
    private AiMetrics aiMetrics;

    private AiComplianceServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiComplianceServiceImpl();
        AiInvoker invoker = new AiInvoker();
        ReflectionTestUtils.setField(invoker, "agnesClient", agnesClient);
        ReflectionTestUtils.setField(invoker, "aiMetrics", aiMetrics);
        ReflectionTestUtils.setField(invoker, "aiResultCache", aiResultCache);
        AiJsonSupport jsonSupport = new AiJsonSupport();
        ReflectionTestUtils.setField(jsonSupport, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(service, "aiInvoker", invoker);
        ReflectionTestUtils.setField(service, "complianceFallback", new AiComplianceFallback());
        ReflectionTestUtils.setField(service, "aiJsonSupport", jsonSupport);
        ReflectionTestUtils.setField(service, "aiResultCache", aiResultCache);
    }

    /** 未命中用例公用打桩：启用 + 缓存 Key 非 null + 缓存未命中 + 配置版本段依赖 */
    private void stubEnabledAndCacheMiss() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiConfigService.getComplianceKeywordList()).thenReturn(List.of());
        when(aiConfigService.getEffectiveModel()).thenReturn("agnes-test");
        when(aiResultCache.buildKey(anyString(), anyString(), anyString())).thenReturn("ck");
        when(aiResultCache.get(eq("ck"), any(TypeReference.class))).thenReturn(null);
    }

    @Test
    @DisplayName("未命中成功：meta → phase(compliance,文案) → result(enabled=true,compliant) → done，并写缓存")
    void missSuccessEventSequenceAndCaches() {
        stubEnabledAndCacheMiss();
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.ok(
                        "{\"compliant\":true,\"reason\":\"用途正常\"}", AiUsage.EMPTY));
        RecordingStructuredSink<AiComplianceVO> sink = new RecordingStructuredSink<>();

        service.streamCheck("算法小组周会", 7L, sink);

        assertEquals(List.of(
                "meta(true,false,null)",
                "phase(" + AiConstants.PHASE_CODE_COMPLIANCE + "," + AiConstants.PHASE_TEXT_COMPLIANCE + ")",
                "result", "done"), sink.events);
        assertTrue(sink.result.isEnabled());
        assertTrue(sink.result.isCompliant());
        assertEquals("用途正常", sink.result.getReason());
        assertNull(sink.result.getMessage());
        verify(aiResultCache).put(eq("ck"), any(AiComplianceVO.class),
                eq((long) AiConstants.AI_CACHE_TTL_COMPLIANCE_SECONDS));
    }

    @Test
    @DisplayName("缓存命中：meta → result（缓存 VO）→ done，无 phase、不调上游、不写缓存")
    void cacheHitSkipsPhaseAndUpstream() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiConfigService.getComplianceKeywordList()).thenReturn(List.of());
        when(aiConfigService.getEffectiveModel()).thenReturn("agnes-test");
        when(aiResultCache.buildKey(anyString(), anyString(), anyString())).thenReturn("ck");
        AiComplianceVO cached = AiComplianceVO.disabled();
        cached.setEnabled(true);
        cached.setCompliant(false);
        cached.setReason("缓存中的结论");
        when(aiResultCache.get(eq("ck"), any(TypeReference.class))).thenReturn(cached);
        RecordingStructuredSink<AiComplianceVO> sink = new RecordingStructuredSink<>();

        service.streamCheck("举办团建活动", 7L, sink);

        assertEquals(List.of("meta(true,false,null)", "result", "done"), sink.events);
        assertFalse(sink.events.stream().anyMatch(e -> e.startsWith("phase(")));
        assertFalse(sink.result.isCompliant());
        assertEquals("缓存中的结论", sink.result.getReason());
        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
        verify(aiResultCache, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("AI 关闭：meta(false,true,关闭话术) → result(disabled) → done，不查缓存不调上游")
    void disabledShortCircuit() {
        when(aiConfigService.isAiEnabled()).thenReturn(false);
        RecordingStructuredSink<AiComplianceVO> sink = new RecordingStructuredSink<>();

        service.streamCheck("班会", 7L, sink);

        assertEquals(List.of(
                "meta(false,true," + AiConstants.AI_DISABLED_MESSAGE + ")",
                "result", "done"), sink.events);
        assertFalse(sink.result.isEnabled());
        assertFalse(sink.events.stream().anyMatch(e -> e.startsWith("phase(")));
        verify(aiResultCache, never()).get(anyString(), any(TypeReference.class));
        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("降级：phase 照发，result 为关键词兜底结论且 message=降级原因，不写缓存")
    void degradedUsesKeywordFallbackWithoutCaching() {
        stubEnabledAndCacheMiss();
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.degraded(AiConstants.AI_SERVICE_ERROR_MESSAGE));
        RecordingStructuredSink<AiComplianceVO> sink = new RecordingStructuredSink<>();

        service.streamCheck("普通课程答疑", 7L, sink);

        assertEquals(List.of(
                "meta(true,false,null)",
                "phase(" + AiConstants.PHASE_CODE_COMPLIANCE + "," + AiConstants.PHASE_TEXT_COMPLIANCE + ")",
                "result", "done"), sink.events);
        assertEquals(AiConstants.AI_SERVICE_ERROR_MESSAGE, sink.result.getMessage());
        verify(aiResultCache, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("入参校验：空白抛异常；255 字边界合法，256 字抛异常（Controller 在 SSE 建立前调用）")
    void validateInputBoundaries() {
        assertThrows(BusinessException.class, () -> service.validateCheckInput(" "));
        assertThrows(BusinessException.class, () -> service.validateCheckInput("用".repeat(256)));
        assertDoesNotThrow(() -> service.validateCheckInput("用".repeat(255)));
    }

    @Test
    @DisplayName("客户端在 phase 前断开：UncheckedIOException 中止，上游不被调用、不发 result/done、不写缓存")
    void abortBeforePhaseSkipsUpstream() {
        stubEnabledAndCacheMiss();

        assertThrows(UncheckedIOException.class, () ->
                service.streamCheck("班会", 7L, RecordingStructuredSink.phaseAborting()));

        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
        verify(aiResultCache, never()).put(anyString(), any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
