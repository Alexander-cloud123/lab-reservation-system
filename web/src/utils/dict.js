/**
 * 业务字典统一来源
 * 纯函数约束：不 import Vue / Element Plus，可在 Node 下直接验证
 */

/* ========== 教室类型 ========== */

/** 数字形态（与后端 classroom.type 一致）：供 value 为数字的下拉与转换使用 */
export const ROOM_TYPES = [
  { value: 1, label: '普通教室' },
  { value: 2, label: '实验室' },
  { value: 3, label: '机房' }
]

/**
 * 文案形态：仅供 value 必须为「中文字符串」的场景使用。
 * 为什么需要它：AI 快速预约的 roomType 会被后端解析结果回填为中文字符串
 * （AiQuickReserve.vue `res.data.roomType || ''`），该处 el-select 必须保持字符串 value，
 * 否则回填值无法匹配选项 → 下拉显示空白。不要把这处改成数字值。
 */
export const ROOM_TYPE_LABELS = ROOM_TYPES.map((t) => t.label)

/** 数字 → 文案；未知值兜底 '未知' */
export function typeText(type) {
  // 双端 String 归一：精确复现旧实现「对象键索引」语义
  // （旧写法 {1:'普通教室'}['1'] 命中、[' 1 ']/[null]/[true] 不命中；Number() 会把 null/''/true 折叠成 0/1 造成误命中）
  const hit = ROOM_TYPES.find((t) => String(t.value) === String(type))
  return hit ? hit.label : '未知'
}

/** 文案 → 数字；未匹配返回 null（替代 AiQuickReserve 内的 TYPE_MAP） */
export function typeValue(label) {
  const hit = ROOM_TYPES.find((t) => t.label === label)
  return hit ? hit.value : null
}

/* ========== 预约状态 ========== */

export const RES_STATUS = [
  { value: 0, label: '待审核', tag: 'warning' },
  { value: 1, label: '已通过', tag: 'success' },
  { value: 2, label: '已驳回', tag: 'danger' },
  { value: 3, label: '已取消', tag: 'info' }
]

/** 状态 → 文案；未知值兜底 '未知' */
export function statusText(status) {
  const hit = RES_STATUS.find((s) => String(s.value) === String(status))
  return hit ? hit.label : '未知'
}

/** 状态 → Element Plus tag 类型；未知值兜底 'info' */
export function statusTagType(status) {
  const hit = RES_STATUS.find((s) => String(s.value) === String(status))
  return hit ? hit.tag : 'info'
}
