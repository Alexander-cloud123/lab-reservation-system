package com.example.reservation.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预约规则配置（前端实时校验口径的唯一来源）
 * 由后端 Constants 派生后下发，替代前端 booking.js 中手工镜像的
 * SLOT_START / SLOT_END / MAX_RESERVATION_HOURS，消除两处同步的隐性维护成本
 *
 * @author reservation-team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingRulesVO {

    /** 每日可预约窗口左边界（含），HH:mm */
    private String slotStart;

    /** 每日可预约窗口右边界（含），HH:mm */
    private String slotEnd;

    /** 单次预约最长时长（小时） */
    private Integer maxReservationHours;
}
