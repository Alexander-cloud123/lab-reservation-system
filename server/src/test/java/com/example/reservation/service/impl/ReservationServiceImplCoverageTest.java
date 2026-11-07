package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.BatchAuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.vo.CalendarVO;
import com.example.reservation.vo.ConflictVO;
import com.example.reservation.vo.ReservationExportVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 预约业务分支覆盖补充（与 ReservationServiceImplTest 互补，不重复其 H1/H2/H3/M1 用例）
 *
 * <p>覆盖目标：参数校验各分支、审核/批量的授权与状态校验、导出上限保护、日历区间校验与可见性
 * （管理员/本人可见用途，其他学生不可见）、管理端关键词三种拼接分支。
 *
 * <p>纯 Mockito：不连数据库；Lambda 元数据在 {@code @BeforeAll} 手动初始化。
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplCoverageTest {

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private RedisCache redisCache;

    @Mock
    private ReservationConverter reservationConverter;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Reservation.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), SysUser.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(1L, "student01", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ==================== checkConflict ==================== */

    @Test
    @DisplayName("checkConflict：教室 ID / 日期 / 时间参数校验")
    void checkConflict_parameterValidation() {
        assertMessage("教室 ID 不能为空", () -> reservationService.checkConflict(null, "2026-11-08", "09:00", "10:00"));
        assertMessage("教室不存在", () -> reservationService.checkConflict(1L, "2026-11-08", "09:00", "10:00"));
        // 后续用例需要越过教室存在性校验，才检查日期 / 时间参数
        when(classroomMapper.selectById(1L)).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        assertMessage("预约日期不能为空", () -> reservationService.checkConflict(1L, " ", "09:00", "10:00"));
        assertMessage("预约时间不能为空", () -> reservationService.checkConflict(1L, "2026-11-08", "", "10:00"));
        assertMessage("开始时间必须早于结束时间",
                () -> reservationService.checkConflict(1L, "2026-11-08", "10:00", "09:00"));
    }

    @Test
    @DisplayName("checkConflict：与已通过预约重叠 → conflict=true 并给出冲突时段")
    void checkConflict_detectsOverlap() {
        when(classroomMapper.selectById(1L)).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(approved(1L, LocalTime.of(9, 0), LocalTime.of(11, 0))));

        ConflictVO vo = reservationService.checkConflict(1L, "2026-11-08", "10:00", "12:00");

        assertTrue(vo.getConflict());
        assertTrue(vo.getReason().contains("09:00-11:00"));
        assertTrue(vo.getReason().contains("已通过预约冲突"));
    }

    @Test
    @DisplayName("checkConflict：无重叠 → conflict=false，提示可预约")
    void checkConflict_noOverlapIsFree() {
        when(classroomMapper.selectById(1L)).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        when(reservationMapper.selectList(any())
        ).thenReturn(List.of(approved(1L, LocalTime.of(9, 0), LocalTime.of(11, 0))));

        ConflictVO vo = reservationService.checkConflict(1L, "2026-11-08", "11:00", "12:00");

        assertFalse(vo.getConflict());
        assertEquals("该时段可预约", vo.getReason());
    }

    /* ==================== createReservation 参数校验 ==================== */

    @Test
    @DisplayName("createReservation：教室为空 / 不存在 / 已停用 三种分支")
    void createReservation_roomValidation() {
        ReservationDTO noRoom = dto("2026-11-08", "09:00", "10:00");
        noRoom.setClassroomId(null);
        assertMessage("教室不能为空", () -> reservationService.createReservation(noRoom));

        // 第一次查询返回 null（教室不存在），第二次返回停用教室
        when(classroomMapper.selectOne(any())).thenReturn(null, room(1L, Constants.CLASSROOM_STATUS_DISABLED));
        assertMessage("教室不存在", () -> reservationService.createReservation(dto("2026-11-08", "09:00", "10:00")));
        assertMessage("该教室已停用", () -> reservationService.createReservation(dto("2026-11-08", "09:00", "10:00")));
    }

    @Test
    @DisplayName("createReservation：日期 / 时间 / 用途 / 起止先后 参数校验")
    void createReservation_fieldValidation() {
        when(classroomMapper.selectOne(any())).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        assertMessage("预约日期不能为空", () -> reservationService.createReservation(dto(" ", "09:00", "10:00")));
        assertMessage("预约时间不能为空", () -> reservationService.createReservation(dto("2026-11-08", "", "10:00")));
        assertMessage("预约用途不能为空", () -> reservationService.createReservation(dto("2026-11-08", "09:00", "10:00", "   ")));
        assertMessage("预约用途过长", () -> {
            ReservationDTO dto = dto("2026-11-08", "09:00", "10:00");
            dto.setPurpose("x".repeat(Constants.PURPOSE_MAX_LENGTH + 1));
            reservationService.createReservation(dto);
        });
        assertMessage("开始时间必须早于结束时间",
                () -> reservationService.createReservation(dto("2026-11-08", "10:00", "10:00")));
    }

    @Test
    @DisplayName("createReservation：后端二次冲突检测命中 → 拒绝提交")
    void createReservation_rejectsWhenConflict() {
        when(classroomMapper.selectOne(any())).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(approved(1L, LocalTime.of(9, 0), LocalTime.of(11, 0))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto("2026-11-08", "10:00", "12:00")));
        assertTrue(ex.getMessage().contains("冲突"));
    }

    @Test
    @DisplayName("createReservation：校验通过 → 插入待审核记录并失效缓存")
    void createReservation_persistsPendingAndInvalidatesCache() {
        when(classroomMapper.selectOne(any())).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        when(reservationMapper.selectList(any())).thenReturn(List.of());

        reservationService.createReservation(dto("2026-11-08", "09:00", "11:00"));

        verify(reservationMapper).insert(any(Reservation.class));
        // 无事务时 TransactionCacheInvalidator 立即执行，委托给 RedisCache#invalidateBusinessCaches
        verify(redisCache).invalidateBusinessCaches();
    }

    /* ==================== pageMine / pageManage / listExport ==================== */

    @Test
    @DisplayName("pageMine：非法状态与非法分页参数被拒绝")
    void pageMine_rejectsInvalidStatusAndPage() {
        assertMessage("状态参数不合法", () -> reservationService.pageMine(1, 10, 9));
        assertMessage("页码必须大于等于 1", () -> reservationService.pageMine(0, 10, null));
        assertMessage("每页条数必须在 1-500 之间", () -> reservationService.pageMine(1, 501, null));
    }

    @Test
    @DisplayName("pageManage：非法状态 / 日期倒序 / 教室不存在 被拒绝")
    void pageManage_rejectsInvalidFilters() {
        assertMessage("状态参数不合法", () -> reservationService.pageManage(1, 10, 8, null, null, null, null));

        when(reservationConverter.parseDateOrNull("2026-11-10")).thenReturn(LocalDate.of(2026, 11, 10));
        when(reservationConverter.parseDateOrNull("2026-11-01")).thenReturn(LocalDate.of(2026, 11, 1));
        assertMessage("开始日期不能晚于结束日期",
                () -> reservationService.pageManage(1, 10, null, "2026-11-10", "2026-11-01", null, null));

        when(classroomMapper.selectById(99L)).thenReturn(null);
        assertMessage("教室不存在", () -> reservationService.pageManage(1, 10, null, null, null, null, 99L));
    }

    @Test
    @DisplayName("pageManage：关键词三种命中分支与「无命中恒假条件」均返回空结果")
    void pageManage_keywordBranches() {
        Page<Reservation> empty = new Page<>();
        when(reservationMapper.selectPage(any(), any())).thenReturn(empty);

        // ① 用户与教室都无命中 → id=-1 恒假条件
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(classroomMapper.selectList(any())).thenReturn(List.of());
        assertTrue(reservationService.pageManage(1, 10, null, null, null, "查无此人", null).getRecords().isEmpty());

        // ② 仅用户命中
        SysUser user = new SysUser();
        user.setId(2L);
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(classroomMapper.selectList(any())).thenReturn(List.of());
        reservationService.pageManage(1, 10, null, null, null, "张三", null);

        // ③ 仅教室命中
        when(userMapper.selectList(any())).thenReturn(List.of());
        when(classroomMapper.selectList(any())).thenReturn(List.of(room(1L, Constants.CLASSROOM_STATUS_ENABLED)));
        reservationService.pageManage(1, 10, null, null, null, "A101", null);

        // ④ 用户与教室都命中
        when(userMapper.selectList(any())).thenReturn(List.of(user));
        when(classroomMapper.selectList(any())).thenReturn(List.of(room(1L, Constants.CLASSROOM_STATUS_ENABLED)));
        reservationService.pageManage(1, 10, null, null, null, "A101", null);

        verify(userMapper, org.mockito.Mockito.times(4)).selectList(any());
    }

    @Test
    @DisplayName("listExport：超过 10000 条上限时拒绝导出")
    void listExport_rejectsTooManyRows() {
        Page<Reservation> page = new Page<>(1, 10001);
        page.setTotal(10001);
        when(reservationMapper.selectPage(any(), any())).thenReturn(page);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.listExport(null, null, null, null, null));
        assertTrue(ex.getMessage().contains("10000"));
    }

    @Test
    @DisplayName("listExport：正常导出 → 批量预查后逐条转换")
    void listExport_convertsRows() {
        Reservation r = pending(1L);
        Page<Reservation> page = new Page<>(1, 10001);
        page.setRecords(List.of(r));
        page.setTotal(1);
        when(reservationMapper.selectPage(any(), any())).thenReturn(page);
        when(reservationConverter.batchUserMap(any())).thenReturn(java.util.Map.of());
        when(reservationConverter.batchClassroomMap(any())).thenReturn(java.util.Map.of());
        when(reservationConverter.toExportVO(any(), any(), any())).thenReturn(new ReservationExportVO());

        List<ReservationExportVO> result = reservationService.listExport(null, null, null, null, null);

        assertEquals(1, result.size());
        verify(reservationConverter).toExportVO(any(), any(), any());
    }

    /* ==================== cancelReservation ==================== */

    @Test
    @DisplayName("cancelReservation：null ID / 不存在 / 非本人 / 已驳回状态 均拒绝")
    void cancelReservation_rejectsInvalidCases() {
        assertMessage("预约 ID 不能为空", () -> reservationService.cancelReservation(null));

        when(reservationMapper.selectById(9L)).thenReturn(null);
        assertMessage("预约不存在", () -> reservationService.cancelReservation(9L));

        Reservation others = pending(1L);
        others.setUserId(2L);
        when(reservationMapper.selectById(1L)).thenReturn(others);
        assertMessage("只能取消自己的预约", () -> reservationService.cancelReservation(1L));

        Reservation rejected = pending(1L);
        rejected.setStatus(Constants.RES_STATUS_REJECTED);
        when(reservationMapper.selectById(1L)).thenReturn(rejected);
        assertMessage("当前状态不可取消", () -> reservationService.cancelReservation(1L));
    }

    /* ==================== auditReservation ==================== */

    @Test
    @DisplayName("auditReservation：ID 为空 / 状态非法 / 驳回缺备注 被拒绝")
    void auditReservation_validationsBeforeDatabase() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        assertMessage("预约 ID 不能为空", () -> reservationService.auditReservation(null, audit(Constants.RES_STATUS_APPROVED, null)));
        assertMessage("审核状态参数不合法", () -> reservationService.auditReservation(1L, audit(Constants.RES_STATUS_CANCELED, null)));
        assertMessage("驳回必须填写审核备注", () -> reservationService.auditReservation(1L, audit(Constants.RES_STATUS_REJECTED, " ")));
    }

    @Test
    @DisplayName("auditReservation：预约不存在 / 非待审核 均拒绝")
    void auditReservation_rejectsMissingOrNonPending() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        when(reservationMapper.selectById(1L)).thenReturn(null);
        assertMessage("预约不存在", () -> reservationService.auditReservation(1L, audit(Constants.RES_STATUS_APPROVED, null)));

        Reservation approved = pending(1L);
        approved.setStatus(Constants.RES_STATUS_APPROVED);
        when(reservationMapper.selectById(1L)).thenReturn(approved);
        assertMessage("仅待审核状态的预约可审核",
                () -> reservationService.auditReservation(1L, audit(Constants.RES_STATUS_APPROVED, null)));
    }

    @Test
    @DisplayName("auditReservation：通过前复审命中已通过预约 → 拒绝通过")
    void auditReservation_rejectsWhenReviewConflict() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        Reservation target = pending(1L);
        target.setStartTime(LocalTime.of(10, 0));
        target.setEndTime(LocalTime.of(12, 0));
        when(reservationMapper.selectById(1L)).thenReturn(target);
        when(classroomMapper.selectOne(any())).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(approved(1L, LocalTime.of(9, 0), LocalTime.of(11, 0))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.auditReservation(1L, audit(Constants.RES_STATUS_APPROVED, null)));
        assertTrue(ex.getMessage().contains("时间冲突"));
    }

    @Test
    @DisplayName("auditReservation：条件更新影响 0 行 → 提示刷新重试")
    void auditReservation_rejectsWhenStatusChanged() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        when(reservationMapper.selectById(1L)).thenReturn(pending(1L));
        when(reservationMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.auditReservation(1L, audit(Constants.RES_STATUS_REJECTED, "冲突")));
        assertTrue(ex.getMessage().contains("状态已变更"));
    }

    @Test
    @DisplayName("auditReservation：驳回成功写入备注并失效缓存")
    void auditReservation_rejectPersistsRemark() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        when(reservationMapper.selectById(1L)).thenReturn(pending(1L));
        when(reservationMapper.update(any(), any())).thenReturn(1);

        reservationService.auditReservation(1L, audit(Constants.RES_STATUS_REJECTED, "时段冲突"));

        verify(reservationMapper).update(any(), any());
        verify(redisCache).invalidateBusinessCaches();
    }

    /* ==================== batchAudit ==================== */

    @Test
    @DisplayName("batchAudit：空 ID 列表 / 状态非法 / 驳回缺备注 被拒绝")
    void batchAudit_validations() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        assertMessage("预约 ID 列表不能为空", () -> reservationService.batchAudit(batch(List.of(), Constants.RES_STATUS_APPROVED, null)));
        assertMessage("审核状态参数不合法", () -> reservationService.batchAudit(batch(List.of(1L), Constants.RES_STATUS_CANCELED, null)));
        assertMessage("驳回必须填写审核备注", () -> reservationService.batchAudit(batch(List.of(1L), Constants.RES_STATUS_REJECTED, " ")));
    }

    @Test
    @DisplayName("batchAudit：批量驳回不复查冲突，按条件更新返回实际条数")
    void batchAudit_rejectBatchReturnsUpdatedCount() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        when(reservationMapper.update(any(), any())).thenReturn(2);

        int updated = reservationService.batchAudit(batch(List.of(1L, 2L), Constants.RES_STATUS_REJECTED, "材料不全"));

        assertEquals(2, updated);
        verify(redisCache).invalidateBusinessCaches();
    }

    @Test
    @DisplayName("batchAudit：批量通过时与已通过预约冲突 → 整批拒绝并点名 ID")
    void batchAudit_rejectsConflictWithApproved() {
        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        Reservation target = pending(101L);
        target.setStartTime(LocalTime.of(10, 0));
        target.setEndTime(LocalTime.of(12, 0));
        when(reservationMapper.selectBatchIds(any())).thenReturn(List.of(target));
        when(classroomMapper.selectOne(any())).thenReturn(room(1L, Constants.CLASSROOM_STATUS_ENABLED));
        // 第一次 selectList：加锁后的当前读目标；第二次：该教室该日期的已通过列表
        when(reservationMapper.selectList(any()))
                .thenReturn(List.of(target))
                .thenReturn(List.of(approved(1L, LocalTime.of(9, 0), LocalTime.of(11, 0))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.batchAudit(batch(List.of(101L), Constants.RES_STATUS_APPROVED, null)));
        assertTrue(ex.getMessage().contains("时间冲突"));
        assertTrue(ex.getMessage().contains("101"));
        verify(reservationMapper, never()).update(any(), any());
    }

    /* ==================== listCalendar ==================== */

    @Test
    @DisplayName("listCalendar：缺省日期 / 倒序 / 超跨度 / 教室不存在 被拒绝")
    void listCalendar_rangeValidation() {
        assertMessage(Constants.CALENDAR_START_REQUIRED_MSG, () -> reservationService.listCalendar(null, " ", "2026-11-30"));
        assertMessage(Constants.CALENDAR_END_REQUIRED_MSG, () -> reservationService.listCalendar(null, "2026-11-01", " "));
        assertMessage("开始日期不能晚于结束日期",
                () -> reservationService.listCalendar(null, "2026-11-30", "2026-11-01"));
        assertMessage(Constants.CALENDAR_RANGE_MSG,
                () -> reservationService.listCalendar(null, "2024-01-01", "2026-01-01"));

        when(classroomMapper.selectById(99L)).thenReturn(null);
        assertMessage("教室不存在", () -> reservationService.listCalendar(99L, "2026-11-01", "2026-11-30"));
    }

    @Test
    @DisplayName("listCalendar：区间无预约 → 不查教室表，返回空列表")
    void listCalendar_emptyRange() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());

        List<CalendarVO> result = reservationService.listCalendar(null, "2026-11-01", "2026-11-30");

        assertTrue(result.isEmpty());
        verify(classroomMapper, never()).selectBatchIds(any());
    }

    @Test
    @DisplayName("listCalendar：学生只能看到自己的用途；管理员可见全部用途")
    void listCalendar_visibilityByRole() {
        Reservation mine = pending(1L);
        mine.setUserId(1L);
        mine.setPurpose("我的自习");
        Reservation others = pending(2L);
        others.setUserId(2L);
        others.setPurpose("别人的答辩");
        when(reservationMapper.selectList(any())).thenReturn(List.of(mine, others));
        when(classroomMapper.selectBatchIds(any())).thenReturn(List.of(room(1L, Constants.CLASSROOM_STATUS_ENABLED)));

        List<CalendarVO> studentView = reservationService.listCalendar(null, "2026-11-01", "2026-11-30");
        assertEquals(2, studentView.size());
        assertTrue(studentView.get(0).getMine());
        assertEquals("我的自习", studentView.get(0).getPurpose());
        assertFalse(studentView.get(1).getMine());
        assertNull(studentView.get(1).getPurpose());
        assertEquals("A101", studentView.get(0).getClassroomName());

        UserContext.set(1L, "admin", Constants.ROLE_ADMIN);
        List<CalendarVO> adminView = reservationService.listCalendar(null, "2026-11-01", "2026-11-30");
        assertEquals("别人的答辩", adminView.get(1).getPurpose());
    }

    /* ==================== 工具方法 ==================== */

    private static Classroom room(Long id, int status) {
        Classroom c = new Classroom();
        c.setId(id);
        c.setName("A101");
        c.setBuilding("信息楼");
        c.setRoomNo("A1");
        c.setStatus(status);
        return c;
    }

    private static Reservation pending(long id) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setUserId(1L);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.of(2026, 11, 8));
        r.setStartTime(LocalTime.of(9, 0));
        r.setEndTime(LocalTime.of(11, 0));
        r.setStatus(Constants.RES_STATUS_PENDING);
        return r;
    }

    private static Reservation approved(long classroomId, LocalTime start, LocalTime end) {
        Reservation r = new Reservation();
        r.setId(99L);
        r.setClassroomId(classroomId);
        r.setReserveDate(LocalDate.of(2026, 11, 8));
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(Constants.RES_STATUS_APPROVED);
        return r;
    }

    private static ReservationDTO dto(String date, String start, String end) {
        ReservationDTO dto = new ReservationDTO();
        dto.setClassroomId(1L);
        dto.setReserveDate(date);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setPurpose("自习");
        return dto;
    }

    private static ReservationDTO dto(String date, String start, String end, String purpose) {
        ReservationDTO dto = dto(date, start, end);
        dto.setPurpose(purpose);
        return dto;
    }

    private static AuditDTO audit(int status, String remark) {
        AuditDTO dto = new AuditDTO();
        dto.setStatus(status);
        dto.setAuditRemark(remark);
        return dto;
    }

    private static BatchAuditDTO batch(List<Long> ids, int status, String remark) {
        BatchAuditDTO dto = new BatchAuditDTO();
        dto.setIds(ids);
        dto.setStatus(status);
        dto.setAuditRemark(remark);
        return dto;
    }

    /** 断言异常类型与消息包含关系，减少重复的 assertThrows 样板 */
    private static void assertMessage(String expectedPart, Runnable action) {
        BusinessException ex = assertThrows(BusinessException.class, action::run);
        assertTrue(ex.getMessage().contains(expectedPart), "实际消息：" + ex.getMessage());
    }
}
