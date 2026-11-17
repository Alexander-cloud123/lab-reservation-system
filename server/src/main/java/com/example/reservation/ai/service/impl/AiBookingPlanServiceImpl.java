package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.dto.AiPlanCandidateVO;
import com.example.reservation.ai.dto.AiPlanVO;
import com.example.reservation.ai.service.AiBookingPlanService;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiParseService;
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
 * 对话内预约规划实现（R15-1）
 *
 * <p>只读：本类不注入任何写服务、不调用提交/审核接口，仅「解析 + 检索 + 排序 + 生成理由」。
 * 用户确认后由既有确认弹窗自行提交，提交路径不受本类影响。
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
    public AiPlanVO plan(Long userId, String text) {
        // 双开关关闭：与其余 AI 入口一致，返回 enabled=false 的友好提示
        if (!aiConfigService.isAiEnabled()) {
            return AiPlanVO.disabled();
        }
        AiPlanVO vo = new AiPlanVO();
        vo.setEnabled(true);

        // 1) 解析需求：失败（含非法时段）走澄清，不抛给上层
        AiParseVO parse;
        try {
            parse = aiParseService.parse(text);
        } catch (RuntimeException e) {
            log.warn("预约方案解析失败：{}", e.getMessage());
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }
        if (parse == null || StrUtil.isNotBlank(parse.getError())) {
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }
        String date = parse.getDate();
        String start = parse.getStartTime();
        String end = parse.getEndTime();
        if (StrUtil.isBlank(date) || StrUtil.isBlank(start) || StrUtil.isBlank(end)) {
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }

        // 2) 日期/时段合法性（与后端校验同口径：不早于今天、开始早于结束）
        LocalDate day;
        LocalTime startTime;
        LocalTime endTime;
        try {
            day = TimeUtil.parseDate(date);
            startTime = TimeUtil.parseTime(start);
            endTime = TimeUtil.parseTime(end);
        } catch (RuntimeException e) {
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }
        if (day.isBefore(LocalDate.now())) {
            vo.setClarify(AiConstants.PLAN_DATE_PAST);
            return vo;
        }
        if (!startTime.isBefore(endTime)) {
            vo.setClarify(AiConstants.PLAN_CLARIFY);
            return vo;
        }

        Integer capacity = parse.getCapacity() != null && parse.getCapacity() > 0 ? parse.getCapacity() : null;
        Integer type = typeOf(parse.getRoomType());
        vo.setDate(day.toString());
        vo.setStartTime(start);
        vo.setEndTime(end);
        vo.setCapacity(capacity);
        vo.setRoomType(parse.getRoomType());
        vo.setPurpose(parse.getPurpose());

        // 3) 检索该日期该类型的全部可用教室（含已通过预约占用时段）
        PageResult<ClassroomVO> page = classroomService.pageClassroomsForStudent(
                1, AiConstants.PLAN_SCAN_SIZE, null, null, type, date);
        List<ClassroomVO> rooms = page.getRecords() == null ? List.of() : page.getRecords();
        Map<String, Integer> buildingPref = historyPreference(userId);

        List<Scored> available = new ArrayList<>();
        int excluded = 0;
        for (ClassroomVO room : rooms) {
            if (capacity != null && room.getCapacity() != null && room.getCapacity() < capacity) {
                excluded++;
                continue;
            }
            if (overlaps(room.getOccupiedSlots(), startTime, endTime)) {
                excluded++;
                continue;
            }
            available.add(new Scored(room, score(room, type, capacity, buildingPref)));
        }

        // 4) 排序取 Top3：画像分高者优先；同分时容量更贴合、ID 更小者优先（保证可复跑）
        available.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparingInt(s -> Math.abs((s.room().getCapacity() == null ? 0 : s.room().getCapacity())
                        - (capacity == null ? 0 : capacity)))
                .thenComparingLong(s -> s.room().getId()));

        vo.setExcludedCount(excluded);
        List<AiPlanCandidateVO> candidates = new ArrayList<>();
        for (int i = 0; i < available.size() && i < AiConstants.PLAN_TOP_COUNT; i++) {
            candidates.add(toCandidate(available.get(i).room(), type, capacity, buildingPref));
        }
        vo.setCandidates(candidates);
        vo.setMoreAvailable(Math.max(0, available.size() - AiConstants.PLAN_TOP_COUNT));
        if (candidates.isEmpty()) {
            vo.setClarify(AiConstants.PLAN_EMPTY);
        }
        return vo;
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
    private int score(ClassroomVO room, Integer type, Integer capacity, Map<String, Integer> buildingPref) {
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
                                          Map<String, Integer> buildingPref) {
        AiPlanCandidateVO candidate = new AiPlanCandidateVO();
        candidate.setClassroomId(room.getId());
        candidate.setName(room.getName());
        candidate.setBuilding(room.getBuilding());
        candidate.setRoomNo(room.getRoomNo());
        candidate.setType(room.getType());
        candidate.setCapacity(room.getCapacity());
        candidate.setReason(reason(room, type, capacity, buildingPref));
        return candidate;
    }

    /** 推荐理由：模板句，不额外调模型（避免为一句理由再花一次额度） */
    private String reason(ClassroomVO room, Integer type, Integer capacity, Map<String, Integer> buildingPref) {
        List<String> parts = new ArrayList<>();
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
}
