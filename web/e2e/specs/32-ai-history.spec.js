/**
 * AI 对话历史持久化 E2E（浏览器级）
 *
 * 覆盖（对应「历史对话」改造的 4 个验收点）：
 *   1) 发送一轮 → 刷新页面 → 侧栏出现该会话，点击/自动恢复后正文与「已思考」面板都在
 *   2) 两个会话刷新后都在，顺序为最近活动在前
 *   3) 删除会话（二次确认）→ 刷新后不再出现，本地 key 随之清理
 *   4) 退出登录 → 本地会话 key 全部清除（同机换账号不串数据）
 *
 * 约束：真实模型零调用（mockAi 在浏览器层拦截 /api/ai/*）；不依赖共享库里的对话数据，
 * 每个用例开始前清掉本浏览器的历史 key，避免用例互相污染。
 */
import { test, expect } from '@playwright/test'
import { loginAs } from '../helpers/auth'
import { AI_PATHS, mockAi } from '../helpers/ai'

const HISTORY_PREFIX = 'reservation_ai_conversations:'

/**
 * 清掉当前浏览器里的对话历史（每个用例独立）。
 *
 * addInitScript 会在每次页面加载（含 reload）前重跑，因此用 sessionStorage 打一次性标记，
 * 只清第一次；否则刷新后历史会被自己清掉，恢复类断言永远失败。
 */
async function clearHistory(page) {
  await page.addInitScript((prefix) => {
    try {
      if (window.sessionStorage.getItem('__ai_history_cleared') === '1') {
        return
      }
      window.sessionStorage.setItem('__ai_history_cleared', '1')
      Object.keys(window.localStorage)
        .filter((key) => key.startsWith(prefix))
        .forEach((key) => window.localStorage.removeItem(key))
    } catch {
      // 隐私模式下 localStorage 不可用，用例自行失败即可
    }
  }, HISTORY_PREFIX)
}

/** 取某用户的存储 key 总数 */
async function historyKeyCount(page) {
  return page.evaluate(
    (prefix) => Object.keys(window.localStorage).filter((key) => key.startsWith(prefix)).length,
    HISTORY_PREFIX
  )
}

/** 挂载 AI 拦截：回答按提问动态生成，便于断言「恢复的是哪一条」 */
async function mockChat(page) {
  return mockAi(page, {
    [AI_PATHS.chat]: (body) => ({
      enabled: true,
      message: null,
      answer: `关于「${body.question}」：明日 14:00-16:00 的 C301 机房仍空闲。`,
      _think: {
        steps: [
          { id: 'scene', label: '确认问题是否属于预约范围', elapsedMs: 12 },
          { id: 'retrieve', label: '检索你的预约记录', elapsedMs: 28 }
        ],
        reasoning: [],
        summary: null,
        elapsedMs: 460
      }
    })
  })
}

test('发送后刷新：历史会话与思考面板都能恢复', async ({ page }) => {
  await clearHistory(page)
  await mockChat(page)
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('E2E：历史会话一')
  await page.locator('.composer-send').click()
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('E2E：历史会话一', {
    timeout: 15000
  })
  // 落盘是防抖 + 结束即时写，等 key 出现再刷新，避免与 400ms 防抖赛跑
  await expect.poll(() => historyKeyCount(page)).toBe(1)

  await page.reload()

  const rows = page.locator('.conv-row')
  await expect(rows).toHaveCount(1)
  await expect(rows.first().locator('.conv-title')).toContainText('E2E：历史会话一')
  // 恢复后自动落在该会话（activeId 已持久化）
  await expect(page.locator('.chat-msg.user')).toHaveCount(1)
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('C301 机房仍空闲')
  // 思考轨迹按数据恢复：恢复即已收敛为「已思考」，不会停在「正在思考」
  await expect(page.locator('.thinking')).toHaveCount(1)
  await expect(page.locator('.thinking .head-text')).toContainText('已思考')
})

test('两个会话刷新后都在，最近活动的排最前', async ({ page }) => {
  await clearHistory(page)
  await mockChat(page)
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('E2E：历史会话二')
  await page.locator('.composer-send').click()
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('历史会话二', {
    timeout: 15000
  })
  await expect.poll(() => historyKeyCount(page)).toBe(1)

  await page.locator('.ai-chat-new').click()
  await page.locator('#ai-chat-input').fill('E2E：历史会话三')
  await page.locator('.composer-send').click()
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('历史会话三', {
    timeout: 15000
  })
  await expect.poll(() => page.locator('.conv-row').count()).toBe(2)

  await page.reload()

  const rows = page.locator('.conv-row')
  await expect(rows).toHaveCount(2)
  // 最近活动的「历史会话三」排在第一
  await expect(rows.first().locator('.conv-title')).toContainText('E2E：历史会话三')
  await expect(page.locator('.conv-group-title').first()).toHaveText('今天')
})

test('删除会话（二次确认）后刷新不再出现', async ({ page }) => {
  await clearHistory(page)
  await mockChat(page)
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('E2E：待删除会话')
  await page.locator('.composer-send').click()
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('待删除会话', {
    timeout: 15000
  })
  await expect.poll(() => page.locator('.conv-row').count()).toBe(1)

  const row = page.locator('.conv-row').first()
  await row.hover()
  await row.locator('.conv-delete').click()
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await box.getByRole('button', { name: '删除', exact: true }).click()

  await expect(page.locator('.conv-row')).toHaveCount(0)
  await expect(page.locator('.conv-empty')).toContainText('还没有历史会话')

  await page.reload()
  await expect(page.locator('.conv-row')).toHaveCount(0)
  await expect.poll(() => historyKeyCount(page)).toBe(0)
})

test('退出登录后本地会话历史被清除', async ({ page }) => {
  await clearHistory(page)
  await mockChat(page)
  await loginAs(page, { who: 'student', path: '/student/ai', aiEnabled: true })

  await page.locator('#ai-chat-input').fill('E2E：登出前的会话')
  await page.locator('.composer-send').click()
  await expect(page.locator('.chat-msg.ai .markdown-body')).toContainText('登出前的会话', {
    timeout: 15000
  })
  await expect.poll(() => historyKeyCount(page)).toBe(1)

  // 回教室列表 → 顶栏用户菜单 → 退出登录（二次确认）
  await page.locator('.ai-chat-back').click()
  await expect(page).toHaveURL(/\/student\/home$/)
  await page.locator('.user-entry').click()
  await page.locator('.el-dropdown-menu').getByText('退出登录').click()
  const box = page.locator('.el-message-box')
  await expect(box).toBeVisible()
  await box.getByRole('button', { name: '退出', exact: true }).click()

  await expect(page).toHaveURL(/\/login$/)
  await expect.poll(() => historyKeyCount(page)).toBe(0)
})
