import request from '@/utils/request'

/**
 * 统计模块接口封装（R5 数据看板，管理员专属）
 * 全部为只读统计：首页数据概览 / 教室使用率排行 / 月度预约趋势 / 热门时段分布
 */

/** 管理端首页数据概览：今日预约 / 待审核 / 教室总数 / 用户总数 */
export function getOverview() {
  return request.get('/stats/overview')
}

/** 教室使用率排行（柱状图）：startDate/endDate 可选，缺省近 30 天 */
export function getUsageRate(params) {
  return request.get('/stats/usage-rate', { params })
}

/** 月度预约趋势（折线图） */
export function getTrend(params) {
  return request.get('/stats/trend', { params })
}

/** 热门时段分布（饼图） */
export function getTimeDistribution(params) {
  return request.get('/stats/time-distribution', { params })
}
