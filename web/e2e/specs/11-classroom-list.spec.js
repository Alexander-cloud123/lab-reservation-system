/**
 * 学生端 - 教室列表页（/student/home，ClassroomList.vue）
 *
 * 覆盖：多条件筛选（关键词/楼栋/类型/日期）、关键词命中与无结果、卡片展示字段、
 *       分页切换、实时状态标签与今日剩余时段（与接口口径一致）、筛选条件记忆、空状态引导。
 *
 * 稳定性策略：所有列表断言都以「列表接口响应体」为期望来源（等待响应 → 再断言渲染），
 * 避免"点击查询后旧卡片尚未重渲染"的竞态。
 *
 * 数据隔离：仅使用被分配的教室 A101(1)/A102(2)/A201(3)/A301(4)/B101(5) 与 futureDate(1)~futureDate(6)。
 * 不断言绝对数量（其他任务并行增删），只断言存在/包含/相对变化/字段正确。
 */
import { test, expect } from '@playwright/test'
import { loginAs, apiLogin } from '../helpers/auth'
import { expectOk, listClassrooms, tryCancel } from '../helpers/api'
import { pageToken, seedApprovedAny } from '../helpers/stable-seed'
import { mockAiOff } from '../helpers/ai'
import { openMoreFilters } from '../helpers/ui'
import { e2ePurpose, futureDate, SLOTS } from '../helpers/data'

const PAGE = '/student/home'
/** 楼栋下拉选项来自独立全量字典（真实种子：信息楼 / 实验楼 / 综合楼） */
const BUILDING = '信息楼'

/** 列表接口 URL 匹配 */
const LIST_URL = '/api/classroom/list'

/** 只匹配列表数据请求，排除独立的 size=500 全量楼栋字典请求。 */
function isListResponse(response) {
  return response.url().includes(LIST_URL) && new URL(response.url()).searchParams.get('size') !== '500'
}

/** 打开教室列表页，返回首屏列表接口的 records（期望来源） */
async function openList(page) {
  const respP = page.waitForResponse(
    isListResponse
  )
  await mockAiOff(page)
  await loginAs(page, { who: 'student', path: PAGE, aiEnabled: false })
  const resp = await respP
  return (await resp.json()).data.records
}

/** 点击「查询」并等待列表接口返回，返回本次 records */
async function clickSearch(page) {
  const respP = page.waitForResponse((r) => isListResponse(r))
  await page.getByRole('button', { name: '查询' }).click()
  const resp = await respP
  return (await resp.json()).data.records
}

/** 按教室编号定位卡片（roomNo 唯一） */
function cardByNo(page, roomNo) {
  return page.locator('.room-card').filter({ hasText: roomNo }).first()
}

/** 选择 el-select 下拉项 */
async function pickSelect(page, trigger, optionText) {
  await trigger.click()
  await page.locator('.el-select-dropdown__item:visible', { hasText: optionText }).first().click()
}

/** 填写 el-date-picker 并提交（value-format=YYYY-MM-DD） */
async function pickDate(page, placeholder, date) {
  const input = page.getByPlaceholder(placeholder)
  await input.click()
  await input.fill(date)
  await input.press('Enter')
}

test.describe('教室列表页 - 筛选与展示', () => {
  test('关键词筛选：命中 / 无结果空状态 / 重置恢复', async ({ page }) => {
    await openList(page)

    const keyword = page.getByPlaceholder('教室名称 / 编号')
    // 命中：关键词 A101
    await keyword.fill('A101')
    const hit = await clickSearch(page)
    expect(hit.length, '关键词 A101 应至少命中 1 间').toBeGreaterThan(0)
    await expect(page.locator('.room-card')).toHaveCount(hit.length)
    await expect(cardByNo(page, 'A101')).toBeVisible()

    // 无结果：关键词不存在的编号 → 友好空状态
    await keyword.fill('ZZZ_NOT_EXIST_9527')
    const none = await clickSearch(page)
    expect(none.length).toBe(0)
    await expect(page.getByText('没有找到符合条件的教室，换个条件试试吧')).toBeVisible()
    await expect(page.getByRole('button', { name: '重置筛选' })).toBeVisible()

    // 重置：恢复全部教室
    const respP = page.waitForResponse((r) => isListResponse(r))
    await page.getByRole('button', { name: '重置筛选' }).click()
    const all = (await (await respP).json()).data.records
    await expect(keyword).toHaveValue('')
    await expect(page.locator('.room-card')).toHaveCount(all.length)
    await expect(page.getByText('没有找到符合条件的教室，换个条件试试吧')).toHaveCount(0)
  })

  test('楼栋 / 类型组合筛选：结果全部符合所选条件', async ({ page }) => {
    await openList(page)
    await openMoreFilters(page)

    const buildingSelect = page.locator('.el-form-item').filter({ hasText: '楼栋' }).locator('.el-select')
    await pickSelect(page, buildingSelect, BUILDING)
    const byBuilding = await clickSearch(page)
    expect(byBuilding.length, '信息楼应至少 1 间').toBeGreaterThan(0)
    await expect(page.locator('.room-card')).toHaveCount(byBuilding.length)
    for (const room of byBuilding) {
      expect(room.building, '接口返回应全部为所选楼栋').toBe(BUILDING)
      await expect(cardByNo(page, room.roomNo).locator('.meta-item').filter({ hasText: BUILDING })).toHaveCount(1)
    }

    // 叠加类型 = 实验室（type=2），楼栋条件继续保持
    const typeSelect = page.locator('.el-form-item').filter({ hasText: '类型' }).locator('.el-select')
    await pickSelect(page, typeSelect, '实验室')
    const byBoth = await clickSearch(page)
    expect(byBoth.length, '信息楼 + 实验室应至少 1 间').toBeGreaterThan(0)
    await expect(page.locator('.room-card')).toHaveCount(byBoth.length)
    for (const room of byBoth) {
      expect(room.building).toBe(BUILDING)
      expect(room.type).toBe(2)
      await expect(cardByNo(page, room.roomNo).locator('.room-type')).toHaveText('实验室')
    }
  })

  test('日期筛选：显示该日已通过占用提示', async ({ page, request }) => {
    // 先进入页面（学生 Token 以页面为准，避免“登录顶号”导致 Token 失效）
    await openList(page)
    const token = await pageToken(page)

    // 造一条已通过预约（A101，挑一个空档），使列表出现"当天已有 N 个时段被预约"
    const admin = await apiLogin(request, 'admin')
    const seeded = await seedApprovedAny(request, {
      adminToken: admin.token,
      studentToken: token,
      classroomId: 1,
      dates: [futureDate(1), futureDate(2), futureDate(3)],
      slots: [SLOTS.morning, SLOTS.noon, SLOTS.afternoon, SLOTS.evening],
      purpose: e2ePurpose('11-日期筛选')
    })

    await openMoreFilters(page)
    await pickDate(page, '选择日期', seeded.date)
    await clickSearch(page)

    const card = cardByNo(page, 'A101')
    await expect(card.locator('.occupied-bar')).toContainText(`${seeded.date} 当天已有`)
    await expect(card.locator('.occupied-bar')).toContainText('个时段被预约')

    await tryCancel(request, token, seeded.id)
  })

  test('卡片展示字段：名称/编号/楼栋/容量/类型/今日剩余/详情入口', async ({ page, request }) => {
    const { token } = await apiLogin(request, 'student')
    const rooms = expectOk(await listClassrooms(request, token, { page: 1, size: 100 }), '取教室列表').records
    const room = rooms.find((r) => r.id === 1)
    expect(room, '种子教室 id=1 应存在').toBeTruthy()

    await openList(page)
    await page.getByPlaceholder('教室名称 / 编号').fill('A101')
    const hit = await clickSearch(page)
    const expected = hit.find((r) => r.id === 1)
    expect(expected, 'A101 应在筛选结果中').toBeTruthy()

    // 卡片标题会去掉与门牌重复的编号前缀（如「A101多媒体教室」→「多媒体教室」）
    const displayName = expected.name.startsWith(expected.roomNo)
      ? expected.name.slice(expected.roomNo.length).trim() || expected.name
      : expected.name
    const card = cardByNo(page, expected.roomNo)
    await expect(card.locator('.room-name')).toHaveText(displayName)
    await expect(card.locator('.room-no')).toHaveText(expected.roomNo)
    await expect(card.getByText(`${expected.building} 楼栋`)).toBeVisible()
    await expect(card.getByText(`容量 ${expected.capacity} 人`)).toBeVisible()
    await expect(card).toHaveAttribute('role', 'button')
    await expect(card.locator('.card-cta')).toHaveText('查看详情并预约')
    await expect(card.locator('.remain-slots')).toContainText('今日剩余')
    await expect(card.locator('.remain-slots')).toContainText('时段可约')
    // 类型标签文案（1-普通教室）
    expect(expected.type).toBe(1)
    await expect(card.locator('.room-type')).toHaveText('普通教室')
  })

  test('收起高级筛选：已生效楼栋条件仍可见且可单项清除', async ({ page }) => {
    await openList(page)
    await openMoreFilters(page)
    const buildingSelect = page.locator('.el-form-item').filter({ hasText: '楼栋' }).locator('.el-select')
    await pickSelect(page, buildingSelect, BUILDING)
    await clickSearch(page)

    await page.getByRole('button', { name: '收起筛选' }).click()
    const summary = page.locator('.filter-summary')
    await expect(summary).toBeVisible()
    await expect(summary).toContainText(BUILDING)
    const clearBuilding = summary.locator('.filter-summary-item').filter({ hasText: BUILDING })
    await expect(clearBuilding).toHaveCount(1)

    const respP = page.waitForResponse((r) => isListResponse(r))
    await clearBuilding.click()
    const all = (await (await respP).json()).data.records
    await expect(summary).toHaveCount(0)
    await expect(page.locator('.room-card')).toHaveCount(all.length)
  })

  test('分页切换：改页长后翻页生效，高页码改页长先回第 1 页', async ({ page }) => {
    await openList(page)

    // 页长改为 6 → 请求第 1 页（size=6），再翻到第 2 页
    const sizeRespP = page.waitForResponse((r) => isListResponse(r))
    await page.locator('.el-pagination__sizes .el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: '6条/页' }).first().click()
    const p1 = (await (await sizeRespP).json()).data.records
    await expect(page.locator('.room-card')).toHaveCount(p1.length)

    const nextBtn = page.locator('.el-pagination .btn-next')
    if (await nextBtn.isDisabled()) {
      // 数据不足以分页时（其他任务删减），至少验证页长切换生效即通过
      await expect(page.locator('.el-pager li.is-active')).toHaveText('1')
      return
    }
    const nextRespP = page.waitForResponse((r) => isListResponse(r))
    await nextBtn.click()
    const p2 = (await (await nextRespP).json()).data.records
    await expect(page.locator('.el-pager li.is-active')).toHaveText('2')
    await expect(page.locator('.room-card')).toHaveCount(p2.length)

    // 高页码直接改页长：首次请求必须带 page=1，不能先请求旧页码再由组件夹紧。
    const resetPageRespP = page.waitForResponse((r) => {
      if (!isListResponse(r)) return false
      const url = new URL(r.url())
      return url.searchParams.get('page') === '1' && url.searchParams.get('size') === '12'
    })
    await page.locator('.el-pagination__sizes .el-select').click()
    await page.locator('.el-select-dropdown__item:visible', { hasText: '12条/页' }).first().click()
    await resetPageRespP
    await expect(page.locator('.el-pager li.is-active')).toHaveText('1')
  })

  test('实时状态标签 / 今日剩余时段与接口口径一致', async ({ page, request }) => {
    const { token } = await apiLogin(request, 'student')
    const rooms = expectOk(await listClassrooms(request, token, { page: 1, size: 100 }), '取教室列表').records
    const byName = Object.fromEntries(rooms.map((r) => [r.name, r]))

    const shown = await openList(page)
    expect(shown.length, '列表应至少渲染 1 张卡片').toBeGreaterThan(0)
    await expect(page.locator('.room-card')).toHaveCount(shown.length)

    for (const room of shown) {
      const apiRoom = byName[room.name]
      expect(apiRoom, `卡片「${room.name}」应存在于接口返回`).toBeTruthy()
      const card = page.locator('.room-card').filter({ hasText: room.roomNo }).first()
      const status = (await card.locator('.room-card-head .status-cell').innerText()).trim()
      // 状态标签三态之一且与接口一致
      expect(['当前空闲', '使用中', '已结束']).toContain(status)
      expect(status, `「${room.name}」状态标签应与接口一致`).toBe(apiRoom.statusLabel)
      // 今日剩余时段与接口一致
      await expect(card.locator('.remain-slots')).toContainText(String(room.todayRemainingSlots))
    }
  })

  test('筛选条件记忆：离开教室列表再返回，条件仍保留', async ({ page }) => {
    await openList(page)

    const keyword = page.getByPlaceholder('教室名称 / 编号')
    await keyword.fill('A1')
    await openMoreFilters(page)
    const buildingSelect = page.locator('.el-form-item').filter({ hasText: '楼栋' }).locator('.el-select')
    await pickSelect(page, buildingSelect, BUILDING)
    await clickSearch(page)

    // 离开：进入我的预约后返回
    await page.getByRole('menuitem', { name: '我的预约' }).click()
    await expect(page).toHaveURL(/\/student\/my-reservations/)
    await page.getByRole('menuitem', { name: '教室列表' }).click()
    await expect(page).toHaveURL(/\/student\/home/)

    // 条件保留
    await expect(page.getByPlaceholder('教室名称 / 编号')).toHaveValue('A1')
    await expect(page.locator('.el-form-item').filter({ hasText: '楼栋' }).locator('.el-select')).toContainText(
      BUILDING
    )
  })

  test('列表请求失败：显示局部错误与重试，不伪装成空教室', async ({ page }) => {
    await mockAiOff(page)
    await page.route('**/api/classroom/list*', (route) => route.abort('failed'))
    await loginAs(page, { who: 'student', path: PAGE, aiEnabled: false })

    await expect(page.locator('.list-error')).toBeVisible()
    await expect(page.locator('.list-error')).toContainText('加载失败')
    await expect(page.getByText('没有找到符合条件的教室，换个条件试试吧')).toHaveCount(0)

    await page.unroute('**/api/classroom/list*')
    const respP = page.waitForResponse((r) => isListResponse(r) && r.status() === 200)
    await page.locator('.list-error').getByRole('button', { name: '重试' }).click()
    await respP
    await expect(page.locator('.list-error')).toHaveCount(0)
    await expect(page.locator('.room-card').first()).toBeVisible()
  })
})