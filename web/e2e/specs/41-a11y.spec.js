/**
 * 可点击非交互元素的键盘可达性回归（全局指令 v-clickable）
 *
 * 背景：卡片式 / 列表项式容器用 div、span 承接 @click，鼠标可用但键盘与读屏用户完全不可达。
 * src/directives/clickable.js 以 v-clickable 补齐 role="button"、tabindex="0" 与
 * Enter/空格键激活（空格需 preventDefault 防页面滚动），本文件为其回归断言。
 *
 * 覆盖全量 19 处（6 文件 / 9 类），逐类断言三件事：
 *   ① 按钮语义完整：role="button" + tabindex="0"
 *   ② Tab 可真实到达（不是仅有 tabindex 属性）
 *   ③ Enter / 空格激活后的行为与鼠标点击一致
 * 分布：AdminWelcome .quick-card×5、Profile .quick-item×4 / .settings-row.clickable×4 /
 *      .fav-item×1 / .msg-item×1、AuditManage .ai-tag-clickable×1 / .reason-tag×1、
 *      AiRecommendCard .recommend-item×1、AiQuickReserve .room-pick-item×1。
 *
 * 刻意排除：ClassroomList 的 .room-card——其内部已有真实 el-button，
 * 再补按钮语义会造成交互元素嵌套与双 Tab 停靠，故不加 v-clickable，也不在此断言。
 *
 * 数据策略：优先用 page.route 造确定性数据（收藏列表 / 我的预约 / AI 接口），
 * 只有「审核页待审核行」必须落库（驳回弹窗依赖真实行），收尾用 tryCancel 撤销。
 */
import { test, expect } from '@playwright/test'
import { apiLogin, loginAs } from '../helpers/auth'
import { createReservation, expectOk, tryCancel } from '../helpers/api'
import { isSlotFree } from '../helpers/stable-seed'
import { AI_PATHS, mockAi } from '../helpers/ai'
import { CLASSROOMS, e2ePurpose, futureDate, SLOTS } from '../helpers/data'

const PATH = '/student/profile'

/** 每次运行唯一后缀，避免与历史遗留数据同文案导致严格模式多匹配 */
const RUN = String(Date.now()).slice(-6)

/** 本文件专用造数资源（与其他 spec 错开）：教室 B101/B102/B201 × 未来 19~21 天 */
const ROOMS = [CLASSROOMS.B101.id, CLASSROOMS.B102.id, CLASSROOMS.B201.id]
const DATES = [futureDate(19), futureDate(20), futureDate(21)]
const SLOTS_LIST = [SLOTS.morning, SLOTS.noon, SLOTS.afternoon]

/** 假造的收藏与「我的预约」（结构对齐后端 VO，用于渲染 .fav-item / .msg-item） */
const FAKE_FAVORITES = [
  {
    id: 9001,
    classroomId: CLASSROOMS.A201.id,
    name: 'A201',
    building: 'A栋',
    roomNo: 'A201',
    type: 2,
    capacity: 40
  }
]
const FAKE_MY_RESERVATIONS = [
  {
    id: 9001,
    classroomName: 'A201',
    reserveDate: futureDate(19),
    startTime: SLOTS.morning[0],
    endTime: SLOTS.morning[1],
    status: 0,
    createTime: '2026-09-25 10:00:00'
  }
]

/** 断言一组元素都具备完整按钮语义（role + tabindex） */
async function expectButtonSemantics(locator, label) {
  const attrs = await locator.evaluateAll((els) =>
    els.map((el) => ({ role: el.getAttribute('role'), tab: el.getAttribute('tabindex') }))
  )
  expect(attrs.length, `${label}：应至少匹配到 1 个元素`).toBeGreaterThan(0)
  attrs.forEach((a, i) => {
    expect(a.role, `${label}：第 ${i + 1} 项应有 role="button"`).toBe('button')
    expect(a.tab, `${label}：第 ${i + 1} 项应有 tabindex="0"`).toBe('0')
  })
}

/**
 * 断言元素在 Tab 序列中可真实到达：
 * 取 DOM 顺序中它前一个可见可聚焦元素并聚焦，再按一次 Tab，焦点应落到目标元素上。
 * （元素自身隐藏时 indexOf 为 -1，直接判失败）
 */
async function expectTabReachable(page, locator) {
  const ok = await locator.evaluate((el) => {
    const FOCUSABLE =
      'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])'
    const all = [...document.querySelectorAll(FOCUSABLE)].filter((n) => n.offsetParent !== null)
    const i = all.indexOf(el)
    if (i <= 0) return false
    all[i - 1].focus()
    return document.activeElement === all[i - 1]
  })
  expect(ok, '目标元素不在 Tab 序列中（或前一个可聚焦元素定位失败）').toBe(true)
  await page.keyboard.press('Tab')
  await expect(locator).toBeFocused()
}

async function openProfile(page) {
  await loginAs(page, { who: 'student', path: PATH, aiEnabled: false })
  await expect(page.locator('.identity-card')).toBeVisible()
}

/** 账户与安全里的一行 */
const settingsRow = (page, label) => page.locator('.settings-row').filter({ hasText: label })
/** 弹窗（按标题精确定位，避免命中其他 el-dialog） */
const dialogByTitle = (page, title) => page.locator(`.el-dialog:has(.el-dialog__title:text-is("${title}"))`)

/** 在候选 (教室 × 日期 × 时段) 中挑第一个无冲突时段 */
async function freeSlot(request, token) {
  for (const classroomId of ROOMS) {
    for (const date of DATES) {
      for (const [startTime, endTime] of SLOTS_LIST) {
        if (await isSlotFree(request, token, { classroomId, date, startTime, endTime })) {
          return { classroomId, date, startTime, endTime }
        }
      }
    }
  }
  throw new Error('候选时段全部被占用，无法造数')
}

test.describe('键盘可达性（v-clickable）', () => {
  test('管理端首页 5 个快捷卡片：语义完整，Tab/Enter/空格均可激活跳转', async ({ page }) => {
    await loginAs(page, { who: 'admin', path: '/admin/home' })
    const cards = page.locator('.quick-card')
    const card = (name) => cards.filter({ has: page.locator('.quick-name', { hasText: name }) })
    await expect(cards).toHaveCount(5)
    await expectButtonSemantics(cards, '管理端快捷卡片')
    await expect(page.locator('.quick-card[role="button"]')).toHaveCount(5)
    await expectTabReachable(page, card('用户管理'))

    // Enter 激活 → 与鼠标点击同路由
    await card('用户管理').press('Enter')
    await expect(page).toHaveURL(/\/admin\/users$/)

    // 空格激活 → 与鼠标点击同路由
    await page.goto('/admin/home')
    await card('预约审核').press('Space')
    await expect(page).toHaveURL(/\/admin\/audits$/)
  })

  test('个人中心快捷功能四格：语义完整，Tab/Enter/空格均可激活跳转', async ({ page }) => {
    await openProfile(page)
    const quick = (name) => page.locator('.quick-item').filter({ hasText: name })
    await expect(page.locator('.quick-item')).toHaveCount(4)
    await expectButtonSemantics(page.locator('.quick-item'), '快捷功能四格')
    await expectTabReachable(page, quick('我的预约'))

    await quick('我的预约').press('Enter')
    await expect(page).toHaveURL(/\/student\/my-reservations$/)

    await page.goto(PATH)
    await quick('教室列表').press('Space')
    await expect(page).toHaveURL(/\/student\/home$/)
  })

  test('个人中心账户与安全四行：语义完整，Enter 开弹窗且空格不滚动页面', async ({ page }) => {
    await openProfile(page)
    const rows = page.locator('.settings-row.clickable')
    await expect(rows).toHaveCount(4)
    await expectButtonSemantics(rows, '账户与安全可点击行')
    await expectTabReachable(page, settingsRow(page, '修改密码'))

    // Enter 激活「姓名」行 → 打开编辑资料弹窗
    await settingsRow(page, '姓名').press('Enter')
    await expect(dialogByTitle(page, '编辑资料')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(dialogByTitle(page, '编辑资料')).toBeHidden()

    // 记录空格键的 defaultPrevented：指令必须阻止默认滚动，否则整页会被顺带滚走
    await page.evaluate(() => {
      window.spacePrevented = null
      document.addEventListener('keydown', (e) => {
        if (e.key === ' ') window.spacePrevented = e.defaultPrevented
      })
    })
    await settingsRow(page, '修改密码').press('Space')
    await expect(dialogByTitle(page, '修改密码')).toBeVisible()
    expect(await page.evaluate(() => window.spacePrevented), '空格激活应 preventDefault，避免页面滚动').toBe(true)
  })

  test('个人中心收藏卡片与消息项：语义完整，Enter 激活与点击一致', async ({ page }) => {
    const ok = (data) => (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: '操作成功', data }) })
    // 造确定性数据，避免依赖共享库里的收藏/预约存量（按 pathname 判定，勿用 glob：会误拦 /src/api/*.js 源码模块）
    await page.route((url) => url.pathname === '/api/favorite/list', ok(FAKE_FAVORITES))
    await page.route((url) => url.pathname === '/api/reservation/mine', ok({ records: FAKE_MY_RESERVATIONS, total: 1 }))
    await openProfile(page)

    const fav = page.locator('.fav-item')
    await expect(fav).toHaveCount(1)
    await expectButtonSemantics(fav, '收藏卡片')

    const msg = page.locator('.msg-item')
    await expect(msg).toHaveCount(1)
    await expectButtonSemantics(msg, '消息项')
    await expect(msg).toHaveClass(/unread/)

    // Enter 激活消息项 → 即时标记已读（与鼠标点击一致，无需刷新）+ 居中展开只读详情
    // 注意：弹窗会接管焦点（Element Plus 焦点陷阱），必须先关闭再继续操作页面其他元素
    await msg.press('Enter')
    await expect(msg).not.toHaveClass(/unread/)
    await expect(msg.locator('.msg-dot')).toHaveCount(0)

    const msgDialog = dialogByTitle(page, '预约待审核')
    await expect(msgDialog).toBeVisible()
    await expect(msgDialog.locator('.msg-detail-room')).toHaveText('A201')
    await page.keyboard.press('Escape')
    await expect(msgDialog).toBeHidden()

    // Enter 激活收藏卡片 → 直达教室详情
    await fav.press('Enter')
    await expect(page).toHaveURL(new RegExp(`/student/classrooms/${CLASSROOMS.A201.id}$`))
  })

  test('审核管理驳回快捷原因与 AI 校验标签：语义完整且键盘可激活', async ({ page, request }) => {
    // 前置：造一条待审核预约（驳回弹窗依赖真实待审核行；独立时段，跑完撤销）
    const student = await apiLogin(request, 'student')
    const slot = await freeSlot(request, student.token)
    const purpose = e2ePurpose(`a11y-审核-${RUN}`)
    const id = expectOk(
      await createReservation(request, student.token, {
        classroomId: slot.classroomId,
        reserveDate: slot.date,
        startTime: slot.startTime,
        endTime: slot.endTime,
        purpose
      }),
      '造待审核预约'
    )

    // AI 合规校验全部失败 → 保留可点击的「AI 校验」重试入口（成功则标签变为不可点击的结果态）
    await mockAi(page)
    let complianceCalls = 0
    await page.route(
      (url) => url.pathname === AI_PATHS.compliance,
      async (route) => {
        complianceCalls += 1
        await route.abort('failed')
      }
    )

    await loginAs(page, { who: 'admin', path: '/admin/audits', aiEnabled: true })
    await page.getByPlaceholder('用户账号 / 姓名 / 教室名称').fill('zhangsan')
    await page.getByRole('button', { name: '查询' }).click()
    const row = page.locator('.el-table__row').filter({ hasText: purpose })
    await expect(row).toBeVisible()

    // ① AI 校验标签（span.el-tag）：语义完整 + Enter 触发一次重试校验
    const aiTag = row.locator('.ai-tag-clickable')
    await expect(aiTag).toBeVisible()
    await expectButtonSemantics(aiTag, 'AI 校验标签')
    await expect.poll(() => complianceCalls).toBeGreaterThan(0)
    const before = complianceCalls
    await aiTag.press('Enter')
    await expect.poll(() => complianceCalls, 'Enter 应触发一次校验请求（与点击等价）').toBeGreaterThan(before)

    // ② 驳回弹窗内快捷原因标签：语义完整 + Enter 回填备注
    await row.getByRole('button', { name: '驳回' }).click()
    const dialog = dialogByTitle(page, '驳回预约')
    await expect(dialog).toBeVisible()
    await expectButtonSemantics(dialog.locator('.reason-tag'), '驳回快捷原因标签')
    await dialog.locator('.reason-tag', { hasText: '时间冲突' }).press('Enter')
    await expect(dialog.getByPlaceholder('驳回必须填写审核备注')).toHaveValue('时间冲突')
    await dialog.getByRole('button', { name: '取消' }).click()
    await expect(dialog).toBeHidden()

    // 收尾：撤销待审核预约
    await tryCancel(request, student.token, id)
  })

  test('教室列表 AI 推荐项：语义完整，Tab/Enter 可直达详情', async ({ page }) => {
    await mockAi(page)
    await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

    const items = page.locator('.recommend-item')
    await expect(items).toHaveCount(3)
    await expectButtonSemantics(items, 'AI 推荐项')
    await expectTabReachable(page, items.first())

    // 默认 mock 的第一条为 C301(id=11)
    await items.first().press('Enter')
    await expect(page).toHaveURL(new RegExp(`/student/classrooms/${CLASSROOMS.C301.id}$`))
  })

  test('AI 快速预约候选教室项：语义完整，Enter 选中进入确认页', async ({ page }) => {
    await mockAi(page, {
      [AI_PATHS.parse]: {
        enabled: true,
        message: null,
        date: futureDate(21),
        startTime: SLOTS.evening[0],
        endTime: SLOTS.evening[1],
        capacity: 40,
        roomType: '普通教室',
        purpose: e2ePurpose(`a11y-AI选教室-${RUN}`),
        error: null
      }
    })
    await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

    await page.getByRole('button', { name: 'AI 快速预约' }).click()
    const dialog = page.locator('.el-dialog').filter({ hasText: 'AI 快速预约' })
    await dialog
      .getByPlaceholder('用自然语言描述预约需求，例如：明天下午2点到4点 40人 机房 做课程设计')
      .fill('E2E：大后天下午四点到六点，40 人，普通教室，自习')
    await dialog.getByRole('button', { name: '智能解析' }).click()
    await dialog.getByRole('button', { name: '下一步：选择教室' }).click()

    const picks = dialog.locator('.room-pick-item')
    await expect(picks.first()).toBeVisible()
    await expectButtonSemantics(picks, '候选教室项')

    // Enter 激活 → 进入确认页，且确认页展示的正是被激活的那间教室
    const name = (await picks.first().locator('.room-pick-name').innerText()).trim()
    await picks.first().press('Enter')
    await expect(dialog.locator('.picked-room')).toContainText(name)
  })
})