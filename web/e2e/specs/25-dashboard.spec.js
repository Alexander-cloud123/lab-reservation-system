/**
 * 管理端 25 —— 数据看板（/admin/dashboard，Dashboard.vue）
 *
 * 覆盖：三个 ECharts 图表渲染出 canvas、时间范围切换（近7天/近30天/近90天/本年）、
 *       自定义日期范围、刷新按钮生效。
 */
import { test, expect } from '@playwright/test'
import dayjs from 'dayjs'
import { loginAs } from '../helpers/auth'

/** [按钮文案, 期望起始日] 起始日与 Dashboard.vue handleQuickRange 的算法一致 */
const QUICK_RANGES = [
  ['近 7 天', dayjs().subtract(6, 'day').format('YYYY-MM-DD')],
  ['近 30 天', dayjs().subtract(29, 'day').format('YYYY-MM-DD')],
  ['近 90 天', dayjs().subtract(89, 'day').format('YYYY-MM-DD')],
  ['本年', dayjs().startOf('year').format('YYYY-MM-DD')]
]

const TODAY = dayjs().format('YYYY-MM-DD')

const CHARTS = ['教室使用率排行', '热门时段分布', '月度预约趋势']

function chartCard(page, title) {
  return page.locator('.chart-card').filter({ has: page.locator('.chart-title', { hasText: title }) })
}

async function expectAllChartsRendered(page) {
  await expect(page.locator('.chart-box canvas')).toHaveCount(3)
  for (const title of CHARTS) {
    await expect(chartCard(page, title).locator('canvas'), `「${title}」应渲染出 canvas`).toBeVisible()
  }
}

test.describe('管理端-数据看板（Dashboard）', () => {
  test.beforeEach(async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/dashboard' })
    await expect(page.locator('.page-title', { hasText: '数据看板' })).toBeVisible()
  })

  test('三个图表容器均渲染出 canvas', async ({ page }) => {
    for (const title of CHARTS) {
      await expect(chartCard(page, title)).toBeVisible()
    }
    await expectAllChartsRendered(page)
  })

  test('时间范围切换：近7天 / 近30天 / 近90天 / 本年', async ({ page }) => {
    await expectAllChartsRendered(page)

    for (const [label, expectedStart] of QUICK_RANGES) {
      const button = page.locator('.el-radio-button').filter({ hasText: label })
      await button.click()
      await expect(button.locator('input[type="radio"]'), `「${label}」应处于选中态`).toBeChecked()
      await expect(page.getByPlaceholder('开始日期')).toHaveValue(expectedStart)
      await expect(page.getByPlaceholder('结束日期')).toHaveValue(TODAY)
      // 每次切换都会重新拉取三图数据并重渲染
      await expectAllChartsRendered(page)
    }
  })

  test('自定义日期范围：覆盖快捷范围选中态', async ({ page }) => {
    await expectAllChartsRendered(page)

    // 先选一个快捷范围，再手工改区间
    await page.locator('.el-radio-button').filter({ hasText: '近 7 天' }).click()
    await expect(page.locator('.el-radio-button.is-active')).toHaveCount(1)

    const start = dayjs().subtract(10, 'day').format('YYYY-MM-DD')
    const end = dayjs().subtract(3, 'day').format('YYYY-MM-DD')
    const startInput = page.getByPlaceholder('开始日期')
    const endInput = page.getByPlaceholder('结束日期')
    await startInput.click()
    await startInput.fill(start)
    await endInput.fill(end)
    await page.keyboard.press('Enter')
    await page.keyboard.press('Escape')

    await expect(startInput).toHaveValue(start)
    await expect(endInput).toHaveValue(end)
    // quickRange 被清空 → 没有任何快捷按钮处于选中态
    await expect(page.locator('.el-radio-button.is-active'), '自定义区间应取消快捷范围选中').toHaveCount(0)
    await expectAllChartsRendered(page)
  })

  test('刷新按钮：重新请求统计数据并保持三图渲染', async ({ page }) => {
    await expectAllChartsRendered(page)

    const [response] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/stats/') && r.request().method() === 'GET'),
      page.getByRole('button', { name: '刷新' }).click()
    ])
    expect(response.status(), '刷新触发的统计接口应返回 200').toBe(200)
    const body = await response.json()
    expect(body.code, `统计接口业务码应为 200：${JSON.stringify(body).slice(0, 120)}`).toBe(200)

    await expectAllChartsRendered(page)
  })
})