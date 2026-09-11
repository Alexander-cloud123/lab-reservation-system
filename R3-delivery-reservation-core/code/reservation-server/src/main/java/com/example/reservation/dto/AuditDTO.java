package com.example.reservation.dto;

import lombok.Data;

/**
 * 预约审核请求参数（R3）
 * 规则：仅待审核(0)可审核；驳回(2)必须填写审核备注 auditRemark
 *
 * @author reservation-team
 */
@Data
public class AuditDTO {

    /** 审核结果：1-通过，2-驳回（必填） */
    private Integer status;

    /** 审核备注（驳回必填） */
    private String auditRemark;
}
