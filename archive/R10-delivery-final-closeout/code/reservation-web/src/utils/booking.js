/**
 * 预约提交前置校验公共纯函数（N3 收口：ClassroomDetail / CalendarOverview / AiQuickReserve 三入口统一口径）
 * 纯函数约束：不 import Vue、不 import Element Plus、不自行发起请求，可在 Node 下直接单测。
 *
 * 规则来源：后端 /api/config/booking-rules 下发（后端 Constants 为唯一来源），
 * 由路由守卫在进入受保护页面时调用 setBookingRules 注入；接口不可用时回退下方兜底默认值，
 * 校验永不中断。兜底值仅作降级使用，不再是需要与后端手工同步的「镜像常量」。
 */

/** 兜底默认规则（后端接口不可用时的降级值，与后端当前口径一致） */
const FALLBACK_RULES = { slotStart: '08:00', slotEnd: '22:00', maxReservationHours: 8 }

/** 当前生效的预约规则（默认兜底值，收到后端下发后覆盖） */
const rules = { ...FALLBACK_RULES }

/**
 * 注入后端下发的预约规则；字段缺失或非法时保留原值，避免坏数据污染校验口径。
 * @param {{ slotStart?: string, slotEnd?: string, maxReservationHours?: number }|null} payload
 */
export function setBookingRules(payload) {
  if (!payload) return
  if (typeof payload.slotStart === 'string' && payload.slotStart) rules.slotStart = payload.slotStart
  if (typeof payload.slotEnd === 'string' && payload.slotEnd) rules.slotEnd = payload.slotEnd
  const hours = Number(payload.maxReservationHours)
  if (Number.isFinite(hours) && hours > 0) rules.maxReservationHours = hours
}

/** 读取当前生效规则快照（供测试与调试） */
export function getActiveBookingRules() {
  return { ...rules }
}

/**
 * 校验一次预约提交的日期与时段组合。
 * 依次校验：① 三个字段必填 → ② 开始 < 结束 → ③ 结束-开始 ≤ 单次时长上限 →
 * ④ 时间落在可预约窗口内 → ⑤ 若 reserveDate 为「今天」，开始时间必须晚于 now。
 *
 * @param {{ reserveDate: string, startTime: string, endTime: string, now?: Date }} param
 *   reserveDate 形如 YYYY-MM-DD；startTime / endTime 形如 HH:mm；now 默认当前时间（可注入以便测试）
 * @returns {{ ok: boolean, message: string }} ok=false 时 message 为可直接展示的提示文案
 */
export function validateBooking({ reserveDate, startTime, endTime, now = new Date() }) {
  // ① 三个字段必填
  if (!reserveDate || !startTime || !endTime) {
    return { ok: false, message: '请选择预约日期与开始、结束时间' }
  }
  // ② 开始 < 结束（HH:mm 定宽字符串比较与数值比较等价）
  if (startTime >= endTime) {
    return { ok: false, message: '开始时间必须早于结束时间' }
  }
  // ③ 单次时长上限
  const startMin = toMinutes(startTime)
  const endMin = toMinutes(endTime)
  if (endMin - startMin > rules.maxReservationHours * 60) {
    return { ok: false, message: `单次预约时长不能超过 ${rules.maxReservationHours} 小时` }
  }
  // ④ 可预约窗口
  if (startTime < rules.slotStart || endTime > rules.slotEnd) {
    return { ok: false, message: `可预约时段为 ${rules.slotStart}-${rules.slotEnd}` }
  }
  // ⑤ 今天已过时刻（reserveDate 为今天时，开始时间必须晚于当前时刻）
  if (reserveDate === formatDate(now)) {
    const nowMin = now.getHours() * 60 + now.getMinutes()
    if (startMin <= nowMin) {
      return { ok: false, message: '该时刻已过，请选择今天稍后的时间' }
    }
  }
  return { ok: true, message: '' }
}

/** HH:mm → 当日分钟数 */
function toMinutes(time) {
  const [h, m] = time.split(':').map(Number)
  return h * 60 + m
}

/** Date → YYYY-MM-DD（与后端 / 前端 el-date-picker value-format 一致） */
function formatDate(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
