/**
 * 学生端 - 教室详情页（/student/classrooms/:id，ClassroomDetail.vue）
 *
 * 覆盖：基础信息展示、当日/指定日时段占用可视化、收藏与取消收藏、收藏上限 10（第 11 间被拒并提示，
 *       用例结束还原原收藏集）、预约申请弹窗表单、必填校验、开始时间<结束时间校验、
 *       冲突实时校验（与已通过预约重叠 → 提示并禁用提交）、成功提交后「我的预约」可见。
 *
 * 数据隔离：只用被分配教室 A101(1)/A102(2)/A201(3)/A301(4)/B101(5) 与 futureDate(1)~futureDate(6)。
 * 收藏用例只动 zhangsan 自己的收藏，结束按「原始收藏集」精确还原。
 */
import { test, expect } from '@playwright/test'
import { loginAs, apiLogin } from '../helpers/auth'
import {
  expectOk,
  getClassroom,
  listFavorites,
  toggleFavorite,
  listMine,
  tryCancel
} from '../helpers/api'
import { pageToken, seedApprovedAny, isSlotFree } from '../helpers/stable-seed'
import { e2ePurpose, futureDate, today, SLOTS } from '../helpers/data'

const detailPath = (id) => `/student/classrooms/${id}`

/**
 * 用途后缀：每次运行唯一。
 * 预约的「已驳回/已取消」是终态、无法经接口删除，固定用途会让历史遗留记录与本轮数据
 * 一起命中同一文案（严格模式下多处匹配即失败）。带后缀既可区分，又保留 E2E 前缀便于统一清理。
 */
const RUN = String(Date.now()).slice(-6)
const tag = (t) => e2ePurpose(`${t}-${RUN}`)

async function openDetail(page, id) {
  await loginAs(page, { who: 'student', path: detailPath(id), aiEnabled: false })
}

/**
 * 定位预约弹窗中的 el-time-select。
 * 注意：Element Plus 2.7 的 el-select 用 <span> 展示占位文案，输入框上并无 placeholder 属性，
 * 因此不能用 getByPlaceholder 定位，改为按表单项 label 过滤。
 */
function timeSelect(dialog, label) {
  return dialog.locator('.el-form-item').filter({ hasText: label }).locator('.el-select')
}

/**
 * 打开 el-time-select 并选择某个时间项。
 * 实测坑（Element Plus 2.7）：下拉关闭时有约 300ms 的高度过渡，过渡期间仍被判定为「可见」且可命中；
 * 若紧接着展开第二个下拉，点击会落到上一个正在关闭的下拉项上（报 element is not visible）。
 * 故先等上一个下拉彻底关闭，再展开目标下拉，并确认当前只有 1 个可见下拉。
 */
async function pickTime(page, dialog, label, optionText) {
  await page.locator('.el-select-dropdown:visible').last().waitFor({ state: 'hidden' }).catch(() => {})
  await timeSelect(dialog, label).click()
  const dropdown = page.locator('.el-select-dropdown:visible')
  await expect(dropdown).toHaveCount(1)
  await dropdown.first().locator('.el-select-dropdown__item', { hasText: optionText }).first().click()
  await expect(dropdown).toHaveCount(0)
}

/** 填写 el-date-picker（value-format=YYYY-MM-DD） */
async function pickDate(page, input, date) {
  await input.click()
  await input.fill(date)
  await input.press('Enter')
}

/** 按用途精确清理本用例造的预约 */
async function cancelByPurpose(request, token, purpose) {
  const data = expectOk(await listMine(request, token, { page: 1, size: 100 }), '清理-查我的预约')
  const hit = (data.records || []).find((r) => r.purpose === purpose)
  if (hit) await tryCancel(request, token, hit.id)
}

/** 把当前用户的收藏集合精确设置为 desired（先删多余、再补缺失） */
async function setFavorites(request, token, desired) {
  const cur = expectOk(await listFavorites(request, token), '取收藏列表').map((f) => f.classroomId)
  for (const id of cur) {
    if (!desired.includes(id)) await toggleFavorite(request, token, id)
  }
  for (const id of desired) {
    if (!cur.includes(id)) await toggleFavorite(request, token, id)
  }
}

/** 在候选中挑一个空闲时段（供 UI 提交用） */
async function firstFreeSlot(request, token, classroomId, dates) {
  const slots = [SLOTS.morning, SLOTS.noon, SLOTS.afternoon, SLOTS.evening]
  for (const date of dates) {
    for (const [startTime, endTime] of slots) {
      if (await isSlotFree(request, token, { classroomId, date, startTime, endTime })) {
        return { date, startTime, endTime }
      }
    }
  }
  throw new Error('候选时段全部被占用')
}

test.describe('教室详情页', () => {
  test('基础信息展示：名称/编号/楼栋/类型/容量/设备/备注/状态/预约按钮', async ({ page, request }) => {
    await openDetail(page, 1)
    const token = await pageToken(page)
    const room = expectOk(await getClassroom(request, token, 1), '取教室详情')

    await expect(page.locator('.nav-title')).toHaveText('教室详情')
    await expect(page.getByRole('button', { name: '返回列表' })).toBeVisible()
    await expect(page.locator('.info-head h2')).toHaveText(room.name)
    await expect(page.locator('.info-head .room-no')).toHaveText(`${room.building} · ${room.roomNo}`)
    await expect(page.locator('.info-head .el-tag')).toHaveText(room.statusLabel)

    // el-descriptions 字段
    await expect(page.getByText('教室编号')).toBeVisible()
    await expect(page.getByText('所属楼栋')).toBeVisible()
    await expect(page.getByText('教室类型')).toBeVisible()
    await expect(page.getByText('容纳人数')).toBeVisible()
    await expect(page.getByText('设备说明')).toBeVisible()
    await expect(page.getByText('备注描述')).toBeVisible()
    await expect(page.locator('.info-table')).toContainText(room.roomNo)
    await expect(page.locator('.info-table')).toContainText(`${room.capacity} 人`)

    // 时段占用区 + 预约按钮
    await expect(page.getByText('时段占用（已通过预约）')).toBeVisible()
    await expect(page.getByRole('button', { name: '预约申请' })).toBeEnabled()
    // 收藏按钮文字为「收藏」或「已收藏」
    await expect(page.locator('.fav-btn')).toHaveText(/已?收藏/)
  })

  test('时段占用可视化：指定日期显示已通过时段与用途', async ({ page, request }) => {
    await openDetail(page, 1)
    const token = await pageToken(page)
    const admin = await apiLogin(request, 'admin')
    const purpose = tag('12-占用可视化')
    const seeded = await seedApprovedAny(request, {
      adminToken: admin.token,
      studentToken: token,
      classroomId: 1,
      dates: [futureDate(1), futureDate(2), futureDate(3)],
      slots: [SLOTS.afternoon, SLOTS.evening, SLOTS.morning],
      purpose
    })

    // 选择该日期 → 时间轴出现该时段色块，明细表出现该时段与用途（本人可见用途）
    await pickDate(page, page.getByPlaceholder('选择日期查看'), seeded.date)
    await expect(page.locator('.timeline-slot').filter({ hasText: `${seeded.startTime}-${seeded.endTime}` })).toBeVisible()
    const table = page.locator('.slots-card .el-table')
    await expect(table).toContainText(seeded.startTime)
    await expect(table).toContainText(seeded.endTime)
    await expect(table).toContainText(purpose)

    await tryCancel(request, token, seeded.id)
  })

  test('收藏 / 取消收藏：按钮状态与提示正确', async ({ page, request }) => {
    await openDetail(page, 5)
    const token = await pageToken(page)
    // 前置：确保 B101 当前未被收藏（消除历史残留影响）
    const faved = expectOk(await listFavorites(request, token), '取收藏列表').some((f) => f.classroomId === 5)
    if (faved) await toggleFavorite(request, token, 5)
    await page.reload()

    const favBtn = page.locator('.fav-btn')
    await expect(favBtn).toHaveText('收藏')

    // 收藏
    await favBtn.click()
    await expect(favBtn).toHaveText('已收藏')
    await expect(page.getByText('收藏成功')).toBeVisible()
    expect(
      expectOk(await listFavorites(request, token), '取收藏列表').some((f) => f.classroomId === 5),
      '收藏后接口收藏列表应包含 B101'
    ).toBe(true)

    // 取消收藏（还原）
    await favBtn.click()
    await expect(favBtn).toHaveText('收藏')
    await expect(page.getByText('已取消收藏')).toBeVisible()
    expect(
      expectOk(await listFavorites(request, token), '取收藏列表').some((f) => f.classroomId === 5),
      '取消后接口收藏列表不应包含 B101'
    ).toBe(false)
  })

  test('收藏上限 10：第 11 间被拒并提示，结束后还原原收藏集', async ({ page, request }) => {
    await openDetail(page, 1)
    const token = await pageToken(page)
    const original = expectOk(await listFavorites(request, token), '取收藏列表').map((f) => f.classroomId)

    try {
      // 先收满 10 间
      await setFavorites(request, token, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
      expect(expectOk(await listFavorites(request, token), '取收藏列表').length, '应已收藏 10 间').toBe(10)

      // 打开第 11 间（C301，id=11）详情，点收藏 → 被拒
      await page.goto(detailPath(11))
      const favBtn = page.locator('.fav-btn')
      await expect(favBtn).toHaveText('收藏')
      await favBtn.click()
      await expect(page.getByText('收藏数量已达上限（10 间），请先取消部分收藏')).toBeVisible()
      await expect(favBtn).toHaveText('收藏')
    } finally {
      // 还原为原始收藏集
      await setFavorites(request, token, original)
    }
  })

  test('预约申请弹窗：表单展示与必填校验', async ({ page }) => {
    await openDetail(page, 1)
    await page.getByRole('button', { name: '预约申请' }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog.locator('.el-dialog__title')).toHaveText('预约申请')
    // 字段与默认值
    await expect(dialog.getByPlaceholder('选择日期')).toHaveValue(today())
    await expect(timeSelect(dialog, '开始时间')).toContainText('选择开始时间')
    await expect(timeSelect(dialog, '结束时间')).toContainText('选择结束时间')
    await expect(dialog.getByPlaceholder('请填写具体教学 / 实验 / 自习等用途')).toBeVisible()

    // 清空日期、留空时段/用途 → 必填校验提示
    await pickDate(page, dialog.getByPlaceholder('选择日期'), '')
    await dialog.getByRole('button', { name: '提交预约' }).click()
    await expect(dialog.getByText('请选择预约日期')).toBeVisible()
    await expect(dialog.getByText('请选择开始时间')).toBeVisible()
    await expect(dialog.getByText('请选择结束时间')).toBeVisible()
    await expect(dialog.getByText('请填写预约用途')).toBeVisible()
  })

  test('开始时间必须早于结束时间：前端实时提示', async ({ page }) => {
    await openDetail(page, 1)
    await page.getByRole('button', { name: '预约申请' }).click()
    const dialog = page.getByRole('dialog')

    await pickTime(page, dialog, '开始时间', '10:00')
    await pickTime(page, dialog, '结束时间', '09:00')
    await expect(dialog.getByText('开始时间必须早于结束时间')).toBeVisible()
  })

  test('冲突实时校验：与已通过预约重叠时提示并禁用提交', async ({ page, request }) => {
    await openDetail(page, 1)
    const token = await pageToken(page)
    const admin = await apiLogin(request, 'admin')
    const seeded = await seedApprovedAny(request, {
      adminToken: admin.token,
      studentToken: token,
      classroomId: 1,
      dates: [futureDate(2), futureDate(3)],
      slots: [SLOTS.afternoon, SLOTS.evening],
      purpose: tag('12-冲突')
    })

    await page.reload()
    await page.getByRole('button', { name: '预约申请' }).click()
    const dialog = page.getByRole('dialog')
    await pickDate(page, dialog.getByPlaceholder('选择日期'), seeded.date)
    await pickTime(page, dialog, '开始时间', seeded.startTime)
    await pickTime(page, dialog, '结束时间', seeded.endTime)

    await expect(dialog.locator('.el-alert')).toContainText('冲突')
    await expect(dialog.getByRole('button', { name: '提交预约' })).toBeDisabled()

    await tryCancel(request, token, seeded.id)
  })

  test('成功提交预约：提示成功且「我的预约」待审核可见（用途含 E2E 前缀）', async ({ page, request }) => {
    await openDetail(page, 1)
    const token = await pageToken(page)
    const slot = await firstFreeSlot(request, token, 1, [
      futureDate(3),
      futureDate(4),
      futureDate(5),
      futureDate(6)
    ])
    const purpose = tag('12-提交成功')

    await page.getByRole('button', { name: '预约申请' }).click()
    const dialog = page.getByRole('dialog')
    await pickDate(page, dialog.getByPlaceholder('选择日期'), slot.date)
    await pickTime(page, dialog, '开始时间', slot.startTime)
    await pickTime(page, dialog, '结束时间', slot.endTime)
    await dialog.getByPlaceholder('请填写具体教学 / 实验 / 自习等用途').fill(purpose)
    await dialog.getByRole('button', { name: '提交预约' }).click()

    await expect(page.getByText('预约提交成功，待管理员审核')).toBeVisible()

    // 我的预约 → 待审核 → 可见该条
    await page.getByRole('menuitem', { name: '我的预约' }).click()
    await expect(page).toHaveURL(/\/student\/my-reservations/)
    await page.getByRole('tab', { name: '待审核' }).click()
    const row = page.locator('.el-table__row').filter({ hasText: purpose })
    await expect(row).toBeVisible()
    await expect(row).toContainText('E2E自动化-')

    await cancelByPurpose(request, token, purpose)
  })
})