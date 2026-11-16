#!/usr/bin/env node
/**
 * 演示素材录制（GitHub 作品集用）
 *
 * 一次运行产出两样东西：
 *   1. docs/images/demo-walkthrough.gif —— 约 30 秒、960 宽，直接嵌在 README（入库）
 *   2. .devtmp/demo/demo-walkthrough.mp4 —— 约 72 秒完整版，发布到 GitHub Release（不入库）
 *
 * 与 capture-screens.mjs 的区别：
 *   - 它只截静图；本脚本用真实鼠标轨迹 + 页面内叠加的字幕条录屏，走完整交互链路
 *   - 所有关键状态都有 DOM 断言（冲突提示出现、提交按钮禁用、批量通过 toast 等），
 *     任一步断言失败即整体失败，避免产出「看起来动了但其实是空壳」的视频
 *
 * 前置条件：
 *   1. 全栈已启动：docker compose up -d（api 8080 / web 8088 / mysql 3307 / redis 6380）
 *      或本地 dev（web 5173）后把 --base 指过去
 *   2. 已安装 ffmpeg 静态包到 .devtmp/tools/ffmpeg/ffmpeg.exe（或 --ffmpeg 指定）
 *   3. 已执行过 npm ci（需要 @playwright/test 的 chromium）
 *
 * 用法：
 *   node web/scripts/capture-demo-video.mjs --mode=gif
 *   node web/scripts/capture-demo-video.mjs --mode=full
 *   node web/scripts/capture-demo-video.mjs --mode=gif --encode=false   # 只录原始 webm，调试用
 *   node web/scripts/capture-demo-video.mjs --mode=gif --reset=none     # 跳过数据库复位
 *
 * 默认行为（可直接用于出片）：
 *   - 录制前/后各执行一次数据库复位（database/init_db.sql，游标日期相对今天）
 *   - 录制前用 API 造 2 条「明日待审核」预约，供管理端批量审核演示
 *   - 录完自动编码 GIF / MP4，并打印体积与时长
 */

import { chromium } from '@playwright/test'
import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

/** 运行时配置（可被命令行覆盖） */
const cfg = {
  mode: 'gif',
  base: 'http://127.0.0.1:8088',
  api: 'http://127.0.0.1:8080',
  ffmpeg: path.join(ROOT, '.devtmp', 'tools', 'ffmpeg', 'ffmpeg.exe'),
  out: path.join(ROOT, '.devtmp', 'demo'),
  reset: 'both',
  encode: true,
  gifFps: 12,
  gifWidth: 960,
  gifColors: 192
}

const GIF_FILE = path.join(ROOT, 'docs', 'images', 'demo-walkthrough.gif')
const AI_FILE = path.join(cfg.out, 'demo-ai-thinking.mp4')

/* ============================ 小工具 ============================ */

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

function log(...parts) {
  console.log('[demo]', ...parts)
}

function parseArgs(argv) {
  for (const raw of argv) {
    const matched = /^--([\w-]+)(?:=(.*))?$/.exec(raw)
    if (!matched) continue
    const key = matched[1]
    const value = matched[2] === undefined ? 'true' : matched[2]
    if (key === 'gif-fps') cfg.gifFps = Number(value)
    else if (key === 'gif-width') cfg.gifWidth = Number(value)
    else if (key === 'gif-colors') cfg.gifColors = Number(value)
    else if (key === 'encode') cfg.encode = value !== 'false'
    else if (key in cfg) cfg[key] = value
  }
  if (!['gif', 'full', 'ai'].includes(cfg.mode)) {
    throw new Error(`--mode 只支持 gif / full / ai，收到 ${cfg.mode}`)
  }
  if (!['none', 'before', 'after', 'both'].includes(cfg.reset)) {
    throw new Error(`--reset 只支持 none / before / after / both，收到 ${cfg.reset}`)
  }
  if (!Number.isFinite(cfg.gifFps) || cfg.gifFps < 5) throw new Error('--gif-fps 至少 5')
  if (!Number.isFinite(cfg.gifWidth) || cfg.gifWidth < 480) throw new Error('--gif-width 至少 480')
  if (!Number.isFinite(cfg.gifColors) || cfg.gifColors < 32 || cfg.gifColors > 256) {
    throw new Error('--gif-colors 取值 32-256')
  }
  cfg.base = cfg.base.replace(/\/$/, '')
  cfg.api = cfg.api.replace(/\/$/, '')
  cfg.out = path.resolve(cfg.out)
  cfg.ffmpeg = path.resolve(cfg.ffmpeg)
}

function url(p) {
  return `${cfg.base}${p}`
}

/** 明日日期（本地时区，YYYY-MM-DD；录制演示统一用「明天」，任何时刻录制都有数据） */
function tomorrow() {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  const pad = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 断言：元素出现 */
async function mustSee(locator, label, timeout = 8000) {
  try {
    await locator.first().waitFor({ state: 'visible', timeout })
  } catch {
    throw new Error(`断言失败：${label} 未出现`)
  }
}

/** 断言：元素数量 */
async function waitCount(locator, expected, label, timeout = 8000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if ((await locator.count()) === expected) return
    await sleep(150)
  }
  throw new Error(`断言失败：${label} 期望 ${expected} 个，实际 ${await locator.count()}`)
}

/** 断言：按钮可用 */
async function waitEnabled(locator, label, timeout = 8000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (!(await locator.first().isDisabled())) return
    await sleep(150)
  }
  throw new Error(`断言失败：${label} 一直处于禁用状态`)
}

/** 断言：按钮禁用 */
async function waitDisabled(locator, label, timeout = 8000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    if (await locator.first().isDisabled()) return
    await sleep(150)
  }
  throw new Error(`断言失败：${label} 未禁用`)
}

/**
 * 带鼠标轨迹的点击：真实 mousemove + down/up，页内合成光标会同步跟随
 * （Playwright 默认 click 是瞬移，录屏里看不到轨迹）
 */
async function tap(page, target, { delay = 240 } = {}) {
  const locator = typeof target === 'string' ? page.locator(target) : target
  const el = locator.first()
  await el.scrollIntoViewIfNeeded().catch(() => {})
  const box = await el.boundingBox()
  if (!box) throw new Error('点击目标不可见（boundingBox 为空）')
  const x = box.x + box.width / 2
  const y = box.y + box.height / 2
  await page.mouse.move(x, y, { steps: 12 })
  await sleep(150)
  await page.mouse.down()
  await sleep(110)
  await page.mouse.up()
  await sleep(delay)
}

/** 设置底部字幕（页面内叠加，跨导航由 sessionStorage 保留） */
async function setCaption(page, text) {
  await page.evaluate(t => {
    if (typeof window.__demoCaption === 'function') window.__demoCaption(t)
  }, text)
}

/** Element Plus 下拉：选择完全匹配的选项 */
async function pickOption(page, text, { delay = 120 } = {}) {
  const dropdown = page.locator('.el-select-dropdown:visible').last()
  await mustSee(dropdown, `下拉选项（期望 ${text}）`)
  const item = dropdown
    .locator('.el-select-dropdown__item')
    .filter({ hasText: new RegExp(`^\\s*${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*$`) })
  await mustSee(item, `选项 ${text}`)
  await tap(page, item, { delay })
  await sleep(380)
}

/** 表单：设置预约日期（直接写 input 再回车，比点日历格子稳） */
async function setDate(page, scope, date) {
  const input = scope.locator('input[placeholder="选择日期"]').first()
  await tap(page, input, { delay: 80 })
  await input.fill(date)
  await input.press('Enter')
  await sleep(300)
  const value = await input.inputValue()
  if (value !== date) {
    throw new Error(`断言失败：日期未写入，期望 ${date}，实际 ${value}`)
  }
}

/** 表单：选择时间（el-time-select 内部就是 el-select） */
async function pickTime(page, scope, label, time) {
  const select = scope
    .locator('.el-form-item')
    .filter({ hasText: label })
    .locator('.el-select')
    .first()
  await tap(page, select, { delay: 100 })
  await pickOption(page, time, { delay: 80 })
}

/* ============================ API / 数据准备 ============================ */

async function api(pathname, { token, method = 'GET', body } = {}) {
  const res = await fetch(cfg.api + pathname, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const json = await res.json().catch(() => null)
  if (!json || json.code !== 200) {
    throw new Error(`接口 ${pathname} 失败：${json ? json.message : res.status}`)
  }
  return json.data
}

async function apiLogin(username, password, role) {
  const data = await api('/api/user/login', { method: 'POST', body: { username, password, role } })
  return { token: data.token, user: data.user, aiEnabled: data.aiEnabled }
}

/**
 * 造 2 条「明日待审核」预约，供管理端批量通过演示。
 * 先查冲突再创建，避免撞上种子数据里的已通过预约。
 */
async function seedPendingReservations(date) {
  const student = await apiLogin('zhangsan', '123456', 0)
  const candidates = [
    { classroomId: 4, startTime: '15:00', endTime: '16:00', purpose: '课程设计答辩彩排' },
    { classroomId: 5, startTime: '16:00', endTime: '17:00', purpose: '算法实验课' },
    { classroomId: 6, startTime: '17:00', endTime: '18:00', purpose: '数据结构习题课' },
    { classroomId: 4, startTime: '17:00', endTime: '18:00', purpose: '课程设计答辩彩排' }
  ]
  const created = []
  for (const candidate of candidates) {
    if (created.length >= 2) break
    const conflict = await api(
      `/api/reservation/conflict?classroomId=${candidate.classroomId}&date=${date}` +
        `&startTime=${candidate.startTime}&endTime=${candidate.endTime}`,
      { token: student.token }
    )
    if (conflict.conflict) continue
    const id = await api('/api/reservation', {
      method: 'POST',
      token: student.token,
      body: {
        classroomId: candidate.classroomId,
        reserveDate: date,
        startTime: candidate.startTime,
        endTime: candidate.endTime,
        purpose: candidate.purpose
      }
    })
    created.push({ id, purpose: candidate.purpose })
  }
  if (created.length < 2) throw new Error('无法创建 2 条待审核演示数据（候选时段均冲突）')
  return {
    student,
    admin: await apiLogin('admin', 'admin123', 1),
    purposes: created.map(item => item.purpose)
  }
}

/** 数据库复位（database/init_db.sql 自包含，可重复执行；相对日期保证「今天」总有数据） */
function resetDatabase() {
  const sqlPath = path.join(ROOT, 'database', 'init_db.sql')
  if (!fs.existsSync(sqlPath)) throw new Error(`找不到 ${sqlPath}`)
  const sql = fs.readFileSync(sqlPath)
  const result = spawnSync(
    'docker',
    ['compose', 'exec', '-T', 'mysql', 'mysql', '-uroot', '-proot', '--default-character-set=utf8mb4'],
    { cwd: ROOT, input: sql, stdio: ['pipe', 'pipe', 'pipe'] }
  )
  if (result.status !== 0) {
    const stderr = result.stderr ? result.stderr.toString('utf8') : ''
    throw new Error(`数据库复位失败（docker compose exec 退出码 ${result.status}）\n${stderr}`)
  }
  log('数据库已复位（13 条预约 / 5 个账号 / 12 间教室）')
}

/* ============================ 页面交互片段 ============================ */

const MENU = text => `.el-menu-item:visible:has-text("${text}")`
const TAB = text => `.el-tabs__item:has-text("${text}")`
const RADIO = text => `.el-radio-button:has-text("${text}")`
const BUTTON = text => `button:has-text("${text}")`

async function openLogin(page) {
  await page.goto(url('/login'), { waitUntil: 'domcontentloaded' })
  await mustSee(page.locator('.login-card'), '登录页')
}

async function roleButton(page, role) {
  return page.locator('.role-group .el-radio-button').filter({ hasText: role === 1 ? '管理员' : '学' })
}

/** 登录段：空提交触发字段校验 → 输账号密码 → 真实登录 */
async function segLogin(page, { role, account, password, fast = false }) {
  await setCaption(page, '登录：字段级校验 + 真实 JWT 会话')
  const userInput = page.locator('input[placeholder="请输入登录账号"]').first()
  const pwdInput = page.locator('input[placeholder="请输入密码"]').first()
  const submit = page.locator('.login-btn')

  await tap(page, submit, { delay: 520 }) // 空提交 → 两条必填提示
  await mustSee(page.locator('.el-form-item__error').first(), '必填字段错误提示', 4000)
  await sleep(900)

  if (!fast) {
    const otherRole = role === 1 ? 0 : 1
    await tap(page, await roleButton(page, otherRole), { delay: 420 })
    await tap(page, await roleButton(page, role), { delay: 420 })
  }

  await tap(page, userInput, { delay: 60 })
  await userInput.pressSequentially(account, { delay: 95 })
  await sleep(120)
  await tap(page, pwdInput, { delay: 60 })
  await pwdInput.pressSequentially(password, { delay: 95 })
  await sleep(200)

  await tap(page, submit, { delay: 200 })
  const expectUrl = role === 1 ? /\/admin\// : /\/student\/home/
  await page.waitForURL(expectUrl, { timeout: 15000 })
  await sleep(800)
}

/** 学生首页：关键词 + 楼栋筛选 */
async function segClassroomList(page, { keyword = 'B101', building = false, buildingName = '实验楼' } = {}) {
  await setCaption(page, '教室检索：关键词 / 楼栋 / 类型组合筛选')
  await sleep(400)
  const kw = page.locator('input[placeholder="教室名称 / 编号"]').first()
  await tap(page, kw, { delay: 60 })
  await kw.pressSequentially(keyword, { delay: 90 })
  await sleep(150)
  await tap(page, page.locator(BUTTON('查询')).first(), { delay: 100 })
  await waitCount(page.locator('.room-card'), 1, `关键词 ${keyword} 过滤结果`)
  await sleep(900)

  if (building) {
    const buildingSelect = page
      .locator('.el-form-item')
      .filter({ hasText: '楼栋' })
      .locator('.el-select')
      .first()
    await tap(page, buildingSelect, { delay: 100 })
    await pickOption(page, buildingName)
    await sleep(500)
  }

  await tap(page, page.locator(BUTTON('重置')).first(), { delay: 100 })
  await waitCount(page.locator('.room-card'), 12, '重置后教室数量')
  await sleep(800)
}

async function openReserveDialog(page) {
  await tap(page, page.locator(BUTTON('预约申请')).first(), { delay: 150 })
  const dialog = page.locator('.el-dialog').filter({ hasText: '预约申请' })
  await mustSee(dialog, '预约申请弹窗')
  await sleep(300)
  return dialog
}

const submitButton = dialog => dialog.locator('button').filter({ hasText: '提交预约' }).first()

/**
 * 冲突演示：09:00-10:00 撞上种子数据里 B101 明日的已通过预约（09:00-11:00，预约 id=3）
 * → 实时提示 + 提交禁用；再把时段改到 14:00-15:00 → 提交成功
 */
async function segReserveConflict(page, { date, purpose, conflictStart = '09:00', conflictEnd = '10:00' }) {
  await setCaption(page, '冲突检测：新开始 < 旧结束 AND 新结束 > 旧开始')
  await sleep(600)
  const dialog = await openReserveDialog(page)

  await setDate(page, dialog, date)
  await pickTime(page, dialog, '开始时间', conflictStart)
  await pickTime(page, dialog, '结束时间', conflictEnd)

  const conflictAlert = dialog.locator('.el-alert').filter({ hasText: '冲突' })
  await mustSee(conflictAlert.first(), '冲突提示', 9000)
  await waitDisabled(submitButton(dialog), '冲突时的提交按钮')
  await sleep(1500)

  // 改到空闲时段：14:00-15:00
  await pickTime(page, dialog, '开始时间', '14:00')
  await pickTime(page, dialog, '结束时间', '15:00')
  await waitEnabled(submitButton(dialog), '改时段后的提交按钮')

  const textarea = dialog.locator('textarea').first()
  await tap(page, textarea, { delay: 60 })
  await textarea.pressSequentially(purpose, { delay: 75 })
  await sleep(300)

  await tap(page, submitButton(dialog), { delay: 200 })
  await mustSee(page.locator('.el-message').filter({ hasText: '预约提交成功' }).first(), '提交成功提示', 8000)
  await sleep(900)
}

/** 我的预约：状态筛选 */
async function segMyReservations(page, { bothTabs = true } = {}) {
  await setCaption(page, '状态流转：待审核 → 已通过 / 已驳回 / 已取消')
  await tap(page, page.locator(MENU('我的预约')).first(), { delay: 200 })
  await page.waitForURL(/\/student\/my-reservations/, { timeout: 8000 })
  await sleep(900)
  await tap(page, page.locator(TAB('待审核')).first(), { delay: 150 })
  await sleep(900)
  if (bothTabs) {
    await tap(page, page.locator(TAB('已通过')).first(), { delay: 150 })
    await sleep(900)
    await tap(page, page.locator(TAB('全部')).first(), { delay: 150 })
    await sleep(700)
  }
}

/** 日历总览：月 / 周视图 */
async function segCalendar(page) {
  await setCaption(page, '日历总览：月 / 周视图，按预约状态着色')
  await tap(page, page.locator(MENU('日历总览')).first(), { delay: 200 })
  await page.waitForURL(/\/student\/calendar/, { timeout: 8000 })
  await mustSee(page.locator('.fc-daygrid-day').first(), '日历网格', 10000)
  await sleep(600)
  await tap(page, page.locator(RADIO('周视图')).first(), { delay: 150 })
  await waitCount(page.locator('.fc-daygrid-day'), 7, '周视图 7 天')
  await sleep(900)
  await tap(page, page.locator(RADIO('月视图')).first(), { delay: 150 })
  await sleep(900)
}

/** 管理端：GIF 用 localStorage 硬切（省时间），画面仍是完整数据看板 */
async function switchToAdminInstant(page, admin) {
  await page.evaluate(
    ({ token, user, aiEnabled }) => {
      localStorage.setItem('reservation_token', token)
      localStorage.setItem('reservation_user', JSON.stringify(user))
      localStorage.setItem('reservation_ai_enabled', String(Boolean(aiEnabled)))
    },
    admin
  )
  await page.goto(url('/admin/dashboard'), { waitUntil: 'domcontentloaded' })
  await mustSee(page.locator('.page-title').filter({ hasText: '数据看板' }), '数据看板标题')
}

/** 数据看板：等 ECharts 画出来 */
async function segDashboard(page) {
  await setCaption(page, '数据看板：使用率 / 时段分布 / 预约趋势')
  if (!/\/admin\/dashboard/.test(page.url())) {
    await tap(page, page.locator(MENU('数据看板')).first(), { delay: 200 })
    await page.waitForURL(/\/admin\/dashboard/, { timeout: 8000 })
  }
  await mustSee(page.locator('.chart-box canvas').first(), '图表画布', 10000)
  await waitCount(page.locator('.chart-box canvas'), 3, '三张图表', 12000)
  await sleep(1800)
}

/** 预约审核：勾选两条 → 批量通过 → 二次确认 → 成功 toast */
async function segAuditBatch(page, purposes) {
  await setCaption(page, '审核：批量通过，批内重叠校验 + 与已通过记录复查')
  await tap(page, page.locator(MENU('预约审核')).first(), { delay: 200 })
  await page.waitForURL(/\/admin\/audits/, { timeout: 8000 })
  await sleep(900)

  for (const purpose of purposes) {
    const row = page.locator('.el-table__row').filter({ hasText: purpose }).first()
    await mustSee(row, `审核列表行（${purpose}）`, 10000)
    await tap(page, row.locator('.el-checkbox').first(), { delay: 180 })
    await sleep(300)
  }

  const batch = page.locator(BUTTON('批量通过')).first()
  await waitEnabled(batch, '批量通过按钮')
  await sleep(500)
  await tap(page, batch, { delay: 150 })

  const box = page.locator('.el-message-box')
  await mustSee(box, '批量通过确认框')
  await sleep(300)
  await tap(page, box.locator('button').filter({ hasText: '确定通过' }).first(), { delay: 150 })
  await mustSee(page.locator('.el-message').filter({ hasText: '批量通过成功' }).first(), '批量通过成功提示', 8000)
  await sleep(1200)
}

/** 真实退出登录（full 模式用，体现会话撤销） */
async function segLogout(page) {
  await setCaption(page, '会话安全：退出即失效（JWT + 服务端 token_version）')
  await tap(page, page.locator('.user-entry').first(), { delay: 150 })
  const item = page.locator('.el-dropdown-menu__item').filter({ hasText: '退出登录' }).first()
  await mustSee(item, '退出登录菜单项')
  await tap(page, item, { delay: 150 })
  const box = page.locator('.el-message-box')
  await mustSee(box, '退出确认框')
  await sleep(250)
  await tap(page, box.locator('button').filter({ hasText: '退出' }).first(), { delay: 150 })
  await page.waitForURL(/\/login/, { timeout: 8000 })
  await sleep(500)
}

/** 结尾卡（full 模式）：作品定位 + 量化指标 */
async function segEndCard(page) {
  await page.evaluate(payload => {
    if (typeof window.__demoEndCard === 'function') window.__demoEndCard(payload)
  }, {
    product: '高校实验室预约管理系统',
    subtitle: 'Spring Boot 3 + Vue 3 · 8 周课程设计作品',
    stats: [
      '309 项后端测试（JUnit）· 80.6% 行覆盖率',
      '41 项接口冒烟 · 120 项 E2E（Chromium + Edge）',
      '3 条 CI 流水线 · 双重冲突校验 · AI 可降级'
    ],
    repo: 'github.com/Alexander-cloud123/lab-reservation-system'
  })
  await sleep(6500)
}

/**
 * 预热推荐缓存（服务端 60 秒 TTL）：首屏探测命中缓存可以让悬浮球与推荐卡立即出现。
 * 真实模型首次生成推荐可能需要数秒到数十秒，直接录会让片头长时间空等。
 */
async function warmRecommendCache() {
  const student = await apiLogin('zhangsan', '123456', 0)
  const started = Date.now()
  const res = await fetch(`${cfg.api}/api/ai/recommend?stream=true`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${student.token}`, 'Content-Type': 'application/json' },
    body: '{}'
  })
  await res.text()
  return Date.now() - started
}

/**
 * AI 助手演示（ai 模式）：推荐卡 → 悬浮球对话 → 思考面板展开/折叠 → 推理原文或「未返回推理」说明。
 * 面板事件全部来自真实后端（含耗时），因此录制时长的波动属上游真实表现，不压缩、不伪造。
 */
async function segAiAssistant(page) {
  await setCaption(page, 'AI 助手：结合常用教室与空闲时段给出推荐')
  await mustSee(page.locator('.ai-recommend-card'), 'AI 推荐卡', 25000)
  await sleep(2400)

  await tap(page, page.locator('.assistant-ball'), { delay: 220 })
  await page.waitForURL(/\/student\/ai$/, { timeout: 15000 })
  const drawer = page.locator('.ai-chat-page')
  await mustSee(drawer, 'AI 对话页')

  await setCaption(page, '自然语言提问：明天有哪些预约、取消要注意什么')
  const input = page.locator('#ai-chat-input')
  await input.fill('我明天有哪些预约？取消预约要注意什么？')
  await sleep(800)

  await tap(page, page.locator('.composer-send'), { delay: 260 })
  await setCaption(page, '思考过程先展开：步骤来自真实执行轨迹，不是伪造的思维链')

  const panel = drawer.locator('.thinking')
  await mustSee(panel.first(), '思考面板')
  await mustSee(drawer.locator('.pulse-dots'), '思考等待动画')
  await sleep(1400)

  const head = panel.first().locator('.thinking-head')
  if ((await head.getAttribute('aria-expanded')) === 'false') {
    await tap(page, head, { delay: 200 })
  }
  await mustSee(drawer.locator('.step-label').first(), '思考步骤')
  await setCaption(page, '场景判断 → 检索预约记录 → 调用模型 → 校验回答，每步带真实耗时')
  await sleep(1800)

  let answered = true
  try {
    await page.waitForFunction(
      () => {
        const body = document.querySelector('.ai-chat-page .markdown-body')
        return !!body && body.textContent.trim().length > 0
      },
      { timeout: 45000 }
    )
  } catch {
    answered = false
  }

  if (!answered) {
    const stop = page.locator('.composer-stop')
    if (await stop.count()) await tap(page, stop, { delay: 180 })
    await setCaption(page, '上游排队较慢：前端手动中断，服务端 60 秒预算兜底降级')
    await sleep(2000)
  } else {
    await setCaption(page, '回答到达后自动折叠为「已思考 X.X 秒」，可随时展开回看')
    await sleep(1600)
    if ((await head.getAttribute('aria-expanded')) !== 'true') {
      await tap(page, head, { delay: 220 })
    }
    await sleep(1600)
    if (await drawer.locator('.reasoning-text').count()) {
      await setCaption(page, '上游返回推理原文时原样展示，并固定标注「模型推理 · 仅供参考」')
    } else {
      await setCaption(page, '模型未返回推理时明确说明「以上为本次处理的实际步骤」，不编造内容')
    }
    await drawer.locator('.ai-chat-scroll').evaluate(el => { el.scrollTop = el.scrollHeight }).catch(() => {})
    await sleep(2200)
  }

  await setCaption(page, 'AI 只读不写：业务操作仍由你手动确认；生成内容固定标注「仅供参考」')
  await sleep(2200)
  await page.locator('.topbar-back').click().catch(() => {})
  await sleep(600)
}

/* ============================ 页内叠加层 ============================ */

/**
 * 注入合成光标 + 底部字幕条 + 结尾卡。
 * 字幕条由页面自身渲染（而不是 ffmpeg drawtext），避免中文字体缺失与转义问题；
 * 字幕文本写 sessionStorage，导航到新页面后自动恢复。
 */
const OVERLAY_SCRIPT = `(() => {
  const CAPTION_KEY = '__demo_caption_text__'
  const CURSOR_KEY = '__demo_cursor_pos__'

  function boot() {
    if (document.getElementById('__demo_cursor__')) return

    const cursor = document.createElement('div')
    cursor.id = '__demo_cursor__'
    Object.assign(cursor.style, {
      position: 'fixed',
      left: '0px',
      top: '0px',
      width: '16px',
      height: '16px',
      marginLeft: '-8px',
      marginTop: '-8px',
      borderRadius: '999px',
      background: 'rgba(255,255,255,0.92)',
      border: '2px solid #0f172a',
      boxShadow: '0 0 0 5px rgba(15,23,42,0.12)',
      pointerEvents: 'none',
      zIndex: '2147483600',
      transition: 'transform 160ms ease-out, width 120ms, height 120ms'
    })

    const caption = document.createElement('div')
    caption.id = '__demo_caption__'
    Object.assign(caption.style, {
      position: 'fixed',
      left: '50%',
      bottom: '22px',
      transform: 'translateX(-50%)',
      maxWidth: '900px',
      padding: '9px 16px',
      borderRadius: '8px',
      background: 'rgba(15,23,42,0.90)',
      color: '#f8fafc',
      fontFamily: '"Microsoft YaHei", "Segoe UI", "PingFang SC", sans-serif',
      fontSize: '15px',
      lineHeight: '1.5',
      letterSpacing: '0.2px',
      textAlign: 'center',
      whiteSpace: 'nowrap',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      boxShadow: '0 8px 24px rgba(0,0,0,0.22)',
      pointerEvents: 'none',
      opacity: '0',
      transition: 'opacity 200ms ease-out',
      zIndex: '2147483601'
    })

    document.documentElement.appendChild(cursor)
    document.documentElement.appendChild(caption)

    const savedCaption = sessionStorage.getItem(CAPTION_KEY)
    if (savedCaption) {
      caption.textContent = savedCaption
      caption.style.opacity = '1'
    }

    try {
      const savedPos = JSON.parse(sessionStorage.getItem(CURSOR_KEY) || 'null')
      if (savedPos && typeof savedPos.x === 'number') {
        cursor.style.transform = 'translate3d(' + savedPos.x + 'px,' + savedPos.y + 'px,0)'
      }
    } catch (e) {
      // 首次进入无历史位置，保持默认
    }

    let downTimer = null
    const setPressed = pressed => {
      cursor.style.width = pressed ? '13px' : '16px'
      cursor.style.height = pressed ? '13px' : '16px'
      cursor.style.marginLeft = pressed ? '-6.5px' : '-8px'
      cursor.style.marginTop = pressed ? '-6.5px' : '-8px'
    }

    document.addEventListener('mousemove', event => {
      cursor.style.transform = 'translate3d(' + event.clientX + 'px,' + event.clientY + 'px,0)'
    }, true)
    document.addEventListener('mousedown', () => {
      setPressed(true)
      clearTimeout(downTimer)
      downTimer = setTimeout(() => setPressed(false), 900)
    }, true)
    document.addEventListener('mouseup', () => setPressed(false), true)
    setInterval(() => {
      try {
        const current = cursor.style.transform.match(/translate3d\\(([-0-9.]+)px, ([-0-9.]+)px/)
        if (current) sessionStorage.setItem(CURSOR_KEY, JSON.stringify({ x: Number(current[1]), y: Number(current[2]) }))
      } catch (e) {
        // 位置持久化失败不影响录制
      }
    }, 400)

    window.__demoCaption = text => {
      caption.textContent = text || ''
      caption.style.opacity = text ? '1' : '0'
      try {
        sessionStorage.setItem(CAPTION_KEY, text || '')
      } catch (e) {
        // 忽略存储失败
      }
    }

    window.__demoEndCard = payload => {
      if (document.getElementById('__demo_end__')) return
      const wrap = document.createElement('div')
      wrap.id = '__demo_end__'
      Object.assign(wrap.style, {
        position: 'fixed',
        inset: '0',
        background: 'rgba(10,15,26,0.94)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: '2147483602',
        opacity: '0',
        transition: 'opacity 320ms ease-out'
      })

      const box = document.createElement('div')
      Object.assign(box.style, {
        width: '720px',
        color: '#f8fafc',
        fontFamily: '"Microsoft YaHei", "Segoe UI", "PingFang SC", sans-serif',
        textAlign: 'left'
      })

      const brand = document.createElement('div')
      brand.textContent = '课程设计作品 · 2026'
      Object.assign(brand.style, { fontSize: '13px', letterSpacing: '2px', color: '#94a3b8', marginBottom: '14px' })

      const title = document.createElement('div')
      title.textContent = payload.product
      Object.assign(title.style, { fontSize: '34px', fontWeight: '600', lineHeight: '1.3', marginBottom: '6px' })

      const subtitle = document.createElement('div')
      subtitle.textContent = payload.subtitle
      Object.assign(subtitle.style, { fontSize: '15px', color: '#cbd5e1', marginBottom: '26px' })

      box.appendChild(brand)
      box.appendChild(title)
      box.appendChild(subtitle)

      for (const line of payload.stats) {
        const row = document.createElement('div')
        row.textContent = line
        Object.assign(row.style, {
          fontSize: '15px',
          lineHeight: '1.9',
          color: '#e2e8f0',
          paddingLeft: '14px',
          borderLeft: '2px solid #475569'
        })
        box.appendChild(row)
      }

      const repo = document.createElement('div')
      repo.textContent = payload.repo
      Object.assign(repo.style, { fontSize: '13px', color: '#94a3b8', marginTop: '28px', letterSpacing: '0.5px' })

      box.appendChild(repo)
      wrap.appendChild(box)
      document.documentElement.appendChild(wrap)
      requestAnimationFrame(() => { wrap.style.opacity = '1' })
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true })
  } else {
    boot()
  }
})()`

/* ============================ 编码 ============================ */

function runFFmpeg(args, label) {
  const result = spawnSync(cfg.ffmpeg, args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 })
  if (result.status !== 0) {
    const tail = (result.stderr || '').trim().split('\n').slice(-8).join('\n')
    throw new Error(`${label} 失败（ffmpeg 退出码 ${result.status}）\n${tail}`)
  }
}

function fileSizeMB(file) {
  return fs.statSync(file).size / 1024 / 1024
}

function encodeGif(webm, out, { fps, width, colors }) {
  const filter =
    `fps=${fps},scale=${width}:-2:flags=lanczos,` +
    'split[a][b];[a]palettegen=max_colors=' + colors + ':stats_mode=diff[p];' +
    '[b][p]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle'
  runFFmpeg(['-y', '-i', webm, '-vf', filter, '-loop', '0', out], 'GIF 编码')
}

function encodeMp4(webm, out) {
  runFFmpeg(
    [
      '-y',
      '-i',
      webm,
      '-c:v',
      'libx264',
      '-preset',
      'medium',
      '-crf',
      '23',
      '-pix_fmt',
      'yuv420p',
      '-movflags',
      '+faststart',
      '-an',
      out
    ],
    'MP4 编码'
  )
}

/* ============================ 主流程 ============================ */

async function runGif(page, seed) {
  const started = Date.now()
  await openLogin(page)
  await sleep(300)
  await segLogin(page, { role: 0, account: 'zhangsan', password: '123456', fast: true })
  await segClassroomList(page, { keyword: 'B101' })
  await tap(page, page.locator('.room-card').filter({ hasText: 'B101' }).first().locator('button').filter({ hasText: '查看详情' }).first(), { delay: 200 })
  await page.waitForURL(/\/student\/classrooms\/5/, { timeout: 8000 })
  await sleep(900)
  await segReserveConflict(page, { date: seed.date, purpose: '课程设计答辩彩排' })
  await segMyReservations(page, { bothTabs: false })
  await switchToAdminInstant(page, seed.admin)
  await segDashboard(page)
  await segAuditBatch(page, seed.purposes)
  log(`GIF 演示录制完成，用时 ${((Date.now() - started) / 1000).toFixed(1)}s`)
}

async function runFull(page, seed) {
  const started = Date.now()
  await openLogin(page)
  await setCaption(page, '高校实验室预约管理系统 · Spring Boot 3 + Vue 3')
  await sleep(2500)

  await segLogin(page, { role: 0, account: 'zhangsan', password: '123456' })
  log('段 1/10：学生登录完成')
  await segClassroomList(page, { keyword: 'B101', building: true })
  log('段 2/10：教室检索完成')

  await tap(page, page.locator('.room-card').filter({ hasText: 'B101' }).first().locator('button').filter({ hasText: '查看详情' }).first(), { delay: 250 })
  await page.waitForURL(/\/student\/classrooms\/5/, { timeout: 8000 })
  await sleep(1500)

  await segReserveConflict(page, { date: seed.date, purpose: '课程设计答辩彩排' })
  log('段 3/10：冲突检测完成')
  await segMyReservations(page, { bothTabs: true })
  log('段 4/10：我的预约完成')
  await segCalendar(page)
  log('段 5/10：日历完成')
  await segLogout(page)
  log('段 6/10：退出登录完成')
  await segLogin(page, { role: 1, account: 'admin', password: 'admin123' })
  log('段 7/10：管理员登录完成')
  await segDashboard(page)
  log('段 8/10：数据看板完成')
  await segAuditBatch(page, seed.purposes)
  log('段 9/10：批量审核完成')
  await segEndCard(page)
  log('段 10/10：结尾卡完成')
  log(`完整版演示录制完成，用时 ${((Date.now() - started) / 1000).toFixed(1)}s`)
}

/** AI 片段（ai 模式）：约 40–70 秒，可单独上传为 Release 资产或作为视频尾部彩蛋 */
async function runAi(page, seed) {
  const started = Date.now()
  const warmMs = await warmRecommendCache()
  log(`推荐缓存预热完成（${(warmMs / 1000).toFixed(1)}s）`)
  await openLogin(page)
  await setCaption(page, 'AI 助手：真实思考过程、思考计时与等待动画')
  await sleep(1800)
  await segLogin(page, { role: 0, account: 'zhangsan', password: '123456', fast: true })
  log('段 1/3：学生登录完成')
  await segAiAssistant(page)
  log('段 2/3：AI 思考过程展示完成')
  await setCaption(page, 'AI 生成，仅供参考 · 所有写操作由用户手动确认')
  await sleep(2400)
  log(`AI 演示录制完成，用时 ${((Date.now() - started) / 1000).toFixed(1)}s`)
}

async function main() {
  parseArgs(process.argv.slice(2))
  log(`模式 ${cfg.mode} · 页面 ${cfg.base} · 接口 ${cfg.api}`)

  const ffmpegNeeded = cfg.encode
  if (ffmpegNeeded && !fs.existsSync(cfg.ffmpeg)) {
    throw new Error(
      `找不到 ffmpeg：${cfg.ffmpeg}\n` +
        '可执行 npm pack @ffmpeg-installer/win32-x64@4.1.0 后解包，或用 --ffmpeg 指定路径'
    )
  }

  const rawDir = path.join(cfg.out, 'raw')
  fs.mkdirSync(rawDir, { recursive: true })

  if (cfg.reset === 'before' || cfg.reset === 'both') resetDatabase()
  const seedDate = tomorrow()
  const seed = { ...(await seedPendingReservations(seedDate)), date: seedDate }
  log(`已准备演示数据：2 条明日待审核（${seed.purposes.join(' / ')}）`)

  const browser = await chromium.launch()
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    recordVideo: { dir: rawDir, size: { width: 1440, height: 900 } },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  context.setDefaultTimeout(10000)
  await context.addInitScript(OVERLAY_SCRIPT)

  const page = await context.newPage()
  const video = page.video()
  let failure = null

  try {
    if (cfg.mode === 'gif') await runGif(page, seed)
    else if (cfg.mode === 'full') await runFull(page, seed)
    else await runAi(page, seed)
  } catch (error) {
    failure = error
  } finally {
    await context.close().catch(() => {})
    await browser.close().catch(() => {})
  }

  if (failure) throw failure

  const webm = await video.path()
  if (!fs.existsSync(webm)) throw new Error(`录制文件缺失：${webm}`)
  log(`原始录屏：${webm}（${fileSizeMB(webm).toFixed(1)}MB）`)

  if (cfg.reset === 'after' || cfg.reset === 'both') {
    resetDatabase()
  }

  if (!cfg.encode) {
    log('已跳过编码（--encode=false）')
    return
  }

  if (cfg.mode === 'gif') {
    fs.mkdirSync(path.dirname(GIF_FILE), { recursive: true })
    let fps = cfg.gifFps
    let colors = cfg.gifColors
    let width = cfg.gifWidth
    encodeGif(webm, GIF_FILE, { fps, width, colors })
    let size = fileSizeMB(GIF_FILE)
    log(`GIF：${GIF_FILE}（${size.toFixed(1)}MB, ${fps}fps, ${width}px）`)
    if (size > 8) {
      fps = 10
      colors = 128
      if (width > 880) width = 880
      log(`GIF 超过 8MB，按 ${fps}fps / ${colors} 色 / ${width}px 重编码`)
      encodeGif(webm, GIF_FILE, { fps, width, colors })
      size = fileSizeMB(GIF_FILE)
      log(`GIF（重编码）：${size.toFixed(1)}MB`)
    }
    if (size > 8) throw new Error('GIF 仍超过 8MB，请降低 --gif-width 或 --gif-fps')
  } else if (cfg.mode === 'ai') {
    encodeMp4(webm, AI_FILE)
    log(`AI 片段 MP4：${AI_FILE}（${fileSizeMB(AI_FILE).toFixed(1)}MB）`)
  } else {
    const mp4 = path.join(cfg.out, 'demo-walkthrough.mp4')
    encodeMp4(webm, mp4)
    log(`MP4：${mp4}（${fileSizeMB(mp4).toFixed(1)}MB）`)
  }
}

main().catch(error => {
  console.error('[demo] 失败：', error.message)
  process.exit(1)
})
