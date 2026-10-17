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
 *   6) AI 降级提示渲染：4 个消费面（推荐卡 / 快速预约 / 助手 / 管理端合规标签）
 *      在 message 非空时切换来源标注并透出降级原因，使用户能区分真实模型与本地规则结果
 *
 * 约束遵守：
 *   - 真实模型零调用：全程用 mockAi 在浏览器层拦截 /api/ai/*（请求不出浏览器）
 *   - 造预约只用教室 C101(9)；AI 推荐的唯一可点击项也是 C101(9)
 *   - 不断言共享库的绝对数量；「入口数=0」属允许的「不存在」断言
 *   - 造数一律带 e2ePurpose 前缀（便于统一清理），并在用例内 tryCancel 收尾
 */
import { test, expect } from '@playwright/test'
import { apiLogin, loginAs } from '../helpers/auth'
import { createReservation, expectOk, listManage, listMine, tryCancel } from '../helpers/api'
import { CLASSROOMS, e2ePurpose, futureDate, SLOTS } from '../helpers/data'
import { AI_PATHS, mockAi } from '../helpers/ai'

/**
 * 从 SSE 响应体按线顺序提取全部事件名（R13-5 帧序列断言用）。
 * mock 线格式为 event:xxx\ndata:{...}\n\n（冒号后无空格），与 SseEmitter 一致。
 */
function eventNamesOf(body) {
  return [...body.matchAll(/^event:(.+)$/gm)].map((m) => m[1])
}

/** 提取指定事件帧的 data 并 JSON.parse；帧不存在返回 null */
function sseDataOf(body, event) {
  const m = body.match(new RegExp(`event:${event}\\ndata:([\\s\\S]*?)(?:\\n\\n|$)`))
  return m ? JSON.parse(m[1]) : null
}

/** 收集指定 AI 路径的全部请求 URL（用于断言前端确实带 ?stream=true） */
function watchAiUrls(page, path) {
  const urls = []
  page.on('request', (req) => {
    if (new URL(req.url()).pathname === path) {
      urls.push(req.url())
    }
  })
  return urls
}

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
  // R13-5：网络层录制 SSE 帧序列（meta→phase→result→done）
  const streams = []
  const calls = await mockAi(page, { [AI_PATHS.recommend]: recommendPayload() }, {
    onStream: (s) => streams.push(s)
  })
  const urls = watchAiUrls(page, AI_PATHS.recommend)
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

  // R13-5：前端走 ?stream=true；响应为结构化 SSE，线序 meta→phase→result→done，result 帧即 VO 本体
  expect(urls.some((u) => u.includes('?stream=true'))).toBe(true)
  const rec = streams.find((s) => s.path === AI_PATHS.recommend && s.stream)
  expect(rec, '推荐接口应返回 SSE 流').toBeTruthy()
  expect(eventNamesOf(rec.body)).toEqual(['meta', 'phase', 'result', 'done'])
  expect(sseDataOf(rec.body, 'meta')).toEqual({ enabled: true, degraded: false, message: null })
  expect(sseDataOf(rec.body, 'phase')).toEqual({
    phase: 'recommend',
    text: '正在分析你的预约习惯与明日教室空闲'
  })
  const resultVo = sseDataOf(rec.body, 'result')
  expect(resultVo.enabled).toBe(true)
  expect(resultVo.recommendations).toHaveLength(3)
  expect(resultVo.recommendations[0].classroomId).toBe(CLASSROOMS.C101.id)
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
  const streams = []
  const calls = await mockAi(page, { [AI_PATHS.parse]: parsePayload }, {
    onStream: (s) => streams.push(s)
  })
  const urls = watchAiUrls(page, AI_PATHS.parse)
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

  // R13-5：解析请求走 ?stream=true；meta→phase→result→done，result 帧字段即回填表单的同构 VO
  expect(urls.some((u) => u.includes('?stream=true'))).toBe(true)
  const ps = streams.find((s) => s.path === AI_PATHS.parse && s.stream)
  expect(ps, '解析接口应返回 SSE 流').toBeTruthy()
  expect(eventNamesOf(ps.body)).toEqual(['meta', 'phase', 'result', 'done'])
  expect(sseDataOf(ps.body, 'phase')).toEqual({ phase: 'parse', text: '正在理解你的预约需求' })
  const parseVo = sseDataOf(ps.body, 'result')
  expect(parseVo).toMatchObject({
    enabled: true,
    date: parseDate,
    startTime: SLOTS.afternoon[0],
    endTime: SLOTS.afternoon[1],
    capacity: 40,
    roomType: '普通教室',
    purpose: parsedPurpose,
    error: null
  })
})

/* ------------------------------------------------------------------ */
/* 2.1 R13-5 结构化流式：缓存命中无 phase 帧（meta→result→done）         */
/* ------------------------------------------------------------------ */
test('AI 快速预约：缓存命中（无 phase 帧）时解析结果仍正常回填', async ({ page }) => {
  const parseDate = futureDate(20)
  // _streamPhase:false 模拟后端缓存命中：直接 meta→result→done
  const parsePayload = {
    enabled: true,
    message: null,
    date: parseDate,
    startTime: SLOTS.morning[0],
    endTime: SLOTS.morning[1],
    capacity: 60,
    roomType: '机房',
    purpose: e2ePurpose(`AI流式缓存命中-${Date.now()}`),
    error: null,
    _streamPhase: false
  }
  const streams = []
  await mockAi(page, { [AI_PATHS.parse]: parsePayload }, { onStream: (s) => streams.push(s) })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  await page.getByRole('button', { name: 'AI 快速预约' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: 'AI 快速预约' })
  await dialog
    .getByPlaceholder('用自然语言描述预约需求，例如：明天下午2点到4点 40人 机房 做课程设计')
    .fill('E2E 自动化：缓存命中场景')
  await dialog.getByRole('button', { name: '智能解析' }).click()

  // 无 phase 帧时前端不得卡 loading，表单照常回填
  await expect(dialog.locator('.el-form-item', { hasText: '预约日期' }).locator('input')).toHaveValue(parseDate)
  await expect(dialog.locator('.el-form-item', { hasText: '预约用途' }).locator('input')).toHaveValue(parsePayload.purpose)
  await expect(dialog.getByRole('button', { name: '智能解析' })).not.toHaveClass(/is-loading/)

  const ps = streams.find((s) => s.path === AI_PATHS.parse && s.stream)
  expect(ps).toBeTruthy()
  expect(eventNamesOf(ps.body)).toEqual(['meta', 'result', 'done'])
  // result VO 中不得泄露 mock 控制字段
  expect(ps.body).not.toContain('_streamPhase')
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

  // 发送问题 → SSE 流式渲染回答
  await chatInput.fill('E2E：怎么预约教室？')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.getByText('E2E：怎么预约教室？')).toBeVisible()
  // 接收期（mock 有 120ms 延迟）：最终答案尚未出现，先处于接收态
  await expect(page.locator('.typing-cursor')).toBeVisible()
  await expect(page.getByText(answer)).toBeHidden()
  // 多帧按序拼接后得到完整答案（若 SSE 解析/拼接错误，文本不会等于 answer）
  await expect(page.getByText(answer)).toBeVisible()
  await expect(page.locator('.typing-cursor')).toHaveCount(0)

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

  const streams = []
  await mockAi(page, {
    [AI_PATHS.compliance]: { enabled: true, message: null, compliant: false, reason }
  }, { onStream: (s) => streams.push(s) })
  const urls = watchAiUrls(page, AI_PATHS.compliance)
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

  // R13-5：合规校验走 ?stream=true；meta→phase→result→done，result 帧即合规 VO
  expect(urls.some((u) => u.includes('?stream=true'))).toBe(true)
  const cs = streams.find((s) => s.path === AI_PATHS.compliance && s.stream)
  expect(cs, '合规接口应返回 SSE 流').toBeTruthy()
  expect(eventNamesOf(cs.body)).toEqual(['meta', 'phase', 'result', 'done'])
  expect(sseDataOf(cs.body, 'phase')).toEqual({
    phase: 'compliance',
    text: '正在校验预约用途的合规性'
  })
  expect(sseDataOf(cs.body, 'result')).toMatchObject({
    enabled: true,
    compliant: false,
    reason
  })

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
/* 4.2 结构化流式：建链前校验失败回 Result JSON 时不卡 loading（R13-5）   */
/* ------------------------------------------------------------------ */
test('管理端 AI 合规校验：SSE 建链前返回 Result JSON 时识别为失败，入口保持可重试', async ({ page }) => {
  const purpose = e2ePurpose(`AI合规JSON失败-${Date.now()}`)

  const student = await apiLogin(page.request, 'student')
  const id = expectOk(
    await createReservation(page.request, student.token, {
      classroomId: CLASSROOMS.C101.id,
      reserveDate: futureDate(21),
      startTime: SLOTS.afternoon[0],
      endTime: SLOTS.afternoon[1],
      purpose
    }),
    'AI合规JSON失败-造待审核预约'
  )

  await mockAi(page)
  // 模拟后端在 SSE 建立前抛 BusinessException：HTTP 200 + application/json 的 Result（全局异常处理器口径）
  let complianceCalls = 0
  await page.route(
    (url) => url.pathname === AI_PATHS.compliance,
    async (route) => {
      complianceCalls += 1
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 500, message: '请输入预约用途', data: null })
      })
    }
  )

  await loginAs(page, { who: 'admin', path: '/admin/audits', aiEnabled: true })
  await page.getByPlaceholder('用户账号 / 姓名 / 教室名称').fill('zhangsan')
  await page.getByRole('button', { name: '查询' }).click()

  const row = page.locator('.el-table__row').filter({ hasText: purpose })
  await expect(row).toBeVisible()
  await expect.poll(() => complianceCalls).toBeGreaterThan(0)

  // 前端必须把 JSON Result 识别为终态错误：标签不卡在「校验中…」，可重试
  const tag = row.locator('.ai-tag')
  await expect(tag).toBeVisible()
  await expect(tag).toHaveText('AI 校验')

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

/* ------------------------------------------------------------------ */
/* 6. AI 降级提示渲染（message 透出，覆盖 4 个消费面）                    */
/* ------------------------------------------------------------------ */
/**
 * 降级提示文案：与后端 AiConstants.AI_RATE_LIMITED_MESSAGE 同义。
 * 前端一律直接渲染后端 message（不另造文案），故这里只断言「透出」而非「拼写」。
 */
const DEGRADE_MSG = 'AI 请求过于频繁，已自动切换为本地规则模式'

test('AI 降级提示 · 推荐卡：来源标注切换为「本地规则结果」并透出降级原因', async ({ page }) => {
  await mockAi(page, {
    [AI_PATHS.recommend]: { ...recommendPayload(), message: DEGRADE_MSG }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  const card = page.locator('.ai-recommend-card')
  await expect(card).toBeVisible()

  // 来源标注：降级时结果来自本地规则，不应再标注「AI 生成」
  await expect(card.getByText('本地规则结果', { exact: true })).toBeVisible()
  await expect(card.getByText('AI 生成，仅供参考', { exact: true })).toHaveCount(0)

  // 页脚：降级原因替换常规说明文案
  const foot = card.locator('.ai-recommend-foot')
  await expect(foot).toContainText(DEGRADE_MSG)
  await expect(foot).not.toContainText('基于您的历史预约习惯')
})

test('AI 降级提示 · 快速预约：顶部标注改为降级原因', async ({ page }) => {
  await mockAi(page, {
    [AI_PATHS.parse]: {
      enabled: true,
      message: DEGRADE_MSG,
      date: futureDate(15),
      startTime: SLOTS.afternoon[0],
      endTime: SLOTS.afternoon[1],
      capacity: 40,
      roomType: '普通教室',
      purpose: '课程设计',
      error: null
    }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  await page.getByRole('button', { name: 'AI 快速预约' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: 'AI 快速预约' })
  await dialog
    .getByPlaceholder('用自然语言描述预约需求，例如：明天下午2点到4点 40人 机房 做课程设计')
    .fill('E2E 自动化：降级态解析')
  await dialog.getByRole('button', { name: '智能解析' }).click()

  // 顶部标注：降级原因替换「AI 生成，仅供参考，可手动修改」（本用例不提交，不写库）
  await expect(dialog.getByText(DEGRADE_MSG)).toBeVisible()
  await expect(dialog.getByText('AI 生成，仅供参考，可手动修改')).toHaveCount(0)
})

test('AI 降级提示 · 助手：底栏提示替换为降级原因', async ({ page }) => {
  await mockAi(page, {
    [AI_PATHS.chat]: { enabled: true, message: DEGRADE_MSG, answer: '【E2E-MOCK】降级态回答' }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  const tip = page.locator('.chat-tip')
  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()
  // 未提问时仍是常规 AI 标注
  await expect(tip).toContainText('AI 生成，仅供参考 · 仅解答预约相关问题')

  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E：怎么取消预约？')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.getByText('【E2E-MOCK】降级态回答')).toBeVisible()

  // 回答降级后底栏切换为降级原因（此时回答来自本地 FAQ 规则）
  await expect(tip).toContainText(DEGRADE_MSG)
  await expect(tip).not.toContainText('AI 生成，仅供参考')
})

test('AI 降级提示 · 管理端合规校验：标签显示「规则校验：…」且悬浮含降级原因', async ({ page }) => {
  const purpose = e2ePurpose(`AI合规降级-${Date.now()}`)
  const reason = '规则理由占位：建议补充具体课程或实验内容'

  // 前置：造一条待审核预约（学生 zhangsan，教室 C101(9)，未来日期，不审核）
  const student = await apiLogin(page.request, 'student')
  const id = expectOk(
    await createReservation(page.request, student.token, {
      classroomId: CLASSROOMS.C101.id,
      reserveDate: futureDate(19),
      startTime: SLOTS.afternoon[0],
      endTime: SLOTS.afternoon[1],
      purpose
    }),
    'AI合规降级-造待审核预约'
  )

  await mockAi(page, {
    [AI_PATHS.compliance]: { enabled: true, message: DEGRADE_MSG, compliant: true, reason }
  })
  await loginAs(page, { who: 'admin', path: '/admin/audits', aiEnabled: true })

  await page.getByPlaceholder('用户账号 / 姓名 / 教室名称').fill('zhangsan')
  await page.getByRole('button', { name: '查询' }).click()

  const row = page.locator('.el-table__row').filter({ hasText: purpose })
  await expect(row).toBeVisible()

  const tag = row.locator('.ai-tag')
  await expect(tag).toBeVisible()
  // 首屏限量自动校验（5 条）未覆盖到该行时，手动点击触发
  if ((await tag.innerText()).trim() === 'AI 校验') {
    await tag.click()
  }

  // 降级时判定来自关键词规则，标注不应是「AI 校验」
  await expect(tag).toHaveText(/^规则校验：(通过|违规)$/)
  await expect(row.getByText('AI 校验：通过')).toHaveCount(0)

  // 悬浮文案 = 规则判定原因 + 降级原因
  await row.locator('.purpose-cell > span').first().hover()
  const popper = page.locator('.el-popper').filter({ hasText: reason })
  await expect(popper).toBeVisible()
  await expect(popper).toContainText(DEGRADE_MSG)

  // 收尾：取消该待审核预约（未来日期，允许取消）
  await tryCancel(page.request, student.token, id)
})

/* ------------------------------------------------------------------ */
/* 7. AI 助手流式问答（SSE：meta/delta/done）                            */
/* ------------------------------------------------------------------ */
test('AI 助手流式：请求带 stream=true，多帧拼接为完整答案', async ({ page }) => {
  const answer = '【E2E-STREAM】待审核或已通过的预约可在开始前1小时自由取消。'
  const calls = await mockAi(page, {
    [AI_PATHS.chat]: { enabled: true, message: null, answer }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E-流式：怎么取消？')
  await page.getByRole('button', { name: '发送' }).click()

  // SSE 多帧按序拼接，最终文本完整
  await expect(page.getByText(answer)).toBeVisible()
  await expect(page.locator('.chat-tip')).toContainText('AI 生成，仅供参考 · 仅解答预约相关问题')
  // 确实走的是流式 URL（pathname 计数仍归 /api/ai/chat）
  expect(calls[AI_PATHS.chat] || 0).toBe(1)
})

test('AI 助手流式降级：meta.degraded=true 时底栏切换为降级原因，FAQ 文本完整渲染', async ({ page }) => {
  const fallbackAnswer = '【E2E-STREAM】降级分段回答ABCDEFGHIJKLMNOPQRSTUVWXYZ'
  await mockAi(page, {
    [AI_PATHS.chat]: { enabled: true, message: DEGRADE_MSG, answer: fallbackAnswer }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  const tip = page.locator('.chat-tip')
  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E-流式降级：审核？')
  await page.getByRole('button', { name: '发送' }).click()

  await expect(page.getByText(fallbackAnswer)).toBeVisible()
  await expect(tip).toContainText(DEGRADE_MSG)
  await expect(tip).not.toContainText('AI 生成，仅供参考')
})

test('AI 助手流式关闭态：meta.enabled=false 时气泡展示关闭文案，与降级/AI生成标注区分', async ({ page }) => {
  const disabledMsg = 'AI 服务未启用，当前为纯预约系统模式'
  await mockAi(page, {
    [AI_PATHS.chat]: { enabled: false, message: disabledMsg }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  const tip = page.locator('.chat-tip')
  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E-流式关闭态：在吗？')
  await page.getByRole('button', { name: '发送' }).click()

  // 关闭态：气泡直接展示后端关闭文案（不落入「暂时无法回答」兜底），光标消失
  await expect(page.locator('.chat-bubble').filter({ hasText: disabledMsg })).toBeVisible()
  await expect(page.locator('.typing-cursor')).toHaveCount(0)
  // 底栏透出关闭文案（info），既不是降级 warning，也不出现「AI 生成」标注
  await expect(tip).toContainText(disabledMsg)
  await expect(tip).not.toContainText('AI 生成，仅供参考')
})

test('AI 助手流式：发送中点「停止」中断接收，空气泡移除且可立即再次发送', async ({ page }) => {
  await mockAi(page)
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  // 挂起 chat SSE：handler 永不 fulfill（后注册的路由优先生效，mockAi 的 chat 被遮蔽，
  // recommend 探测仍走 mockAi）。模拟「回答持续生成中」的稳定时机，避免与延迟赛跑。
  await page.route(
    (url) => url.pathname === AI_PATHS.chat,
    async () => {
      await new Promise(() => {})
    }
  )

  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()

  // 第一次发送：进入流式接收态后点停止
  const streamRequest = page.waitForRequest((r) => r.url().includes('/api/ai/chat?stream=true'))
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E-停止：第一次问题')
  await page.getByRole('button', { name: '发送' }).click()
  await streamRequest

  await expect(page.getByRole('button', { name: '停止' })).toBeVisible()
  await page.getByRole('button', { name: '停止' }).click()

  // 发送态复位：「发送」按钮回来、打字光标消失；空 AI 气泡被移除，仅余欢迎语 + 用户提问
  await expect(page.getByRole('button', { name: '发送' })).toBeVisible()
  await expect(page.locator('.typing-cursor')).toHaveCount(0)
  await expect(page.locator('.chat-msg')).toHaveCount(2)
  await expect(page.locator('.chat-msg.user .chat-bubble')).toContainText('第一次问题')

  // 输入框未被锁定：可立即发起第二次发送（再次进入接收态后停止收尾）
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E-停止：第二次问题')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.getByRole('button', { name: '停止' })).toBeVisible()
  await page.getByRole('button', { name: '停止' }).click()
  await expect(page.getByRole('button', { name: '发送' })).toBeVisible()
  await expect(page.locator('.chat-msg')).toHaveCount(3)
})

test('AI 助手 Markdown：标题/加粗/列表/链接/代码块渲染正确，HTML 注入被转义不执行', async ({ page }) => {
  // 含完整 Markdown 语法 + XSS 探针；mock 按 8 字切 delta，代码块围栏在增量期必然被切碎，
  // done 后全量终渲染必须把围栏闭合成 pre>code（两段式渲染的 E2E 锁定）
  const answer = [
    '# 取消规则',
    '',
    '**开始前 1 小时**可自由取消，说明见 [帮助文档](https://example.com/help)。',
    '',
    '- 待审核可取消',
    '- 已通过可取消',
    '',
    '```',
    '我的预约 → 取消 → 二次确认',
    '```',
    '',
    '<img src=x onerror=window.__xss=1><script>window.__xss=1</script>'
  ].join('\n')
  await mockAi(page, {
    [AI_PATHS.chat]: { enabled: true, message: null, answer }
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill('E2E-MD：怎么取消？')
  await page.getByRole('button', { name: '发送' }).click()

  const bubble = page.locator('.chat-msg.ai .chat-bubble').last()
  // 块级/行内语法
  await expect(bubble.locator('h1')).toHaveText('取消规则')
  await expect(bubble.locator('strong')).toHaveText('开始前 1 小时')
  await expect(bubble.locator('ul li')).toHaveText(['待审核可取消', '已通过可取消'])
  const link = bubble.locator('a')
  await expect(link).toHaveText('帮助文档')
  await expect(link).toHaveAttribute('href', 'https://example.com/help')
  await expect(link).toHaveAttribute('target', '_blank')
  await expect(link).toHaveAttribute('rel', /noopener/)
  // 切碎的围栏在流终闭合
  await expect(bubble.locator('pre code')).toContainText('我的预约 → 取消 → 二次确认')

  // XSS 双保险：无 img/script 元素、探针未执行；原始标签以转义文本呈现
  await expect(bubble.locator('img')).toHaveCount(0)
  await expect(bubble.locator('script')).toHaveCount(0)
  expect(await page.evaluate(() => window.__xss)).toBeUndefined()
  await expect(bubble).toContainText('<img')
})

test('AI 助手多轮：第二轮请求携带第一轮问答作为 history，首轮不带 history', async ({ page }) => {
  const answer1 = '【E2E-HIST-A1】教二 301 明天 10 点到 12 点空闲，可以直接预约。'
  const answer2 = '【E2E-HIST-A2】你说的是教二 301，按流程提交后等待管理员审核即可。'
  await mockAi(page, {
    // 两轮答案按问题文本分流（同一 mock 实例对每次请求动态返回）
    [AI_PATHS.chat]: (body) => ({
      enabled: true,
      message: null,
      answer: body.question.includes('第二轮') ? answer2 : answer1
    })
  })
  await loginAs(page, { who: 'student', path: '/student/home', aiEnabled: true })

  // 收集每次问答请求体（pathname 不含 query，stream=true 也能命中）
  const chatBodies = []
  page.on('request', (req) => {
    if (req.method() === 'POST' && new URL(req.url()).pathname === AI_PATHS.chat && req.postData()) {
      chatBodies.push(JSON.parse(req.postData()))
    }
  })

  await page.getByRole('button', { name: '打开 AI 预约助手' }).click()
  const input = page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…')

  await input.fill('E2E-HIST-Q1：教二301明天有空吗')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.locator('.chat-msg.ai .chat-bubble').last()).toContainText(answer1)

  await input.fill('E2E-HIST-Q2：第二轮，那我怎么预约它')
  await page.getByRole('button', { name: '发送' }).click()
  await expect(page.locator('.chat-msg.ai .chat-bubble').last()).toContainText(answer2)

  // 首轮无 history（字段缺省）；第二轮携带第一轮 user/assistant 两条，顺序与内容精确一致
  expect(chatBodies).toHaveLength(2)
  expect(chatBodies[0].history).toBeFalsy()
  expect(chatBodies[1].history).toEqual([
    { role: 'user', content: 'E2E-HIST-Q1：教二301明天有空吗' },
    { role: 'assistant', content: answer1 }
  ])
})