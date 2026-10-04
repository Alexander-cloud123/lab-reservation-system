package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiPrompts;
import com.example.reservation.ai.dto.AiChatVO;
import com.example.reservation.ai.service.AiChatService;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.support.AiChatFallback;
import com.example.reservation.ai.support.AiHash;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.common.UserContext;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 预约智能问答实现（R7）
 * 场景绝对限定（需求文档 1.4 AI 业务规则 1）：仅解答预约/教室/个人记录相关问题，
 * 无关问题统一返回预设话术；个人数据通过检索增强注入上下文（最近预约 + 用户概况）
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class AiChatServiceImpl implements AiChatService {

    @Resource
    private AiConfigService aiConfigService;

    /** AI 调用模板（统一「调用 → 解析 → 降级」骨架） */
    @Resource
    private AiInvoker aiInvoker;

    @Resource
    private AiChatFallback chatFallback;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

    /** 模型 JSON 输出容错解析（统一解析入口，见 AiJsonSupport） */
    @Resource
    private AiJsonSupport aiJsonSupport;

    /** AI 结果缓存（复用既有 RedisCache；命中即免上游调用） */
    @Resource
    private AiResultCache aiResultCache;

    /** 提问文本最大长度（M11 修复：防超长输入放大 token 消耗与超时概率） */
    private static final int QUESTION_MAX_LENGTH = 200;

    @Override
    public AiChatVO chat(String question) {
        if (StrUtil.isBlank(question)) {
            throw new BusinessException("请输入您的问题");
        }
        // M11 修复：AI 入参长度上限
        if (question.length() > QUESTION_MAX_LENGTH) {
            throw new BusinessException("问题过长（不超过 " + QUESTION_MAX_LENGTH + " 字）");
        }
        // 双开关关闭：返回友好提示，不报 500（前端隐藏悬浮助手）
        if (!aiConfigService.isAiEnabled()) {
            return AiChatVO.disabled();
        }

        // 1. 尝试大模型问答（检索增强注入个人预约上下文）；失败/非法输出统一由 AiInvoker 降级
        String userMessage = buildContext() + "\n【用户问题】" + question;
        // 结果缓存：Key 含 userId + 问题（答案注入了个人预约上下文，故不跨用户复用）；TTL 取 30s（上下文会变）
        String cacheKey = aiResultCache.buildKey(
                AiConstants.AI_CACHE_NS_CHAT,
                AiPrompts.versionOf(AiPrompts.CHAT) + "-" + aiConfigService.getEffectiveModel(),
                AiHash.sha256Prefix8(UserContext.getUserId() + ":" + question));
        // M7 修复：传入当前用户 ID，限流按用户维度隔离
        return aiInvoker.invoke("问答", UserContext.getUserId(), AiPrompts.CHAT, userMessage,
                this::parseModelOutput,
                vo -> {
                    vo.setEnabled(true);
                    return vo;
                },
                // 2. 降级：关键词匹配 FAQ 库（未命中返回场景外预设话术；降级原因由 AiInvoker 注入 message）
                reason -> {
                    AiChatVO vo = new AiChatVO();
                    vo.setEnabled(true);
                    vo.setMessage(reason);
                    vo.setAnswer(chatFallback.chatFallback(question));
                    return vo;
                },
                // 3. 结果缓存规格（仅成功分支入缓存，降级结果不缓存）
                new AiInvoker.AiCacheSpec<>(cacheKey, AiConstants.AI_CACHE_TTL_CHAT_SECONDS,
                        new TypeReference<AiChatVO>() {
                        }));
    }

    /**
     * 构建个人上下文（检索增强：最近 5 条预约 + 预约总数），供模型回答个人记录类问题
     */
    private String buildContext() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return "【用户信息】未获取到当前登录用户";
        }
        List<Reservation> recent = reservationMapper.selectList(
                new LambdaQueryWrapper<Reservation>()
                        .eq(Reservation::getUserId, userId)
                        .orderByDesc(Reservation::getCreateTime)
                        .last("LIMIT 5"));
        Long total = reservationMapper.selectCount(
                new LambdaQueryWrapper<Reservation>().eq(Reservation::getUserId, userId));

        StringBuilder sb = new StringBuilder();
        sb.append("【用户预约概况】共 ").append(total).append(" 条预约记录");
        if (recent.isEmpty()) {
            sb.append("；暂无预约记录");
            return sb.toString();
        }
        // 教室名称映射（避免 N+1）
        List<Long> roomIds = recent.stream().map(Reservation::getClassroomId).distinct().toList();
        Map<Long, String> roomNameMap = roomIds.isEmpty() ? Map.of()
                : classroomMapper.selectBatchIds(roomIds).stream()
                        .collect(Collectors.toMap(Classroom::getId, Classroom::getName));
        sb.append("；最近预约：");
        sb.append(recent.stream()
                .map(r -> roomNameMap.getOrDefault(r.getClassroomId(), "教室" + r.getClassroomId())
                        + " " + r.getReserveDate()
                        + " " + TimeUtil.formatTime(r.getStartTime()) + "-" + TimeUtil.formatTime(r.getEndTime())
                        + "（" + statusText(r.getStatus()) + "）")
                .collect(Collectors.joining("；")));
        return sb.toString();
    }

    /** 状态文案（与前端三态标签口径一致） */
    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Constants.RES_STATUS_PENDING -> "待审核";
            case Constants.RES_STATUS_APPROVED -> "已通过";
            case Constants.RES_STATUS_REJECTED -> "已驳回";
            case Constants.RES_STATUS_CANCELED -> "已取消";
            default -> "未知";
        };
    }

    /**
     * 解析大模型 JSON 输出（{"answer":"..."}），非法返回 null（触发降级）
     */
    private AiChatVO parseModelOutput(String content) {
        try {
            JsonNode root = aiJsonSupport.readObject(content);
            if (root == null) {
                return null;
            }
            if (!root.hasNonNull("answer") || StrUtil.isBlank(root.get("answer").asText())) {
                return null;
            }
            AiChatVO vo = new AiChatVO();
            vo.setAnswer(root.get("answer").asText());
            return vo;
        } catch (Exception e) {
            log.warn("问答模型输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }
}
