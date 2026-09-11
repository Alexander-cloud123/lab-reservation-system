package com.example.reservation.vo;

import lombok.Data;

/**
 * 数据看板-热门时段分布视图对象（R5 亮点功能，需求文档 2.4 第 13 页饼图）
 * 口径：按预约开始时间分桶（08:00-10:00 / 10:00-12:00 / 14:00-16:00 / 16:00-18:00 / 19:00-21:00 / 其他），
 * 统计区间内【已通过】预约条数及占比（%，保留 1 位小数，总和约 100）
 *
 * @author reservation-team
 */
@Data
public class TimeDistributionVO {

    /** 时段桶标签（Constants.TIME_SLOT_LABELS） */
    private String slot;

    /** 该时段已通过预约条数 */
    private Long count;

    /** 占比（%，保留 1 位小数） */
    private Double percentage;
}
