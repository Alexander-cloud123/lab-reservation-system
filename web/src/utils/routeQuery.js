/**
 * URL 查询参数解析与规范化工具。
 * 仅处理路由标量，不保存业务数据；数组参数视为非法值并回退默认值。
 */

export function queryText(value) {
  if (Array.isArray(value) || value === null || value === undefined) return ''
  return String(value).trim()
}

export function queryEnum(value, allowed, fallback = '') {
  const text = queryText(value)
  return allowed.map(String).includes(text) ? text : fallback
}

export function queryInteger(value, { fallback = 1, min = 1, allowed = null } = {}) {
  const text = queryText(value)
  if (!/^\d+$/.test(text)) return fallback
  const parsed = Number(text)
  if (!Number.isSafeInteger(parsed) || parsed < min) return fallback
  if (allowed && !allowed.includes(parsed)) return fallback
  return parsed
}

export function queryDate(value) {
  const text = queryText(value)
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!match) return ''
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return ''
  }
  return text
}

/** 删除空值，同时保留 0 等有效状态值。 */
export function compactQuery(query) {
  return Object.fromEntries(
    Object.entries(query).filter(([, value]) => value !== '' && value !== null && value !== undefined)
  )
}

/** 比较规范化后的 query；键顺序不影响结果。 */
export function sameQuery(left, right) {
  const normalize = (query) => {
    const compact = compactQuery(query || {})
    return Object.fromEntries(
      Object.keys(compact)
        .sort()
        .map((key) => [key, queryText(compact[key])])
    )
  }
  return JSON.stringify(normalize(left)) === JSON.stringify(normalize(right))
}
