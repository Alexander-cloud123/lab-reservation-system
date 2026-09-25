/**
 * 接口直调辅助（构造前置条件 / 收尾清理）
 *
 * 用途：E2E 主路径必须走浏览器点击，但"前置数据"（如已通过的预约）用接口造更快更稳；
 * 断言仍以页面表现为准。每个用例的前置数据都带 E2E 用途前缀，便于统一清理。
 */
import { expect } from '@playwright/test'
import { API_BASE } from './auth'

/** 断言 Result 结构并返回 data；失败信息带原始响应体，便于定位 */
export function expectOk(res, ctx) {
  expect(res.body, `${ctx}：响应非 JSON —— HTTP ${res.status} ${res.text.slice(0, 200)}`).toBeTruthy()
  expect(res.body.code, `${ctx}：业务码 ${res.body.code} —— ${res.body.message}`).toBe(200)
  return res.body.data
}

/** 断言业务失败（用于校验"后端二次校验/权限/规则拦截"确实生效） */
export function expectBizFail(res, code, ctx) {
  expect(res.body, `${ctx}：响应非 JSON —— HTTP ${res.status}`).toBeTruthy()
  expect(res.body.code, `${ctx}：期望业务码 ${code}，实际 ${res.body.code} —— ${res.body.message}`).toBe(code)
  return res.body
}

async function call(request, token, method, path, { data, params } = {}) {
  const url = new URL(API_BASE + path)
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') {
        url.searchParams.set(k, String(v))
      }
    }
  }
  const resp = await request.fetch(url.toString(), {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    data
  })
  const text = await resp.text()
  let body = null
  try {
    body = JSON.parse(text)
  } catch {
    // 非 JSON 响应（如 Excel 导出文件流）保留 text 供调用方判断
  }
  return { status: resp.status(), body, text }
}

/* ---------------- 预约 ---------------- */

/** 冲突检测：返回 { conflict, reason } */
export function checkConflict(request, token, { classroomId, date, startTime, endTime }) {
  return call(request, token, 'GET', '/api/reservation/conflict', {
    params: { classroomId, date, startTime, endTime }
  })
}

/** 提交预约（返回预约 id） */
export function createReservation(request, token, dto) {
  return call(request, token, 'POST', '/api/reservation', { data: dto })
}

/** 我的预约分页 */
export function listMine(request, token, params) {
  return call(request, token, 'GET', '/api/reservation/mine', { params })
}

/** 管理员全量查询 */
export function listManage(request, token, params) {
  return call(request, token, 'GET', '/api/reservation/manage', { params })
}

/** 取消预约（仅本人；开始前 1 小时内禁止） */
export function cancelReservation(request, token, id) {
  return call(request, token, 'PUT', `/api/reservation/${id}/cancel`)
}

/** 审核：status 1=通过 2=驳回（驳回必填 auditRemark） */
export function auditReservation(request, token, id, status, auditRemark) {
  return call(request, token, 'PUT', `/api/reservation/${id}/audit`, { data: { status, auditRemark } })
}

/** 批量审核：仅待审核记录可参与 */
export function batchAudit(request, token, ids, status, auditRemark) {
  return call(request, token, 'POST', '/api/reservation/batch-audit', { data: { ids, status, auditRemark } })
}

/** 日历区间查询 */
export function listCalendar(request, token, params) {
  return call(request, token, 'GET', '/api/reservation/calendar', { params })
}

/* ---------------- 教室 ---------------- */

/** 学生端教室列表 */
export function listClassrooms(request, token, params) {
  return call(request, token, 'GET', '/api/classroom/list', { params })
}

/** 教室详情（可选 date） */
export function getClassroom(request, token, id, date) {
  return call(request, token, 'GET', `/api/classroom/${id}`, { params: { date } })
}

/* ---------------- 收藏 ---------------- */

/** 收藏/取消收藏（toggle），返回 true=已收藏 */
export function toggleFavorite(request, token, classroomId) {
  return call(request, token, 'POST', `/api/favorite/${classroomId}`)
}

/** 我的收藏列表 */
export function listFavorites(request, token) {
  return call(request, token, 'GET', '/api/favorite/list')
}

/* ---------------- 统计 ---------------- */

export function getOverview(request, token) {
  return call(request, token, 'GET', '/api/stats/overview')
}

/* ---------------- 组合前置 ---------------- */

/**
 * 造一条「已通过」预约（冲突检测只认已通过；审核通过会复查冲突，故按真实链路：提交→审核通过）
 * @returns {Promise<number>} 预约 id
 */
export async function seedApprovedReservation(request, { adminToken, studentToken, classroomId, date, startTime, endTime, purpose }) {
  const created = await createReservation(request, studentToken, {
    classroomId,
    reserveDate: date,
    startTime,
    endTime,
    purpose
  })
  const id = expectOk(created, `造已通过预约-提交（教室 ${classroomId} ${date} ${startTime}-${endTime}）`)
  const audited = await auditReservation(request, adminToken, id, 1)
  expectOk(audited, `造已通过预约-审核通过（id=${id}）`)
  return id
}

/**
 * 收尾：尽力取消一条预约（已是终态或超出取消时限时忽略失败，不抛错）
 * 用例清理用，避免因清理失败把已通过的用例判红。
 */
export async function tryCancel(request, token, id) {
  try {
    await cancelReservation(request, token, id)
  } catch {
    // 忽略：清理是尽力而为，不参与断言
  }
}