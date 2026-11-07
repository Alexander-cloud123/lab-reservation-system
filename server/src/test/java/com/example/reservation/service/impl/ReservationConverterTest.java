package com.example.reservation.service.impl;

import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.vo.ReservationExportVO;
import com.example.reservation.vo.ReservationManageVO;
import com.example.reservation.vo.ReservationVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 预约 VO 转换器单测（R6 导出 / R9 重构后外移的转换逻辑）
 *
 * <p>重点回归两类历史缺陷：
 * ① 批量预查必须对空集合 / 空 ID 短路，不能触发 selectBatchIds（N+1 与 mapper 空参数异常）；
 * ② 导出状态文案必须与前端状态标签一致（0 待审核 / 1 已通过 / 2 已驳回 / 3 已取消 / 其他 未知 / null 空串）。
 */
@ExtendWith(MockitoExtension.class)
class ReservationConverterTest {

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper userMapper;

    @InjectMocks
    private ReservationConverter converter;

    /* ==================== 批量预查 ==================== */

    @Test
    @DisplayName("batchClassroomMap：null / 空列表 → 空 Map，不查库")
    void batchClassroomMap_emptyList_returnsEmpty() {
        assertTrue(converter.batchClassroomMap(null).isEmpty());
        assertTrue(converter.batchClassroomMap(List.of()).isEmpty());
        verifyNoInteractions(classroomMapper);
    }

    @Test
    @DisplayName("batchClassroomMap：列表里 classroomId 全为 null → 空 Map，不查库")
    void batchClassroomMap_allNullIds_returnsEmpty() {
        Reservation r = new Reservation();
        assertTrue(converter.batchClassroomMap(List.of(r)).isEmpty());
        verify(classroomMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("batchClassroomMap：按 ID 去重后批量查询，并转成 id → 教室 Map")
    void batchClassroomMap_dedupesIdsAndMaps() {
        doReturn(List.of(classroom(1L, "A101"), classroom(2L, "A102")))
                .when(classroomMapper).selectBatchIds(any());

        Reservation r1 = reservation(1L, 2L);
        Reservation r2 = reservation(1L, 3L);
        Map<Long, Classroom> map = converter.batchClassroomMap(List.of(r1, r2));

        assertEquals(2, map.size());
        assertEquals("A101", map.get(1L).getName());
        assertEquals("A102", map.get(2L).getName());
    }

    @Test
    @DisplayName("batchUserMap：空列表与全 null 用户 ID 均短路")
    void batchUserMap_emptyAndNullIds() {
        Reservation r = new Reservation();
        assertTrue(converter.batchUserMap(null).isEmpty());
        assertTrue(converter.batchUserMap(List.of(r)).isEmpty());
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("batchUserMap：按 ID 批量查询并转成 id → 用户 Map")
    void batchUserMap_mapsUsers() {
        doReturn(List.of(user(2L, "zhangsan", "张三"))).when(userMapper).selectBatchIds(any());

        Map<Long, SysUser> map = converter.batchUserMap(List.of(reservation(1L, 2L)));

        assertEquals(1, map.size());
        assertEquals("张三", map.get(2L).getName());
        assertEquals("zhangsan", map.get(2L).getUsername());
    }

    /* ==================== 学生端 VO ==================== */

    @Test
    @DisplayName("toReservationVO：字段透传 + 时间格式化为 HH:mm + 教室信息来自预查 Map")
    void toReservationVO_mapsAllFields() {
        Reservation r = new Reservation();
        r.setId(9L);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.of(2026, 11, 7));
        r.setStartTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(11, 30));
        r.setPurpose("答辩彩排");
        r.setStatus(Constants.RES_STATUS_APPROVED);
        r.setAuditRemark("通过");
        r.setAuditTime(LocalDateTime.of(2026, 11, 6, 10, 0));

        ReservationVO vo = converter.toReservationVO(r, Map.of(1L, classroom(1L, "A101")));

        assertEquals(9L, vo.getId());
        assertEquals(1L, vo.getClassroomId());
        assertEquals("A101", vo.getClassroomName());
        assertEquals("信息楼", vo.getBuilding());
        assertEquals("A1", vo.getRoomNo());
        assertEquals(LocalDate.of(2026, 11, 7), vo.getReserveDate());
        assertEquals("09:00", vo.getStartTime());
        assertEquals("11:30", vo.getEndTime());
        assertEquals("答辩彩排", vo.getPurpose());
        assertEquals(Constants.RES_STATUS_APPROVED, vo.getStatus());
        assertEquals("通过", vo.getAuditRemark());
    }

    @Test
    @DisplayName("toReservationVO：教室 Map 里没有该教室时，教室展示字段保持 null（不抛异常）")
    void toReservationVO_missingRoom_leavesRoomFieldsNull() {
        Reservation r = new Reservation();
        r.setClassroomId(99L);
        r.setStartTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(10, 0));

        ReservationVO vo = converter.toReservationVO(r, Map.of());

        assertNull(vo.getClassroomName());
        assertNull(vo.getBuilding());
        assertNull(vo.getRoomNo());
    }

    /* ==================== 管理端 VO ==================== */

    @Test
    @DisplayName("toManageVO：补全预约人账号 / 姓名与审核信息")
    void toManageVO_includesUserAndAuditInfo() {
        Reservation r = new Reservation();
        r.setId(10L);
        r.setUserId(2L);
        r.setClassroomId(1L);
        r.setStartTime(LocalTime.of(8, 0));
        r.setEndTime(LocalTime.of(9, 0));
        r.setStatus(Constants.RES_STATUS_PENDING);
        r.setAuditorId(1L);
        r.setAuditTime(LocalDateTime.of(2026, 11, 6, 10, 30));

        ReservationManageVO vo = converter.toManageVO(r,
                Map.of(2L, user(2L, "zhangsan", "张三")),
                Map.of(1L, classroom(1L, "A101")));

        assertEquals(10L, vo.getId());
        assertEquals(2L, vo.getUserId());
        assertEquals("zhangsan", vo.getUserAccount());
        assertEquals("张三", vo.getUserName());
        assertEquals("A101", vo.getClassroomName());
        assertEquals(1L, vo.getAuditorId());
        assertEquals("08:00", vo.getStartTime());
        assertEquals(LocalDateTime.of(2026, 11, 6, 10, 30), vo.getAuditTime());
    }

    @Test
    @DisplayName("toManageVO：用户 / 教室 Map 缺项时对应展示字段为 null")
    void toManageVO_missingUserAndRoom_leavesFieldsNull() {
        Reservation r = new Reservation();
        r.setUserId(99L);
        r.setClassroomId(98L);
        r.setStartTime(LocalTime.of(8, 0));
        r.setEndTime(LocalTime.of(9, 0));

        ReservationManageVO vo = converter.toManageVO(r, Map.of(), Map.of());

        assertNull(vo.getUserAccount());
        assertNull(vo.getUserName());
        assertNull(vo.getClassroomName());
    }

    /* ==================== 导出 VO ==================== */

    @Test
    @DisplayName("toExportVO：状态文案与前端标签一致（0/1/2/3/未知/null）")
    void toExportVO_statusTextMatchesFrontendLabels() {
        assertEquals("待审核", exportVO(Constants.RES_STATUS_PENDING).getStatusText());
        assertEquals("已通过", exportVO(Constants.RES_STATUS_APPROVED).getStatusText());
        assertEquals("已驳回", exportVO(Constants.RES_STATUS_REJECTED).getStatusText());
        assertEquals("已取消", exportVO(Constants.RES_STATUS_CANCELED).getStatusText());
        assertEquals("未知", exportVO(9).getStatusText());
        assertEquals("", exportVO(null).getStatusText());
    }

    @Test
    @DisplayName("toExportVO：日期与时间统一字符串输出，auditTime 为空时输出 null")
    void toExportVO_formatsDateAndTimes() {
        Reservation r = new Reservation();
        r.setId(11L);
        r.setUserId(2L);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.of(2026, 11, 7));
        r.setStartTime(LocalTime.of(19, 30));
        r.setEndTime(LocalTime.of(21, 0));
        r.setStatus(Constants.RES_STATUS_APPROVED);
        r.setAuditTime(LocalDateTime.of(2026, 11, 6, 10, 0, 5));
        r.setCreateTime(LocalDateTime.of(2026, 11, 5, 20, 0, 0));

        ReservationExportVO vo = converter.toExportVO(r,
                Map.of(2L, user(2L, "zhangsan", "张三")),
                Map.of(1L, classroom(1L, "A101")));

        assertEquals("2026-11-07", vo.getReserveDate());
        assertEquals("19:30", vo.getStartTime());
        assertEquals("21:00", vo.getEndTime());
        assertEquals("2026-11-06 10:00:05", vo.getAuditTime());
        assertEquals("2026-11-05 20:00:00", vo.getCreateTime());
        assertEquals("zhangsan", vo.getUserAccount());
        assertEquals("张三", vo.getUserName());
        assertEquals("A101", vo.getClassroomName());
    }

    @Test
    @DisplayName("toExportVO：auditTime 为空时输出 null（不抛空指针）")
    void toExportVO_nullAuditTime_returnsNullString() {
        Reservation r = new Reservation();
        r.setUserId(2L);
        r.setClassroomId(1L);
        r.setStartTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(10, 0));

        ReservationExportVO vo = converter.toExportVO(r, Map.of(), Map.of());

        assertNull(vo.getAuditTime());
        assertNull(vo.getCreateTime());
    }

    /* ==================== 日期解析 ==================== */

    @Test
    @DisplayName("parseDateOrNull：空白 → null；合法 → 日期；非法 → 400 业务异常")
    void parseDateOrNull_behaviour() {
        assertNull(converter.parseDateOrNull(null));
        assertNull(converter.parseDateOrNull("   "));
        assertEquals(LocalDate.of(2026, 11, 7), converter.parseDateOrNull("2026-11-07"));
        assertThrows(BusinessException.class, () -> converter.parseDateOrNull("2026-02-30"));
    }

    /* ==================== 工具方法 ==================== */

    private ReservationExportVO exportVO(Integer status) {
        Reservation r = new Reservation();
        r.setUserId(2L);
        r.setClassroomId(1L);
        r.setStatus(status);
        r.setStartTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(10, 0));
        return converter.toExportVO(r, Map.of(), Map.of());
    }

    private static Reservation reservation(Long classroomId, Long userId) {
        Reservation r = new Reservation();
        r.setClassroomId(classroomId);
        r.setUserId(userId);
        return r;
    }

    private static Classroom classroom(Long id, String name) {
        Classroom c = new Classroom();
        c.setId(id);
        c.setName(name);
        c.setBuilding("信息楼");
        c.setRoomNo("A" + id);
        return c;
    }

    private static SysUser user(Long id, String username, String name) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername(username);
        u.setName(name);
        return u;
    }
}
