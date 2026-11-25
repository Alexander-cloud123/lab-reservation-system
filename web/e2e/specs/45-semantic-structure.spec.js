/** 语义结构、跳至主内容、路由焦点与强制颜色可读性。 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { mockAiOff } from '../helpers/ai'

const studentPages = [
  '/student/home',
  '/student/classrooms/1',
  '/student/my-reservations',
  '/student/calendar',
  '/student/profile',
  '/student/ai'
]

for (const path of studentPages) {
  test(`${path}：恰有一个可见 h1 与一个 main`, async ({ page }) => {
    await mockAiOff(page)
    await loginAs(page, { who: 'student', path, aiEnabled: false })
    await expect(page.locator('h1:visible')).toHaveCount(1)
    await expect(page.locator('main:visible')).toHaveCount(1)
  })
}

test('学生端：首个 Tab 到 skip link，Enter 后主区获得焦点', async ({ page }) => {
  await mockAiOff(page)
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: false })
  await expect(page.locator('#student-main')).toBeVisible()
  await page.evaluate(() => document.activeElement && document.activeElement.blur())
  await page.keyboard.press('Tab')
  await expect(page.locator('.skip-link')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#student-main')).toBeFocused()
})

test('管理端：skip link 可用，路径切换聚焦主区', async ({ page }) => {
  await mockAiOff(page)
  await loginAs(page, { who: 'admin', path: '/admin/home', aiEnabled: false })
  await expect(page.locator('#admin-main')).toBeVisible()
  await page.evaluate(() => document.activeElement && document.activeElement.blur())
  await page.keyboard.press('Tab')
  await expect(page.locator('.skip-link')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#admin-main')).toBeFocused()
  await page.getByRole('menuitem', { name: '用户管理' }).click()
  await expect(page).toHaveURL(/\/admin\/users/)
  await expect(page.locator('#admin-main')).toBeFocused()
})

test('query-only 更新不触发路由主区焦点', async ({ page }) => {
  await mockAiOff(page)
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: false })
  const trigger = page.getByRole('button', { name: '更多筛选' })
  await trigger.focus()
  await trigger.press('Enter')
  await expect(page.getByRole('button', { name: '收起筛选' })).toBeFocused()
  await expect(page.locator('#student-main')).not.toBeFocused()
  await expect.poll(() => new URL(page.url()).searchParams.get('expanded')).toBe('1')
})

test('AI 欢迎标识在 forced-colors 模式下不使用透明文字', async ({ page }) => {
  await mockAiOff(page)
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: false })
  const mark = page.locator('.welcome-mark')
  await expect(mark).toBeVisible()
  const session = await page.context().newCDPSession(page)
  await session.send('Emulation.setEmulatedMedia', {
    features: [{ name: 'forced-colors', value: 'active' }]
  })
  const color = await mark.evaluate((el) => getComputedStyle(el).color)
  expect(color).not.toBe('transparent')
  expect(color).not.toBe('rgba(0, 0, 0, 0)')
  await session.send('Emulation.setEmulatedMedia', { features: [] })
})
