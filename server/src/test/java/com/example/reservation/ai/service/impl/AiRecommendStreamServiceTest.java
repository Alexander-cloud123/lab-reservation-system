package com.example.reservation.ai.service.impl;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiRecommendItemVO;
import com.example.reservation.ai.dto.AiRecommendVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiRecommendFallback;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.ai.support.RecordingStructuredSink;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流式推荐编排单测（R13-5）：
 * AiInvoker 用 Mock（捕获 beforeUpstreamCall 回调以模拟「缓存命中/未命中」两种时序），
 * 验证事件序列、phase 仅在未命中时出现、空候选短路不发 phase、关闭短路、用户校验。
 * 真实的「缓存→上游→降级」链路在 AiInvokerBeforeCallTest 与 parse/compliance 用例中覆盖。
 */
@ExtendWith(MockitoExtension.class)
class AiRecommendStreamServiceTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private AiInvoker aiInvoker;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ClassroomMapper classroomMapper;
    @Mock
    private AiResultCache aiResultCache;

    private AiRecommendServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiRecommendServiceImpl();
        ReflectionTestUtils.setField(service, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(service, "aiInvoker", aiInvoker);
        ReflectionTestUtils.setField(service, "recommendFallback", new AiRecommendFallback());
        ReflectionTestUtils.setField(service, "sysUserMapper", sysUserMapper);
        ReflectionTestUtils.setField(service, "reservationMapper", reservationMapper);
        ReflectionTestUtils.setField(service, "classroomMapper", classroomMapper);
        ReflectionTestUtils.setField(service, "aiResultCache", aiResultCache);
        AiJsonSupport jsonSupport = new AiJsonSupport();
        ReflectionTestUtils.setField(jsonSupport, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiJsonSupport", jsonSupport);
    }

    private SysUser stubUser() {
        SysUser user = new SysUser();
        user.setId(7L);
        when(sysUserMapper.selectById(7L)).thenReturn(user);
        return user;
    }

    private Classroom stubOneEnabledRoom() {
        Classroom room = new Classroom();
        room.setId(1L);
        room.setName("教二 301");
        room.setBuilding("教二");
        room.setRoomNo("301");
        room.setType(0);
        room.setCapacity(40);
        when(classroomMapper.selectList(any())).thenReturn(List.of(room));
        // buildCandidates 的「明日已通过预约」查询返回空 → 该教室全天空闲
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        return room;
    }

    private AiRecommendVO oneItemVo() {
        AiRecommendItemVO item = new AiRecommendItemVO();
        item.setClassroomId(1L);
        item.setName("教二 301");
        AiRecommendVO vo = new AiRecommendVO();
        vo.setEnabled(true);
        vo.setDate(LocalDate.now().plusDays(1).toString());
        vo.setRecommendations(List.of(item));
        return vo;
    }

    /** 模拟「缓存未命中」：invoker 在返回前先执行 beforeUpstreamCall（即发 phase） */
    private void stubInvokerMiss(AiRecommendVO vo) {
        when(aiInvoker.invoke(anyString(), anyLong(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.getArgument(9, Runnable.class).run();
                    return vo;
                });
    }

    /** 模拟「缓存命中」：invoker 直接返回，不执行 beforeUpstreamCall（无 phase） */
    private void stubInvokerHit(AiRecommendVO vo) {
        when(aiInvoker.invoke(anyString(), anyLong(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any(Runnable.class)))
                .thenReturn(vo);
    }

    @Test
    @DisplayName("未命中：meta → phase(recommend,文案) → result（推荐项同构）→ done")
    void missEmitsPhaseThenResult() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiConfigService.getEffectiveModel()).thenReturn("agnes-test");
        stubUser();
        stubOneEnabledRoom();
        stubInvokerMiss(oneItemVo());
        RecordingStructuredSink<AiRecommendVO> sink = new RecordingStructuredSink<>();

        service.streamRecommend(7L, sink);

        assertEquals(List.of(
                "meta(true,false,null)",
                "phase(" + AiConstants.PHASE_CODE_RECOMMEND + "," + AiConstants.PHASE_TEXT_RECOMMEND + ")",
                "result", "done"), sink.events);
        assertTrue(sink.result.isEnabled());
        assertEquals(1, sink.result.getRecommendations().size());
        assertEquals(1L, sink.result.getRecommendations().get(0).getClassroomId());
    }

    @Test
    @DisplayName("缓存命中：meta → result → done，不发 phase（回调由 invoker 在命中分支跳过）")
    void hitSkipsPhase() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiConfigService.getEffectiveModel()).thenReturn("agnes-test");
        stubUser();
        stubOneEnabledRoom();
        stubInvokerHit(oneItemVo());
        RecordingStructuredSink<AiRecommendVO> sink = new RecordingStructuredSink<>();

        service.streamRecommend(7L, sink);

        assertEquals(List.of("meta(true,false,null)", "result", "done"), sink.events);
        assertFalse(sink.events.stream().anyMatch(e -> e.startsWith("phase(")));
    }

    @Test
    @DisplayName("无候选教室短路：meta → result（enabled=true 空列表）→ done，不调 invoker、不发 phase")
    void emptyCandidatesShortCircuit() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        stubUser();
        // 历史为空 + 无启用教室
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(classroomMapper.selectList(any())).thenReturn(List.of());
        RecordingStructuredSink<AiRecommendVO> sink = new RecordingStructuredSink<>();

        service.streamRecommend(7L, sink);

        assertEquals(List.of("meta(true,false,null)", "result", "done"), sink.events);
        assertTrue(sink.result.isEnabled());
        assertTrue(sink.result.getRecommendations().isEmpty());
        assertFalse(sink.events.stream().anyMatch(e -> e.startsWith("phase(")));
        verify(aiInvoker, never()).invoke(anyString(), anyLong(), anyString(), any(), anyString(),
                any(), any(), any(), any(), any(Runnable.class));
    }

    @Test
    @DisplayName("AI 关闭：meta(false,true,关闭话术) → result(disabled) → done，不查库不调 invoker")
    void disabledShortCircuit() {
        when(aiConfigService.isAiEnabled()).thenReturn(false);
        RecordingStructuredSink<AiRecommendVO> sink = new RecordingStructuredSink<>();

        service.streamRecommend(7L, sink);

        assertEquals(List.of(
                "meta(false,true," + AiConstants.AI_DISABLED_MESSAGE + ")",
                "result", "done"), sink.events);
        assertFalse(sink.result.isEnabled());
        assertFalse(sink.events.stream().anyMatch(e -> e.startsWith("phase(")));
        verify(sysUserMapper, never()).selectById(anyLong());
    }

    @Test
    @DisplayName("入参校验：userId 为 null / 用户不存在抛 BusinessException（SSE 建链前由 Controller 侧暴露）")
    void invalidUserRejected() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        RecordingStructuredSink<AiRecommendVO> sink = new RecordingStructuredSink<>();

        assertThrows(com.example.reservation.common.BusinessException.class,
                () -> service.streamRecommend(null, sink));
        when(sysUserMapper.selectById(99L)).thenReturn(null);
        assertThrows(com.example.reservation.common.BusinessException.class,
                () -> service.streamRecommend(99L, sink));
    }

    @Test
    @DisplayName("非流式回归：recommend() 未命中路径仍透传 userId 与缓存 Key 组装（invoker 被调用 1 次）")
    void nonStreamRecommendStillInvokes() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiConfigService.getEffectiveModel()).thenReturn("agnes-test");
        stubUser();
        stubOneEnabledRoom();
        stubInvokerMiss(oneItemVo());

        AiRecommendVO vo = service.recommend(7L);

        assertTrue(vo.isEnabled());
        verify(aiInvoker).invoke(anyString(), org.mockito.ArgumentMatchers.eq(7L),
                anyString(), any(), anyString(), any(), any(), any(), any(), any(Runnable.class));
        // phase 常量保护
        assertEquals("recommend", AiConstants.PHASE_CODE_RECOMMEND);
    }
}
