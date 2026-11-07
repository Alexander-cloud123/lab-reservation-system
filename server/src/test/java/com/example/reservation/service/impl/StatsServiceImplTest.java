package com.example.reservation.service.impl;

import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.config.RedisCache;
import com.example.reservation.config.RedisProperties;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.example.reservation.vo.OverviewVO;
import com.example.reservation.vo.TimeDistributionVO;
import com.example.reservation.vo.TrendVO;
import com.example.reservation.vo.UsageRateVO;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据看板聚合逻辑单测（R5）
 *
 * <p>覆盖口径（与 StatsServiceImpl 类注释一致，避免「代码改了测试没改」的口径漂移）：
 * 使用率 = 窗口内占用小时 ÷（天数 × 14h），每日 14h 封顶；趋势按预约日期归属自然月、全部状态；
 * 时段分布按开始时间分桶、仅已通过，第 6 桶为「其他」；日期区间缺省近 30 天、上限 366 天。
 *
 * <p>Mapper 全部 mock，不依赖 MySQL；RedisCache 也 mock，用于验证缓存命中短路与回填 key 的口径。
 */
@ExtendWith(MockitoExtension.class)
class StatsServiceImplTest {

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private ClassroomMapper classroomMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private RedisCache redisCache;

    /** 深桩：getCache().getStatsTtlSeconds() 取默认 0，测试只关心 key 与内容 */
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private RedisProperties redisProperties;

    @InjectMocks
    private StatsServiceImpl statsService;

    /* ==================== overview ==================== */

    @Test
    @DisplayName("overview：四项计数分别取今日预约 / 待审核 / 教室总数 / 用户总数")
    void overview_countsFourMetrics() {
        when(reservationMapper.selectCount(any())).thenReturn(3L).thenReturn(2L);
        when(classroomMapper.selectCount(any())).thenReturn(12L);
        when(sysUserMapper.selectCount(any())).thenReturn(5L);

        OverviewVO vo = statsService.overview();

        assertEquals(3L, vo.getTodayReservationCount());
        assertEquals(2L, vo.getPendingAuditCount());
        assertEquals(12L, vo.getClassroomCount());
        assertEquals(5L, vo.getUserCount());
    }

    /* ==================== usageRate ==================== */

    @Test
    @DisplayName("usageRate：缓存命中时直接返回，不查库也不回填")
    void usageRate_cacheHit_shortCircuits() {
        UsageRateVO cached = usageVO(1L, 7.5, 50.0);
        doReturn(List.of(cached)).when(redisCache).getObject(anyString(), any());

        List<UsageRateVO> result = statsService.usageRate("2026-11-01", "2026-11-07");

        assertEquals(1, result.size());
        assertEquals(7.5, result.get(0).getApprovedHours(), 0.001);
        verifyNoInteractions(reservationMapper, classroomMapper);
        verify(redisCache, never()).setObject(anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("usageRate：占用先裁剪到 08:00-22:00，再按每日 14h 封顶（历史脏数据不会让使用率超 100%）")
    void usageRate_clipsWindowAndCapsDailyHours() {
        // 07:30-23:00 裁剪后正好 14h（840min）；再叠加一条 1h，合计 900min 仍按 14h 封顶
        Reservation clipped = reservation(1L, LocalDate.of(2026, 11, 2),
                LocalTime.of(7, 30), LocalTime.of(23, 0));
        Reservation extra = reservation(1L, LocalDate.of(2026, 11, 2),
                LocalTime.of(10, 0), LocalTime.of(11, 0));
        when(reservationMapper.selectList(any())).thenReturn(List.of(clipped, extra));
        when(classroomMapper.selectList(any())).thenReturn(List.of(classroom(1L, "A101"), classroom(2L, "A102")));

        List<UsageRateVO> result = statsService.usageRate("2026-11-01", "2026-11-07");

        // 7 天 × 14h = 98h 分母；14h / 98h ≈ 14.3%
        UsageRateVO room1 = findRoom(result, 1L);
        assertEquals(14.0, room1.getApprovedHours(), 0.001);
        assertEquals(14.3, room1.getUsageRate(), 0.001);
        // 无预约的教室占用 0、使用率 0，并排在后面
        UsageRateVO room2 = findRoom(result, 2L);
        assertEquals(0.0, room2.getApprovedHours(), 0.001);
        assertEquals(0.0, room2.getUsageRate(), 0.001);
        assertEquals(1L, result.get(0).getClassroomId());
    }

    @Test
    @DisplayName("usageRate：使用率相同时按占用小时倒序，再按教室 ID 升序，保证排行稳定")
    void usageRate_sortsByRateThenHoursThenId() {
        Reservation oneHour = reservation(1L, LocalDate.of(2026, 11, 1), 9, 10);
        Reservation twoHoursB = reservation(2L, LocalDate.of(2026, 11, 1), 9, 11);
        Reservation twoHoursC = reservation(3L, LocalDate.of(2026, 11, 2), 9, 11);
        when(reservationMapper.selectList(any())).thenReturn(List.of(oneHour, twoHoursB, twoHoursC));
        when(classroomMapper.selectList(any()))
                .thenReturn(List.of(classroom(3L, "C"), classroom(2L, "B"), classroom(1L, "A")));

        List<UsageRateVO> result = statsService.usageRate("2026-11-01", "2026-11-07");

        assertEquals(List.of(2L, 3L, 1L), result.stream().map(UsageRateVO::getClassroomId).toList());
        assertEquals(2.0, result.get(0).getUsageRate(), 0.001);
        assertEquals(1.0, result.get(2).getUsageRate(), 0.001);
    }

    @Test
    @DisplayName("usageRate：仅传开始日期时按开始 +29 天补全（30 天口径），缓存 key 记录归一化后的区间")
    void usageRate_startOnly_defaultsThirtyDayWindow() {
        Reservation oneHour = reservation(1L, LocalDate.of(2026, 11, 2), 9, 10);
        when(reservationMapper.selectList(any())).thenReturn(List.of(oneHour));
        when(classroomMapper.selectList(any())).thenReturn(List.of(classroom(1L, "A101")));

        List<UsageRateVO> result = statsService.usageRate("2026-11-01", null);

        // 30 天 × 14h = 420h；1h / 420h ≈ 0.2%
        assertEquals(0.2, result.get(0).getUsageRate(), 0.001);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setObject(key.capture(), any(), anyLong());
        assertEquals(RedisCache.STATS_KEY_PREFIX + "g0:usage-rate:2026-11-01:2026-11-30", key.getValue());
    }

    @Test
    @DisplayName("usageRate：两个日期都缺省时按近 30 天（今天 -29 ~ 今天）")
    void usageRate_noDate_defaultsRecentThirtyDays() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());
        when(classroomMapper.selectList(any())).thenReturn(List.of());

        List<UsageRateVO> result = statsService.usageRate(null, null);

        LocalDate today = LocalDate.now();
        assertTrue(result.isEmpty());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setObject(key.capture(), any(), anyLong());
        String expected = RedisCache.STATS_KEY_PREFIX + "g0:usage-rate:" + today.minusDays(29) + ":" + today;
        assertEquals(expected, key.getValue());
    }

    @Test
    @DisplayName("usageRate：开始日期晚于结束日期 → 400 业务异常")
    void usageRate_startAfterEnd_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> statsService.usageRate("2026-11-10", "2026-11-01"));
        assertEquals("开始日期不能晚于结束日期", ex.getMessage());
    }

    @Test
    @DisplayName("usageRate：跨度超过 366 天 → 400 业务异常")
    void usageRate_spanTooLong_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> statsService.usageRate("2025-01-01", "2026-11-01"));
        assertEquals("统计区间跨度不能超过 366 天", ex.getMessage());
    }

    @Test
    @DisplayName("usageRate：非法日期格式 → 400 业务异常")
    void usageRate_invalidDateFormat_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> statsService.usageRate("2026-13-01", null));
        assertEquals("日期格式不正确，应为 yyyy-MM-dd", ex.getMessage());
    }

    /* ==================== trend ==================== */

    @Test
    @DisplayName("trend：按预约日期归属自然月计数，月份升序，并回填缓存")
    void trend_groupsByMonthAscending() {
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservation(1L, LocalDate.of(2026, 11, 5), 9, 10),
                reservation(1L, LocalDate.of(2026, 10, 30), 9, 10),
                reservation(1L, LocalDate.of(2026, 11, 1), 9, 10),
                reservation(1L, LocalDate.of(2026, 12, 1), 9, 10)
        ));

        List<TrendVO> result = statsService.trend("2026-10-01", "2026-12-31");

        assertEquals(3, result.size());
        assertEquals("2026-10", result.get(0).getMonth());
        assertEquals(1L, result.get(0).getCount());
        assertEquals("2026-11", result.get(1).getMonth());
        assertEquals(2L, result.get(1).getCount());
        assertEquals("2026-12", result.get(2).getMonth());
        assertEquals(1L, result.get(2).getCount());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(redisCache).setObject(key.capture(), any(), anyLong());
        assertEquals(RedisCache.STATS_KEY_PREFIX + "g0:trend:2026-10-01:2026-12-31", key.getValue());
    }

    @Test
    @DisplayName("trend：缓存命中时直接返回，不查库")
    void trend_cacheHit_shortCircuits() {
        TrendVO cached = new TrendVO();
        cached.setMonth("2026-11");
        cached.setCount(9L);
        doReturn(List.of(cached)).when(redisCache).getObject(anyString(), any());

        List<TrendVO> result = statsService.trend("2026-11-01", "2026-11-07");

        assertEquals(1, result.size());
        assertEquals(9L, result.get(0).getCount());
        verifyNoInteractions(reservationMapper);
    }

    /* ==================== timeDistribution ==================== */

    @Test
    @DisplayName("timeDistribution：按开始时间分桶（前 5 桶 + 其他），占比保留 1 位小数")
    void timeDistribution_bucketsIncludingOther() {
        when(reservationMapper.selectList(any())).thenReturn(List.of(
                reservation(1L, LocalDate.of(2026, 11, 1), 8, 9, 5),
                reservation(1L, LocalDate.of(2026, 11, 1), 11, 12, 30),
                reservation(1L, LocalDate.of(2026, 11, 2), 13, 14),
                reservation(1L, LocalDate.of(2026, 11, 2), 20, 21)
        ));

        List<TimeDistributionVO> result = statsService.timeDistribution("2026-11-01", "2026-11-07");

        assertEquals(Constants.TIME_SLOT_BUCKET_COUNT, result.size());
        // 08:05 → 桶 0；11:30 → 桶 1；20:00 → 桶 4（19:00-21:00）；13:00 无对应区间 → 桶 5（其他）
        assertEquals(Constants.TIME_SLOT_LABELS[0], result.get(0).getSlot());
        assertEquals(1L, result.get(0).getCount());
        assertEquals(25.0, result.get(0).getPercentage(), 0.001);
        assertEquals(1L, result.get(1).getCount());
        assertEquals(25.0, result.get(1).getPercentage(), 0.001);
        assertEquals(0L, result.get(2).getCount());
        assertEquals(0.0, result.get(2).getPercentage(), 0.001);
        assertEquals(1L, result.get(5).getCount());
        assertEquals(25.0, result.get(5).getPercentage(), 0.001);
        assertEquals(1L, result.get(4).getCount());
        assertEquals("其他", result.get(5).getSlot());
    }

    @Test
    @DisplayName("timeDistribution：区间内无已通过预约 → 6 个桶全 0，不出现 NaN")
    void timeDistribution_noApproved_allZero() {
        when(reservationMapper.selectList(any())).thenReturn(List.of());

        List<TimeDistributionVO> result = statsService.timeDistribution("2026-11-01", "2026-11-07");

        assertEquals(6, result.size());
        for (TimeDistributionVO vo : result) {
            assertEquals(0L, vo.getCount());
            assertEquals(0.0, vo.getPercentage(), 0.001);
        }
    }

    /* ==================== 工具方法 ==================== */

    private static Classroom classroom(Long id, String name) {
        Classroom c = new Classroom();
        c.setId(id);
        c.setName(name);
        c.setBuilding("信息楼");
        c.setRoomNo("A" + id);
        c.setType(1);
        c.setCapacity(60);
        return c;
    }

    private static Reservation reservation(Long classroomId, LocalDate date, int startHour, int endHour) {
        return reservation(classroomId, date, startHour, endHour, 0);
    }

    private static Reservation reservation(Long classroomId, LocalDate date,
                                           LocalTime start, LocalTime end) {
        Reservation r = new Reservation();
        r.setId(1L);
        r.setClassroomId(classroomId);
        r.setUserId(2L);
        r.setReserveDate(date);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setStatus(Constants.RES_STATUS_APPROVED);
        return r;
    }

    private static Reservation reservation(Long classroomId, LocalDate date, int startHour, int endHour, int startMinute) {
        Reservation r = new Reservation();
        r.setId(1L);
        r.setClassroomId(classroomId);
        r.setUserId(2L);
        r.setReserveDate(date);
        r.setStartTime(LocalTime.of(startHour, startMinute));
        r.setEndTime(LocalTime.of(endHour, 0));
        r.setStatus(Constants.RES_STATUS_APPROVED);
        return r;
    }

    private static UsageRateVO usageVO(Long classroomId, double hours, double rate) {
        UsageRateVO vo = new UsageRateVO();
        vo.setClassroomId(classroomId);
        vo.setApprovedHours(hours);
        vo.setUsageRate(rate);
        return vo;
    }

    private static UsageRateVO findRoom(List<UsageRateVO> list, Long id) {
        return list.stream().filter(v -> id.equals(v.getClassroomId())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少教室 " + id));
    }
}
