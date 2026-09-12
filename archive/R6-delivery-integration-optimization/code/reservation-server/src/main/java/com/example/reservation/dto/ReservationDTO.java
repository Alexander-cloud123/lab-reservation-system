package com.example.reservation.dto;

import lombok.Data;

/**
 * 提交预约请求参数（R3）
 * 校验：教室/日期/时段/用途必填，开始 < 结束；后端二次冲突检测兜底
 *
 * @author reservation-team
 */
@Data
public class ReservationDTO {

    /** 预约教室 ID（必填） */
    private Long classroomId;

    /** 预约日期（必填，yyyy-MM-dd） */
    private String reserveDate;

    /** 开始时间（必填，HH:mm） */
    private String startTime;

    /** 结束时间（必填，HH:mm） */
    private String endTime;

    /** 预约用途（必填） */
    private String purpose;
}
