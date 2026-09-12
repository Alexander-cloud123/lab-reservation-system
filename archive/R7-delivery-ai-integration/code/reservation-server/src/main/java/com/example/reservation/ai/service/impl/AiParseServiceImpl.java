package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiParseVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiFallbackEngine;
import com.example.reservation.ai.service.AiParseService;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.TimeUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private AgnesClient agnesClient;

    @Resource
    private AiFallbackEngine fallbackEngine;

    @Override
    public AiParseVO parse(String text) {
        if (StrUtil.isBlank(text)) {
            throw new BusinessException("请输入预约需求描述");
        }
        // 双开关关闭：返回友好提示，不报 500、不阻断核心（前端据此隐藏 AI 入口）
        if (!aiConfigService.isAiEnabled()) {
            return AiParseVO.disabled();
        }

        // 1. 尝试大模型解析（Prompt 严格限定场景与结构化 JSON 输出，AGENTS 4.4）
        String system = aiConfigService.getPromptParse();
        if (StrUtil.isBlank(system)) {
            system = "你是教室预约解析器，只输出JSON：{\"date\":\"YYYY-MM-DD\",\"startTime\":\"HH:mm\",\"endTime\":\"HH:mm\",\"capacity\":int,\"roomType\":\"普通教室|实验室|机房|null\",\"purpose\":\"string\"}";
        }
        AgnesClient.AgnesResponse resp = agnesClient.chat(system, text, true);
        if (resp.ok()) {
            AiParseVO vo = parseModelOutput(resp.content());
            if (vo != null) {
                vo.setEnabled(true);
                return vo;
            }
            log.warn("解析模型输出不合法，切换降级：{}", resp.content());
        }
        // 2. 降级：正则 + 关键词模板；降级原因透出到 message（限流/密钥缺失/服务异常可观测）
        AiParseVO vo = fallbackEngine.parseFallback(text);
        vo.setMessage(resp.reason());
        return vo;
    }

    /**
     * 解析大模型 JSON 输出（结构约定见 ai_config.prompt_parse），非法返回 null（触发降级）
     */
    private AiParseVO parseModelOutput(String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
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
