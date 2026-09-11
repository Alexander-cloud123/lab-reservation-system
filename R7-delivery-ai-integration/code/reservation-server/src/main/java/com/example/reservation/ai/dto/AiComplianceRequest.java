package com.example.reservation.ai.dto;

import lombok.Data;

/**
 * 预约合规校验请求（POST /api/ai/compliance-check，需求文档 2.5）
 *
 * @author reservation-team
 */
@Data
public class AiComplianceRequest {

    /** 预约用途文本（校验是否与教学/实验/自习/竞赛等正当用途相关） */
    private String purpose;
}
