package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.UserContext;
import com.example.reservation.config.RedisCache;
import com.example.reservation.dto.AuditDTO;
import com.example.reservation.dto.BatchAuditDTO;
import com.example.reservation.dto.ReservationDTO;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预约核心逻辑单元测试（L1 修复：审查报告指出"零自动化测试"，先补齐最核心的冲突/审核/取消规则回归集）。
 * 覆盖软件审查修复项：
 *  - H1 批量审核批内互斥（同批重叠整批拒绝）
 *  - H2 审核通过路径执行复审加锁读且无冲突时正常通过
 *  - H3 可预约时段窗口后端强制（08:00-22:00 + 单次时长上限）
 *  - M1 状态流转条件更新（并发状态变更后写者拒绝，不覆盖前写）
 *
 * @author reservation-team
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private RedisCache redisCache;

    /** 转换器外移后新增依赖：现有 6 条用例均不经过转换方法，mock 空实现即可 */
    @Mock
    private ReservationConverter reservationConverter;

    @InjectMocks
    private ReservationServiceImpl reservationService;

    /**
     * 纯 Mockito 环境不经过 Spring 启动，MyBatis-Plus 的 Lambda 元数据（Reservation::getXxx 解析）
     * 需手动初始化，否则构造 LambdaQueryWrapper/LambdaUpdateWrapper 即抛
     * "can not find lambda cache for this entity"
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Reservation.class);
        TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(configuration, ""), Classroom.class);
    }

    @BeforeEach
    void setUp() {
        // 模拟登录态（学生 1 号）
        UserContext.set(1L, "student01", Constants.ROLE_STUDENT);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /* ===== H3：可预约时段窗口后端强制（08:00-22:00 + 单次时长上限） ===== */

    @Test
    @DisplayName("H3：开始时间早于 08:00 被拒绝")
    void createReservation_rejectsStartBeforeWindow() {
        stubEnabledRoom();
        ReservationDTO dto = dto("2026-09-20", "06:00", "08:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("08:00"));
    }

    @Test
    @DisplayName("H3：结束时间晚于 22:00 被拒绝")
    void createReservation_rejectsEndAfterWindow() {
        stubEnabledRoom();
        ReservationDTO dto = dto("2026-09-20", "21:00", "23:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("22:00"));
    }

    @Test
    @DisplayName("H3：单次时长超过 8 小时上限被拒绝")
    void createReservation_rejectsTooLongDuration() {
        stubEnabledRoom();
        ReservationDTO dto = dto("2026-09-20", "08:00", "21:00");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.createReservation(dto));
        assertTrue(ex.getMessage().contains("8 小时"));
    }

    /* ===== H1：批量审核批内互斥（同批重叠必须整批拒绝） ===== */

    @Test
    @DisplayName("H1：同批两条时段重叠的待审核记录被整批拒绝")
    void batchAudit_rejectsOverlapWithinBatch() {
        Reservation r1 = reservation(101L, LocalTime.of(9, 0), LocalTime.of(11, 0));
        Reservation r2 = reservation(102L, LocalTime.of(10, 0), LocalTime.of(12, 0));
        when(reservationMapper.selectBatchIds(any())).thenReturn(List.of(r1, r2));

        BatchAuditDTO dto = new BatchAuditDTO();
        dto.setIds(List.of(101L, 102L));
        dto.setStatus(Constants.RES_STATUS_APPROVED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.batchAudit(dto));
        assertTrue(ex.getMessage().contains("时段重叠"));
        // 错误信息应点名冲突双方 ID，便于管理员单独处理
        assertTrue(ex.getMessage().contains("101"));
    }

    /* ===== H2：审核通过路径执行复审（加锁读已通过列表）且无冲突时正常通过 ===== */

    @Test
    @DisplayName("H2：审核通过无冲突时完成条件更新")
    void auditReservation_approveWhenNoConflict() {
        Reservation r = reservation(1L, LocalTime.of(9, 0), LocalTime.of(11, 0));
        when(reservationMapper.selectById(1L)).thenReturn(r);
        when(classroomMapper.selectOne(any())).thenReturn(new Classroom());
        // 无已通过记录 → 复审无冲突
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(reservationMapper.update(any(), any())).thenReturn(1);

        AuditDTO dto = new AuditDTO();
        dto.setStatus(Constants.RES_STATUS_APPROVED);

        reservationService.auditReservation(1L, dto);

        // 复审加锁读已执行（FOR UPDATE 查询已通过列表）+ 条件更新已执行
        verify(reservationMapper).selectList(any());
        verify(reservationMapper).update(any(), any());
    }

    /* ===== M1：条件更新 + 影响行数校验（并发状态变更后写者不覆盖） ===== */

    @Test
    @DisplayName("M1：取消时状态已被并发变更（影响 0 行）则拒绝并提示刷新")
    void cancelReservation_rejectsWhenStatusChanged() {
        Reservation r = reservation(1L, LocalTime.of(10, 0), LocalTime.of(12, 0));
        r.setReserveDate(LocalDate.now().plusDays(1));
        when(reservationMapper.selectById(1L)).thenReturn(r);
        // 模拟并发：条件更新影响 0 行（状态已被管理员审核/学生先取消）
        when(reservationMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reservationService.cancelReservation(1L));
        assertTrue(ex.getMessage().contains("状态已变更"));
    }

    /* ===== 测试数据构造 ===== */

    /** 预置一间启用中的教室（createReservation 先锁教室行再校验时段，必须 stub 教室查询） */
    private void stubEnabledRoom() {
        Classroom room = new Classroom();
        room.setId(1L);
        room.setStatus(Constants.CLASSROOM_STATUS_ENABLED);
        when(classroomMapper.selectOne(any())).thenReturn(room);
    }

    private ReservationDTO dto(String date, String start, String end) {
        ReservationDTO dto = new ReservationDTO();
        dto.setClassroomId(1L);
        dto.setReserveDate(date);
        dto.setStartTime(start);
        dto.setEndTime(end);
        dto.setPurpose("自习");
        return dto;
    }

    private Reservation reservation(long id, LocalTime start, LocalTime end) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setClassroomId(1L);
        r.setReserveDate(LocalDate.of(2026, 9, 20));
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(Constants.RES_STATUS_PENDING);
        r.setUserId(1L);
        return r;
    }
}
