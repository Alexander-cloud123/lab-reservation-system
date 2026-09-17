package com.example.reservation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.config.RedisCache;
import com.example.reservation.config.RedisProperties;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.service.StatsService;
import com.example.reservation.vo.TimeDistributionVO;
import com.example.reservation.vo.TrendVO;
import com.example.reservation.vo.UsageRateVO;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统计业务实现（R5 数据看板，只读）
 * 口径（写进交付说明，测试按此断言与库中数据一致）：
 *  1. 使用率 = 区间内该教室【已通过】预约占用小时数 ÷（区间天数 × 每日可预约时长 14h）× 100，保留 1 位小数；
 *  2. 月度趋势 = 按预约日期归属自然月统计区间内【全部状态】预约条数；
 *  3. 时段分布 = 按开始时间分桶（08-10/10-12/14-16/16-18/19-21/其他）统计【已通过】预约占比。
 * 日期区间缺省逻辑：全缺省 = 近 30 天；仅一个缺省 = 以另一个为基准补 29 天；跨度上限 366 天。
 *
 * @author reservation-team
 */
@Service
public class StatsServiceImpl implements StatsService {

    /** 时段桶左边界（含），与 Constants.TIME_SLOT_LABELS 一一对应（前 5 桶，第 6 桶「其他」兜底） */
    private static final LocalTime[] SLOT_STARTS = {
            LocalTime.of(8, 0), LocalTime.of(10, 0),
            LocalTime.of(14, 0), LocalTime.of(16, 0), LocalTime.of(19, 0)
    };
    /** 时段桶右边界（不含） */
    private static final LocalTime[] SLOT_ENDS = {
            LocalTime.of(10, 0), LocalTime.of(12, 0),
            LocalTime.of(16, 0), LocalTime.of(18, 0), LocalTime.of(21, 0)
    };

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Resource
    private RedisCache redisCache;

    @Resource
    private RedisProperties redisProperties;

    @Override
    public List<UsageRateVO> usageRate(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        // Redis 加分项：看板聚合结果读缓存（命中直接返回，未命中回源后回填）；Redis 异常时 getObject 返回 null 自动回源
        String cacheKey = statsCacheKey("usage-rate", range);
        List<UsageRateVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 区间内【已通过】预约（使用率口径：实际占用）
        List<Reservation> approved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));

        // 教室 ID → 占用小时数（分钟转小时，保留 1 位）
        Map<Long, Double> hoursByClassroom = approved.stream().collect(Collectors.groupingBy(
                Reservation::getClassroomId,
                Collectors.summingDouble(r -> ChronoUnit.MINUTES.between(r.getStartTime(), r.getEndTime()) / 60.0)));

        long days = ChronoUnit.DAYS.between(range.start, range.end) + 1;
        double denominatorHours = days * Constants.DAILY_AVAILABLE_HOURS;

        List<Classroom> rooms = classroomMapper.selectList(new LambdaQueryWrapper<Classroom>());
        List<UsageRateVO> result = new ArrayList<>(rooms.size());
        for (Classroom room : rooms) {
            double hours = hoursByClassroom.getOrDefault(room.getId(), 0.0);
            UsageRateVO vo = new UsageRateVO();
            vo.setClassroomId(room.getId());
            vo.setName(room.getName());
            vo.setBuilding(room.getBuilding());
            vo.setRoomNo(room.getRoomNo());
            vo.setType(room.getType());
            vo.setCapacity(room.getCapacity());
            vo.setApprovedHours(round1(hours));
            // 使用率 = 占用小时 /（天数 × 每日可预约时长），超 100 不封顶（口径一致即可复算）
            vo.setUsageRate(round1(denominatorHours == 0 ? 0 : hours / denominatorHours * 100));
            result.add(vo);
        }
        // 排行：使用率倒序 → 占用小时倒序 → 教室 ID 升序（稳定可断言）
        result.sort(Comparator.comparing(UsageRateVO::getUsageRate, Comparator.reverseOrder())
                .thenComparing(UsageRateVO::getApprovedHours, Comparator.reverseOrder())
                .thenComparing(UsageRateVO::getClassroomId));
        // 回填缓存（短 TTL；预约/教室变更时由业务写操作主动失效，保证一致性）
        redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
        return result;
    }

    @Override
    public List<TrendVO> trend(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        // Redis 加分项：看板聚合结果读缓存（命中直接返回，未命中回源后回填）
        String cacheKey = statsCacheKey("trend", range);
        List<TrendVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 区间内【全部状态】预约（趋势口径：预约提交活跃度）
        List<Reservation> all = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end));

        // 按预约日期归属自然月分组计数，月份升序输出
        Map<YearMonth, Long> byMonth = all.stream().collect(Collectors.groupingBy(
                r -> YearMonth.from(r.getReserveDate()), Collectors.counting()));
        return byMonth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    TrendVO vo = new TrendVO();
                    vo.setMonth(e.getKey().toString());
                    vo.setCount(e.getValue());
                    return vo;
                })
                .collect(Collectors.collectingAndThen(Collectors.toList(), result -> {
                    // 回填缓存（短 TTL；预约/教室变更时主动失效）
                    redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
                    return result;
                }));
    }

    @Override
    public List<TimeDistributionVO> timeDistribution(String startDate, String endDate) {
        DateRange range = resolveRange(startDate, endDate);
        // Redis 加分项：看板聚合结果读缓存（命中直接返回，未命中回源后回填）
        String cacheKey = statsCacheKey("time-distribution", range);
        List<TimeDistributionVO> cached = redisCache.getObject(cacheKey, new TypeReference<>() {
        });
        if (cached != null) {
            return cached;
        }
        // 区间内【已通过】预约（时段分布口径：实际占用时段）
        List<Reservation> approved = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .ge(Reservation::getReserveDate, range.start)
                .le(Reservation::getReserveDate, range.end)
                .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));

        // 分桶计数（保持桶顺序：前 5 桶 + 「其他」兜底）
        long[] counts = new long[Constants.TIME_SLOT_BUCKET_COUNT];
        long total = 0;
        for (Reservation r : approved) {
            int idx = slotIndex(r.getStartTime());
            counts[idx]++;
            total++;
        }

        List<TimeDistributionVO> result = new ArrayList<>(Constants.TIME_SLOT_BUCKET_COUNT);
        for (int i = 0; i < Constants.TIME_SLOT_BUCKET_COUNT; i++) {
            TimeDistributionVO vo = new TimeDistributionVO();
            vo.setSlot(Constants.TIME_SLOT_LABELS[i]);
            vo.setCount(counts[i]);
            vo.setPercentage(round1(total == 0 ? 0 : counts[i] * 100.0 / total));
            result.add(vo);
        }
        // 回填缓存（短 TTL；预约/教室变更时主动失效）
        redisCache.setObject(cacheKey, result, redisProperties.getCache().getStatsTtlSeconds());
        return result;
    }

    /* ==================== 私有工具方法 ==================== */

    /** 组装看板统计缓存 Key：cache:stats:{接口名}:{开始日期}:{结束日期}（区间经 resolveRange 归一化，相同区间共享同一份缓存） */
    private String statsCacheKey(String api, DateRange range) {
        return RedisCache.STATS_KEY_PREFIX + api + ":" + range.start + ":" + range.end;
    }

    /** 开始时间 → 时段桶下标（0-4 对应前 5 桶，5 为「其他」） */
    private int slotIndex(LocalTime start) {
        for (int i = 0; i < SLOT_STARTS.length; i++) {
            // 桶范围：[左边界, 右边界)
            if (!start.isBefore(SLOT_STARTS[i]) && start.isBefore(SLOT_ENDS[i])) {
                return i;
            }
        }
        return Constants.TIME_SLOT_BUCKET_COUNT - 1;
    }

    /** 日期区间解析：校验格式/先后/跨度；缺省按近 30 天补齐 */
    private DateRange resolveRange(String startDate, String endDate) {
        LocalDate start = parseDateOrNull(startDate);
        LocalDate end = parseDateOrNull(endDate);
        LocalDate today = LocalDate.now();
        if (start == null && end == null) {
            end = today;
            start = end.minusDays(Constants.STATS_DEFAULT_DAYS - 1L);
        } else if (start == null) {
            start = end.minusDays(Constants.STATS_DEFAULT_DAYS - 1L);
        } else if (end == null) {
            end = start.plusDays(Constants.STATS_DEFAULT_DAYS - 1L);
        }
        if (start.isAfter(end)) {
            throw new BusinessException("开始日期不能晚于结束日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > Constants.STATS_MAX_DAYS) {
            throw new BusinessException("统计区间跨度不能超过 366 天");
        }
        return new DateRange(start, end);
    }

    /** 解析可选日期参数（空返回 null，非法抛 400） */
    private LocalDate parseDateOrNull(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        return TimeUtil.parseDate(date);
    }

    /** 数值保留 1 位小数（四舍五入） */
    private double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** 日期区间（开始/结束均含） */
    private static final class DateRange {
        private final LocalDate start;
        private final LocalDate end;

        private DateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }
    }
}
