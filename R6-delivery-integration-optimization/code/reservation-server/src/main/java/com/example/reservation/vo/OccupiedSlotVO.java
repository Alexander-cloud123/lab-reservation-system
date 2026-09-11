package com.example.reservation.vo;

import lombok.Data;

/**
 * 预约时段占用信息（教室详情/列表指定日期占用展示）
 * 时段为已通过(1)预约的占用区间
 *
 * @author reservation-team
 */
@Data
public class OccupiedSlotVO {

    /** 开始时间（HH:mm） */
    private String startTime;

    /** 结束时间（HH:mm） */
    private String endTime;

    /** 预约用途 */
    private String purpose;
}
