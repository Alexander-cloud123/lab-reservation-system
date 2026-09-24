package com.example.reservation.controller;

import com.example.reservation.common.Result;
import com.example.reservation.service.StatsService;
import com.example.reservation.vo.OverviewVO;
import com.example.reservation.vo.TimeDistributionVO;
import com.example.reservation.vo.TrendVO;
import com.example.reservation.vo.UsageRateVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计模块控制器（R5 数据看板，只读）
 * 权限：/api/stats 前缀在 Constants.ADMIN_API_PREFIXES 内，拦截器自动校验管理员角色；
 * 未登录访问返回 401，学生 Token 访问返回 403
 *
 * @author reservation-team
 */
@Tag(name = "统计模块", description = "数据看板：教室使用率排行 / 月度预约趋势 / 热门时段分布（管理员）")
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Resource
    private StatsService statsService;

    /**
     * 管理端首页数据概览：今日预约 / 待审核 / 教室总数 / 用户总数（需求文档 1.3 管理员端第 8 页）
     * 权限：路径在 Constants.ADMIN_API_PREFIXES 的 /api/stats 前缀内，拦截器自动校验管理员角色
     */
    @Operation(summary = "统计-首页数据概览", description = "今日预约（全部状态）、待审核（不限日期）、教室总数（含停用）、用户总数")
    @GetMapping("/overview")
    public Result<OverviewVO> overview() {
        return Result.success(statsService.overview());
    }

    /**
     * 教室使用率排行（柱状图）：区间内已通过预约占用小时数 /（天数 × 14h），全部教室参与排行
     */
    @Operation(summary = "统计-教室使用率排行", description = "口径：占用小时/(天数×14h)×100，保留 1 位小数；全部教室参与，按使用率倒序")
    @GetMapping("/usage-rate")
    public Result<List<UsageRateVO>> usageRate(@RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate) {
        return Result.success(statsService.usageRate(startDate, endDate));
    }

    /**
     * 月度预约趋势（折线图）：按预约日期归属自然月统计全部状态预约条数
     */
    @Operation(summary = "统计-月度预约趋势", description = "按 reserve_date 归属自然月统计全部状态预约条数，月份升序")
    @GetMapping("/trend")
    public Result<List<TrendVO>> trend(@RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate) {
        return Result.success(statsService.trend(startDate, endDate));
    }

    /**
     * 热门时段分布（饼图）：按开始时间分桶统计已通过预约条数及占比
     */
    @Operation(summary = "统计-热门时段分布", description = "按开始时间分桶（08-10/10-12/14-16/16-18/19-21/其他）统计已通过预约占比")
    @GetMapping("/time-distribution")
    public Result<List<TimeDistributionVO>> timeDistribution(@RequestParam(required = false) String startDate,
                                                             @RequestParam(required = false) String endDate) {
        return Result.success(statsService.timeDistribution(startDate, endDate));
    }
}
