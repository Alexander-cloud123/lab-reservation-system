/**
 * 会话撤销 E2E（Token 版本号持久撤销）
 *
 * 背景：纯 JWT 在到期前无法作废；本项目把「登出 / 改密 / 管理员重置 / 禁用」落到
 * sys_user.token_version 自增，JWT 携带签发时的版本号，拦截器比对不一致即 401。
 * 因此撤销不依赖 Redis（本 spec 断言的正是接口层可见的撤销结果）。
 *
 * 覆盖：
 *   1) POST /api/user/logout 后旧 Token 立即 401，重新登录恢复可用；
 *   2) 管理员重置密码后目标用户旧 Token 立即 401，默认密码 123456 可重新登录
 *      （种子学生密码本就是 123456，重置不改密码，只换版本号——因此本用例可重复执行且不留脏数据）。
 *
 * 数据策略：只走登录/登出/重置接口，不创建预约、不修改业务数据；账号一律使用种子账号。
 */
import { test, expect } from '@playwright/test'
import { ACCOUNTS, API_BASE, apiLogin } from '../helpers/auth'
import { expectOk } from '../helpers/api'

/** 直接调用接口并返回 { status, body, text }（失败态断言需要原始 HTTP 状态） */
async function raw(request, token, method, path, data) {
  const resp = await request.fetch(`${API_BASE}${path}`, {
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
    // 非 JSON 响应保留 text 供失败信息使用
  }
  return { status: resp.status(), body, text }
}

test.describe('会话撤销（Token 版本号）', () => {
  test('退出登录后旧 Token 立即失效，重新登录可恢复', async ({ request }) => {
    const { token } = await apiLogin(request, 'student')

    expectOk(await raw(request, token, 'POST', '/api/user/logout'), '退出登录')

    const stale = await raw(request, token, 'GET', '/api/user/info')
    expect(stale.status, `登出后旧 Token 应被拒绝：${stale.text.slice(0, 120)}`).toBe(401)
    expect(stale.body && stale.body.code).toBe(401)

    // 重新登录签发新版本 Token，立即可用（同时把会话状态恢复给后续用例）
    const fresh = await apiLogin(request, 'student')
    expectOk(await raw(request, fresh.token, 'GET', '/api/user/info'), '重新登录后访问受保护接口')
  })

  test('管理员重置密码后该用户旧 Token 失效，默认密码可重新登录', async ({ request }) => {
    const admin = await apiLogin(request, 'admin')
    const student = await apiLogin(request, 'student')

    // 用管理端查询定位种子学生 id，避免硬编码 id
    const page = expectOk(
      await raw(request, admin.token, 'GET', `/api/user/manage?page=1&size=10&keyword=${ACCOUNTS.student.username}`),
      '按账号查询用户'
    )
    const target = page.records.find((r) => r.username === ACCOUNTS.student.username)
    expect(target, '应能查到种子学生账号').toBeTruthy()

    expectOk(
      await raw(request, admin.token, 'PUT', `/api/user/manage/${target.id}/password`),
      '管理员重置目标用户密码'
    )

    const stale = await raw(request, student.token, 'GET', '/api/user/info')
    expect(stale.status, `重置后旧 Token 应被拒绝：${stale.text.slice(0, 120)}`).toBe(401)
    expect(stale.body && stale.body.code).toBe(401)

    // 重置为默认密码 123456（与种子密码相同），必须重新登录换新版本 Token 才能访问
    const relogin = expectOk(
      await raw(request, null, 'POST', '/api/user/login', ACCOUNTS.student),
      '重置后用默认密码重新登录'
    )
    expectOk(await raw(request, relogin.token, 'GET', '/api/user/info'), '重置后新 Token 访问受保护接口')
  })
})
