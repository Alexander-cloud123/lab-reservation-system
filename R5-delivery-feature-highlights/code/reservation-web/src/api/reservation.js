import request from '@/utils/request'

/**
 * 预约模块接口封装（R3 预约核心）
 * 学生端：conflict / 提交 / mine / cancel；管理端：manage / audit / batch-audit
 */

/** 冲突检测：教室 + 日期 + 时段 → { conflict, reason } */
export function checkConflict(params) {
  return request.get('/reservation/conflict', { params })
}

/** 提交预约（后端二次冲突检测兜底） */
export function submitReservation(data) {
  return request.post('/reservation', data)
}

/** 我的预约列表（status 可选：0/1/2/3） */
export function getMyReservations(params) {
  return request.get('/reservation/mine', { params })
}

/** 取消预约（开始前 1 小时内禁止） */
export function cancelReservation(id) {
  return request.put(`/reservation/${id}/cancel`)
}

/** 管理端：全量预约查询（status/startDate/endDate/keyword） */
export function pageManageReservations(params) {
  return request.get('/reservation/manage', { params })
}

/** 管理端：审核单条预约（status: 1-通过, 2-驳回；驳回必填 auditRemark） */
export function auditReservation(id, data) {
  return request.put(`/reservation/${id}/audit`, data)
}

/** 管理端：批量审核（ids + status；批量驳回必填 auditRemark） */
export function batchAuditReservations(data) {
  return request.post('/reservation/batch-audit', data)
}

/** 日历总览区间查询（R5）：classroomId 可选，startDate/endDate 必填（yyyy-MM-dd） */
export function getCalendarReservations(params) {
  return request.get('/reservation/calendar', { params })
}
