package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.dto.AiPlanCandidateVO;
import com.example.reservation.ai.dto.AiPlanTimeOptionVO;
import com.example.reservation.ai.dto.AiPlanVO;
import com.example.reservation.ai.service.AiBookingPlanService;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiParseService;
import com.example.reservation.ai.support.AiBookingIntent;
import com.example.reservation.ai.support.AiParseFallback;
import com.example.reservation.common.Constants;
import com.example.reservation.common.PageResult;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.Classroom;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.service.ClassroomService;
import com.example.reservation.vo.ClassroomVO;
import com.example.reservation.vo.OccupiedSlotVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对话内预约规划实现（R15-1 / R15-2）
 *
 * <p>只读：本类不注入任何写服务、不调用提交/审核接口，仅「解析 + 检索 + 排序 + 生成理由」。
 * 用户确认后由既有确认弹窗自行提交，提交路径不受本类影响。
 *
 * <p>R15-2：用户没说结束时间时，按 1 / 2 / 3 小时三档各检索一次，把三档交给交互层
 * （方案卡的时段按钮）让用户自己选，不再退回「请补充结束时间」的澄清。
 *
 * @author hyr
 */
@Slf4j
@Service
public class AiBookingPlanServiceImpl implements AiBookingPlanService {

    /** AI 双开关（关闭时返回友好提示，不报错） */
    @Resource
    private AiConfigService aiConfigService;

    /** 自然语言解析（模型 + 本地规则降级） */
    @Resource
    private AiParseService aiParseService;

    /** R15-2：模型没解析出结束时间时，用本地规则补日期/开始时间（只读正则，不额外调模型） */
    @Resource
    private AiParseFallback aiParseFallback;

    /** 学生端教室分页（一次拿到占用时段，避免逐间查冲突的 N+1） */
    @Resource
    private ClassroomService classroomService;

    /** 个人预约历史（仅读取，用于楼栋/类型偏好加权） */
    @Resource
    private ReservationMapper reservationMapper;

    /** 教室（仅用于历史偏好取楼栋，只读） */
    @Resource
    private ClassroomMapper classroomMapper;

    @Override
    public AiPlanVO plan(Long userId, String text, Long preferredClassroomId) {
        // 双开关关闭：与其余 AI 入口一致，返回 enabled=false 的友好提示
        if (!aiConfigService.isAiEnabled()) {
            return AiPlanVO.disabled();
        }
        AiPlanVO vo = new AiPlanVO();
        vo.setEnabled(true);

        // 1) 解析需求。模型输出缺结束时间会被整体判非法（parse 返回 error），
        //    这里再用本地规则补出「日期 + 开始时间」，把结束时间交给交互层选（R15-2）。
        AiParseVO parse = null;
        try {
            parse = aiParseService.parse(text);
        } catch (RuntimeException e) {
            log.warn("预约方案解析失败：{}", e.getMessage());
        }
        PartialIntent intent = resolveIntent(text, parse);
        if (intent == null) {
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }
        vo.setCapacity(intent.capacity);
        vo.setRoomType(intent.roomType);
        vo.setPurpose(intent.purpose);
        vo.setDate(intent.day.toString());
        vo.setStartTime(TimeUtil.formatTime(intent.start));

        // 2) 日期/时段合法性（与后端校验同口径：不早于今天、开始早于结束）
        if (intent.day.isBefore(LocalDate.now())) {
            vo.setClarify(AiConstants.PLAN_DATE_PAST);
            return vo;
        }

        // 3) 检索该日期该类型的全部教室（一次分页，三档估时复用同一份占用数据）
        List<Integer> durations = intent.end == null ? validDurations(intent.start) : List.of();
        if (intent.end == null && durations.isEmpty()) {
            // 开始时间太晚：连 1 小时都排不下，直接澄清，不做无意义的分页查询
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }
        PageResult<ClassroomVO> page = classroomService.pageClassroomsForStudent(
                1, AiConstants.PLAN_SCAN_SIZE, null, null, typeOf(intent.roomType), intent.day.toString());
        List<ClassroomVO> rooms = page == null || page.getRecords() == null ? List.of() : page.getRecords();
        Map<String, Integer> buildingPref = historyPreference(userId);

        if (intent.end == null) {
            return estimateOption(vo, intent, rooms, buildingPref, durations, preferredClassroomId);
        }

        SearchResult result = search(rooms, intent.day, intent.start, intent.end,
                intent.capacity, typeOf(intent.roomType), buildingPref, preferredClassroomId);
        apply(vo, result, intent.end);
        if (vo.getCandidates().isEmpty()) {
            vo.setClarify(AiConstants.PLAN_EMPTY);
        }
        return vo;
    }

    /** 占位：保持 vo 复用（便于后续扩展用户维度的默认值，当前无字段需要） */
    private AiPlanVO userAdjusted(AiPlanVO vo, Long userId) {
        return vo;
    }

    /** 有效的估时档位：结束时间不得超过 22:00 业务上限（超出的档位直接剔除） */
    private List<Integer> validDurations(LocalTime start) {
        List<Integer> durations = new ArrayList<>();
        for (int hours : AiConstants.PLAN_DURATION_HOURS) {
            LocalTime end = start.plusHours(hours);
            if (start.isBefore(end) && !end.isAfter(Constants.DAILY_SLOT_END)) {
                durations.add(hours);
            }
        }
        return durations;
    }

    /**
     * 估时分支：1 / 2 / 3 小时三档各检索一次，默认档取候选最多的一档（并列优先 2 小时）。
     */
    private AiPlanVO estimateOption(AiPlanVO vo, PartialIntent intent,
                                    List<ClassroomVO> rooms, Map<String, Integer> buildingPref,
                                    List<Integer> durations, Long preferredClassroomId) {
        Integer type = typeOf(intent.roomType);
        List<AiPlanTimeOptionVO> options = new ArrayList<>();
        List<Integer> optionHours = new ArrayList<>();
        for (int hours : durations) {
            LocalTime end = intent.start.plusHours(hours);
            if (end.isAfter(Constants.DAILY_SLOT_END) || !intent.start.isBefore(end)) {
                continue;
            }
            SearchResult result = search(rooms, intent.day, intent.start, end,
                    intent.capacity, type, buildingPref, preferredClassroomId);
            AiPlanTimeOptionVO option = new AiPlanTimeOptionVO();
            option.setStartTime(TimeUtil.formatTime(intent.start));
            option.setEndTime(TimeUtil.formatTime(end));
            option.setAvailableCount(result.available);
            option.setExcludedCount(result.excluded);
            option.setCandidates(result.candidates);
            options.add(option);
            optionHours.add(hours);
        }
        if (options.isEmpty()) {
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }
        // 默认档：候选最多优先；并列时取时长最接近 2 小时的档（多数实验课两学时）
        AiPlanTimeOptionVO best = options.get(0);
        int bestHours = optionHours.get(0);
        long bestWeight = Long.MIN_VALUE;
        for (int i = 0; i < options.size(); i++) {
            long weight = (long) options.get(i).getAvailableCount() * 10 - Math.abs(optionHours.get(i) - 2);
            if (weight >= bestWeight) {
                best = options.get(i);
                bestHours = optionHours.get(i);
                bestWeight = weight;
            }
        }
        vo.setEndTimeGuessed(true);
        vo.setGuessHours(bestHours);
        vo.setTimeOptions(options);
        apply(vo, new SearchResult(best.getCandidates(), best.getAvailableCount(), best.getExcludedCount()),
                TimeUtil.parseTime(best.getEndTime()));
        if (vo.getCandidates().isEmpty()) {
            vo.setClarify(AiConstants.PLAN_EMPTY);
        }
        return vo;
    }

    /** 把检索结果写回 VO（显式时段与默认估时档共用） */
    private void apply(AiPlanVO vo, SearchResult result, LocalTime end) {
        vo.setEndTime(TimeUtil.formatTime(end));
        vo.setCandidates(result.candidates);
        vo.setMoreAvailable(Math.max(0, result.available - AiConstants.PLAN_TOP_COUNT));
        vo.setExcludedCount(result.excluded);
    }

    /**
     * 统一解析入口：优先模型结果（含完整结束时间），否则用本地规则补日期 + 开始时间。
     * 返回 null 表示连日期/开始时间都没有，需要澄清。
     *
     * <p>R15-2：结束时间以**用户原文**为准。用户没明确说结束时间（如「明天下午两点」）时，
     * 即便模型自行补了一个 endTime，也按「未指定」处理并交给方案卡给可选档——
     * 否则「估算」会被伪装成用户真实需求。
     */
    private PartialIntent resolveIntent(String text, AiParseVO parse) {
        boolean endGiven = AiBookingIntent.mentionsEndTime(text);
        if (parse != null && StrUtil.isBlank(parse.getError())
                && StrUtil.isNotBlank(parse.getDate()) && StrUtil.isNotBlank(parse.getStartTime())) {
            LocalDate day = safeDate(parse.getDate());
            LocalTime start = safeTime(parse.getStartTime());
            LocalTime end = !endGiven || StrUtil.isBlank(parse.getEndTime()) ? null : safeTime(parse.getEndTime());
            if (day != null && start != null && (end == null || start.isBefore(end))) {
                return new PartialIntent(day, start, end, parse.getCapacity(), parse.getRoomType(), parse.getPurpose());
            }
        }
        // 模型没给出可用的开始时间（典型：只说「明天下午两点」，缺结束时间被判非法）→ 本地规则兜底
        AiParseVO fallback;
        try {
            fallback = aiParseFallback.parseFallback(text);
        } catch (RuntimeException e) {
            log.warn("预约方案本地降级解析失败：{}", e.getMessage());
            return null;
        }
        return partialOf(fallback);
    }

    /** 本地规则结果 → PartialIntent（结束时间一律视为「未指定」，交给交互层选） */
    private PartialIntent partialOf(AiParseVO fallback) {
        if (fallback == null || StrUtil.isBlank(fallback.getDate()) || StrUtil.isBlank(fallback.getStartTime())) {
            return null;
        }
        LocalDate day = safeDate(fallback.getDate());
        LocalTime start = safeTime(fallback.getStartTime());
        if (day == null || start == null) {
            return null;
        }
        return new PartialIntent(day, start, null, fallback.getCapacity(), fallback.getRoomType(), fallback.getPurpose());
    }

    private LocalDate safeDate(String text) {
        try {
            return TimeUtil.parseDate(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private LocalTime safeTime(String text) {
        try {
            return TimeUtil.parseTime(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 单次检索：容量 + 类型 + 冲突过滤，排序取 Top3 */
    private SearchResult search(List<ClassroomVO> rooms, LocalDate day, LocalTime start, LocalTime end,
                                Integer capacity, Integer type, Map<String, Integer> buildingPref,
                                Long preferredClassroomId) {
        List<Scored> available = new ArrayList<>();
        int excluded = 0;
        for (ClassroomVO room : rooms) {
            if (capacity != null && room.getCapacity() != null && room.getCapacity() < capacity) {
                excluded++;
                continue;
            }
            if (overlaps(room.getOccupiedSlots(), start, end)) {
                excluded++;
                continue;
            }
            available.add(new Scored(room, score(room, type, capacity, buildingPref, preferredClassroomId)));
        }
        // R15-3：正在查看的教室只要满足硬条件（容量/类型/时段），直接排第一——
        // 用户问「这间教室」时期待先看到它，评分加权不足以覆盖「容量刚好」的差距。
        Comparator<Scored> preferredFirst = Comparator.comparing(
                s -> preferredClassroomId != null && preferredClassroomId.equals(s.room().getId()));
        available.sort(preferredFirst.reversed()
                .thenComparing(Comparator.comparingInt(Scored::score).reversed())
                .thenComparingInt(s -> Math.abs((s.room().getCapacity() == null ? 0 : s.room().getCapacity())
                        - (capacity == null ? 0 : capacity)))
                .thenComparingLong(s -> s.room().getId()));
        List<AiPlanCandidateVO> candidates = new ArrayList<>();
        for (int i = 0; i < available.size() && i < AiConstants.PLAN_TOP_COUNT; i++) {
            candidates.add(toCandidate(available.get(i).room(), type, capacity, buildingPref,
                    preferredClassroomId));
        }
        return new SearchResult(candidates, available.size(), excluded);
    }

    /** 需求类型文案 → DB 类型值；未指定返回 null（不限类型） */
    private Integer typeOf(String roomType) {
        if (StrUtil.isBlank(roomType)) {
            return null;
        }
        return switch (roomType) {
            case AiConstants.ROOM_TYPE_NORMAL -> 1;
            case AiConstants.ROOM_TYPE_LAB -> 2;
            case AiConstants.ROOM_TYPE_COMPUTER -> 3;
            default -> null;
        };
    }

    /** 冲突判定与后端一致：新开始 < 旧结束 AND 新结束 > 旧开始（首尾相接不算冲突） */
    private boolean overlaps(List<OccupiedSlotVO> slots, LocalTime start, LocalTime end) {
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        for (OccupiedSlotVO slot : slots) {
            LocalTime slotStart = TimeUtil.parseTime(slot.getStartTime());
            LocalTime slotEnd = TimeUtil.parseTime(slot.getEndTime());
            if (start.isBefore(slotEnd) && end.isAfter(slotStart)) {
                return true;
            }
        }
        return false;
    }

    /** 个人偏好：近 30 条预约中，每个楼栋出现次数（只用于加分，不改变筛选条件） */
    private Map<String, Integer> historyPreference(Long userId) {
        Map<String, Integer> pref = new LinkedHashMap<>();
        if (userId == null) {
            return pref;
        }
        List<Reservation> history = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .orderByDesc(Reservation::getCreateTime)
                .last("limit " + AiConstants.PLAN_HISTORY_LOOKBACK));
        if (history == null || history.isEmpty()) {
            return pref;
        }
        Set<Long> classroomIds = new HashSet<>();
        history.forEach(r -> classroomIds.add(r.getClassroomId()));
        List<Classroom> rooms = classroomIds.isEmpty()
                ? List.of() : classroomMapper.selectBatchIds(classroomIds);
        Map<Long, String> buildingById = new LinkedHashMap<>();
        if (rooms != null) {
            rooms.forEach(r -> buildingById.put(r.getId(), r.getBuilding()));
        }
        history.forEach(r -> {
            String building = buildingById.get(r.getClassroomId());
            if (StrUtil.isNotBlank(building)) {
                pref.merge(building, 1, Integer::sum);
            }
        });
        return pref;
    }

    /** 画像分：类型精确匹配 > 常用楼栋 > 容量贴合 > 剩余时段（口径写进 docs/AI智能体设计.md） */
    private int score(ClassroomVO room, Integer type, Integer capacity, Map<String, Integer> buildingPref,
                      Long preferredClassroomId) {
        int score = 0;
        if (type != null && type.equals(room.getType())) {
            score += 100;
        }

        if (StrUtil.isNotBlank(room.getBuilding()) && buildingPref.getOrDefault(room.getBuilding(), 0) > 0) {
            score += 30;
        }
        if (capacity != null && room.getCapacity() != null) {
            score += Math.max(0, 40 - Math.min(40, room.getCapacity() - capacity));
        }
        score += freeSlots(room.getOccupiedSlots()) * 2;
        return score;
    }

    /** 当日剩余整点时段（08:00-22:00 共 14 个），与占用时段有重叠的扣除 */
    private int freeSlots(List<OccupiedSlotVO> slots) {
        int free = Constants.DAILY_AVAILABLE_HOURS;
        if (slots == null) {
            return free;
        }
        for (OccupiedSlotVO slot : slots) {
            LocalTime slotStart = TimeUtil.parseTime(slot.getStartTime());
            LocalTime slotEnd = TimeUtil.parseTime(slot.getEndTime());
            int begin = slotStart.getHour() - Constants.DAILY_SLOT_START_HOUR;
            int finish = slotEnd.getHour() - Constants.DAILY_SLOT_START_HOUR;
            free -= Math.max(0, Math.min(Constants.DAILY_AVAILABLE_HOURS, finish) - Math.max(0, begin));
        }
        return Math.max(0, free);
    }

    private AiPlanCandidateVO toCandidate(ClassroomVO room, Integer type, Integer capacity,
                                          Map<String, Integer> buildingPref,
                                          Long preferredClassroomId) {
        AiPlanCandidateVO candidate = new AiPlanCandidateVO();
        candidate.setClassroomId(room.getId());
        candidate.setName(room.getName());
        candidate.setBuilding(room.getBuilding());
        candidate.setRoomNo(room.getRoomNo());
        candidate.setType(room.getType());
        candidate.setCapacity(room.getCapacity());
        candidate.setReason(reason(room, type, capacity, buildingPref, preferredClassroomId));
        return candidate;
    }

    /** 推荐理由：模板句，不额外调模型（避免为一句理由再花一次额度） */
    private String reason(ClassroomVO room, Integer type, Integer capacity, Map<String, Integer> buildingPref,
                          Long preferredClassroomId) {
        List<String> parts = new ArrayList<>();
        if (preferredClassroomId != null && preferredClassroomId.equals(room.getId())) {
            parts.add("你正在看这间");
        }
        if (type != null && type.equals(room.getType())) {
            parts.add("类型匹配");
        }
        if (StrUtil.isNotBlank(room.getBuilding()) && buildingPref.getOrDefault(room.getBuilding(), 0) > 0) {
            parts.add("你常约" + room.getBuilding());
        }
        if (capacity != null && room.getCapacity() != null) {
            parts.add(room.getCapacity() - capacity <= 5 ? "容量刚好" : "容量 " + room.getCapacity() + " 人");
        }
        parts.add("该时段空闲");
        return String.join(" · ", parts);
    }

    /** 排序中间态（record 保持不可变，便于排序后仍能取回教室） */
    private record Scored(ClassroomVO room, int score) {
    }

    /** 单次检索结果 */
    private record SearchResult(List<AiPlanCandidateVO> candidates, int available, int excluded) {
    }

    /** 解析中间态：end 为 null 表示「用户没说结束时间」 */
    private record PartialIntent(LocalDate day, LocalTime start, LocalTime end,
                                 Integer capacity, String roomType, String purpose) {
    }
}
