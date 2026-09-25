/**
 * 学生端 - 我的预约（/student/my-reservations，MyReservation.vue）
 *
 * 覆盖：状态标签页切换（请求参数 + 行状态一致）、列表字段展示、今日预约置顶、
 *       取消预约（二次确认：再想想 / 确定取消）、空状态引导。
 *
 * 数据隔离：只用被分配教室 A101(1)/A102(2)/A201(3)/A301(4)/B101(5) 与 futureDate(1)~futureDate(6)；
 * 每条用例自己造数、结束尽力用接口清理（已驳回为终态无法取消，保留 E2E 前缀便于统一清理）。
 */
import { test, expect } from '@playwright/test'
import dayjs from 'dayjs'
import { loginAs, apiLogin } from '../helpers/auth'
import {
  expectOk,
  createReservation,
  auditReservation,
  cancelReservation,
  tryCancel,
  getClassroom
} from '../helpers/api'
import { pageToken, isSlotFree } from '../helpers/stable-seed'
import { e2ePurpose, futureDate } from '../helpers/data'

const PATH = '/student/my-reservations'

/**
 * 用途后缀：每次运行唯一。
 * 「已驳回/已取消」是终态、无法经接口删除，固定用途会让历史遗留记录与本轮数据一起命中
 * 同一文案（严格模式下多处匹配即失败）；带后缀既可区分，又保留 E2E 前缀便于统一清理。
 */
const RUN = String(Date.now()).slice(-6)
const tag = (t) => e2ePurpose(`${t}-${RUN}`)

async function openMine(page) {
  await loginAs(page, { who: 'student', path: PATH, aiEnabled: false })
}

/** 在候选 (date × slot) 中挑第一个无冲突时段 */
async function freeSlotFor(request, token, classroomId, dates, slots) {
  for (const date of dates) {
    for (const [startTime, endTime] of slots) {
      if (await isSlotFree(request, token, { classroomId, date, startTime, endTime })) {
        return { date, startTime, endTime }
      }
    }
  }
  throw new Error(`候选时段全部被占用：教室 ${classroomId}`)
}

/** 造一条「待审核」预约，返回 id */
async function seedPending(request, token, { classroomId, date, startTime, endTime, purpose }) {
  const created = await createReservation(request, token, {
    classroomId,
    reserveDate: date,
    startTime,
    endTime,
    purpose
  })
  return expectOk(created, `造待审核预约（教室 ${classroomId} ${date} ${startTime}-${endTime}）`)
}

/** 点击状态标签页并等待列表请求，返回响应体（含请求 query 中的 status） */
async function switchTab(page, label) {
  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/reservation/mine') && r.request().method() === 'GET'),
    page.getByRole('tab', { name: label, exact: true }).click()
  ])
  return { status: new URL(resp.url()).searchParams.get('status'), body: await resp.json() }
}

/** 表格中携带指定文案的行 */
function rowBy(page, text) {
  return page.locator('.el-table__row').filter({ hasText: text })
}

test.describe('我的预约', () => {
  test('状态标签页切换：请求按状态过滤且行状态文案一致', async ({ page, request }) => {
    await openMine(page)
    const token = await pageToken(page)
    const admin = await apiLogin(request, 'admin')

    // 四种状态各造一条（不同教室，互不冲突）：1 待审核 / 2 已通过 / 3 已驳回 / 4 已取消
    const created = {}
    const purposes = {}
    for (const [key, roomId] of [
      ['pending', 1],
      ['approved', 2],
      ['rejected', 3],
      ['cancelled', 4]
    ]) {
      const s = await freeSlotFor(request, token, roomId, [futureDate(1)], [
        ['08:00', '10:00'],
        ['10:00', '12:00'],
        ['14:00', '16:00']
      ])
      const purpose = tag(`13-${key}`)
      const id = await seedPending(request, token, { classroomId: roomId, ...s, purpose })
      created[key] = id
      purposes[key] = purpose
      if (key === 'approved') {
        expectOk(await auditReservation(request, admin.token, id, 1), '审核通过')
      } else if (key === 'rejected') {
        expectOk(await auditReservation(request, admin.token, id, 2, 'E2E驳回备注'), '审核驳回')
      } else if (key === 'cancelled') {
        expectOk(await cancelReservation(request, token, id), '取消预约')
      }
    }

    // 各标签页：请求 status 参数正确 + 响应内状态单一 + 目标行以其状态文案呈现
    for (const [label, status, key] of [
      ['待审核', '0', 'pending'],
      ['已通过', '1', 'approved'],
      ['已驳回', '2', 'rejected'],
      ['已取消', '3', 'cancelled']
    ]) {
      const { status: reqStatus, body } = await switchTab(page, label)
      expect(reqStatus, `「${label}」标签页请求状态参数`).toBe(status)
      expect(body.code, `「${label}」列表接口`).toBe(200)
      for (const r of body.data.records) {
        expect(r.status, `「${label}」页内记录状态`).toBe(Number(status))
      }
      await expect(page.locator('.el-table__row')).toHaveCount(body.data.records.length)
      await expect(rowBy(page, purposes[key])).toContainText(label)
    }

    // 回到「全部」：四种状态均可同时出现
    const all = await switchTab(page, '全部')
    expect(all.status, '「全部」标签页不应带 status 参数').toBeNull()
    for (const key of Object.keys(purposes)) {
      await expect(rowBy(page, purposes[key])).toBeVisible()
    }

    // 收尾：已驳回为终态无法取消，其余尽力取消
    for (const key of ['pending', 'approved', 'cancelled']) {
      await tryCancel(request, token, created[key])
    }
  })

  test('列表字段：教室/日期/时段/用途/状态/审核备注/操作列', async ({ page, request }) => {
    await openMine(page)
    const token = await pageToken(page)
    const room = expectOk(await getClassroom(request, token, 5), '取教室信息')
    const s = await freeSlotFor(request, token, 5, [futureDate(2)], [
      ['08:00', '10:00'],
      ['10:00', '12:00'],
      ['14:00', '16:00']
    ])
    const purpose = tag('13-字段')
    const id = await seedPending(request, token, { classroomId: 5, ...s, purpose })

    await page.reload()
    await expect(page.locator('.page-title')).toHaveText('我的预约')
    await expect(page.locator('.page-tip')).toContainText('今日预约已置顶显示')

    // 表头
    for (const head of ['教室', '预约日期', '时段', '用途', '状态', '审核备注', '操作']) {
      await expect(page.locator('.el-table__header')).toContainText(head)
    }

    const row = rowBy(page, purpose)
    await expect(row).toBeVisible()
    await expect(row).toContainText(room.name)
    await expect(row).toContainText(`${room.building}-${room.roomNo}`)
    await expect(row).toContainText(s.date)
    await expect(row).toContainText(`${s.startTime}-${s.endTime}`)
    await expect(row).toContainText(purpose)
    await expect(row.locator('.el-tag')).toHaveText('待审核')
    await expect(row.locator('td').nth(5)).toHaveText('-')
    // 待审核可取消 → 操作列出现「取消」
    await expect(row.getByRole('button', { name: '取消' })).toBeVisible()

    await tryCancel(request, token, id)
  })

  test('今日预约置顶：当日预约排在列表最前并带「今日」标记', async ({ page, request }) => {
    const startAt = dayjs().add(3, 'hour').startOf('hour')
    const endAt = startAt.add(1, 'hour')
    test.skip(startAt.hour() > 21, '当前时刻已无今日可预约时段')

    await openMine(page)
    const token = await pageToken(page)

    // 今日：挑一间当前时段空闲的教室
    let seeded = null
    for (const classroomId of [1, 2, 3, 4, 5]) {
      const date = dayjs().format('YYYY-MM-DD')
      const startTime = startAt.format('HH:mm')
      const endTime = endAt.format('HH:mm')
      if (await isSlotFree(request, token, { classroomId, date, startTime, endTime })) {
        const purpose = tag('13-今日置顶')
        const id = await seedPending(request, token, { classroomId, date, startTime, endTime, purpose })
        seeded = { id, purpose, date, startTime, endTime }
        break
      }
    }
    test.skip(!seeded, '今日候选教室均被占用，跳过置顶用例')

    await page.reload()
    const todayRow = rowBy(page, seeded.purpose)
    await expect(todayRow).toBeVisible()
    await expect(todayRow.locator('.today-tag')).toHaveText('今日')
    await expect(todayRow).toHaveClass(/today-row/)

    // 相对断言：今日行必须排在任何非今日行之前
    const dates = await page.locator('.el-table__row td:nth-child(2)').allInnerTexts()
    const firstNonToday = dates.findIndex((d) => !d.includes(seeded.date))
    const myIndex = dates.findIndex((d) => d.includes(seeded.date) && d.includes('今日'))
    expect(myIndex, '应存在今日行').toBeGreaterThanOrEqual(0)
    if (firstNonToday !== -1) {
      expect(myIndex, '今日行应排在最前（早于所有非今日行）').toBeLessThan(firstNonToday)
    }

    await tryCancel(request, token, seeded.id)
  })

  test('取消预约：二次确认「再想想」不生效、「确定取消」后状态变为已取消', async ({ page, request }) => {
    await openMine(page)
    const token = await pageToken(page)
    const s = await freeSlotFor(request, token, 2, [futureDate(3)], [
      ['08:00', '10:00'],
      ['10:00', '12:00'],
      ['14:00', '16:00']
    ])
    const purpose = tag('13-取消')
    const id = await seedPending(request, token, { classroomId: 2, ...s, purpose })

    await page.reload()
    const row = rowBy(page, purpose)
    await expect(row).toContainText('待审核')

    // 第一次：点「再想想」→ 不取消
    await row.getByRole('button', { name: '取消' }).click()
    const box = page.locator('.el-message-box')
    await expect(box).toBeVisible()
    await expect(box).toContainText('取消预约确认')
    await expect(box).toContainText('取消后不可恢复')
    await box.getByRole('button', { name: '再想想' }).click()
    await expect(box).toBeHidden()
    await expect(rowBy(page, purpose)).toContainText('待审核')

    // 第二次：点「确定取消」→ 状态变已取消
    await rowBy(page, purpose).getByRole('button', { name: '取消' }).click()
    await expect(page.locator('.el-message-box')).toContainText('取消后不可恢复')
    await page.locator('.el-message-box').getByRole('button', { name: '确定取消' }).click()
    await expect(page.getByText('取消成功')).toBeVisible()
    await expect(rowBy(page, purpose).locator('.el-tag')).toHaveText('已取消')

    await tryCancel(request, token, id)
  })

  test('空状态：无记录时给出引导并可跳转教室列表', async ({ page }) => {
    // 拦截列表接口返回空页（不依赖库里真实数据）
    await page.route('**/api/reservation/mine**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '操作成功', data: { total: 0, records: [] } })
      })
    )
    await openMine(page)

    await expect(page.locator('.el-empty__description')).toHaveText('暂无预约记录，去挑一间教室吧')
    await expect(page.getByRole('button', { name: '去教室列表' })).toBeVisible()

    await page.getByRole('button', { name: '去教室列表' }).click()
    await expect(page).toHaveURL(/\/student\/home/)
  })
})