/**
 * 学生端 - 预约日历总览（/student/calendar，CalendarOverview.vue）
 *
 * 覆盖：FullCalendar 月/周视图切换（网格结构 + 数据请求）、预约色块按状态渲染（时段 + 教室名）、
 *       点击日期格快速发起预约（弹窗预填）、教室筛选（仅保留所选教室色块）。
 *
 * 数据隔离：只用被分配教室 A101(1)/A102(2)/A201(3)/A301(4)/B101(5) 与 futureDate(1)~futureDate(6)。
 * 定位要点：FullCalendar 渲染的色块 title 属性含「教室 时段 状态｜用途」，用唯一的 E2E 用途可在
 *          共享数据中精确定位到本用例的色块。
 */
import { test, expect } from '@playwright/test'
import { loginAs, apiLogin } from '../helpers/auth'
import { expectOk, createReservation, auditReservation, getClassroom, tryCancel } from '../helpers/api'
import { pageToken, isSlotFree } from '../helpers/stable-seed'
import { e2ePurpose, futureDate, SLOTS } from '../helpers/data'

const PATH = '/student/calendar'

/**
 * 用途后缀：每次运行唯一。
 * 日历会展示区间内「全部状态」的预约，而「已取消/已驳回」是终态、无法经接口删除，
 * 固定用途会让历史遗留记录与本轮色块同时命中同一文案（严格模式下多处匹配即失败）。
 */
const RUN = String(Date.now()).slice(-6)
const tag = (t) => e2ePurpose(`${t}-${RUN}`)

async function openCalendar(page) {
  await loginAs(page, { who: 'student', path: PATH, aiEnabled: false })
}

/** 重新加载页面并等待日历区间数据请求返回 */
async function reloadCalendar(page) {
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/reservation/calendar')),
    page.reload()
  ])
}

/**
 * 定位表单里的 el-time-select（Element Plus 2.7 的下拉占位文案在 span 上，输入框无 placeholder 属性）
 */
function timeSelect(dialog, label) {
  return dialog.locator('.el-form-item').filter({ hasText: label }).locator('.el-select')
}

/**
 * 展开某个 el-select 并选择下拉项。
 * 实测坑：下拉关闭有约 300ms 高度过渡，过渡期间仍被判为「可见」，故先等上一个下拉关闭再展开。
 */
async function pickOption(page, trigger, optionText) {
  await page.locator('.el-select-dropdown:visible').last().waitFor({ state: 'hidden' }).catch(() => {})
  await trigger.click()
  const dropdown = page.locator('.el-select-dropdown:visible')
  await expect(dropdown).toHaveCount(1)
  await dropdown.first().locator('.el-select-dropdown__item', { hasText: optionText }).first().click()
  await expect(dropdown).toHaveCount(0)
}

/** 在候选 (date × slot) 中挑第一个无冲突时段（可排除已占用时段） */
async function freeSlotFor(request, token, classroomId, dates, slots, exclude = []) {
  for (const date of dates) {
    for (const [startTime, endTime] of slots) {
      if (exclude.some((e) => e.date === date && e.startTime === startTime && e.endTime === endTime)) {
        continue
      }
      if (await isSlotFree(request, token, { classroomId, date, startTime, endTime })) {
        return { date, startTime, endTime }
      }
    }
  }
  throw new Error(`候选时段全部被占用：教室 ${classroomId}`)
}

/** 造一条预约（待审核），返回 id */
async function seedPending(request, token, { classroomId, date, startTime, endTime, purpose }) {
  return expectOk(
    await createReservation(request, token, { classroomId, reserveDate: date, startTime, endTime, purpose }),
    `造预约（教室 ${classroomId} ${date} ${startTime}-${endTime}）`
  )
}

test.describe('预约日历总览', () => {
  test('月/周视图切换：网格结构与区间数据请求同步变化', async ({ page }) => {
    await openCalendar(page)
    await expect(page.locator('.page-title')).toHaveText('预约日历总览')

    const cells = page.locator('.fc-daygrid-day')
    // 注意：el-radio-button 的原生 input 被 <span class="el-radio-button__inner"> 覆盖，
    // 直接点 role=radio 会被判定 pointer events 被拦截，故点它的 label（可见文案）
    const monthBtn = page.locator('.el-radio-button').filter({ hasText: '月视图' })
    const weekBtn = page.locator('.el-radio-button').filter({ hasText: '周视图' })
    await expect(page.getByRole('radio', { name: '月视图' })).toBeChecked()
    await expect.poll(() => cells.count(), { message: '月视图网格应铺满多周' }).toBeGreaterThanOrEqual(28)

    // 切周视图：日期格恰好 7 个，且切换会重新拉取区间数据
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/reservation/calendar')),
      weekBtn.click()
    ])
    expect(resp.status(), '周视图区间请求').toBe(200)
    await expect(page.getByRole('radio', { name: '周视图' })).toBeChecked()
    await expect(cells).toHaveCount(7)

    // 切回月视图
    await monthBtn.click()
    await expect(page.getByRole('radio', { name: '月视图' })).toBeChecked()
    await expect.poll(() => cells.count(), { message: '切回月视图应恢复多周网格' }).toBeGreaterThanOrEqual(28)
  })

  test('预约色块渲染：按状态着色并展示时段与教室名', async ({ page, request }) => {
    await openCalendar(page)
    const token = await pageToken(page)
    const admin = await apiLogin(request, 'admin')
    const room = expectOk(await getClassroom(request, token, 1), '取教室信息')
    const dates = [futureDate(1), futureDate(2)]

    // 待审核(0)
    const purposePending = tag('14-待审核色块')
    const sPending = await freeSlotFor(request, token, 1, dates, [SLOTS.morning, SLOTS.noon])
    const pendingId = await seedPending(request, token, { classroomId: 1, ...sPending, purpose: purposePending })

    // 已通过(1)：避开上一条时段（审核通过会复查冲突）
    const purposeApproved = tag('14-已通过色块')
    const sApproved = await freeSlotFor(request, token, 1, dates, [SLOTS.afternoon, SLOTS.evening], [sPending])
    const approvedId = await seedPending(request, token, { classroomId: 1, ...sApproved, purpose: purposeApproved })
    expectOk(await auditReservation(request, admin.token, approvedId, 1), '审核通过')

    await reloadCalendar(page)

    const evPending = page.locator(`.fc-daygrid-event[title*="${purposePending}"]`)
    await expect(evPending).toBeVisible()
    await expect(evPending).toHaveClass(/res-ev-0/)
    await expect(evPending.locator('.res-event-time')).toHaveText(`${sPending.startTime}-${sPending.endTime}`)
    await expect(evPending.locator('.res-event-name')).toHaveText(room.name)

    const evApproved = page.locator(`.fc-daygrid-event[title*="${purposeApproved}"]`)
    await expect(evApproved).toBeVisible()
    await expect(evApproved).toHaveClass(/res-ev-1/)
    await expect(evApproved.locator('.res-event-time')).toHaveText(`${sApproved.startTime}-${sApproved.endTime}`)

    await tryCancel(request, token, pendingId)
    await tryCancel(request, token, approvedId)
  })

  test('点击日期格：快速预约弹窗预填日期与默认时段', async ({ page }) => {
    await openCalendar(page)
    const date = futureDate(2)

    await page.locator(`.fc-daygrid-day[data-date="${date}"] .fc-daygrid-day-number`).click()

    const dialog = page.getByRole('dialog')
    await expect(dialog.locator('.el-dialog__title')).toHaveText('快速预约')
    await expect(dialog.getByPlaceholder('选择日期')).toHaveValue(date)
    // 未选教室筛选时，弹窗内教室为空（占位文案）
    await expect(dialog.locator('.el-form-item').filter({ hasText: '教室' }).locator('.el-select')).toContainText('请选择教室')
    await expect(timeSelect(dialog, '开始时间')).toContainText('08:00')
    await expect(timeSelect(dialog, '结束时间')).toContainText('10:00')

    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).toBeHidden()
  })

  test('教室筛选：仅保留所选教室的色块', async ({ page, request }) => {
    await openCalendar(page)
    const token = await pageToken(page)
    const admin = await apiLogin(request, 'admin')
    const room1 = expectOk(await getClassroom(request, token, 1), '取教室1')
    const room2 = expectOk(await getClassroom(request, token, 2), '取教室2')
    const date = futureDate(3)

    const purpose1 = tag('14-筛选教室1')
    const s1 = await freeSlotFor(request, token, 1, [date], [SLOTS.morning, SLOTS.noon, SLOTS.afternoon])
    const id1 = await seedPending(request, token, { classroomId: 1, ...s1, purpose: purpose1 })
    expectOk(await auditReservation(request, admin.token, id1, 1), '审核通过（教室1）')

    const purpose2 = tag('14-筛选教室2')
    const s2 = await freeSlotFor(request, token, 2, [date], [SLOTS.morning, SLOTS.noon, SLOTS.afternoon])
    const id2 = await seedPending(request, token, { classroomId: 2, ...s2, purpose: purpose2 })
    expectOk(await auditReservation(request, admin.token, id2, 1), '审核通过（教室2）')

    await reloadCalendar(page)
    const ev1 = page.locator(`.fc-daygrid-event[title*="${purpose1}"]`)
    const ev2 = page.locator(`.fc-daygrid-event[title*="${purpose2}"]`)
    await expect(ev1).toBeVisible()
    await expect(ev2).toBeVisible()

    // 选择教室 1 → 请求带 classroomId，且教室 2 的色块消失
    const [resp] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/api/reservation/calendar')),
      pickOption(page, page.locator('.toolbar-left .el-select'), `${room1.name}（${room1.roomNo}）`)
    ])
    expect(new URL(resp.url()).searchParams.get('classroomId')).toBe('1')
    await expect(ev1).toBeVisible()
    await expect(ev2).toHaveCount(0)

    await tryCancel(request, token, id1)
    await tryCancel(request, token, id2)
  })
})