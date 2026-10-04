package com.example.reservation.ai.service.impl;

import cn.hutool.core.util.StrUtil;
import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.config.AiPrompts;
import com.example.reservation.ai.dto.AiComplianceVO;
import com.example.reservation.ai.service.AiConfigService;
import com.example.reservation.ai.service.AiComplianceService;
import com.example.reservation.ai.support.AiComplianceFallback;
import com.example.reservation.ai.support.AiHash;
import com.example.reservation.ai.support.AiInvoker;
import com.example.reservation.ai.support.AiJsonSupport;
import com.example.reservation.ai.support.AiResultCache;
import com.example.reservation.common.BusinessException;
import com.example.reservation.common.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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

    @Resource
    private AiConfigService aiConfigService;

    /** AI 调用模板（统一「调用 → 解析 → 降级」骨架） */
    @Resource
    private AiInvoker aiInvoker;

    @Resource
    private AiComplianceFallback complianceFallback;

    /** 模型 JSON 输出容错解析（统一解析入口，见 AiJsonSupport） */
    @Resource
    private AiJsonSupport aiJsonSupport;

    /** AI 结果缓存（复用既有 RedisCache；命中即免上游调用） */
    @Resource
    private AiResultCache aiResultCache;

    /** 用途文本最大长度（M11 修复：与 reservation.purpose 表字段 VARCHAR(255) 口径一致） */
    private static final int PURPOSE_MAX_LENGTH = 255;

    @Override
    public AiComplianceVO check(String purpose) {
        if (StrUtil.isBlank(purpose)) {
            throw new BusinessException("请输入预约用途");
        }
        // M11 修复：AI 入参长度上限（防超长输入放大 token 消耗；与表字段长度一致，杜绝数据库超长错误）
        if (purpose.length() > PURPOSE_MAX_LENGTH) {
            throw new BusinessException("预约用途过长（不超过 " + PURPOSE_MAX_LENGTH + " 字）");
        }
        // 双开关关闭：返回友好提示，不报 500（前端隐藏 AI 校验入口）
        if (!aiConfigService.isAiEnabled()) {
            return AiComplianceVO.disabled();
        }

        // 1. 尝试大模型合规校验（Prompt 限定输出 {"compliant":true|false,"reason":"string"}）
        String system = aiConfigService.getPromptCompliance();
        if (StrUtil.isBlank(system)) {
            system = AiPrompts.COMPLIANCE;
        }
        // 结果缓存：purpose → 结论为纯函数，Key 不含用户（管理员间可共享同一结论）；
        // 版本段并入 Prompt 版本 + 违规关键词库 + 生效模型，任一配置变化即换 Key，旧缓存自动失效
        String cacheVersion = AiPrompts.versionOf(system)
                + "-" + AiHash.sha256Prefix8(String.join(",", aiConfigService.getComplianceKeywordList()))
                + "-" + aiConfigService.getEffectiveModel();
        String cacheKey = aiResultCache.buildKey(
                AiConstants.AI_CACHE_NS_COMPLIANCE, cacheVersion, AiHash.sha256Prefix8(purpose));
        // M7 修复：传入当前用户 ID，限流按用户维度隔离；失败/非法输出统一由 AiInvoker 降级
        return aiInvoker.invoke("合规", UserContext.getUserId(), system, purpose,
                this::parseModelOutput,
                vo -> {
                    vo.setEnabled(true);
                    return vo;
                },
                // 2. 降级：ai_config.compliance_keywords 关键词规则判定（降级原因由 AiInvoker 注入 message）
                reason -> {
                    AiComplianceVO vo = complianceFallback.complianceFallback(
                            purpose, aiConfigService.getComplianceKeywordList());
                    vo.setMessage(reason);
                    return vo;
                },
                // 3. 结果缓存规格（仅成功分支入缓存，降级结果不缓存）
                new AiInvoker.AiCacheSpec<>(cacheKey, AiConstants.AI_CACHE_TTL_COMPLIANCE_SECONDS,
                        new TypeReference<AiComplianceVO>() {
                        }));
    }

    /**
     * 解析大模型 JSON 输出（{"compliant":true|false,"reason":"string"}），非法返回 null（触发降级）
     */
    private AiComplianceVO parseModelOutput(String content) {
        try {
            JsonNode root = aiJsonSupport.readObject(content);
            if (root == null) {
                return null;
            }
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
