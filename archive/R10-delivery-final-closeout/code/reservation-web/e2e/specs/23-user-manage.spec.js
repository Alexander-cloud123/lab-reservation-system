/**
 * 管理端 23 —— 用户管理（/admin/users，UserManage.vue）
 *
 * 覆盖：账号关键词查询、禁用/启用状态切换（含“禁用后不能登录”）、重置密码、分页。
 *
 * 安全边界（重要）：绝不触碰 admin / zhangsan / lisi / wangwu / zhaoliu —— 其他并行任务正在用它们登录。
 * 所有状态切换与密码重置都在本用例新建的 'e2e_' 用户上完成。
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { expectOk } from '../helpers/api'
import { registerUser, rawLogin, uniqueTag } from '../helpers/admin'
import { searchItem, rowOf, setPageSize, expectMessage, readTotal } from '../helpers/ui'

/** 只允许操作自建 e2e_ 用户（用户名与种子账号无交集，天然隔离） */
const SEED_ACCOUNTS = ['admin', 'zhangsan', 'lisi', 'wangwu', 'zhaoliu']

async function createE2eUser(page, over = {}) {
  const username = over.username || uniqueTag('e2e_')
  const password = over.password || 'e2e123456'
  const name = over.name || `E2E用户${username.slice(-4)}`
  const studentNo = over.studentNo || `E2E${String(Date.now()).slice(-8)}`
  expectOk(
    await registerUser(page.request, {
      username,
      password,
      confirmPassword: password,
      name,
      studentNo,
      phone: '13800000000',
      email: `${username}@example.com`
    }),
    `注册测试用户 ${username}`
  )
  return { username, password, name, studentNo }
}

async function searchUser(page, keyword) {
  await searchItem(page, '关键词').getByRole('textbox').fill(keyword)
  await page.getByRole('button', { name: '查询' }).click()
}

test.describe('管理端-用户管理（UserManage）', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/users' })
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test('按账号关键词查询用户', async ({ page }) => {
    const u = await createE2eUser(page)
    await searchUser(page, u.username)

    const row = rowOf(page, u.username)
    await expect(row).toBeVisible()
    await expect(row).toContainText(u.name)
    await expect(row.locator('.el-tag', { hasText: '学生' })).toBeVisible()
    await expect(row.locator('.el-tag', { hasText: '正常' })).toBeVisible()
    // 精确命中：其他账号不应出现在结果里
    for (const account of SEED_ACCOUNTS) {
      await expect(rowOf(page, account), `关键词为唯一账号时不应混入 ${account}`).toHaveCount(0)
    }

    // 关键词→学号 亦可命中
    await searchUser(page, u.studentNo)
    await expect(rowOf(page, u.username)).toBeVisible()
  })

  test('禁用/启用切换：禁用后无法登录，启用后恢复', async ({ page }) => {
    const u = await createE2eUser(page)
    await searchUser(page, u.username)

    const row = () => rowOf(page, u.username)
    await expect(row().locator('.el-tag', { hasText: '正常' })).toBeVisible()

    // 禁用（二次确认）
    await row().getByRole('button', { name: '禁用' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('禁用确认')
    await box.getByRole('button', { name: '确定禁用' }).click()
    await expectMessage(page, '禁用成功')
    await expect(row().locator('.el-tag', { hasText: '禁用' })).toBeVisible()

    const denied = await rawLogin(page.request, u.username, u.password, 0)
    expect(denied.body && denied.body.code, `禁用用户不应能登录：${denied.text.slice(0, 120)}`).not.toBe(200)

    // 启用
    await row().getByRole('button', { name: '启用' }).click()
    await page.locator('.el-message-box').getByRole('button', { name: '确定启用' }).click()
    await expectMessage(page, '启用成功')
    await expect(row().locator('.el-tag', { hasText: '正常' })).toBeVisible()

    const allowed = await rawLogin(page.request, u.username, u.password, 0)
    expect(allowed.body && allowed.body.code, `启用后应可登录：${allowed.text.slice(0, 120)}`).toBe(200)
  })

  test('重置密码为默认 123456', async ({ page }) => {
    const u = await createE2eUser(page, { password: 'e2ePass123' })
    await searchUser(page, u.username)

    const row = rowOf(page, u.username)
    await row.getByRole('button', { name: '重置密码' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toContainText('重置密码确认')
    await box.getByRole('button', { name: '确定重置' }).click()
    await expectMessage(page, '密码已重置为默认密码 123456')

    const withDefault = await rawLogin(page.request, u.username, '123456', 0)
    expect(withDefault.body && withDefault.body.code, `重置后应可用 123456 登录：${withDefault.text.slice(0, 120)}`).toBe(200)

    const withOld = await rawLogin(page.request, u.username, 'e2ePass123', 0)
    expect(withOld.body && withOld.body.code, '原密码应已失效').not.toBe(200)
  })

  test('分页：翻页与每页条数切换', async ({ page }) => {
    // 10 条/页 → 需要 >10 个用户才能验证翻页，补齐自建 e2e_ 用户
    for (let i = 0; i < 6; i++) {
      await createE2eUser(page)
    }
    await page.reload()
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()

    await expect(page.locator('.el-pagination__total')).toHaveText(/共\s*\d+\s*条/)
    const total = await readTotal(page)
    expect(total, '用户总数应超过单页 10 条').toBeGreaterThan(10)

    // 第 1 页满 10 条（总数实时读取，避免并行任务同时写入造成偏差）
    await expect(page.locator('tr.el-table__row')).toHaveCount(Math.min(await readTotal(page), 10))
    await expect(page.locator('.el-pager li.is-active')).toHaveText('1')

    // 下一页
    await page.locator('.el-pagination .btn-next').click()
    await expect(page.locator('.el-pager li.is-active')).toHaveText('2')
    expect(await page.locator('tr.el-table__row').count(), '第 2 页应有数据').toBe(Math.min(total - 10, 10))

    // 每页 20 条：EP 切换每页条数不会主动回到第 1 页（仅在页码越界时才夹紧），需显式回第 1 页
    await setPageSize(page, '20条/页')
    await page.locator('.el-pager li').first().click()
    await expect(page.locator('.el-pager li.is-active')).toHaveText('1')
    await expect(page.locator('tr.el-table__row')).toHaveCount(Math.min(await readTotal(page), 20))
  })
})