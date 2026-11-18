/**
 * 动效契约（R16）：页面入场只在首屏播一次，且用户偏好减少动效时必须完全静音。
 * 两条断言分别守住「有动效」与「不硬撑动效」两端，避免后续改动悄悄删掉或加速过猛。
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { mockAiOff } from '../helpers/ai'

test.describe('动效契约', () => {
  test('默认偏好下，教室列表页头与卡片有入场动画', async ({ page }) => {
    await mockAiOff(page)
    await loginAs(page, { who: 'student', path: '/student/home' })

    // 页头：全局 reveal-up（220ms，opacity + 6px）
    const head = page.locator('.page-head')
    await expect(head).toBeVisible()
    const headAnim = await head.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return { name: style.animationName, duration: style.animationDuration }
    })
    expect(headAnim.name).not.toBe('none')
    expect(headAnim.duration).toBe('0.22s')

    // 教室卡片：逐卡错开的入场（第 2 张的 delay 应为 22ms 的整数倍）
    const card = page.locator('.room-card').nth(1)
    await expect(card).toBeVisible()
    const cardAnim = await card.evaluate((el) => {
      const style = window.getComputedStyle(el)
      return { name: style.animationName, delay: style.animationDelay }
    })
    expect(cardAnim.name).not.toBe('none')
    expect(parseFloat(cardAnim.delay)).toBeGreaterThan(0)
  })

  test('prefers-reduced-motion 下所有入场动画静音', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockAiOff(page)
    await loginAs(page, { who: 'student', path: '/student/home' })

    // 等首屏数据落位后再采样，避免在渲染空窗期读到 null
    await expect(page.locator('.page-head')).toBeVisible()
    await expect(page.locator('.room-card').first()).toBeVisible()

    const results = await page.evaluate(() => {
      const pick = (selector) => {
        const el = document.querySelector(selector)
        if (!el) {
          return null
        }
        const style = window.getComputedStyle(el)
        return { name: style.animationName, duration: style.animationDuration, delay: style.animationDelay }
      }
      return {
        head: pick('.page-head'),
        card: pick('.room-card')
      }
    })

    // 静音策略：保留动画名但把时长/延迟压到不可感知（0.01ms），而不是靠删除规则
    for (const [key, value] of Object.entries(results)) {
      expect(value, `${key} 应存在`).not.toBeNull()
      expect(parseFloat(value.duration), `${key} 动画时长应压到 0.01ms`).toBeLessThan(0.01)
      expect(parseFloat(value.delay || '0'), `${key} 动画延迟应为 0`).toBe(0)
    }
  })
})
