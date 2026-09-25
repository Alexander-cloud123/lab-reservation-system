/**
 * 管理端 20 —— 后台首页（/admin/home，AdminWelcome.vue）
 *
 * 覆盖：
 *  1. 4 张数据卡片（今日预约/待审核/教室总数/用户总数）渲染，值为非负整数
 *  2. 卡片数值与 GET /api/stats/overview 逐字段一致（不做绝对值断言，只做一致性断言）
 *  3. 5 个快捷入口卡片点击后跳转到正确路由
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { getOverview, expectOk } from '../helpers/api'

/** [页面文案, 接口字段] */
const FIELDS = [
  ['今日预约', 'todayReservationCount'],
  ['待审核', 'pendingAuditCount'],
  ['教室总数', 'classroomCount'],
  ['用户总数', 'userCount']
]

const QUICK_ENTRIES = [
  ['用户管理', '/admin/users'],
  ['教室资源管理', '/admin/classrooms'],
  ['预约审核', '/admin/audits'],
  ['预约记录', '/admin/records'],
  ['数据看板', '/admin/dashboard']
]

/** 按卡片文案定位单张数据卡（避免 nth-child 长链） */
function statItem(page, label) {
  return page.locator('.stat-item').filter({ has: page.locator('.stat-label', { hasText: label }) })
}

async function readCardValues(page) {
  const values = {}
  for (const [label, field] of FIELDS) {
    const text = (await statItem(page, label).locator('.stat-value').innerText()).trim()
    values[field] = Number(text)
  }
  return values
}

/** 概览接口返回后 Vue 才会重渲染，教室里一定有数据（≥12），用它作为渲染完成信号 */
async function waitCardsRendered(page) {
  await expect(page.locator('.stat-item')).toHaveCount(4)
  await expect
    .poll(async () => Number((await statItem(page, '教室总数').locator('.stat-value').innerText()).trim()))
    .toBeGreaterThan(0)
}

test.describe('管理端-后台首页（AdminWelcome）', () => {
  test('4 张数据卡片渲染，数值为非负整数', async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/home' })
    await expect(page).toHaveURL(/\/admin\/home$/)
    await expect(page.locator('.page-title, .banner-title').first()).toBeVisible()

    // 等概览数据回填后再读值（仅等元素出现时，数值可能还是占位符）
    await waitCardsRendered(page)

    for (const [label] of FIELDS) {
      await expect(statItem(page, label).locator('.stat-label')).toBeVisible()
      const text = (await statItem(page, label).locator('.stat-value').innerText()).trim()
      expect(text, `${label} 应为非负整数，实际「${text}」`).toMatch(/^\d+$/)
      expect(Number(text)).toBeGreaterThanOrEqual(0)
    }
  })

  test('卡片数值与 /api/stats/overview 接口逐字段一致', async ({ page }) => {
    const login = await loginAs(page, { who: 'admin', path: '/admin/home' })
    await waitCardsRendered(page)

    let api = null
    let values = null
    let matched = false
    // 并行任务同时在增删数据，允许“重新加载后重读”；最终必须完全一致才算通过
    for (let i = 0; i < 3 && !matched; i++) {
      const respPromise = page.waitForResponse(
        (r) => r.url().includes('/stats/overview') && r.request().method() === 'GET'
      )
      await page.reload()
      await respPromise
      await waitCardsRendered(page)
      values = await readCardValues(page)
      api = expectOk(await getOverview(page.request, login.token), 'GET /api/stats/overview')
      matched = FIELDS.every(([, field]) => values[field] === api[field])
    }

    expect(matched, `卡片值 ${JSON.stringify(values)} 与接口 ${JSON.stringify(api)} 不一致`).toBe(true)
  })

  test('5 个快捷入口卡片点击后跳转到正确路由', async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/home' })
    await expect(page.locator('.quick-card')).toHaveCount(5)

    for (const [name, path] of QUICK_ENTRIES) {
      await page.goto('/admin/home')
      const card = page.locator('.quick-card').filter({ has: page.locator('.quick-name', { hasText: name }) })
      await expect(card, `快捷入口「${name}」应可见`).toBeVisible()
      await card.click()
      await expect(page, `「${name}」应跳转到 ${path}`).toHaveURL(new RegExp(`${path.replace(/\//g, '\\/')}$`))
    }
  })
})