import request from '@/utils/request'

/**
 * 系统配置下发接口（只读，登录即可访问）
 */

/** 预约规则：可预约时段窗口 + 单次预约时长上限（后端 Constants 为唯一来源，替代前端手工镜像常量） */
export function getBookingRules() {
  // silent：配置拉取失败静默降级为前端兜底默认值，不弹错误提示打断用户
  return request.get('/config/booking-rules', { silent: true })
}
