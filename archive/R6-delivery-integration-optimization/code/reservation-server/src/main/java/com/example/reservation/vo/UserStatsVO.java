package com.example.reservation.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人预约数据统计视图对象（R4 个人中心数据概览）
 * 口径：累计预约次数（全部状态）、本月预约数（按预约日期所在自然月）、
 * 审核通过率 = 已通过 /（已通过 + 已驳回）× 100（保留一位小数，无审核记录为 0）、
 * 最近一次预约时间（按预约创建时间取最新）
 *
 * @author reservation-team
 */
@Data
public class UserStatsVO {

    /** 累计预约次数（全部状态记录数） */
    private Long totalReservations;

    /** 本月预约数（预约日期属于当前自然月） */
    private Long monthReservations;

    /** 审核通过率（百分比，一位小数，0-100） */
    private Double approvalRate;

    /** 最近一次预约时间（创建时间最新的一条，无记录为 null） */
    private LocalDateTime lastReservationTime;
}
