/**
 * 作品集截图采集（一次性脚本，产出 docs/images/*.png 供 README 展示）
 *
 * 前置：后端 8080 在线（docker compose 的 api 服务即可）、前端 5173 dev server 已启动。
 * 用法：node scripts/capture-screens.mjs
 * 说明：走真实登录接口拿 token 后注入 localStorage，与 E2E 的 loginAs 同一套登录态键名，
 *      不写数据库、不改任何业务数据，只读页面。
 */
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.CAPTURE_BASE || 'http://127.0.0.1:5173'
const API = process.env.CAPTURE_API || 'http://127.0.0.1:8080'
const OUT = process.env.CAPTURE_OUT || path.resolve('docs/images')
const VIEWPORT = { width: 1440, height: 900 }

const ACCOUNTS = {
  admin: { username: 'admin', password: 'admin123', role: 1 },
  student: { username: 'zhangsan', password: '123456', role: 0 }
}

async function apiLogin(who) {
  const acc = ACCOUNTS[who]
  const resp = await fetch(`${API}/api/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(acc)
  })
  const body = await resp.json()
  if (body.code !== 200 || !body.data?.token) {
    throw new Error(`登录失败（${who}）：${JSON.stringify(body)}`)
  }
  return body.data
}

async function openAs(browser, who, urlPath) {
  const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 1 })
  const page = await ctx.newPage()
  let data = null
  if (who) {
    data = await apiLogin(who)
    await page.addInitScript(
      ([token, user]) => {
        localStorage.setItem('reservation_token', token)
        localStorage.setItem('reservation_user', JSON.stringify(user))
      },
      [data.token, data.user]
    )
  }
  await page.goto(BASE + urlPath, { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(600)
  return { ctx, page, data }
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
  console.log(`captured ${name}.png`)
}

const AI_HISTORY_PREFIX = 'reservation_ai_conversations:'

/**
 * 演示会话数据：给 AI 页截图准备「今天 / 昨天」两条历史，左栏才会出现分组与内容。
 *
 * 结构必须与 web/src/utils/aiChatStorage.js 的存储格式一致（version / activeId / conversations），
 * 否则页面会按「坏数据」清空；这里不存 html，恢复时客户端会用 Markdown 重新渲染。
 */
function aiHistoryPayload() {
  const now = Date.now()
  const today = now
  const yesterday = now - 24 * 60 * 60 * 1000
  const trace = {
    state: 'done',
    steps: [
      { id: 'scene', label: '确认问题是否属于预约范围', status: 'done', elapsedMs: 11 },
      { id: 'retrieve', label: '检索你的预约记录', status: 'done', elapsedMs: 24 }
    ],
    reasoning: '',
    summary: '',
    startedAt: 0,
    elapsedMs: 35
  }
  const conversation = (id, title, at, answer, at2) => ({
    id,
    title,
    createdAt: at,
    messages: [
      { id: `${id}-q`, role: 'user', content: title, at },
      { id: `${id}-a`, role: 'ai', content: answer, html: '', typing: false, thinking: trace, at: at2 }
    ]
  })
  return {
    version: 1,
    activeId: 'demo-today',
    conversations: [
      conversation(
        'demo-today',
        '明天下午哪些教室还空着？',
        today,
        '按当前排期，明天下午 **14:00–16:00** 仍可预约的教室有 3 间：\n\n| 教室 | 楼栋 | 容量 |\n| --- | --- | --- |\n| C301 机房 | C | 80 人 |\n| B201 机房 | B | 60 人 |\n| A301 多媒体教室 | A | 40 人 |\n\n同一教室同时段只允许一条「已通过」预约，提交后仍需管理员审核。',
        today + 1000
      ),
      conversation(
        'demo-yesterday',
        '预约冲突是怎么判定的？',
        yesterday,
        '只看时间区间是否重叠：`新开始 < 旧结束 且 新结束 > 旧开始`。\n\n例如 A101 已有 09:00–11:00 的已通过预约，再提交 10:00–11:30 就冲突；改到 13:00–14:30 可以提交。\n\n前端实时提示、后端提交时再校验一次，两端都不允许绕过。',
        yesterday + 1000
      )
    ]
  }
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  const browser = await chromium.launch()
  try {
    {
      const { ctx, page } = await openAs(browser, null, '/login')
      await shot(page, '01-login')
      await ctx.close()
    }
    {
      const { ctx, page, data } = await openAs(browser, 'student', '/student/home')
      await shot(page, '02-student-home')
      await page.goto(BASE + '/student/classrooms/1', { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(600)
      await shot(page, '03-classroom-detail')
      await page.goto(BASE + '/student/my-reservations', { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(600)
      await shot(page, '04-my-reservations')
      await page.goto(BASE + '/student/calendar', { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(800)
      await shot(page, '05-calendar')
      await page.addInitScript(
        ([key, payload]) => localStorage.setItem(key, payload),
        [AI_HISTORY_PREFIX + data.user.id, JSON.stringify(aiHistoryPayload())]
      )
      await page.goto(BASE + '/student/ai', { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(1200)
      await shot(page, '08-ai-chat')
      await ctx.close()
    }
    {
      const { ctx, page } = await openAs(browser, 'admin', '/admin/dashboard')
      await shot(page, '06-admin-dashboard')
      await page.goto(BASE + '/admin/audits', { waitUntil: 'domcontentloaded' })
      await page.waitForTimeout(600)
      await shot(page, '07-admin-audit')
      await ctx.close()
    }
  } finally {
    await browser.close()
  }
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
