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
  if (who) {
    const data = await apiLogin(who)
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
  return { ctx, page }
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, `${name}.png`) })
  console.log(`captured ${name}.png`)
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
      const { ctx, page } = await openAs(browser, 'student', '/student/home')
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
