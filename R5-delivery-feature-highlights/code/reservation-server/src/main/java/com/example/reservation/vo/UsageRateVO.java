package com.example.reservation.vo;

import lombok.Data;

/**
 * 数据看板-教室使用率排行视图对象（R5 亮点功能，需求文档 2.4 第 13 页柱状图）
 * 口径：使用率 = 区间内该教室【已通过】预约占用小时数 ÷（区间天数 × 每日可预约时长 14h）× 100，
 * 保留 1 位小数；无已通过预约的教室使用率为 0（仍参与排行展示）
 *
 * @author reservation-team
 */
@Data
public class UsageRateVO {

    /** 教室 ID */
    private Long classroomId;

    /** 教室名称 */
    private String name;

    /** 所属楼栋 */
    private String building;

    /** 教室编号 */
    private String roomNo;

    /** 类型：1-普通教室，2-实验室，3-机房 */
    private Integer type;

    /** 容纳人数 */
    private Integer capacity;

    /** 区间内已通过预约占用小时数（保留 1 位小数） */
    private Double approvedHours;

    /** 教室使用率（%，保留 1 位小数，按此倒序排行） */
    private Double usageRate;
}
