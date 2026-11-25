/**
 * 跨页面设计原则回归：窄屏核心入口、请求失败与成功空态的边界。
 *
 * 只验证新增行为，不重复各页面既有功能用例；接口失败均由路由拦截注入。
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'

const adminErrorCases = [
  { name: '用户管理', path: '/admin/users', api: '/api/user/manage', route: '**/api/user/manage*' },
  { name: '教室资源管理', path: '/admin/classrooms', api: '/api/classroom/manage', route: '**/api/classroom/manage*' },
  { name: '预约审核', path: '/admin/audits', api: '/api/reservation/manage', route: '**/api/reservation/manage*' },
  { name: '预约记录', path: '/admin/records', api: '/api/reservation/manage', route: '**/api/reservation/manage*' }
]

test.describe('管理端列表：失败不等于空数据', () => {
  for (const item of adminErrorCases) {
    test(`${item.name}：首载失败显示重试，恢复后表格可用`, async ({ page }) => {
      await page.route(item.route, (route) => route.abort('failed'))
      await loginAs(page, { who: 'admin', path: item.path })

      await expect(page.locator('.list-error')).toBeVisible()
      await expect(page.locator('.list-error')).toContainText('加载失败')
      await expect(page.locator('.el-table .el-empty')).toHaveCount(0)

      await page.unroute(item.route)
      const response = page.waitForResponse((r) => r.url().includes(item.api) && r.status() === 200)
      await page.locator('.list-error').getByRole('button', { name: '重试' }).click()
      await response
      await expect(page.locator('.list-error')).toHaveCount(0)
      await expect(page.locator('.el-table')).toBeVisible()
    })
  }

  test('管理首页：概览失败显示未知值而非 0', async ({ page }) => {
    await page.route('**/api/stats/overview*', (route) => route.abort('failed'))
    await loginAs(page, { who: 'admin', path: '/admin/home' })

    await expect(page.locator('.stat-value')).toHaveCount(4)
    for (const value of await page.locator('.stat-value').all()) {
      await expect(value).toHaveText('—')
    }
  })
})

test.describe('窄屏核心导航', () => {
  test('学生端 390px：主导航文字仍可见，页面无整体横向溢出', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: false })

    for (const name of ['教室列表', '我的预约', '日历总览', '个人中心']) {
      await expect(page.getByRole('menuitem', { name, exact: true })).toBeVisible()
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
    expect(overflow, '页面本身不应横向溢出（菜单容器可独立横向滚动）').toBe(false)
  })

  test('AI 页 390px：会话按钮可打开历史侧栏，Esc 关闭并恢复焦点', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

    const sessions = page.getByRole('button', { name: '打开会话列表' })
    await expect(sessions).toBeVisible()
    await sessions.click()
    await expect(page.locator('.ai-chat-sidebar')).toBeVisible()
    await expect(page.getByRole('button', { name: /新对话|新建对话/ })).toBeVisible()

    await page.keyboard.press('Escape')
    await expect(page.locator('.ai-chat-sidebar')).toBeHidden()
    await expect(sessions).toBeFocused()

    await sessions.click()
    await page.setViewportSize({ width: 1024, height: 844 })
    await expect(page.locator('.ai-chat-sidebar-close')).toBeHidden()
    await expect(page.locator('.ai-chat-new')).toBeFocused()
  })

  test('管理端 390px：菜单按钮打开抽屉，Esc 关闭并恢复焦点', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await loginAs(page, { who: 'admin', path: '/admin/home' })

    const trigger = page.getByRole('button', { name: '打开管理菜单' })
    await expect(trigger).toBeVisible()
    await expect(page.locator('.admin-aside')).toBeHidden()
    await trigger.click()
    await expect(page.locator('.admin-aside')).toBeVisible()
    await expect(page.getByRole('menuitem', { name: '数据看板', exact: true })).toBeVisible()
    const close = page.getByRole('button', { name: '关闭管理菜单', exact: true })
    await expect(close).toBeFocused()
    await page.keyboard.press('Shift+Tab')
    expect(await page.evaluate(() => document.querySelector('.admin-aside').contains(document.activeElement))).toBe(true)
    await page.keyboard.press('Tab')
    await expect(close).toBeFocused()

    await page.keyboard.press('Escape')
    await expect(page.locator('.admin-aside')).toBeHidden()
    await expect(trigger).toBeFocused()
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)
    expect(overflow).toBe(false)

    await trigger.click()
    await page.setViewportSize({ width: 1024, height: 844 })
    await expect(page.locator('.admin-menu-close')).toBeHidden()
    await expect(page.getByRole('menuitem', { name: '首页', exact: true })).toBeFocused()
  })
})

test.describe('状态与渐进式披露', () => {
  test('管理筛选收起后显示生效条件，单项清除立即恢复查询', async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/users' })
    await page.getByRole('button', { name: '更多筛选' }).click()
    await page.locator('.el-form-item').filter({ hasText: '状态' }).locator('.el-select').click()
    await page.getByRole('option', { name: '正常', exact: true }).click()
    await page.getByRole('button', { name: '查询', exact: true }).click()
    await page.getByRole('button', { name: '收起筛选' }).click()

    const summary = page.locator('.filter-summary')
    await expect(summary).toBeVisible()
    await expect(summary).toContainText('已生效 1 项高级筛选')
    await expect(summary).toContainText('状态：正常')
    const restored = page.waitForResponse((response) =>
      response.url().includes('/api/user/manage') && response.status() === 200
    )
    await page.getByRole('button', { name: '清除状态筛选' }).click()
    await restored
    await expect(summary).toHaveCount(0)
    // 前序用例会创建临时用户，管理员不一定仍在第一页；只验证恢复为成功的非空列表。
    await expect(page.locator('tr.el-table__row').first()).toBeVisible()
  })

  test('看板刷新失败保留旧图表并明确标注，恢复后可重试', async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/dashboard' })
    await expect(page.locator('.chart-box canvas')).toHaveCount(3)

    const statsRoute = /\/api\/stats\/(usage-rate|trend|time-distribution)/
    await page.route(statsRoute, (route) => route.abort('failed'))
    await page.getByRole('button', { name: '刷新', exact: true }).click()
    await expect(page.locator('.dashboard-error')).toContainText('保留上次成功的图表')
    await expect(page.locator('.chart-box canvas')).toHaveCount(3)

    await page.unroute(statsRoute)
    await page.locator('.dashboard-error').getByRole('button', { name: '重试' }).click()
    await expect(page.locator('.dashboard-error')).toHaveCount(0)
    await expect(page.locator('.chart-box canvas')).toHaveCount(3)
  })
})
