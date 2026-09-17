package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.dto.AiChatVO;
import com.example.reservation.ai.service.AiChatService;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiFallbackEngine;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.Constants;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.common.UserContext;
import com.example.reservation.entity.Classroom;
import com.example.reservation.entity.Reservation;
import com.example.reservation.mapper.ClassroomMapper;
import com.example.reservation.mapper.ReservationMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 场景限定 System Prompt（结构化 JSON 输出，AGENTS 4.4 第 4 条）
     * 2026-09-13 AI 演示准备轮修复：原版「与预约无关的问题统一回复」被 flash 模型理解为一切问题的默认输出（实测场景内问题三次全部拒答）；
     * 改为「业务规则内联 + 仅完全无关问题才回复抱歉」，模型有据可答 */
    private static final String SYSTEM_PROMPT = "你是高校教室预约管理系统的智能助手，请依据以下业务规则回答用户的问题：\n"
            + "- 取消预约：待审核或已通过的预约可在预约开始前 1 小时自由取消（在我的预约页点击取消，需二次确认）；开始前不足 1 小时不可取消，特殊情况请联系管理员。\n"
            + "- 预约冲突：同一教室同一日期下，新预约时段与已通过预约时段存在重叠即判定冲突（前后端双重校验）。\n"
            + "- 审核流程：学生提交预约后状态为待审核，管理员在预约审核页通过或驳回（驳回会填写审核备注）；结果可在我的预约页查看。\n"
            + "- 预约状态：待审核（提交后）、已通过（管理员通过）、已驳回（不可修改）、已取消（用户取消）。\n"
            + "- 预约步骤：教室列表 → 点击教室卡片进入详情 → 选择日期与时段 → 填写用途 → 提交预约（实时冲突校验）；也可用「AI 快速预约」直接描述需求。\n"
            + "- 教室信息：教室列表支持按关键词/楼栋/类型/日期筛选，卡片展示实时状态（空闲/使用中/已结束）与容量、设备；详情页可查看当日时段占用并预约。\n"
            + "请回答与教室预约相关的任何问题（预约、取消、审核、状态、教室信息、个人记录等）。只有当问题与教室预约完全无关（如天气、美食、娱乐）时，才回复：抱歉，我只能解答预约相关问题。\n"
            + "示例：用户问“我怎么取消预约？”，回答：{\"answer\":\"待审核或已通过的预约可在预约开始前 1 小时自由取消，在我的预约页点击取消并二次确认即可；开始前不足 1 小时不可取消。\"}\n"
            + "示例：用户问“预约审核多久有结果？”，回答：{\"answer\":\"学生提交预约后状态为待审核，管理员在预约审核页通过或驳回，结果可在我的预约页查看。\"}\n"
            + "请用简洁的中文回答，并以JSON格式输出：{\"answer\":\"回答内容\"}";

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private AgnesClient agnesClient;

    @Resource
    private AiFallbackEngine fallbackEngine;

    @Resource
    private ReservationMapper reservationMapper;

    @Resource
    private ClassroomMapper classroomMapper;

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

        // 1. 尝试大模型问答（检索增强注入个人预约上下文）
        String userMessage = buildContext() + "\n【用户问题】" + question;
        // M7 修复：传入当前用户 ID，限流按用户维度隔离
        AgnesClient.AgnesResponse resp = agnesClient.chat(UserContext.getUserId(), SYSTEM_PROMPT, userMessage, true);
        if (resp.ok()) {
            AiChatVO vo = parseModelOutput(resp.content());
            if (vo != null) {
                vo.setEnabled(true);
                return vo;
            }
            log.warn("问答模型输出不合法，切换降级：{}", resp.content());
        }
        // 2. 降级：关键词匹配 FAQ 库（未命中返回场景外预设话术）；降级原因透出到 message（限流/密钥缺失/服务异常可观测）
        AiChatVO vo = new AiChatVO();
        vo.setEnabled(true);
        vo.setMessage(resp.reason());
        vo.setAnswer(fallbackEngine.chatFallback(question));
        return vo;
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
            JsonNode root = MAPPER.readTree(content);
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
