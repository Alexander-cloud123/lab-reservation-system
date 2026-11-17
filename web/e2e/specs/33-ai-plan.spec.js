/**
 * AI 预约方案卡 E2E（浏览器级，R15-1）
 *
 * 覆盖「plan & confirm」闭环的前半段：
 *   1) 一句「帮我约明天的机房」→ 对话里出现预约方案卡（3 间候选 + 去预约按钮 + 其余提示）
 *   2) 点「去预约」→ 复用既有确认弹窗，直接落到第 3 步，教室/日期/时段已预填，
 *      实时冲突校验显示「该时段可预约」（AI 不代提交，用户仍需手动点提交）
 *   3) 没有候选时只出澄清文案，不给可点按钮
 *
 * 约束：真实模型零调用（mockAi 拦截 /api/ai/*）；不写业务数据（不点提交），
 * 因此不会污染共享库里的预约记录。
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { AI_PATHS, mockAi } from '../helpers/ai'

/** 明天（YYYY-MM-DD），日期与时区无关的纯字符串拼接 */
function tomorrow() {
  const date = new Date()
  date.setDate(date.getDate() + 1)
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/** 方案帧数据（与后端 AiPlanVO 字段一致） */
function planPayload({ candidates, moreAvailable = 1, clarify = '' }) {
  return {
    enabled: true,
    date: tomorrow(),
    startTime: '14:00',
    endTime: '16:00',
    capacity: 30,
    roomType: '机房',
    purpose: '算法实验课',
    moreAvailable,
    clarify,
    excludedCount: 2,
    candidates
  }
}

const TWO_ROOMS = [
  {
    classroomId: 9,
    name: 'C101 云机房',
    building: '创新楼',
    roomNo: 'C101',
    type: 3,
    capacity: 40,
    reason: '类型匹配 · 该时段空闲'
  },
  {
    classroomId: 10,
    name: 'C201 云机房',
    building: '创新楼',
    roomNo: 'C201',
    type: 3,
    capacity: 32,
    reason: '容量刚好 · 该时段空闲'
  }
]

/** 挂载 AI 拦截：问答固定返回一条带方案帧的流式回答 */
async function mockPlanChat(page, plan) {
  return mockAi(page, {
    [AI_PATHS.chat]: {
      enabled: true,
      message: null,
      answer: plan.candidates.length
        ? '我找到几间合适的教室，选一间点「去预约」，在确认弹窗里核对后再提交。'
        : '这个时段没有满足条件的教室，可以换个时段，或者放宽人数/类型。',
      _plan: plan,
      _think: {
        steps: [
          { id: 'scene', label: '确认问题是否属于预约范围', elapsedMs: 12 },
          { id: 'parse', label: '解析预约需求', elapsedMs: 800 },
          { id: 'retrieve', label: '检索可预约教室', elapsedMs: 40 },
          { id: 'verify', label: '校验时段冲突', elapsedMs: 20 }
        ],
        reasoning: [],
        summary: '方案生成完成',
        elapsedMs: 900
      }
    }
  })
}

test('一句话生成预约方案卡，候选与提示完整', async ({ page }) => {
  await mockPlanChat(page, planPayload({ candidates: TWO_ROOMS }))
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('帮我约明天下午两点的机房，30 人左右')
  await page.locator('.composer-send').click()

  const card = page.locator('.ai-plan')
  await expect(card).toBeVisible({ timeout: 15000 })
  await expect(card.locator('.plan-title')).toHaveText('预约方案')
  await expect(card.locator('.plan-meta')).toContainText('机房')
  await expect(card.locator('.plan-meta')).toContainText('30 人')

  const rows = card.locator('.plan-row')
  await expect(rows).toHaveCount(2)
  await expect(rows.first().locator('.room-name')).toHaveText('C101 云机房')
  await expect(rows.first().locator('.room-sub')).toContainText('创新楼')
  await expect(rows.first().locator('.room-reason')).toContainText('该时段空闲')
  await expect(card.locator('.plan-more')).toContainText('另有 1 间')
  // 方案只是建议，按钮语义必须点名教室，避免读屏时「去预约」不可区分
  await expect(card.locator('.plan-go')).toHaveCount(2)
  await expect(card.locator('.plan-go').first()).toHaveAttribute('aria-label', /去预约 C101 云机房/)
  await expect(card.locator('.plan-note')).toContainText('需在确认页手动提交')
})

test('点「去预约」直接落到确认步骤，预填并实时判冲突', async ({ page }) => {
  await mockPlanChat(page, planPayload({ candidates: TWO_ROOMS }))
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('帮我约明天下午两点的机房，30 人左右')
  await page.locator('.composer-send').click()
  await expect(page.locator('.ai-plan')).toBeVisible({ timeout: 15000 })

  await page.locator('.ai-plan .plan-row').first().locator('.plan-go').click()

  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText('AI 快速预约')
  // 第 3 步：教室已带入，日期/时段来自方案
  await expect(dialog.locator('.picked-room')).toContainText('C101 云机房')
  await expect(dialog.locator('.picked-room')).toContainText('创新楼')
  // 实时冲突校验：明天 14:00-16:00 的 C101 未被已通过预约占用
  await expect(dialog.locator('.conflict-tip')).toContainText('该时段可预约', { timeout: 15000 })
  // 提交按钮仍由用户点击（AI 不代提交）
  await expect(dialog.getByRole('button', { name: '提交预约' })).toBeEnabled()
})

test('没有候选时只出澄清文案，不给可点按钮', async ({ page }) => {
  await mockPlanChat(
    page,
    planPayload({
      candidates: [],
      moreAvailable: 0,
      clarify: '这个时段没有满足条件的教室，可以换个时段，或者放宽人数/类型；需要我按明天上午再找一轮吗？'
    })
  )
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('帮我约明天下午两点的机房，30 人左右')
  await page.locator('.composer-send').click()

  const card = page.locator('.ai-plan')
  await expect(card).toBeVisible({ timeout: 15000 })
  await expect(card.locator('.plan-empty')).toContainText('没有满足条件的教室')
  await expect(card.locator('.plan-go')).toHaveCount(0)
  await expect(card.locator('.plan-row')).toHaveCount(0)
})

/** 缺结束时间：后端给 1/2/3 小时三档（此处取两档），方案卡内切换即可，不再回一句澄清 */
function guessPlanPayload() {
  return {
    enabled: true,
    date: tomorrow(),
    startTime: '14:00',
    endTime: '15:00',
    capacity: 30,
    roomType: '机房',
    purpose: '算法实验课',
    moreAvailable: 0,
    clarify: '',
    excludedCount: 2,
    // 默认档由后端按「候选最多」回填，方案卡按 endTime 对齐选中
    endTimeGuessed: true,
    guessHours: 1,
    candidates: [TWO_ROOMS[0]],
    timeOptions: [
      { startTime: '14:00', endTime: '15:00', availableCount: 2, excludedCount: 1, candidates: [TWO_ROOMS[0]] },
      { startTime: '14:00', endTime: '16:00', availableCount: 1, excludedCount: 2, candidates: [TWO_ROOMS[1]] }
    ]
  }
}

test('缺结束时间时给出可选时段，切换后候选与预填时段同步', async ({ page }) => {
  await mockPlanChat(page, guessPlanPayload())
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('帮我约明天下午两点的机房，30 人左右')
  await page.locator('.composer-send').click()

  const card = page.locator('.ai-plan')
  await expect(card).toBeVisible({ timeout: 15000 })

  // 两档可选 + 估时说明，默认选候选最多的 14:00-15:00
  const chips = card.locator('.plan-time-chip')
  await expect(chips).toHaveCount(2)
  await expect(chips.first()).toHaveText('14:00–15:00 · 2 间')
  await expect(chips.first()).toHaveAttribute('aria-pressed', 'true')
  await expect(card.locator('.plan-guess')).toContainText('已按 1 小时估算')
  await expect(card.locator('.room-name')).toHaveText('C101 云机房')

  // 切到 16:00 档：候选列表随之替换，选中态只有一个
  await chips.nth(1).click()
  await expect(chips.nth(1)).toHaveAttribute('aria-pressed', 'true')
  await expect(chips.first()).toHaveAttribute('aria-pressed', 'false')
  await expect(card.locator('.room-name')).toHaveText('C201 云机房')

  // 去预约：弹窗结束时间必须等于所选档，仍由用户手动提交
  await card.locator('.plan-go').click()
  const dialog = page.locator('.el-dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.locator('.picked-room')).toContainText('C201 云机房')
  // Element Plus 的 el-time-select 把选中值渲染在 span 里，input.value 恒为空，断言展示文本
  await expect(dialog.locator('.el-form-item').filter({ hasText: '结束时间' })).toContainText('16:00')
  await expect(dialog.locator('.conflict-tip')).toContainText('该时段可预约', { timeout: 15000 })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('.chat-msg.user')).toHaveCount(1)
})
