/**
 * AI 页面上下文 E2E（浏览器级，R15-3）
 *
 * 覆盖「用户在教室详情页点悬浮球」的上下文链路：
 *   1) 详情页 → 对话页，URL 带 roomId，顶部出现「正在查看」标签，请求体带 context.classroomId
 *   2) 点标签上的 × 移除后，后续请求不再带 context（AI 不再假设在看某间教室）
 *
 * 约束：真实模型零调用（mockAi 拦截 /api/ai/*）；不写业务数据；
 * 教室详情走真实接口（loginAs 写入 token 后由页面自己拉取）。
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { AI_PATHS, mockAi } from '../helpers/ai'

/** 记录浏览器发出的 /api/ai/chat 请求体，用于断言 context 是否随请求下发 */
function captureChatBodies(page) {
  const bodies = []
  page.on('request', (request) => {
    if (request.method() !== 'POST' || !request.url().includes('/api/ai/chat')) return
    try {
      bodies.push(JSON.parse(request.postData() || '{}'))
    } catch {
      bodies.push({})
    }
  })
  return bodies
}

async function mockChat(page) {
  return mockAi(page, {
    [AI_PATHS.chat]: { enabled: true, message: null, answer: '好的，我记下了。' }
  })
}

test('教室详情页打开 AI：带上正在查看的教室，请求带 context', async ({ page }) => {
  const bodies = captureChatBodies(page)
  await mockChat(page)
  await loginAs(page, { who: 'student', path: '/student/classrooms/1', aiEnabled: true })

  await page.locator('.assistant-ball').click()

  await expect(page).toHaveURL(/\/student\/ai\?roomId=1$/)
  const chip = page.locator('.ai-context-chip')
  await expect(chip).toBeVisible()
  await expect(chip).toContainText('正在查看')

  await page.locator('#ai-chat-input').fill('这间教室在哪个楼')
  await page.locator('.composer-send').click()
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('好的')

  await expect.poll(() => bodies.length).toBeGreaterThan(0)
  expect(bodies.at(-1).context && bodies.at(-1).context.classroomId).toBe(1)
})

test('移除上下文后：标签消失，后续请求不再带 context', async ({ page }) => {
  const bodies = captureChatBodies(page)
  await mockChat(page)
  await loginAs(page, { who: 'student', path: '/student/classrooms/1', aiEnabled: true })

  await page.locator('.assistant-ball').click()
  await expect(page.locator('.ai-context-chip')).toBeVisible()

  await page.locator('.context-remove').click()
  await expect(page.locator('.ai-context-chip')).toHaveCount(0)

  await page.locator('#ai-chat-input').fill('怎么预约教室')
  await page.locator('.composer-send').click()
  await expect.poll(() => bodies.length).toBeGreaterThan(0)
  expect(bodies.at(-1).context).toBeUndefined()
})
