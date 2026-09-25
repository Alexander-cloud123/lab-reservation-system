/**
 * 稳定造数辅助（E2E 专用，供学生端 5 个页面用例共享）
 *
 * 背景（重要）：
 *  后端会话为「单账号单会话」——Redis 键 auth:token:{userId} 只保留最近一次登录的 Token，
 *  同一账号再次登录会让旧 Token 立即 401（实测确认）。
 *  因此本项目的 E2E 造数必须遵守：
 *    · 需要学生身份调用接口时，一律用「页面 localStorage 里的 Token」（pageToken），
 *      不要在 loginAs 之后再 apiLogin 同一账号，否则页面 Token 会被顶掉；
 *    · 不要在 loginAs 之前 apiLogin 同一账号再用其 Token 做收尾（会 401 静默失败）；
 *    · 管理员 Token 仅在真正要用（造已通过预约）时即时获取，缩短被并行任务顶掉的窗口。
 *
 * 另：并行任务在共享同一套种子数据，故造数一律「先冲突检测挑空档，再提交」，
 * 避免别的任务已占用目标时段导致 400。
 */
import { checkConflict, createReservation, auditReservation } from './api'
import { expectOk } from './api'

/** 读取当前页面已登录用户的 Token（最可靠的“最新有效 Token”） */
export function pageToken(page) {
  return page.evaluate(() => localStorage.getItem('reservation_token'))
}

/** 冲突检测（解析响应体），true = 该时段可预约 */
export async function isSlotFree(request, token, { classroomId, date, startTime, endTime }) {
  const res = await checkConflict(request, token, { classroomId, date, startTime, endTime })
  return !!(res.body && res.body.code === 200 && res.body.data && res.body.data.conflict === false)
}

/**
 * 在候选 (date × slot) 中挑第一个无冲突的时段，造一条「已通过」预约（提交 → 管理员审核通过）。
 * @returns {{id:number,date:string,startTime:string,endTime:string}}
 */
export async function seedApprovedAny(request, { adminToken, studentToken, classroomId, dates, slots, purpose }) {
  for (const date of dates) {
    for (const [startTime, endTime] of slots) {
      if (!(await isSlotFree(request, studentToken, { classroomId, date, startTime, endTime }))) {
        continue
      }
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
      return { id, date, startTime, endTime }
    }
  }
  throw new Error(`候选时段全部被占用，无法造数：教室 ${classroomId} / 日期 ${dates.join(',')}`)
}