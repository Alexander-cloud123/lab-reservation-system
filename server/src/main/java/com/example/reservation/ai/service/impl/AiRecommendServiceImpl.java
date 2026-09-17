package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiRecommendItemVO;
import com.example.reservation.ai.dto.AiRecommendVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiFallbackEngine;
import com.example.reservation.ai.service.AiRecommendService;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.entity.SysUser;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.example.reservation.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 智能教室推荐实现（R7，spec.md 6.2）
 * 后端先按规则预筛（用户历史偏好 + 明日实时空闲），大模型对候选教室排序并生成推荐理由；
 * 调用失败/非法输出自动降级为规则打分排序（空闲度 > 楼栋偏好 > 类型偏好 > 容量匹配）
 * 只读不写：AI 仅查询数据、给出建议，不执行任何业务操作
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class AiRecommendServiceImpl implements AiRecommendService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 推荐 System Prompt（限定场景与结构化 JSON 输出） */
    private static final String SYSTEM_PROMPT = "你是高校教室预约管理系统的智能推荐助手。"
            + "根据用户的历史预约偏好与候选教室的明日空闲情况，从候选教室中选择最合适的 3 间，"
            + "按匹配度从高到低排序，并为每间生成一句话中文推荐理由。"
            + "只能从候选教室的 classroomId 中选择，输出JSON数组：[{\"classroomId\":数字,\"reason\":\"一句话理由\"}]";

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private AgnesClient agnesClient;

    @Resource
    private AiFallbackEngine fallbackEngine;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    @Override
    public AiRecommendVO recommend(Long userId) {
        if (userId == null) {
            throw new BusinessException("用户 ID 不能为空");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        // 双开关关闭：返回友好提示，不报 500（前端隐藏推荐卡片）
        if (!aiConfigService.isAiEnabled()) {
            return AiRecommendVO.disabled();
        }

        // 1. 推荐基准日期（默认明天，固定偏移保证可复跑）
        LocalDate targetDate = LocalDate.now().plusDays(AiConstants.RECOMMEND_DATE_OFFSET_DAYS);

        // 2. 用户历史偏好（时段/楼栋/类型/人数）
        List<Reservation> history = reservationMapper.selectList(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getUserId, userId));
        AiFallbackEngine.UserPreference pref = buildPreference(history);

        // 3. 候选教室 + 明日空闲特征（实时空闲状态）
        List<AiFallbackEngine.RecommendCandidate> candidates = buildCandidates(targetDate);
        if (candidates.isEmpty()) {
            AiRecommendVO vo = new AiRecommendVO();
            vo.setEnabled(true);
            vo.setDate(targetDate.toString());
            vo.setRecommendations(List.of());
            return vo;
        }

        // 4. 尝试大模型排序（候选 + 偏好作为上下文，输出 Top3 及理由）
        String userMessage = buildModelContext(targetDate, pref, candidates);
        // M7 修复：传入当前用户 ID，限流按用户维度隔离
        AgnesClient.AgnesResponse resp = agnesClient.chat(userId, SYSTEM_PROMPT, userMessage, true);
        if (resp.ok()) {
            List<AiRecommendItemVO> llmTop = parseModelTop(resp.content(), candidates);
            if (llmTop != null) {
                return buildVO(targetDate, llmTop);
            }
            log.warn("推荐模型输出不合法，切换降级：{}", resp.content());
        }
        // 5. 降级：规则打分排序 Top3；降级原因透出到 message（限流/密钥缺失/服务异常可观测）
        List<AiRecommendItemVO> top = fallbackEngine.rankAndTop(
                candidates, pref, AiConstants.RECOMMEND_TOP_COUNT);
        AiRecommendVO vo = buildVO(targetDate, top);
        vo.setMessage(resp.reason());
        return vo;
    }

    /**
     * 统计用户历史偏好：楼栋/类型/时段（上午/下午/晚上）众数 + 平均教室容量
     */
    private AiFallbackEngine.UserPreference buildPreference(List<Reservation> history) {
        if (history.isEmpty()) {
            return new AiFallbackEngine.UserPreference(null, null, null, 0);
        }
        List<Long> roomIds = history.stream().map(Reservation::getClassroomId).distinct().toList();
        Map<Long, Classroom> roomMap = roomIds.isEmpty() ? Map.of()
                : classroomMapper.selectBatchIds(roomIds).stream()
                        .collect(Collectors.toMap(Classroom::getId, c -> c));

        Map<String, Integer> buildingFreq = new HashMap<>();
        Map<Integer, Integer> typeFreq = new HashMap<>();
        Map<String, Integer> slotFreq = new HashMap<>();
        int capacitySum = 0;
        int capacityCount = 0;
        for (Reservation r : history) {
            Classroom c = roomMap.get(r.getClassroomId());
            if (c == null) {
                continue;
            }
            buildingFreq.merge(c.getBuilding(), 1, Integer::sum);
            typeFreq.merge(c.getType(), 1, Integer::sum);
            if (r.getStartTime() != null) {
                slotFreq.merge(timeSlotText(r.getStartTime()), 1, Integer::sum);
            }
            if (c.getCapacity() != null) {
                capacitySum += c.getCapacity();
                capacityCount++;
            }
        }
        return new AiFallbackEngine.UserPreference(
                maxKey(buildingFreq),
                maxKey(typeFreq),
                maxKey(slotFreq),
                capacityCount == 0 ? 0 : capacitySum / capacityCount);
    }

    /** 时段偏好文案：08:00-12:00 上午 / 12:00-18:00 下午 / 18:00-22:00 晚上 */
    private String timeSlotText(LocalTime time) {
        if (time.isBefore(LocalTime.of(12, 0))) {
            return "上午";
        }
        if (time.isBefore(LocalTime.of(18, 0))) {
            return "下午";
        }
        return "晚上";
    }

    /** 众数键（空 map 返回 null） */
    private <K> K maxKey(Map<K, Integer> freq) {
        return freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * 构建候选教室：可用教室（规则预筛） + 明日已通过预约占用特征（实时空闲状态）
     * L11 修复：候选数量受 RECOMMEND_CANDIDATE_LIMIT 上限约束，防止 Prompt 随教室数量线性膨胀
     * （此前把所有启用教室逐行拼进 Prompt，token 与耗时随教室增长线性上升）
     */
    private List<AiFallbackEngine.RecommendCandidate> buildCandidates(LocalDate targetDate) {
        List<Classroom> rooms = classroomMapper.selectList(
                new LambdaQueryWrapper<Classroom>()
                        .eq(Classroom::getStatus, Constants.CLASSROOM_STATUS_ENABLED)
                        .orderByAsc(Classroom::getId));
        if (rooms.isEmpty()) {
            return List.of();
        }
        // L11：按 ID 升序截取候选上限（规则预筛后再进大模型排序）
        if (rooms.size() > AiConstants.RECOMMEND_CANDIDATE_LIMIT) {
            rooms = rooms.subList(0, AiConstants.RECOMMEND_CANDIDATE_LIMIT);
        }
        List<Long> roomIds = rooms.stream().map(Classroom::getId).toList();
        List<Reservation> dayApproved = reservationMapper.selectList(
                new LambdaQueryWrapper<Reservation>()
                        .in(Reservation::getClassroomId, roomIds)
                        .eq(Reservation::getReserveDate, targetDate)
                        .eq(Reservation::getStatus, Constants.RES_STATUS_APPROVED));
        Map<Long, Long> occupiedCount = dayApproved.stream()
                .collect(Collectors.groupingBy(Reservation::getClassroomId, Collectors.counting()));
        return rooms.stream()
                .map(c -> new AiFallbackEngine.RecommendCandidate(
                        c,
                        !occupiedCount.containsKey(c.getId()),
                        occupiedCount.getOrDefault(c.getId(), 0L).intValue()))
                .toList();
    }

    /**
     * 构建大模型上下文：用户偏好摘要 + 候选教室列表（含明日空闲）
     */
    private String buildModelContext(LocalDate targetDate, AiFallbackEngine.UserPreference pref,
                                     List<AiFallbackEngine.RecommendCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("【推荐基准日期】").append(targetDate).append("\n");
        sb.append("【用户历史偏好】");
        if (pref.preferredBuilding() == null && pref.preferredType() == null && pref.avgCapacity() == 0) {
            sb.append("暂无历史预约记录，按教室空闲与通用适宜度推荐");
        } else {
            List<String> parts = new ArrayList<>();
            if (pref.preferredBuilding() != null) {
                parts.add("常约楼栋：" + pref.preferredBuilding());
            }
            if (pref.preferredType() != null) {
                parts.add("常约类型：" + typeText(pref.preferredType()));
            }
            if (pref.preferredTimeSlot() != null) {
                parts.add("常约时段：" + pref.preferredTimeSlot());
            }
            if (pref.avgCapacity() > 0) {
                parts.add("常用规模约 " + pref.avgCapacity() + " 人");
            }
            sb.append(String.join("；", parts));
        }
        sb.append("\n【候选教室（含明日空闲）】\n");
        for (AiFallbackEngine.RecommendCandidate c : candidates) {
            sb.append("id=").append(c.classroom().getId())
                    .append(" ").append(c.classroom().getName())
                    .append("（").append(c.classroom().getBuilding())
                    .append("，").append(typeText(c.classroom().getType()))
                    .append("，容量").append(c.classroom().getCapacity())
                    .append("，明日").append(c.fullyFree() ? "全天空闲" : "部分占用")
                    .append("）\n");
        }
        sb.append("请输出排序后的 3 间教室 JSON 数组。");
        return sb.toString();
    }

    /**
     * 解析大模型 Top3 输出（JSON 数组 [{"classroomId":N,"reason":"..."}]）
     * 校验：classroomId 必须来自候选集合、最多 3 间、去重；非法返回 null（触发降级）
     */
    private List<AiRecommendItemVO> parseModelTop(String content, List<AiFallbackEngine.RecommendCandidate> candidates) {
        try {
            JsonNode root = MAPPER.readTree(content);
            // 兼容根节点为数组或 {"recommendations":[...]}
            JsonNode arr = root.isArray() ? root : root.path("recommendations");
            if (!arr.isArray() || arr.isEmpty()) {
                return null;
            }
            Set<Long> candidateIds = candidates.stream()
                    .map(c -> c.classroom().getId())
                    .collect(Collectors.toSet());
            Map<Long, Classroom> roomMap = candidates.stream()
                    .collect(Collectors.toMap(c -> c.classroom().getId(), c -> c.classroom()));
            List<AiRecommendItemVO> result = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (JsonNode node : arr) {
                if (result.size() >= AiConstants.RECOMMEND_TOP_COUNT) {
                    break;
                }
                if (!node.hasNonNull("classroomId") || !node.get("classroomId").canConvertToLong()) {
                    return null;
                }
                long id = node.get("classroomId").asLong();
                if (!candidateIds.contains(id) || !seen.add(id)) {
                    return null;
                }
                Classroom c = roomMap.get(id);
                AiRecommendItemVO item = new AiRecommendItemVO();
                item.setClassroomId(c.getId());
                item.setName(c.getName());
                item.setBuilding(c.getBuilding());
                item.setRoomNo(c.getRoomNo());
                item.setType(c.getType());
                item.setCapacity(c.getCapacity());
                item.setReason(node.hasNonNull("reason") ? node.get("reason").asText() : "综合匹配度较高");
                result.add(item);
            }
            if (result.isEmpty()) {
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("推荐模型输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 组装推荐 VO */
    private AiRecommendVO buildVO(LocalDate targetDate, List<AiRecommendItemVO> top) {
        AiRecommendVO vo = new AiRecommendVO();
        vo.setEnabled(true);
        vo.setDate(targetDate.toString());
        vo.setRecommendations(top);
        return vo;
    }

    /** 类型文案 */
    private String typeText(Integer type) {
        if (type == null) {
            return "未知";
        }
        return switch (type) {
            case Constants.CLASSROOM_TYPE_NORMAL -> "普通教室";
            case Constants.CLASSROOM_TYPE_LAB -> "实验室";
            case Constants.CLASSROOM_TYPE_COMPUTER -> "机房";
            default -> "未知";
        };
    }
}
