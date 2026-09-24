package com.example.reservation.vo;

import lombok.Data;

/**
 * 管理端首页数据概览视图对象（需求设计文档 1.3 管理员端第 8 页「首页数据概览」核心数据卡片）
 *
 * <p>口径（写进交付说明，测试按此断言与库中数据一致）：
 * <ol>
 *   <li>今日预约 = 预约日期为今日的预约条数，<b>全部状态</b>（与月度趋势同口径，反映当日预约量）；</li>
 *   <li>待审核 = 状态为待审核(0) 的预约条数，<b>不限日期</b>（反映当前审核工作量）；</li>
 *   <li>教室总数 = 教室表全部记录数（含已停用教室，「总数」口径）；</li>
 *   <li>用户总数 = 用户表全部记录数（含管理员与已禁用账号，「总数」口径）。</li>
 * </ol>
 *
 * @author reservation-team
 */
@Data
public class OverviewVO {

    /** 今日预约条数（预约日期为今日，全部状态） */
    private Long todayReservationCount;

    /** 待审核预约条数（状态待审核(0)，不限日期） */
    private Long pendingAuditCount;

    /** 教室总数（含已停用教室） */
    private Long classroomCount;

    /** 用户总数（含管理员与已禁用账号） */
    private Long userCount;
}