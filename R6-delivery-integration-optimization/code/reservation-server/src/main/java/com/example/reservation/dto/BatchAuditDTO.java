package com.example.reservation.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量审核请求参数（R3）
 * 规则：仅待审核(0)记录可参与；批量驳回(2)必须填写审核备注 auditRemark
 *
 * @author reservation-team
 */
@Data
public class BatchAuditDTO {

    /** 预约 ID 列表（必填，非空） */
    private List<Long> ids;

    /** 审核结果：1-通过，2-驳回（必填） */
    private Integer status;

    /** 审核备注（批量驳回必填） */
    private String auditRemark;
}
