/**
 * 登录态本地存取工具（localStorage）
 * 独立模块避免 request.js 与 stores 循环依赖
 */

const TOKEN_KEY = 'reservation_token'
const USER_KEY = 'reservation_user'

/** 获取本地 Token */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

/** 保存 Token 与用户信息 */
export function saveAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

/** 清除本地登录态 */
export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/** 获取本地用户信息 */
export function getStoredUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch (e) {
    return null
  }
}
