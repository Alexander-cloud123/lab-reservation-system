/**
 * 管理端 E2E 辅助（新增文件；不改动既有 e2e/helpers/*）
 *
 * 职责：为管理端 6 个页面用例提供「接口造前置数据 / 收尾清理 / 数据交叉校验」能力。
 * 断言仍以页面表现为准，接口只用于造数据、清理与交叉复核。
 */
import { expect } from '@playwright/test'
import { API_BASE } from './auth'
import { expectOk, checkConflict, createReservation, auditReservation, listManage } from './api'

/** 与 helpers/api.js 的 call 同构（此处独立实现，避免修改既有文件） */
async function call(request, token, method, path, { data, params } = {}) {
  const url = new URL(API_BASE + path)
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
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
    // 非 JSON（如导出文件流）
  }
  return { status: resp.status(), body, text }
}

let seq = 0
/** 唯一后缀：保证每次运行、以及并行任务的测试数据互不碰撞（用例可重复执行的前提） */
export function uniqueTag(prefix = 'e2e_') {
  return `${prefix}${Date.now().toString(36)}${(++seq).toString(36)}`
}

/* ================= 教室（管理端） ================= */

export function listClassroomsManage(request, token, params) {
  return call(request, token, 'GET', '/api/classroom/manage', { params })
}
export function createClassroomApi(request, token, dto) {
  return call(request, token, 'POST', '/api/classroom/manage', { data: dto })
}
export function updateClassroomApi(request, token, dto) {
  return call(request, token, 'PUT', '/api/classroom/manage', { data: dto })
}
export function deleteClassroomApi(request, token, id) {
  return call(request, token, 'DELETE', `/api/classroom/manage/${id}`)
}
export function setClassroomStatusApi(request, token, id, status) {
  return call(request, token, 'PUT', `/api/classroom/manage/${id}/status`, { data: { status } })
}
export function batchClassroomStatusApi(request, token, ids, status) {
  return call(request, token, 'POST', '/api/classroom/manage/batch-status', { data: { ids, status } })
}

/** 教室 id → 记录（构造记录页「教室」筛选下拉的展示文案需要真实 name/building/roomNo） */
export async function fetchClassroomMap(request, token) {
  const data = expectOk(await listClassroomsManage(request, token, { page: 1, size: 500 }), '管理端教室列表')
  return new Map((data.records || []).map((r) => [r.id, r]))
}

/* ================= 用户（注册 / 管理端） ================= */

export function registerUser(request, dto) {
  return call(request, null, 'POST', '/api/user/register', { data: dto })
}
export function listUsersManage(request, token, params) {
  return call(request, token, 'GET', '/api/user/manage', { params })
}
export function setUserStatusApi(request, token, id, status) {
  return call(request, token, 'PUT', `/api/user/manage/${id}/status`, { data: { status } })
}
export function resetUserPasswordApi(request, token, id) {
  return call(request, token, 'PUT', `/api/user/manage/${id}/password`)
}
/** 直调登录（用于校验「禁用后不能登录 / 重置后只能用默认密码登录」） */
export function rawLogin(request, username, password, role = 0) {
  return call(request, null, 'POST', '/api/user/login', { data: { username, password, role } })
}

/* ================= 预约导出（xlsx 文件流） ================= */

export async function exportReservationsApi(request, token, params) {
  const url = new URL(API_BASE + '/api/reservation/export')
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
  }
  const resp = await request.fetch(url.toString(), {
    method: 'GET',
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  const buf = await resp.body()
  return { status: resp.status(), contentType: resp.headers()['content-type'] || '', bytes: buf.length }
}

/* ================= 预约前置数据（冲突检测探路 + 真实链路审核） ================= */

/** 候选时段（避开 18:00 之后，贴合常规开放时段） */
export const FREE_SLOTS = [
  ['08:00', '10:00'],
  ['10:00', '12:00'],
  ['12:00', '14:00'],
  ['14:00', '16:00'],
  ['16:00', '18:00']
]

/** 两个时段是否（同一教室同一日期下）重叠 */
export function slotOverlaps(a, b) {
  return a.classroomId === b.classroomId && a.date === b.date && a.startTime < b.endTime && a.endTime > b.startTime
}

/**
 * 依次探测教室 × 日期 × 时段，返回第一个无冲突的时段。
 * 为什么需要：预约记录没有删除接口，固定时段在第二次运行时必然冲突，探测空闲时段才能保证用例可重复。
 *
 * avoid：本用例内已占用/已计划的时段列表。冲突检测只认「已通过」记录（api.js 注释亦如此说明），
 *   因此同一次运行内连续创建的待审核记录不会被冲突检测拦住，可能落到同一时段；
 *   而批量审核会校验「同批记录时段重叠」并整批拒绝，故必须自行避让。
 */
export async function findFreeSlot(request, token, { classroomIds, dates, avoid = [] }) {
  for (const classroomId of classroomIds) {
    for (const date of dates) {
      for (const [startTime, endTime] of FREE_SLOTS) {
        const candidate = { classroomId, date, startTime, endTime }
        if (avoid.some((s) => slotOverlaps(candidate, s))) continue
        const data = expectOk(
          await checkConflict(request, token, { classroomId, date, startTime, endTime }),
          `冲突检测 教室${classroomId} ${date} ${startTime}-${endTime}`
        )
        if (data && data.conflict === false) {
          return candidate
        }
      }
    }
  }
  throw new Error(`未找到空闲时段：教室[${classroomIds}] 日期[${dates}]`)
}

/**
 * 造一条 E2E 预约（自动挑空闲时段）；approve=true 时按真实链路「提交 → 审核通过」，
 * 这样出来的记录一定计入冲突检测，可被后续查询稳定命中。
 * @param {Array} [opts.avoid] 本用例内要避开的时段（见 findFreeSlot）
 */
export async function seedReservation(request, { studentToken, adminToken, classroomIds, dates, purpose, approve = false, avoid = [] }) {
  const slot = await findFreeSlot(request, studentToken, { classroomIds, dates, avoid })
  const id = expectOk(
    await createReservation(request, studentToken, {
      classroomId: slot.classroomId,
      reserveDate: slot.date,
      startTime: slot.startTime,
      endTime: slot.endTime,
      purpose
    }),
    `造预约「${purpose}」`
  )
  if (approve) {
    expectOk(await auditReservation(request, adminToken, id, 1), `审核通过 id=${id}`)
  }
  return { id, purpose, ...slot }
}

/** 按用途精确定位管理端记录（跨页扫描，避免依赖“第一条”） */
export async function findManageByPurpose(request, token, purpose, { status, startDate, endDate, scanPages = 3 } = {}) {
  for (let page = 1; page <= scanPages; page++) {
    const data = expectOk(await listManage(request, token, { page, size: 100, status, startDate, endDate }), '全量预约查询')
    const hit = (data.records || []).find((r) => r.purpose === purpose)
    if (hit) return hit
    if (!data.records || data.records.length < 100) break
  }
  return null
}

/** 断言仍在（用于「删除被拒后数据未被破坏」这类反向校验） */
export function expectPresent(value, ctx) {
  expect(value, ctx).toBeTruthy()
  return value
}