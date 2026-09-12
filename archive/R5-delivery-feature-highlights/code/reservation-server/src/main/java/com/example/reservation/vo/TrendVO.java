package com.example.reservation.vo;

import lombok.Data;

/**
 * 数据看板-月度预约趋势视图对象（R5 亮点功能，需求文档 2.4 第 13 页折线图）
 * 口径：按预约日期（reserve_date）归属自然月，统计区间内【全部状态】预约条数；
 * 仅返回区间内有数据的月份，按月份升序
 *
 * @author reservation-team
 */
@Data
public class TrendVO {

    /** 月份（yyyy-MM，按此升序） */
    private String month;

    /** 该月预约条数 */
    private Long count;
}
