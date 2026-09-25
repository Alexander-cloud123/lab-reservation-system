/**
 * 登录态辅助
 *
 * 键名与 src/utils/auth.js 严格一致，改前端存储键必须同步改这里：
 *   reservation_token / reservation_user / reservation_ai_enabled
 *
 * 策略：绝大部分用例走「接口登录 + 注入 localStorage」直达目标页面（快、不受登录页 UI 改动影响）；
 * 登录页本身的表单交互、错误提示另有专门用例覆盖，不在此重复。
 */
import { expect } from '@playwright/test'

export const API_BASE = process.env.E2E_API_BASE || `http://127.0.0.1:${process.env.E2E_API_PORT || '8080'}`

/** 种子账号（database/init_db.sql）：管理员系统预置，学生自主注册 */
export const ACCOUNTS = {
  admin: { username: 'admin', password: 'admin123', role: 1 },
  student: { username: 'zhangsan', password: '123456', role: 0 },
  student2: { username: 'lisi', password: '123456', role: 0 }
}

/** 种子用户 ID（库中固定），用于构造"他人预约"这类跨用户场景 */
export const USER_IDS = { admin: 1, zhangsan: 2, lisi: 3, wangwu: 4, zhaoliu: 5 }

/**
 * 调后端登录接口拿 token 与用户信息（含 aiEnabled 下发字段）
 * @returns {Promise<{token: string, user: object, aiEnabled?: boolean}>} LoginVO.data
 */
export async function apiLogin(request, who = 'student') {
  const acc = ACCOUNTS[who]
  expect(acc, `未定义的账号别名：${who}`).toBeTruthy()
  const resp = await request.post(`${API_BASE}/api/user/login`, { data: acc })
  const text = await resp.text()
  const body = resp.ok() ? JSON.parse(text) : null
  expect(body && body.code, `登录失败（${who}）：HTTP ${resp.status()} ${text}`).toBe(200)
  expect(body.data && body.data.token, `登录返回缺 token：${text}`).toBeTruthy()
  return body.data
}

/**
 * 注入登录态并直达目标页面。
 * 用 addInitScript 而非先 goto('/login') 再写 localStorage：脚本在每次导航前执行，
 * 首屏即为登录态，省掉一次中间跳转，也不受"已登录访问登录页会跳回首页"守卫影响。
 *
 * @param {import('@playwright/test').Page} page
 * @param {object} opts
 * @param {'admin'|'student'|'student2'} [opts.who='student'] 账号别名
 * @param {string} [opts.path]  登录后跳转的路由；不传则停在空白页仅注入登录态
 * @param {boolean|null} [opts.aiEnabled] 写入本地 AI 开关；null=不写（模拟"未知"，走探测）
 */
export async function loginAs(page, { who = 'student', path, aiEnabled = null } = {}) {
  const data = await apiLogin(page.request, who)
  await page.addInitScript(
    ([token, user, ai]) => {
      localStorage.setItem('reservation_token', token)
      localStorage.setItem('reservation_user', JSON.stringify(user))
      if (ai === true) {
        localStorage.setItem('reservation_ai_enabled', 'true')
      } else if (ai === false) {
        localStorage.setItem('reservation_ai_enabled', 'false')
      }
    },
    [data.token, data.user, aiEnabled]
  )
  if (path) {
    await page.goto(path)
  }
  return data
}

/** 仅清除登录态（验证未登录跳转用） */
export async function clearAuth(page) {
  await page.addInitScript(() => {
    localStorage.removeItem('reservation_token')
    localStorage.removeItem('reservation_user')
  })
}