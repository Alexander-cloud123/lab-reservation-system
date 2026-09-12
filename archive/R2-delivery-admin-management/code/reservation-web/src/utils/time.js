import dayjs from 'dayjs'

/** 格式化日期时间 */
export function formatDateTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '-'
}

/** 格式化日期 */
export function formatDate(value) {
  return value ? dayjs(value).format('YYYY-MM-DD') : '-'
}

/** 格式化时间 */
export function formatTime(value) {
  return value ? dayjs(value).format('HH:mm') : '-'
}

/** 判断是否为今天 */
export function isToday(value) {
  return dayjs(value).isSame(dayjs(), 'day')
}
