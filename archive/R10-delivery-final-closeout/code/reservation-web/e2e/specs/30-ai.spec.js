/**
 * AI 三入口 + AI 关闭态 E2E（浏览器级）
 *
 * 覆盖（对应任务清单 B 的 1~5）：
 *   1) 教室列表页「AI 为你推荐」卡区：Top3、字段、AI 标注、重新选择入口、点击直达详情
 *   2) 「AI 快速预约」弹窗：解析回填 → 候选教室 → 取消不写库
 *   3) 「AI 预约智能助手」悬浮球：默认收起 → 展开 → 对话 → AI 标注
 *   4) 管理端 AI 合规校验：显示校验结果（只提示），状态仍为待审核(0)
 *   4.1) 管理端 AI 合规校验：首屏自动校验全部失败时入口仍可见且可点击重试（回归：入口曾会永久消失）
 *   5) AI 关闭态：入口数为 0、核心功能可用、连探测请求都不发
 *
 * 约束遵守：
 *   - 真实模型零调用：全程用 mockAi 在浏览器层拦截 /api/ai/*（请求不出浏览器）
 *   - 造预约只用教室 C101(9)；AI 推荐的唯一可点击项也是 C101(9)
 *   - 不断言共享库的绝对数量；「入口数=0」属允许的「不存在」断言
 */
import { test, expect } from '@playwright/test'
import { apiLogin, loginAs } from '../helpers/auth'
import { createReservation, expectOk, listManage, listMine, tryCancel } from '../helpers/api'
import { CLASSROOMS, e2ePurpose, futureDate, SLOTS } from '../helpers/data'
import { AI_PATHS, mockAi } from '../helpers/ai'

/** 自定义推荐结果：第一位 C101(9) 是本用例唯一会点击/派生的教室，其余两条仅用于展示校验 */
function recommendPayload() {
  const date = futureDate(1)
  return {
    enabled: true,
    message: null,
    date,
    recommendations: [
      {
        classroomId: CLASSROOMS.C101.id,
        name: 'C101报告厅',
        building: '综合楼',
        roomNo: 'C101',
        type: 1,
        capacity: 120,
        reason: '与你常约的报告厅一致，明日 14:00-16:00 空闲'
      },
      {
        classroomId: CLASSROOMS.C201.id,
        name: 'C201电子实验室',
        building: '综合楼',
        roomNo: 'C201',
        type: 2,
        capacity: 40,
        reason: '同为电子实验场景，容量与你近期预约规模匹配'
      },
      {
        classroomId: CLASSROOMS.C401.id,
        name: 'C401自习教室',
        building: '综合楼',
        roomNo: 'C401',
        type: 1,
        capacity: 90,
        reason: '晚间空闲时段较多，适合自习类预约'
      }
    ]
  }
}

/* ------------------------------------------------------------------ */
/* 1. 教室列表页 AI 推荐卡                                              */
/* ------------------------------------------------------------------ */
test('教室列表：AI 为你推荐卡渲染 Top3，可直达详情且提供重新选择入口', async ({ page }) => {
  const calls = await mockAi(page, { [AI_PATHS.recommend]: recommendPayload() })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  await expect(page.getByText('AI 为你推荐')).toBeVisible()
  await expect(page.getByText('AI 生成，仅供参考', { exact: true })).toBeVisible()

  // 推荐条数由本用例 mock 决定（非共享库数据），固定为 3 条
  const items = page.locator('.recommend-item')
  await expect(items).toHaveCount(3)
  await expect(items.nth(0)).toContainText('C101报告厅')
  await expect(items.nth(0)).toContainText('普通教室')
  await expect(items.nth(0)).toContainText('综合楼 · C101 · 容量 120 人')
  await expect(items.nth(0)).toContainText('与你常约的报告厅一致')
  await expect(items.nth(1)).toContainText('C201电子实验室')
  await expect(items.nth(1)).toContainText('实验室')
  await expect(items.nth(2)).toContainText('C401自习教室')

  // 重新选择入口：搜索栏旁的「AI 快速预约」（可重新描述并另选教室）
  await expect(page.getByRole('button', { name: 'AI 快速预约' })).toBeVisible()

  // 点击第一条 → 直达该教室详情（可在详情页发起预约）
  await items.nth(0).click()
  await expect(page).toHaveURL(new RegExp(`/student/classrooms/${CLASSROOMS.C101.id}$`))
  await expect(page.getByText('C101报告厅').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '预约申请' })).toBeVisible()

  expect(calls[AI_PATHS.recommend] || 0).toBeGreaterThan(0)
})

/* ------------------------------------------------------------------ */
/* 2. AI 快速预约弹窗                                                   */
/* ------------------------------------------------------------------ */
test('AI 快速预约：解析结果回填表单 → 选教室 → 取消不写库', async ({ page }) => {
  const parseDate = futureDate(15)
  const parsedPurpose = e2ePurpose(`AI快速预约-取消未提交-${Date.now()}`)
  const parsePayload = {
    enabled: true,
    message: null,
    date: parseDate,
    startTime: SLOTS.afternoon[0],
    endTime: SLOTS.afternoon[1],
    capacity: 40,
    roomType: '普通教室',
    purpose: parsedPurpose,
    error: null
  }
  const calls = await mockAi(page, { [AI_PATHS.parse]: parsePayload })
  const student = await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  // 前置快照：本人预约中不应存在该用途（用唯一用途做「不存在」断言，规避并行写入干扰）
  const before = expectOk(await listMine(page.request, student.token, { page: 1, size: 100 }), 'AI快速预约-前置查询')
  expect(before.records.filter((r) => r.purpose === parsedPurpose)).toHaveLength(0)

  await page.getByRole('button', { name: 'AI 快速预约' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: 'AI 快速预约' })
  await expect(dialog).toBeVisible()

  // 步骤一：口语化描述 → 智能解析
  await dialog
    .getByPlaceholder('用自然语言描述预约需求，例如：明天下午2点到4点 40人 机房 做课程设计')
    .fill('E2E 自动化：下下周 下午两点到四点，40 人，普通教室，做课程设计')
  await dialog.getByRole('button', { name: '智能解析' }).click()

  await expect(dialog.getByText('AI 生成，仅供参考，可手动修改')).toBeVisible()

  // 解析结果回填断言（字段与 body 结构对齐：日期/时段/人数/类型/用途）
  await expect(dialog.locator('.el-form-item', { hasText: '预约日期' }).locator('input')).toHaveValue(parseDate)
  const slotItem = dialog.locator('.el-form-item', { hasText: '时段' })
  await expect(slotItem).toContainText(SLOTS.afternoon[0])
  await expect(slotItem).toContainText(SLOTS.afternoon[1])
  await expect(dialog.locator('.el-form-item', { hasText: '人数' }).locator('input')).toHaveValue('40')
  // 教室类型下拉当前值 = 解析出的中文类型（dict.ROOM_TYPE_LABELS）
  await expect(dialog.locator('.el-form-item', { hasText: '教室类型' })).toContainText('普通教室')
  await expect(dialog.locator('.el-form-item', { hasText: '预约用途' }).locator('input')).toHaveValue(parsedPurpose)

  // 步骤二：按解析条件筛选候选教室 → 选中 C101(9)
  await dialog.getByRole('button', { name: '下一步：选择教室' }).click()
  const pick = dialog.locator('.room-pick-item').filter({ hasText: 'C101报告厅' })
  await expect(pick).toBeVisible()
  await pick.click()

  // 步骤三：确认页展示已选教室（沿用既有提交流程），此处直接取消
  await expect(dialog.getByText('C101报告厅（综合楼 C101）')).toBeVisible()
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(dialog).toBeHidden()

  // 取消流程不写库：本人预约中仍不存在该用途
  const after = expectOk(await listMine(page.request, student.token, { page: 1, size: 100 }), 'AI快速预约-后置查询')
  expect(after.records.filter((r) => r.purpose === parsedPurpose)).toHaveLength(0)

  expect(calls[AI_PATHS.parse] || 0).toBeGreaterThan(0)
})

/* ------------------------------------------------------------------ */
/* 3. AI 预约智能助手悬浮球                                             */
/* ------------------------------------------------------------------ */
test('AI 预约助手：默认收起 → 展开侧边对话窗 → 发送问题渲染回答与 AI 标注', async ({ page }) => {
  const answer = '【E2E-MOCK】预约请到教室列表页选择教室后提交，管理员审核通过即可。'
  const calls = await mockAi(page, {
    [AI_PATHS.chat]: { enabled: true, message: null, answer }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  const ball = page.getByRole('button', { name: '打开 AI 预约助手' })
  const chatInput = page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…')

  // 默认收起：悬浮球在，对话输入框不可见
  await expect(ball).toBeVisible()
  await expect(chatInput).toBeHidden()

  // 点击悬浮球 → 展开侧边对话窗（用标题角色定位，避免命中欢迎语气泡中的同名文本）
  await ball.click()
  await expect(page.getByRole('heading', { name: 'AI 预约助手' })).toBeVisible()
  await expect(chatInput).toBeVisible()

  // 发送问题 → 渲染回答
  await chatInput.fill('E2E：怎么预约教室？')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.getByText('E2E：怎么预约教室？')).toBeVisible()
  await expect(page.getByText(answer)).toBeVisible()

  // 回答区 AI 标注
  await expect(page.getByText('AI 生成，仅供参考 · 仅解答预约相关问题')).toBeVisible()

  expect(calls[AI_PATHS.chat] || 0).toBe(1)
})

/* ------------------------------------------------------------------ */
/* 4. 管理端 AI 合规校验（只提示不改状态）                               */
/* ------------------------------------------------------------------ */
test('管理端 AI 合规校验：显示校验结果，但审核状态仍为待审核', async ({ page }) => {
  const purpose = e2ePurpose(`AI合规校验-${Date.now()}`)
  const reason = '用途描述过于笼统，建议补充具体课程或实验内容'

  // 前置：造一条待审核预约（学生 zhangsan，教室 C101(9)，未来日期，不审核）
  const student = await apiLogin(page.request, 'student')
  const id = expectOk(
    await createReservation(page.request, student.token, {
      classroomId: CLASSROOMS.C101.id,
      reserveDate: futureDate(16),
      startTime: SLOTS.afternoon[0],
      endTime: SLOTS.afternoon[1],
      purpose
    }),
    'AI合规校验-造待审核预约'
  )

  await mockAi(page, {
    [AI_PATHS.compliance]: { enabled: true, message: null, compliant: false, reason }
  })
  const admin = await loginAs(page, { who: 'admin', path: '/admin/audits', aiEnabled: true })

  // 用关键词把列表收敛到 zhangsan 的记录，我的记录为最新一条（页面自动触发前 5 条待审核的合规校验）
  await page.getByPlaceholder('用户账号 / 姓名 / 教室名称').fill('zhangsan')
  await page.getByRole('button', { name: '查询' }).click()

  const row = page.locator('.el-table__row').filter({ hasText: purpose })
  await expect(row).toBeVisible()
  // 违规用途红色高亮 + 「AI 校验：违规」标签
  await expect(row.locator('.purpose-violation')).toBeVisible()
  await expect(row.getByText('AI 校验：违规')).toBeVisible()
  // 悬浮展示校验原因
  await row.locator('.purpose-violation').hover()
  await expect(page.getByText(reason)).toBeVisible()

  // 只提示不改状态：管理端复核该记录仍为待审核(0)
  const manage = expectOk(
    await listManage(page.request, admin.token, { page: 1, size: 50, keyword: 'zhangsan' }),
    'AI合规校验-管理端复核'
  )
  const found = manage.records.find((r) => r.purpose === purpose)
  expect(found, '待审核记录应存在于管理端列表').toBeTruthy()
  expect(found.status, 'AI 合规校验只提示，不应改变审核状态').toBe(0)

  // 收尾：取消该待审核预约（未来日期，允许取消）
  await tryCancel(page.request, student.token, id)
})

/* ------------------------------------------------------------------ */
/* 4.1 管理端 AI 合规校验：自动校验失败时的入口可重试（回归）             */
/* ------------------------------------------------------------------ */
test('管理端 AI 合规校验：首屏自动校验全部失败时「AI 校验」入口仍可见且可点击重试', async ({ page }) => {
  const purpose = e2ePurpose(`AI合规失败重试-${Date.now()}`)

  // 前置：造一条待审核预约（学生 zhangsan，教室 C101(9)，未来日期，不审核）
  const student = await apiLogin(page.request, 'student')
  const id = expectOk(
    await createReservation(page.request, student.token, {
      classroomId: CLASSROOMS.C101.id,
      reserveDate: futureDate(18),
      startTime: SLOTS.afternoon[0],
      endTime: SLOTS.afternoon[1],
      purpose
    }),
    'AI合规失败重试-造待审核预约'
  )

  await mockAi(page)
  // 覆盖合规校验：模拟超时/限流（网络层失败）→ 首屏限量自动校验必然全部失败
  // 注：Playwright 后注册的路由优先匹配，故本路由覆盖 mockAi 的兜底实现
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

  // 自动校验确实发起过（并已失败）
  await expect.poll(() => complianceCalls).toBeGreaterThan(0)

  // 入口仍可见（回归点：aiEnabled 曾只能由「校验成功」置 true，全部失败后入口永久不渲染、无法重试）
  const tag = row.locator('.ai-tag')
  await expect(tag).toBeVisible()
  await expect(tag).toHaveText('AI 校验')

  // 可点击重试：点击后重新发起校验，失败后回到可重试态（不卡在「校验中…」）
  const before = complianceCalls
  await tag.click()
  await expect.poll(() => complianceCalls).toBeGreaterThan(before)
  await expect(tag).toHaveText('AI 校验')

  // 收尾：取消该待审核预约（未来日期，允许取消）
  await tryCancel(page.request, student.token, id)
})

/* ------------------------------------------------------------------ */
/* 5. AI 关闭态                                                        */
/* ------------------------------------------------------------------ */
test('AI 关闭态：AI 入口数为 0，核心预约功能可用且不发探测请求', async ({ page }) => {
  const calls = await mockAi(page)
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: false })

  // AI 入口隐藏（「不存在」断言）
  await expect(page.getByRole('button', { name: 'AI 快速预约' })).toHaveCount(0)
  await expect(page.getByText('AI 为你推荐')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '打开 AI 预约助手' })).toHaveCount(0)

  // 核心功能仍可用：教室列表渲染
  await expect(page.locator('.room-card').filter({ hasText: 'A101' }).first()).toBeVisible()

  // 普通预约入口仍可用：进入教室详情，「预约申请」按钮可点
  await page.locator('.room-card').filter({ hasText: 'A101' }).first().click()
  await expect(page).toHaveURL(/\/student\/classrooms\/1$/)
  await expect(page.getByRole('button', { name: '预约申请' })).toBeVisible()

  // 关闭态下连 /api/ai/recommend 探测都不发
  expect(calls[AI_PATHS.recommend] || 0).toBe(0)
})