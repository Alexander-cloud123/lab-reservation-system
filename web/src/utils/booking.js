/**
 * 预约提交前置校验公共纯函数（N3 收口：ClassroomDetail / CalendarOverview / AiQuickReserve 三入口统一口径）
 * 纯函数约束：不 import Vue、不 import Element Plus，可在 Node 下直接单测。
 * 常量与后端 Constants.java 对齐，改动需两处同步：
 *   SLOT_START / SLOT_END        ↔ Constants.DAILY_SLOT_START / DAILY_SLOT_END（08:00 / 22:00）
 *   MAX_RESERVATION_HOURS        ↔ Constants.MAX_RESERVATION_HOURS（8）
 * 真正的「单一来源」应由后端下发配置，本轮不做；此处常量是后端规则的镜像，务必保持同步。
 */

/** 每日可预约窗口左边界（含） */
export const SLOT_START = '08:00'
/** 每日可预约窗口右边界（含） */
export const SLOT_END = '22:00'
/** 单次预约最长时长（小时） */
export const MAX_RESERVATION_HOURS = 8

/**
 * 校验一次预约提交的日期与时段组合。
 * 依次校验：① 三个字段必填 → ② 开始 < 结束 → ③ 结束-开始 ≤ 8 小时 →
 * ④ 时间落在 [SLOT_START, SLOT_END] 内 → ⑤ 若 reserveDate 为「今天」，开始时间必须晚于 now。
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
  if (endMin - startMin > MAX_RESERVATION_HOURS * 60) {
    return { ok: false, message: `单次预约时长不能超过 ${MAX_RESERVATION_HOURS} 小时` }
  }
  // ④ 可预约窗口
  if (startTime < SLOT_START || endTime > SLOT_END) {
    return { ok: false, message: `可预约时段为 ${SLOT_START}-${SLOT_END}` }
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
