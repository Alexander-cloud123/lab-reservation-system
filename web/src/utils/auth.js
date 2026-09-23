/**
 * 登录态本地存取工具（localStorage）
 * 独立模块避免 request.js 与 stores 循环依赖
 */

const TOKEN_KEY = 'reservation_token'
const USER_KEY = 'reservation_user'
const AI_ENABLED_KEY = 'reservation_ai_enabled'

/** 获取本地 Token */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

/** 保存 Token 与用户信息 */
export function saveAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 清除本地登录态（含 AI 开关：开关随登录下发，登出后不应残留） */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(AI_ENABLED_KEY)
}

/** 获取本地用户信息 */
export function getStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

/**
 * 读取本地 AI 开关（登录接口下发后落盘）。
 * 返回 true/false 表示已知；缺失或非法一律返回 null（未知，交由探测决定显隐）。
 */
export function getStoredAiEnabled() {
  const raw = localStorage.getItem(AI_ENABLED_KEY)
  if (raw === 'true') {
    return true
  }
  if (raw === 'false') {
    return false
  }
  return null
}

/** 保存 AI 开关（只接受布尔值；非布尔按「未知」处理并清除本地值） */
export function saveAiEnabled(value) {
  if (typeof value === 'boolean') {
    localStorage.setItem(AI_ENABLED_KEY, String(value))
  } else {
    localStorage.removeItem(AI_ENABLED_KEY)
  }
}
