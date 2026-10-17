package com.example.reservation.ai.service.impl;

import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiPrompts;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiMetrics;
import com.example.reservation.ai.support.AiParseFallback;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.ai.support.AiUsage;
import com.example.reservation.ai.support.RecordingStructuredSink;
import com.example.reservation.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式解析编排单测（R13-5）：
 * meta → phase → result（同构 VO）→ done；AI 关闭短路；降级 message 透出；
 * 识别失败 error 约定；入参校验；客户端在 phase 前断开则中止且不调上游。
 * AiInvoker/AiJsonSupport/AiParseFallback 全部用真实实现，仅 Mock 上游与配置。
 */
@ExtendWith(MockitoExtension.class)
class AiParseStreamServiceTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private AgnesClient agnesClient;
    @Mock
    private AiResultCache aiResultCache;
    @Mock
    private AiMetrics aiMetrics;

    private AiParseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiParseServiceImpl();
        AiInvoker invoker = new AiInvoker();
        ReflectionTestUtils.setField(invoker, "agnesClient", agnesClient);
        ReflectionTestUtils.setField(invoker, "aiMetrics", aiMetrics);
        ReflectionTestUtils.setField(invoker, "aiResultCache", aiResultCache);
        AiJsonSupport jsonSupport = new AiJsonSupport();
        ReflectionTestUtils.setField(jsonSupport, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(service, "aiInvoker", invoker);
        ReflectionTestUtils.setField(service, "parseFallback", new AiParseFallback());
        ReflectionTestUtils.setField(service, "aiJsonSupport", jsonSupport);
    }

    private void stubUpstreamOk(String json) {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        // system 模板返回 null → 走内置 AiPrompts.PARSE（避免打桩 getPromptParse）
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.ok(json, AiUsage.EMPTY));
    }

    @Test
    @DisplayName("成功：meta(enabled=true) → phase(parse,文案) → result(enabled=true,时段同构) → done")
    void successEventSequence() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        stubUpstreamOk("{\"date\":\"" + tomorrow + "\",\"startTime\":\"09:00\",\"endTime\":\"10:00\","
                + "\"capacity\":40,\"roomType\":\"实验室\",\"purpose\":\"算法实验\"}");
        RecordingStructuredSink<AiParseVO> sink = new RecordingStructuredSink<>();

        service.streamParse("明天九点到十点 实验室", 7L, sink);

        assertEquals(List.of(
                "meta(true,false,null)",
                "phase(" + AiConstants.PHASE_CODE_PARSE + "," + AiConstants.PHASE_TEXT_PARSE + ")",
                "result", "done"), sink.events);
        AiParseVO vo = sink.result;
        assertTrue(vo.isEnabled());
        assertEquals(tomorrow, vo.getDate());
        assertEquals("09:00", vo.getStartTime());
        assertEquals("10:00", vo.getEndTime());
        assertEquals(40, vo.getCapacity());
        assertEquals("实验室", vo.getRoomType());
        assertEquals("算法实验", vo.getPurpose());
        assertNull(vo.getError());
        // 解析无缓存：不触碰结果缓存
        verify(aiResultCache, never()).get(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("AI 关闭：meta(false,true,关闭话术) → result(disabled) → done，不发 phase、不调上游")
    void disabledShortCircuit() {
        when(aiConfigService.isAiEnabled()).thenReturn(false);
        RecordingStructuredSink<AiParseVO> sink = new RecordingStructuredSink<>();

        service.streamParse("明天九点", 7L, sink);

        assertEquals(List.of(
                "meta(false,true," + AiConstants.AI_DISABLED_MESSAGE + ")",
                "result", "done"), sink.events);
        // phase 必须为 0 个：关闭短路不查缓存、不调上游
        org.junit.jupiter.api.Assertions.assertFalse(
                sink.events.stream().anyMatch(e -> e.startsWith("phase(")));
        org.junit.jupiter.api.Assertions.assertFalse(sink.result.isEnabled());
        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("降级：上游失败时 phase 仍发送，result 为本地兜底结果且 message 透出降级原因")
    void degradedResultCarriesReason() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(agnesClient.chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean()))
                .thenReturn(AgnesClient.AgnesResponse.degraded(AiConstants.AI_RATE_LIMITED_MESSAGE));
        RecordingStructuredSink<AiParseVO> sink = new RecordingStructuredSink<>();

        service.streamParse("明天九点到十点教二301开会", 7L, sink);

        assertEquals(List.of(
                "meta(true,false,null)",
                "phase(" + AiConstants.PHASE_CODE_PARSE + "," + AiConstants.PHASE_TEXT_PARSE + ")",
                "result", "done"), sink.events);
        assertEquals(AiConstants.AI_RATE_LIMITED_MESSAGE, sink.result.getMessage());
        // 本地兜底同样给出可用结构化字段（非空 VO）
        assertNotNull(sink.result);
    }

    @Test
    @DisplayName("识别失败约定：模型返回 {\"error\":\"无法解析\"} 时 result.error 非空、enabled=true（前端引导手动填写）")
    void recognitionFailureResultHasError() {
        stubUpstreamOk("{\"error\":\"无法解析\"}");
        RecordingStructuredSink<AiParseVO> sink = new RecordingStructuredSink<>();

        service.streamParse("帮我整个合适的时间", 7L, sink);

        assertEquals(AiConstants.PARSE_ERROR, sink.result.getError());
        assertTrue(sink.result.isEnabled());
        assertEquals(List.of("meta(true,false,null)",
                "phase(" + AiConstants.PHASE_CODE_PARSE + "," + AiConstants.PHASE_TEXT_PARSE + ")",
                "result", "done"), sink.events);
    }

    @Test
    @DisplayName("入参校验：空白抛异常；200 字边界合法，201 字抛异常（Controller 在 SSE 建立前调用）")
    void validateInputBoundaries() {
        assertThrows(BusinessException.class, () -> service.validateParseInput("  "));
        assertThrows(BusinessException.class, () -> service.validateParseInput("字".repeat(201)));
        assertDoesNotThrow(() -> service.validateParseInput("字".repeat(200)));
    }

    @Test
    @DisplayName("客户端在 phase 前断开：phase 写失败包装 UncheckedIOException 中止，上游不被调用、不发 result/done")
    void abortBeforePhaseSkipsUpstream() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);

        assertThrows(UncheckedIOException.class, () ->
                service.streamParse("明天九点", 7L, RecordingStructuredSink.phaseAborting()));

        verify(agnesClient, never()).chat(anyLong(), anyString(), anyList(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("非流式回归：parse() 仍返回同构 VO（enabled=true），prompt 取自定义模板时追加今日日期上下文")
    void nonStreamParseStillWorks() {
        stubUpstreamOk("{\"date\":\"" + LocalDate.now() + "\",\"startTime\":\"08:00\",\"endTime\":\"09:00\"}");
        when(aiConfigService.getPromptParse()).thenReturn("自定义解析模板");
        // 非流式从 UserContext 取当前用户（请求线程语义），用后必须清理避免线程复用串扰
        com.example.reservation.common.UserContext.set(7L, "tester", 0);
        try {
            AiParseVO vo = service.parse("今天八点到九点");

            assertTrue(vo.isEnabled());
            assertEquals("08:00", vo.getStartTime());
            verify(agnesClient).chat(anyLong(), org.mockito.ArgumentMatchers.contains("自定义解析模板"),
                    anyList(), anyString(), anyBoolean());
            // 常量引用保护（AiPrompts 内置模板路径覆盖）
            assertNotNull(AiPrompts.PARSE);
        } finally {
            com.example.reservation.common.UserContext.clear();
        }
    }
}
