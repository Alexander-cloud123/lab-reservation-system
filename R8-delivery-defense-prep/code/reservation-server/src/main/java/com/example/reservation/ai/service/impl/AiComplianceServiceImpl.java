package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.config.AgnesClient;
import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiComplianceService;
import com.example.reservation.ai.service.AiFallbackEngine;
import com.example.reservation.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 预约合规校验实现（R7）
 * Prompt 模板来自 ai_config.prompt_compliance；降级模式使用 ai_config.compliance_keywords 关键词规则判定
 * 只提示不改状态：AI 不执行审核，审核动作仍由管理员手动执行
 *
 * @author reservation-team
 */
@Slf4j
@Service
public class AiComplianceServiceImpl implements AiComplianceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Resource
    private AiConfigService aiConfigService;

    @Resource
    private AgnesClient agnesClient;

    @Resource
    private AiFallbackEngine fallbackEngine;

    @Override
    public AiComplianceVO check(String purpose) {
        if (StrUtil.isBlank(purpose)) {
            throw new BusinessException("请输入预约用途");
        }
        // 双开关关闭：返回友好提示，不报 500（前端隐藏 AI 校验入口）
        if (!aiConfigService.isAiEnabled()) {
            return AiComplianceVO.disabled();
        }

        // 1. 尝试大模型合规校验（Prompt 限定输出 {"compliant":true|false,"reason":"string"}）
        String system = aiConfigService.getPromptCompliance();
        if (StrUtil.isBlank(system)) {
            system = "判断预约用途是否合规（是否与教学/实验/自习/竞赛等正当用途相关），输出{\"compliant\":true|false,\"reason\":\"string\"}";
        }
        AgnesClient.AgnesResponse resp = agnesClient.chat(system, purpose, true);
        if (resp.ok()) {
            AiComplianceVO vo = parseModelOutput(resp.content());
            if (vo != null) {
                vo.setEnabled(true);
                return vo;
            }
            log.warn("合规模型输出不合法，切换降级：{}", resp.content());
        }
        // 2. 降级：ai_config.compliance_keywords 关键词规则判定；降级原因透出到 message
        AiComplianceVO vo = fallbackEngine.complianceFallback(purpose, aiConfigService.getComplianceKeywordList());
        vo.setMessage(resp.reason());
        return vo;
    }

    /**
     * 解析大模型 JSON 输出（{"compliant":true|false,"reason":"string"}），非法返回 null（触发降级）
     */
    private AiComplianceVO parseModelOutput(String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
            if (!root.has("compliant") || !root.get("compliant").isBoolean()) {
                return null;
            }
            AiComplianceVO vo = new AiComplianceVO();
            vo.setCompliant(root.get("compliant").asBoolean());
            vo.setReason(root.hasNonNull("reason") ? root.get("reason").asText() : "");
            return vo;
        } catch (Exception e) {
            log.warn("合规模型输出 JSON 解析失败：{}", e.getMessage());
            return null;
        }
    }
}
