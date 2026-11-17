package com.example.reservation.ai.service.impl;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.dto.AiPlanCandidateVO;
import com.example.reservation.ai.dto.AiPlanVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiParseService;
import com.example.reservation.common.PageResult;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.service.ClassroomService;
import com.example.reservation.vo.ClassroomVO;
import com.example.reservation.vo.OccupiedSlotVO;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预约规划服务单测（R15-1）：容量/类型过滤、冲突排除（含首尾相接不冲突）、排序与 Top3、
 * 澄清与空态、AI 关闭、日期校验、个人偏好加权。
 * 全部用 Mock 数据，不连 DB、不调模型。
 */
@ExtendWith(MockitoExtension.class)
class AiBookingPlanServiceImplTest {

    @Mock
    private AiConfigService aiConfigService;
    @Mock
    private AiParseService aiParseService;
    @Mock
    private ClassroomService classroomService;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ClassroomMapper classroomMapper;

    private AiBookingPlanServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiBookingPlanServiceImpl();
        ReflectionTestUtils.setField(service, "aiConfigService", aiConfigService);
        ReflectionTestUtils.setField(service, "aiParseService", aiParseService);
        ReflectionTestUtils.setField(service, "classroomService", classroomService);
        ReflectionTestUtils.setField(service, "reservationMapper", reservationMapper);
        ReflectionTestUtils.setField(service, "classroomMapper", classroomMapper);
    }

    /** 解析结果：明天 14:00-16:00，30 人，机房 */
    private AiParseVO parsed() {
        AiParseVO vo = new AiParseVO();
        vo.setDate(LocalDate.now().plusDays(1).toString());
        vo.setStartTime("14:00");
        vo.setEndTime("16:00");
        vo.setCapacity(30);
        vo.setRoomType(AiConstants.ROOM_TYPE_COMPUTER);
        vo.setPurpose("小组实验");
        return vo;
    }

    private ClassroomVO room(long id, String name, String building, int type, int capacity,
                             List<OccupiedSlotVO> occupied) {
        ClassroomVO vo = new ClassroomVO();
        vo.setId(id);
        vo.setName(name);
        vo.setBuilding(building);
        vo.setRoomNo("B" + id);
        vo.setType(type);
        vo.setCapacity(capacity);
        vo.setOccupiedSlots(occupied);
        return vo;
    }

    private OccupiedSlotVO slot(String start, String end) {
        OccupiedSlotVO vo = new OccupiedSlotVO();
        vo.setStartTime(start);
        vo.setEndTime(end);
        return vo;
    }

    private PageResult<ClassroomVO> page(List<ClassroomVO> rooms) {
        return new PageResult<>((long) rooms.size(), rooms);
    }

    private void stubQuery(List<ClassroomVO> rooms) {
        when(classroomService.pageClassroomsForStudent(anyLong(), anyLong(), isNull(), isNull(),
                any(), any())).thenReturn(page(rooms));
    }

    @Test
    @DisplayName("排序取 Top3，并统计更多可用数与排除数")
    void returnsTop3AndMoreAvailable() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiParseService.parse("帮我约明天下午 2 点到 4 点的机房")).thenReturn(parsed());
        stubQuery(List.of(
                room(1L, "机房一", "信息楼", 3, 40, List.of()),
                room(2L, "机房二", "信息楼", 3, 50, List.of()),
                room(3L, "机房三", "实验楼", 3, 35, List.of()),
                room(4L, "机房四", "实验楼", 3, 45, List.of()),
                room(5L, "机房五", "信息楼", 3, 60, List.of())));

        AiPlanVO plan = service.plan(7L, "帮我约明天下午 2 点到 4 点的机房");

        assertEquals(3, plan.getCandidates().size());
        assertEquals(2, plan.getMoreAvailable());
        assertEquals(0, plan.getExcludedCount());
        assertNull(plan.getClarify());
        assertEquals("14:00", plan.getStartTime());
        assertEquals("16:00", plan.getEndTime());
        assertEquals(Integer.valueOf(30), plan.getCapacity());
        assertEquals(AiConstants.ROOM_TYPE_COMPUTER, plan.getRoomType());
        assertTrue(plan.getCandidates().get(0).getReason().contains("空闲"));
    }

    @Test
    @DisplayName("容量不足与时段冲突的教室被排除；首尾相接不算冲突")
    void excludesCapacityAndConflict() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiParseService.parse("帮我约明天下午 2 点到 4 点的机房")).thenReturn(parsed());
        stubQuery(List.of(
                room(1L, "小机房", "信息楼", 3, 20, List.of()),                       // 容量不足
                room(2L, "被占用机房", "信息楼", 3, 40, List.of(slot("14:00", "16:00"))), // 完全重叠
                room(3L, "首尾相接机房", "信息楼", 3, 40, List.of(slot("12:00", "14:00"))), // 首尾相接：可约
                room(4L, "空闲机房", "信息楼", 3, 40, List.of())));

        AiPlanVO plan = service.plan(7L, "帮我约明天下午 2 点到 4 点的机房");

        assertEquals(2, plan.getCandidates().size());
        assertEquals(2, plan.getExcludedCount());
        assertTrue(plan.getCandidates().stream().anyMatch(c -> c.getName().equals("首尾相接机房")));
        assertTrue(plan.getCandidates().stream().anyMatch(c -> c.getName().equals("空闲机房")));
        assertNull(plan.getClarify());
    }

    @Test
    @DisplayName("解析失败：只给澄清文案，不检索教室")
    void clarifiesWhenParseFails() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        AiParseVO failed = new AiParseVO();
        failed.setDate(LocalDate.now().plusDays(1).toString());
        failed.setError("无法解析");
        when(aiParseService.parse(any())).thenReturn(failed);

        AiPlanVO plan = service.plan(7L, "帮我约个");

        assertEquals(AiConstants.PLAN_CLARIFY, plan.getClarify());
        assertTrue(plan.getCandidates().isEmpty());
        verify(classroomService, never()).pageClassroomsForStudent(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("该时段无可用教室：空候选 + 换时段提示")
    void clarifiesWhenNoCandidates() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiParseService.parse(any())).thenReturn(parsed());
        stubQuery(List.of(room(1L, "被占用机房", "信息楼", 3, 40, List.of(slot("13:00", "17:00")))));

        AiPlanVO plan = service.plan(7L, "帮我约明天下午 2 点到 4 点的机房");

        assertTrue(plan.getCandidates().isEmpty());
        assertEquals(AiConstants.PLAN_EMPTY, plan.getClarify());
        assertEquals(1, plan.getExcludedCount());
    }

    @Test
    @DisplayName("AI 关闭：返回 enabled=false 提示，不解析不检索")
    void disabledWhenAiOff() {
        when(aiConfigService.isAiEnabled()).thenReturn(false);

        AiPlanVO plan = service.plan(7L, "帮我约明天的机房");

        assertFalse(plan.isEnabled());
        assertEquals(AiConstants.AI_DISABLED_MESSAGE, plan.getMessage());
        assertTrue(plan.getCandidates().isEmpty());
        verify(aiParseService, never()).parse(any());
    }

    @Test
    @DisplayName("日期早于今天：按后端校验同口径拒绝")
    void rejectsPastDate() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        AiParseVO past = parsed();
        past.setDate(LocalDate.now().minusDays(1).toString());
        when(aiParseService.parse(any())).thenReturn(past);

        AiPlanVO plan = service.plan(7L, "帮我约昨天的机房");

        assertEquals(AiConstants.PLAN_DATE_PAST, plan.getClarify());
        assertTrue(plan.getCandidates().isEmpty());
        verify(classroomService, never()).pageClassroomsForStudent(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("个人偏好：常约楼栋的教室排序靠前")
    void boostsPreferredBuilding() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiParseService.parse(any())).thenReturn(parsed());
        stubQuery(List.of(
                room(1L, "实验楼机房", "实验楼", 3, 40, List.of()),
                room(2L, "信息楼机房", "信息楼", 3, 40, List.of())));
        Reservation history = new Reservation();
        history.setClassroomId(9L);
        when(reservationMapper.selectList(any())).thenReturn(List.of(history));
        Classroom preferred = new Classroom();
        preferred.setId(9L);
        preferred.setBuilding("信息楼");
        when(classroomMapper.selectBatchIds(any())).thenReturn(List.of(preferred));

        AiPlanVO plan = service.plan(7L, "帮我约明天下午的机房");

        assertEquals("信息楼机房", plan.getCandidates().get(0).getName());
        AiPlanCandidateVO first = plan.getCandidates().get(0);
        assertNotNull(first.getReason());
        assertTrue(first.getReason().contains("常约信息楼"));
    }

    @Test
    @DisplayName("无历史记录时不做偏好加分，仍返回候选")
    void worksWithoutHistory() {
        when(aiConfigService.isAiEnabled()).thenReturn(true);
        when(aiParseService.parse(any())).thenReturn(parsed());
        stubQuery(List.of(room(1L, "信息楼机房", "信息楼", 3, 40, List.of())));
        AiPlanVO plan = service.plan(null, "帮我约明天下午的机房");

        assertEquals(1, plan.getCandidates().size());
        assertEquals("信息楼机房", plan.getCandidates().get(0).getName());
    }
}
