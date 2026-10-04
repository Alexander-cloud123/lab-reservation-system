package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiPrompts;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiParseService;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiParseFallback;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.TimeUtil;
import com.example.reservation.common.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 自然语言预约解析实现（R7）
 * Prompt 模板来自 ai_config.prompt_parse；调用失败/非法输出自动降级为本地规则模拟
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class AiParseServiceImpl implements AiParseService {

    @Resource
    private AiConfigService aiConfigService;

    /** AI 调用模板（统一「调用 → 解析 → 降级」骨架） */
    @Resource
    private AiInvoker aiInvoker;

    @Resource
    private AiParseFallback parseFallback;

    /** 模型 JSON 输出容错解析（统一解析入口，见 AiJsonSupport） */
    @Resource
    private AiJsonSupport aiJsonSupport;

    /** 解析请求文本最大长度（M11 修复：防超长输入放大 token 消耗与超时概率） */
    private static final int PARSE_TEXT_MAX_LENGTH = 200;

    @Override
    public AiParseVO parse(String text) {
        if (StrUtil.isBlank(text)) {
            throw new BusinessException("请输入预约需求描述");
        }
        // M11 修复：AI 入参长度上限（无 Bean Validation 场景下手写校验兜底，与表字段/前端 maxlength 口径一致）
        if (text.length() > PARSE_TEXT_MAX_LENGTH) {
            throw new BusinessException("预约需求描述过长（不超过 " + PARSE_TEXT_MAX_LENGTH + " 字）");
        }
        // 双开关关闭：返回友好提示，不报 500、不阻断核心（前端据此隐藏 AI 入口）
        if (!aiConfigService.isAiEnabled()) {
            return AiParseVO.disabled();
        }

        // 1. 尝试大模型解析（Prompt 严格限定场景与结构化 JSON 输出，AGENTS 4.4）
        // 模板来自 ai_config.prompt_parse；追加当前日期上下文（模型知识不含实时日期，不注入会幻觉输出绝对日期，
        // 实测 2026-09-13 曾返回 2024-04-04 —— 2026-09-13 AI 演示准备轮修复）
        String template = aiConfigService.getPromptParse();
        String system = StrUtil.isBlank(template)
                ? AiPrompts.PARSE
                : template + "（今天是" + LocalDate.now() + "，用户说“今天/明天/后天/周X”时按此日期推算）";
        // M7 修复：传入当前用户 ID，限流按用户维度隔离；失败/非法输出统一由 AiInvoker 降级
        return aiInvoker.invoke("解析", UserContext.getUserId(), system, text,
                this::parseModelOutput,
                vo -> {
                    vo.setEnabled(true);
                    return vo;
                },
                // 2. 降级：正则 + 关键词模板（降级原因由 AiInvoker 注入 message）
                reason -> {
                    AiParseVO vo = parseFallback.parseFallback(text);
                    vo.setMessage(reason);
                    return vo;
                });
    }

    /**
     * 解析大模型 JSON 输出（结构约定见 ai_config.prompt_parse），非法返回 null（触发降级）
     */
    private AiParseVO parseModelOutput(String content) {
        try {
            JsonNode root = aiJsonSupport.readObject(content);
            if (root == null) {
                return null;
            }
            // 识别失败约定：{"error":"无法解析"}
            if (root.hasNonNull("error")) {
                AiParseVO vo = new AiParseVO();
                vo.setError(AiConstants.PARSE_ERROR);
                return vo;
            }
            if (!root.hasNonNull("date") || !root.hasNonNull("startTime") || !root.hasNonNull("endTime")) {
                return null;
            }
            AiParseVO vo = new AiParseVO();
            vo.setDate(TimeUtil.parseDate(root.get("date").asText()).toString());
            vo.setStartTime(LocalTime.parse(root.get("startTime").asText()).toString());
            vo.setEndTime(LocalTime.parse(root.get("endTime").asText()).toString());
            if (root.hasNonNull("capacity") && root.get("capacity").canConvertToInt()) {
                vo.setCapacity(root.get("capacity").asInt());
            }
            if (root.hasNonNull("roomType")) {
                String roomType = root.get("roomType").asText();
                // 类型文案合法性校验（普通教室|实验室|机房）
                if (isValidRoomType(roomType)) {
                    vo.setRoomType(roomType);
                }
            }
            if (root.hasNonNull("purpose")) {
                vo.setPurpose(root.get("purpose").asText());
            }
            // 日期/时段缺失或非法已在上方拦截；校验起止先后
            if (vo.getStartTime() != null && vo.getEndTime() != null
                    && !vo.getEndTime().equals(vo.getStartTime())
                    && vo.getEndTime().compareTo(vo.getStartTime()) < 0) {
                return null;
            }
            return vo;
        } catch (Exception e) {
            log.warn("解析模型输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 教室类型文案合法性（普通教室/实验室/机房） */
    private boolean isValidRoomType(String roomType) {
        return AiConstants.ROOM_TYPE_NORMAL.equals(roomType)
                || AiConstants.ROOM_TYPE_LAB.equals(roomType)
                || AiConstants.ROOM_TYPE_COMPUTER.equals(roomType);
    }
}
