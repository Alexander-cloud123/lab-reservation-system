package com.example.reservation.service;

import com.example.reservation.vo.TimeDistributionVO;
import com.example.reservation.vo.TrendVO;
import com.example.reservation.vo.UsageRateVO;

import java.util.List;

/**
 * 统计业务接口（R5 数据看板）
 * 全部为只读统计，口径见各方法注释；图表数据必须来自本接口，禁止前端伪造
 *
 * @author reservation-team
 */
public interface StatsService {

    /**
     * 教室使用率排行（柱状图）
     * 口径：使用率 = 区间内该教室【已通过】预约占用小时数 ÷（区间天数 × 每日可预约时长 14h）× 100，
     * 保留 1 位小数；全部教室参与排行（无占用为 0），按使用率倒序
     *
     * @param startDate 区间开始（yyyy-MM-dd，可选，缺省近 30 天）
     * @param endDate   区间结束（yyyy-MM-dd，可选，缺省近 30 天）
     */
    List<UsageRateVO> usageRate(String startDate, String endDate);

    /**
     * 月度预约趋势（折线图）
     * 口径：按预约日期（reserve_date）归属自然月，统计区间内【全部状态】预约条数，月份升序
     */
    List<TrendVO> trend(String startDate, String endDate);

    /**
     * 热门时段分布（饼图）
     * 口径：按预约开始时间分桶（Constants.TIME_SLOT_LABELS），统计区间内【已通过】预约条数及占比
     */
    List<TimeDistributionVO> timeDistribution(String startDate, String endDate);
}
