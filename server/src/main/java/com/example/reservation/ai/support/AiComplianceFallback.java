package com.example.reservation.ai.support;

import com.example.reservation.ai.config.AiConstants;
import com.example.reservation.ai.dto.AiComplianceVO;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 预约合规校验——本地关键词规则降级（spec.md 6.3 降级兜底必实现）
 * 大模型不可用或输出非法时，遍历 ai_config.compliance_keywords 违规关键词库：命中判不合规（附命中词），未命中判合规。
 * 只提示不改状态：降级结果同样只是提示，审核动作仍由管理员手动执行。
 *
 * 说明：本类由原 AiFallbackEngine 拆分而来（按接口职责一分为四，降低单类复杂度）。
 *
 * @author reservation-team
 */
@Component
public class AiComplianceFallback {

    /**
     * 合规降级：遍历本地违规关键词，命中 → 不合规（附命中关键词）；未命中 → 合规
     */
    public AiComplianceVO complianceFallback(String purpose, List<String> keywords) {
        AiComplianceVO vo = new AiComplianceVO();
        vo.setEnabled(true);
        for (String kw : keywords) {
            if (purpose.contains(kw)) {
                vo.setCompliant(false);
                vo.setReason(AiConstants.COMPLIANCE_HIT_PREFIX + kw);
                return vo;
            }
        }
        vo.setCompliant(true);
        vo.setReason(AiConstants.COMPLIANCE_OK_REASON);
        return vo;
    }
}