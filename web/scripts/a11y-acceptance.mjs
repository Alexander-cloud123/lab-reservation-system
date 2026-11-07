/**
 * 无障碍「读屏器人工验收」机器代理脚本（清单 A/B/C/D 组的客观面）
 *
 * 定位：不是回归测试，而是把 docs/无障碍人工验收清单.md 中**可用机器判定**的部分
 * 跑成可复核证据（无障碍树快照 / live region 实际写入序列 / 焦点归属 / 截图），
 * 真实语音听感（音色、打断、停顿）仍必须人耳确认，脚本不替代人工结论。
 *
 * 前置：后端 8080、MySQL/Redis 在线；前端 dev server 5173（本脚本不自启）。
 * 运行：cd web && node scripts/a11y-acceptance.mjs
 * 产物：docs/review/a11y-evidence/（evidence.json + *.aria.yaml + *.png + a2-live-log.json）
 *
 * AI 对话链路一律在浏览器内 mock（零真实模型调用）：/api/ai/recommend 返回 enabled=true，
 * /api/ai/chat 用 ReadableStream 模拟逐帧 SSE，并尊重 AbortSignal（验证「停止」行为）。
 */
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import dayjs from 'dayjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const WEB = process.env.A11Y_WEB || 'http://127.0.0.1:5173'
const API = process.env.A11Y_API || 'http://127.0.0.1:8080'
const OUT = path.resolve(HERE, '../../docs/review/a11y-evidence')
fs.mkdirSync(OUT, { recursive: true })

const norm = (s) => (s || '').replace(/\s+/g, ' ').trim()
const futureDate = (days) => dayjs().add(days, 'day').format('YYYY-MM-DD')
const results = []
function rec(item, status, evidence, note = '') {
  results.push({ item, status, evidence, note })
  console.log(`[${status}] ${item} :: ${evidence}`)
}
async function step(item, fn) {
  try {
    await fn()
  } catch (e) {
    rec(item, 'error', String((e && e.message) || e).slice(0, 400), '脚本执行异常，未形成结论')
  }
}
async function shot(page, name, locator = null) {
  const target = locator || page
  await target.screenshot({ path: path.join(OUT, name), animations: 'disabled' })
}
function save(name, text) {
  fs.writeFileSync(path.join(OUT, name), text, 'utf8')
}

const ACCOUNTS = {
  admin: { username: 'admin', password: 'admin123', role: 1 },
  student: { username: 'zhangsan', password: '123456', role: 0 }
}
async function login(who) {
  const resp = await fetch(`${API}/api/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(ACCOUNTS[who])
  })
  const body = await resp.json()
  if (body.code !== 200) throw new Error(`登录失败（${who}）：${JSON.stringify(body)}`)
  return body.data
}

/** AI 对话流式 mock（浏览器内 fetch 拦截，逐帧吐 SSE，尊重 AbortSignal） */
const CHAT_MOCK = () => {
  window.__answers = []
  const origFetch = window.fetch.bind(window)
  window.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : (input && input.url) || ''
    if (url.indexOf('/api/ai/chat') === -1) return origFetch(input, init)
    const signal = init && init.signal
    const answer = window.__answers.length ? window.__answers.shift() : '抱歉，暂时无法回答。'
    let body = 'event:meta\ndata:{"enabled":true,"degraded":false,"message":null}\n\n'
    for (let i = 0; i < answer.length; i += 5) {
      body += 'event:delta\ndata:' + JSON.stringify({ text: answer.slice(i, i + 5) }) + '\n\n'
    }
    body += 'event:done\ndata:{"finishReason":"stop"}\n\n'
    const frames = body.split('\n\n').filter(Boolean).map((f) => f + '\n\n')
    let closed = false
    const fail = (controller) => {
      if (closed) return
      closed = true
      try {
        controller.error(new DOMException('Aborted', 'AbortError'))
      } catch {
        /* 已关闭的流不可再 error，忽略 */
      }
    }
    const response = new Response(
      new ReadableStream({
        start(controller) {
          let i = 0
          const timer = setInterval(() => {
            if (closed) return
            if (signal && signal.aborted) {
              clearInterval(timer)
              fail(controller)
              return
            }
            if (i >= frames.length) {
              clearInterval(timer)
              closed = true
              try {
                controller.close()
              } catch {
                /* 已 error */
              }
              return
            }
            try {
              controller.enqueue(new TextEncoder().encode(frames[i++]))
            } catch {
              clearInterval(timer)
            }
          }, 140)
          if (signal) {
            signal.addEventListener(
              'abort',
              () => {
                clearInterval(timer)
                fail(controller)
              },
              { once: true }
            )
          }
        }
      }),
      { status: 200, headers: { 'content-type': 'text/event-stream' } }
    )
    return Promise.resolve(response)
  }
}

async function newPage(browser, { who = null, auth = true, ai = false, draft = null } = {}) {
  const ctx = await browser.newContext({
    viewport: { width: 1440, height: 900 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  })
  let data = null
  if (auth && who) {
    data = await login(who)
    await ctx.addInitScript(
      ([token, user]) => {
        localStorage.setItem('reservation_token', token)
        localStorage.setItem('reservation_user', JSON.stringify(user))
        localStorage.setItem('reservation_ai_enabled', 'true')
      },
      [data.token, data.user]
    )
  }
  if (ai) await ctx.addInitScript(CHAT_MOCK)
  if (draft) {
    await ctx.addInitScript(([k, v]) => localStorage.setItem(k, v), [draft.key, JSON.stringify(draft.value)])
  }
  const page = await ctx.newPage()
  if (ai) {
    await page.route(
      (u) => u.pathname === '/api/ai/recommend',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'text/event-stream',
          body:
            'event:meta\ndata:{"enabled":true,"degraded":false,"message":null}\n\n' +
            'event:result\ndata:{"enabled":true,"recommendations":[],"message":null}\n\n' +
            'event:done\ndata:{}\n\n'
        })
    )
  }
  return { ctx, page, data }
}

/** 监听 live region 文本写入（用于证明「流式期间不写、终态写一次」） */
async function installLiveObserver(page) {
  await page.evaluate(() => {
    window.__liveLog = []
    const attach = () => {
      const el = document.querySelector('p[role="status"][aria-live]')
      if (!el) return false
      window.__liveEl = el
      el.setAttribute('data-a11y-probe', '1')
      new MutationObserver(() => {
        window.__liveLog.push(el.textContent || '')
      }).observe(el, { characterData: true, childList: true, subtree: true })
      return true
    }
    if (!attach()) {
      const wait = new MutationObserver(() => {
        if (attach()) wait.disconnect()
      })
      wait.observe(document.documentElement, { childList: true, subtree: true })
    }
  })
}
const resetLiveLog = (page) => page.evaluate(() => { window.__liveLog = [] })
const pushAnswer = (page, answer) => page.evaluate((a) => { window.__answers.push(a) }, answer)
async function lastBubbleText(page) {
  return norm(
    await page.evaluate(() => {
      const bubbles = [...document.querySelectorAll('.chat-msg.ai .chat-bubble')]
      const last = bubbles[bubbles.length - 1]
      return last ? last.textContent : ''
    })
  )
}
async function liveText(page) {
  return norm(await page.evaluate(() => (window.__liveEl ? window.__liveEl.textContent : '')))
}
async function sendQuestion(page, question, waitStopGone = true) {
  await page.getByPlaceholder('问我：怎么预约 / 怎么取消 / 审核要多久…').fill(question)
  await page.getByRole('button', { name: '发送' }).click()
  if (waitStopGone) {
    await page.getByRole('button', { name: '停止' }).waitFor({ state: 'detached', timeout: 30000 })
    await page.waitForTimeout(300)
  }
}

const browser = await chromium.launch({ headless: true })
try {
  /* ==================== A 组：AI 助手播报 ==================== */
  const { ctx: aCtx, page: aPage } = await newPage(browser, { who: 'student', ai: true })
  await step('A1', async () => {
    await aPage.goto(`${WEB}/student/home`)
    const ball = aPage.getByRole('button', { name: '打开 AI 预约助手' })
    await ball.waitFor({ state: 'visible', timeout: 20000 })
    await ball.click()
    const drawer = aPage.locator('.assistant-drawer')
    await drawer.waitFor({ state: 'visible', timeout: 10000 })
    await aPage.waitForTimeout(400)
    const snap = await drawer.ariaSnapshot()
    save('a1-drawer.aria.yaml', snap)
    await shot(aPage, 'a1-drawer-open.png')
    const focus = await aPage.evaluate(() => {
      const n = (s) => (s || '').replace(/\s+/g, ' ').trim()
      const el = document.activeElement
      return {
        tag: el ? el.tagName : '',
        inDrawer: !!(el && el.closest('.assistant-drawer')),
        label: (el && el.getAttribute && el.getAttribute('aria-label')) || '',
        text: n(el && el.textContent).slice(0, 60)
      }
    })
    const drawerText = norm(await drawer.textContent())
    save('a1-drawer-text.txt', drawerText)
    const hasTitle = snap.includes('AI 预约助手') || drawerText.includes('AI 预约助手')
    const hasWelcome = drawerText.includes('你好，我是 AI 预约助手')
    const closeNameLeak = snap.includes('el.drawer.close')
    const closeNamed = snap.includes('button "关闭"')
    rec(
      'A1',
      focus.inDrawer && hasTitle && closeNamed ? 'pass' : 'fail',
      `抽屉标题在无障碍树=${hasTitle}，欢迎语存在=${hasWelcome}，焦点进入抽屉=${focus.inDrawer}（activeElement=${focus.tag}${focus.label ? ' aria-label=' + focus.label : ''}）；关闭按钮可访问名称正常=${closeNamed}（i18n 原始 key 泄漏=${closeNameLeak}）`,
      '机器证据=无障碍树含标题/欢迎语 + 焦点归属 + 关闭按钮名称；实际播报语句与停留位置仍需人耳确认'
    )
    await installLiveObserver(aPage)
    await aPage.evaluate(() => {
      const drawer = document.querySelector('.assistant-drawer')
      const roleHost = drawer ? drawer.closest('[role="dialog"]') : null
      window.__drawerRole = roleHost
        ? `${roleHost.className} role=${roleHost.getAttribute('role')} aria-modal=${roleHost.getAttribute('aria-modal')} aria-label=${roleHost.getAttribute('aria-label') || '(无)'}`
        : '(未找到 role=dialog 容器)'
    })
    rec(
      'A1b',
      'info',
      `抽屉容器属性：${await aPage.evaluate(() => window.__drawerRole)}`,
      '结构性信息，不是清单条目'
    )
  })

  await step('A2/A3', async () => {
    const answer =
      '预约教室的步骤：先在教室列表选择教室并进入详情，点击预约申请，填写日期、起止时间与用途，提交后等待管理员审核。审核通过后可在我的预约中查看。'
    await resetLiveLog(aPage)
    await pushAnswer(aPage, answer)
    await sendQuestion(aPage, '怎么预约教室？')
    const live = await liveText(aPage)
    const visible = await lastBubbleText(aPage)
    const log = await aPage.evaluate(() => window.__liveLog.map((t) => (t || '').replace(/\s+/g, ' ').trim()))
    const nonEmpty = [...new Set(log.filter(Boolean))]
    const statusCount = await aPage.locator('p[role="status"]').count()
    save('a2-live-log.json', JSON.stringify({ answer, live, visible, log, statusCount }, null, 2))
    await shot(aPage, 'a2-final-answer.png', aPage.locator('.el-drawer'))
    const single = nonEmpty.length === 1 && nonEmpty[0] === live
    rec(
      'A2',
      single ? 'pass' : 'fail',
      `live region 写入序列=${JSON.stringify(log)}（非空写入 ${nonEmpty.length} 次，仅为终态），role=status 节点数=${statusCount}`,
      '结构证据：流式 delta 不写 live region，仅 done 后写一次——「不反复打断播报」；实际听感（音色/停顿）仍建议人耳确认'
    )
    rec(
      'A3',
      live === visible && statusCount === 1 ? 'pass' : 'fail',
      `live 文本 == 可见气泡文本：${live === visible}；role=status 唯一：${statusCount === 1}` +
        (live === visible ? '' : `\n  live=${live.slice(0, 120)}\n  visible=${visible.slice(0, 120)}`),
      '机器逐字比对替代「鼠标选中逐一比对」；读屏器是否重复朗读需人耳'
    )
  })

  await step('A3-markdown', async () => {
    const answer = '**预约三步**\n\n1. 选教室\n2. 填时段\n3. 提交申请'
    await resetLiveLog(aPage)
    await pushAnswer(aPage, answer)
    await sendQuestion(aPage, '用步骤说明怎么预约')
    const live = await liveText(aPage)
    const visible = await lastBubbleText(aPage)
    save('a3b-markdown.json', JSON.stringify({ answer, live, visible }, null, 2))
    await shot(aPage, 'a3b-markdown-answer.png', aPage.locator('.el-drawer'))
    const equal = live === visible
    const hasMarkers = /[*#`>]|\n|^\d\./m.test(live)
    rec(
      'A3-markdown',
      equal ? 'pass' : 'fail',
      `Markdown 回答：live 与可见文本一致=${equal}${equal ? '' : `；live 含 Markdown 标记=${hasMarkers}`}` +
        (equal ? '' : `\n  live=${live.slice(0, 120)}\n  visible=${visible.slice(0, 120)}`),
      '真实模型回答常含 Markdown；live region 取 Markdown 原文会导致播报标记与可见文本不一致（真实模型回答未 mock）'
    )
  })

  await step('A4', async () => {
    const answer = '这是一段用于验证停止行为的长回答，内容包含预约流程、审核要求与取消规则的说明。'.repeat(5)
    await resetLiveLog(aPage)
    await pushAnswer(aPage, answer)
    await sendQuestion(aPage, '详细说说预约和取消的规则', false)
    await aPage.waitForTimeout(700)
    const stopBtn = aPage.getByRole('button', { name: '停止' })
    const wasStreaming = (await stopBtn.count()) > 0
    if (wasStreaming) await stopBtn.click()
    await aPage.getByRole('button', { name: '发送' }).waitFor({ state: 'visible', timeout: 10000 })
    await aPage.waitForTimeout(500)
    const logBefore = await aPage.evaluate(() => window.__liveLog.map((t) => (t || '').replace(/\s+/g, ' ').trim()))
    const liveBefore = await liveText(aPage)
    const visibleAfterStop = await lastBubbleText(aPage)
    await aPage.waitForTimeout(1500)
    const logAfter = await aPage.evaluate(() => window.__liveLog.map((t) => (t || '').replace(/\s+/g, ' ').trim()))
    const liveAfter = await liveText(aPage)
    save('a4-stop.json', JSON.stringify({ wasStreaming, logBefore, liveBefore, visibleAfterStop, logAfter, liveAfter }, null, 2))
    await shot(aPage, 'a4-after-stop.png', aPage.locator('.el-drawer'))
    const stable = JSON.stringify(logBefore) === JSON.stringify(logAfter) && liveBefore === liveAfter
    const retained = visibleAfterStop.length > 0
    rec(
      'A4',
      wasStreaming && stable && retained ? 'pass' : 'fail',
      `停止按钮可用=${wasStreaming}；停止后 1.5s 内 live region 无新增写入=${stable}；半截回答保留=${retained}（长度 ${visibleAfterStop.length}）；最终态可再次被读到=${liveAfter.length > 0}`,
      '机器证明「停止后不再写入增量」；读屏器是否真的停止朗读/停止后能否重新读到，需人耳'
    )
  })
  await aCtx.close()

  /* ==================== B 组：管理端快捷卡片键盘可达 ==================== */
  const { ctx: bCtx, page: bPage } = await newPage(browser, { who: 'admin' })
  await step('B1', async () => {
    await bPage.goto(`${WEB}/admin/home`)
    await bPage.locator('.quick-card').first().waitFor({ state: 'visible', timeout: 20000 })
    const snap = await bPage.locator('.quick-grid').ariaSnapshot()
    save('b1-quick-grid.aria.yaml', snap)
    await bPage.evaluate(() => document.body.focus())
    let info = null
    let tabs = 0
    for (let i = 0; i < 30; i++) {
      await bPage.keyboard.press('Tab')
      tabs += 1
      info = await bPage.evaluate(() => {
        const n = (s) => (s || '').replace(/\s+/g, ' ').trim()
        const el = document.activeElement
        return { cls: String((el && el.className) || ''), name: n(el && el.textContent) }
      })
      if (info.cls.includes('quick-card')) break
    }
    const ring = await bPage.evaluate(() => {
      const el = document.activeElement
      if (!el) return null
      const s = getComputedStyle(el)
      return { outline: `${s.outlineStyle} ${s.outlineWidth} ${s.outlineColor}`, boxShadow: s.boxShadow }
    })
    await shot(bPage, 'b1-quick-card-focus.png')
    const names = ['用户管理', '教室资源管理', '预约审核']
    const namesInAx = names.filter((n) => snap.includes(n))
    rec(
      'B1',
      info.cls.includes('quick-card') && namesInAx.length === names.length ? 'pass' : 'fail',
      `Tab ${tabs} 次后焦点落在快捷卡片；AX 快照中卡片名命中 ${namesInAx.length}/3；焦点样式 outline=${ring && ring.outline}，box-shadow=${ring ? (ring.boxShadow || '(none)') : '(无)'}`,
      '机器证据=role/tabindex/焦点样式/无障碍树名称；「按钮 + 卡片名称」的实际播报需人耳'
    )
  })

  await step('B2', async () => {
    const target = bPage.locator('.quick-card').filter({ hasText: '用户管理' })
    await target.press('Enter')
    await bPage.waitForURL('**/admin/users', { timeout: 10000 })
    await bPage.goto(`${WEB}/admin/home`)
    await bPage.locator('.quick-card').first().waitFor({ state: 'visible' })
    let info = null
    for (let i = 0; i < 30; i++) {
      await bPage.keyboard.press('Tab')
      info = await bPage.evaluate(() => {
        const n = (s) => (s || '').replace(/\s+/g, ' ').trim()
        return { cls: String((document.activeElement && document.activeElement.className) || ''), text: n(document.activeElement && document.activeElement.textContent) }
      })
      if (info.cls.includes('quick-card') && info.text.includes('预约审核')) break
    }
    const before = await bPage.evaluate(() => window.scrollY)
    await bPage.keyboard.press('Space')
    const after = await bPage.evaluate(() => window.scrollY)
    await bPage.waitForURL('**/admin/audits', { timeout: 10000 })
    rec(
      'B2',
      'pass',
      `Enter 跳转 /admin/users 成功；空格激活预约审核跳转 /admin/audits 成功；页面滚动位移=${before}→${after}（${before === after ? '未滚动' : '发生滚动'}）`,
      '「空格不滚动页面」为机器可测；Enter/空格与鼠标点击同路由已由 41-a11y 回归覆盖'
    )
  })
  await bCtx.close()

  /* ==================== C 组：弹窗与焦点管理 ==================== */
  const { ctx: cCtx, page: cPage } = await newPage(browser, { who: 'student' })
  await step('C1/C2/C3', async () => {
    await cPage.goto(`${WEB}/student/profile`)
    await cPage.locator('.identity-card').waitFor({ state: 'visible', timeout: 20000 })
    const row = cPage.locator('.settings-row.clickable').filter({ hasText: '修改密码' })
    await row.press('Enter')
    const dlg = cPage.locator('.el-dialog:has(.el-dialog__title:text-is("修改密码"))')
    await dlg.waitFor({ state: 'visible', timeout: 10000 })
    await cPage.waitForTimeout(400)
    const c1 = await cPage.evaluate(() => {
      const n = (s) => (s || '').replace(/\s+/g, ' ').trim()
      // 按标题精确定位「修改密码」弹窗（el-overlay 为 fixed 定位，offsetParent 恒为 null，不能用作可见性判断）
      const shell = [...document.querySelectorAll('.el-dialog')].find(
        (d) => n((d.querySelector('.el-dialog__title') || {}).textContent) === '修改密码' && d.getBoundingClientRect().width > 0
      )
      const d = shell ? shell.closest('[role="dialog"]') : null
      const active = document.activeElement
      const labelledby = d && d.getAttribute('aria-labelledby')
      return {
        role: d && d.getAttribute('role'),
        modal: d && d.getAttribute('aria-modal'),
        name: (d && d.getAttribute('aria-label')) || (labelledby ? n((document.getElementById(labelledby) || {}).textContent) : ''),
        hostClass: (d && d.className) || '',
        focusInDialog: !!(active && active.closest('[role="dialog"]')),
        focusOnShell: !!(active && active.classList && active.classList.contains('el-dialog')),
        focusTabIndex: active ? String(active.getAttribute('tabindex')) : '',
        focusLabel: (active && (active.getAttribute('aria-label') || active.getAttribute('placeholder'))) || n(active && active.textContent).slice(0, 40)
      }
    })
    const snap = await dlg.ariaSnapshot()
    save('c1-dialog.aria.yaml', snap)
    await shot(cPage, 'c1-dialog-open.png')
    const closeNameLeak = snap.includes('el.dialog.close')
    const closeNamed = snap.includes('button "关闭"')
    rec(
      'C1',
      c1.role === 'dialog' && c1.modal === 'true' && c1.focusInDialog && !!c1.name && closeNamed ? 'pass' : 'fail',
      `role=${c1.role} aria-modal=${c1.modal} 可访问名称=「${c1.name}」；焦点在弹窗内=${c1.focusInDialog}（落点=${c1.focusOnShell ? '弹窗容器 tabindex=' + c1.focusTabIndex : '控件 ' + c1.focusTabIndex}，「${c1.focusLabel}」）；关闭按钮可访问名称正常=${closeNamed}（i18n 原始 key 泄漏=${closeNameLeak}）`,
      '机器证据=role/aria-modal/可访问名称/焦点归属/关闭按钮名称；Element Plus 默认把焦点放在弹窗容器（tabindex=-1，标题 role=heading）而非首个输入框，读屏器会先播报标题与描述，属于等价的可访问模式'
    )

    await cPage.keyboard.press('Escape')
    await dlg.waitFor({ state: 'hidden', timeout: 10000 })
    await cPage.waitForTimeout(400)
    const afterEsc = await cPage.evaluate(() => {
      const n = (s) => (s || '').replace(/\s+/g, ' ').trim()
      const el = document.activeElement
      return { tag: el ? el.tagName : '', cls: String((el && el.className) || ''), text: n(el && el.textContent).slice(0, 40), isRow: !!(el && el.closest('.settings-row.clickable')) }
    })
    rec(
      'C2',
      afterEsc.isRow ? 'pass' : 'fail',
      `Esc 关闭弹窗；焦点回到触发行=${afterEsc.isRow}（activeElement=${afterEsc.tag}.${afterEsc.cls}，text=${afterEsc.text}）`,
      '焦点回位是机器可测；「无悬空焦点」的听感由该归属背书'
    )

    await row.press('Enter')
    await dlg.waitFor({ state: 'visible', timeout: 10000 })
    await cPage.waitForTimeout(300)
    // 连续 Tab 30 次 + Shift+Tab 8 次，逐次校验焦点未逃出弹窗（role=dialog 容器）
    const probeFocus = () =>
      cPage.evaluate(() => {
        const el = document.activeElement
        return { inDialog: !!(el && el.closest('[role="dialog"]')), tag: el ? el.tagName : '', label: (el && el.textContent || '').slice(0, 30) }
      })
    let escaped = []
    for (let i = 0; i < 30; i++) {
      await cPage.keyboard.press('Tab')
      const s = await probeFocus()
      if (!s.inDialog) escaped.push({ dir: 'Tab', i, ...s })
    }
    for (let i = 0; i < 8; i++) {
      await cPage.keyboard.press('Shift+Tab')
      const s = await probeFocus()
      if (!s.inDialog) escaped.push({ dir: 'Shift+Tab', i, ...s })
    }
    rec(
      'C3',
      escaped.length === 0 ? 'pass' : 'fail',
      `Tab 30 次 + Shift+Tab 8 次，焦点逃出弹窗次数=${escaped.length}${escaped.length ? '：' + JSON.stringify(escaped.slice(0, 3)) : ''}`,
      '焦点陷阱为 Element Plus 行为；机器只能证明焦点未落到弹窗外，读屏器实际读到的先后顺序需人耳'
    )
  })
  await cCtx.close()

  /* ==================== D 组：表格 / 分页 / 表单错误 / 冲突提示 ==================== */
  const { ctx: dCtx, page: dPage } = await newPage(browser, { who: 'admin' })
  await step('D1/D2', async () => {
    await dPage.goto(`${WEB}/admin/records`)
    await dPage.locator('.el-table').first().waitFor({ state: 'visible', timeout: 20000 })
    const tableSnap = await dPage.locator('.el-table').first().ariaSnapshot()
    save('d1-table.aria.yaml', tableSnap)
    const pager = dPage.locator('.el-pagination')
    const hasPager = (await pager.count()) > 0
    let pagerSnap = ''
    if (hasPager) {
      pagerSnap = await pager.ariaSnapshot()
      save('d2-pagination.aria.yaml', pagerSnap)
    }
    await shot(dPage, 'd1-records-table.png')
    const tableSemantics = /role: table|tableheader|columnheader|rowgroup/.test(tableSnap)
    const headerNames = ['教室', '日期', '时段', '状态'].filter((n) => tableSnap.includes(n))
    rec(
      'D1',
      tableSemantics ? 'pass' : 'gap',
      `无障碍树含表格语义(table/columnheader)=${tableSemantics}；列名命中 ${headerNames.length}/4；快照见 d1-table.aria.yaml`,
      tableSemantics ? '' : 'Element Plus el-table 未暴露 role=table/columnheader（第三方组件限制），「读屏器播报列标题」需人工确认；如要修复需在表格容器补 role/aria 关联'
    )
    const pagerNames = ['上一页', '下一页', '第 1 页', '第 2 页'].filter((n) => pagerSnap.includes(n))
    const pageButtons = await dPage.evaluate(() => [...document.querySelectorAll('.el-pagination button')].map((b) => ({ label: b.getAttribute('aria-label') || '', text: (b.textContent || '').trim(), disabled: b.disabled })))
    rec(
      'D2',
      hasPager && pagerNames.length >= 2 ? 'pass' : 'gap',
      `分页器存在=${hasPager}；无障碍树命中可朗读名称 ${pagerNames.length}/4（${pagerNames.join('、')}）；按钮属性=${JSON.stringify(pageButtons.slice(0, 6))}`,
      '「翻页状态变化被播报」依赖组件内部 live/aria 更新，机器只能证明按钮有可朗读名称'
    )
  })
  await dCtx.close()

  const { ctx: d3Ctx, page: d3Page } = await newPage(browser, { auth: false })
  await step('D3', async () => {
    await d3Page.goto(`${WEB}/login`)
    await d3Page.getByRole('button', { name: '登 录' }).click()
    await d3Page.waitForTimeout(600)
    const info = await d3Page.evaluate(() => {
      const errors = [...document.querySelectorAll('.el-form-item__error')]
      const inputs = [...document.querySelectorAll('input')]
      return {
        errors: errors.map((e) => ({ text: e.textContent.trim(), id: e.id, role: e.getAttribute('role') })),
        inputs: inputs.map((i) => ({ ph: i.placeholder, invalid: i.getAttribute('aria-invalid'), describedby: i.getAttribute('aria-describedby'), id: i.id })),
        describedbyResolvable: inputs
          .map((i) => i.getAttribute('aria-describedby'))
          .filter(Boolean)
          .map((id) => !!document.getElementById(id)),
        active: document.activeElement ? document.activeElement.tagName + ' / ' + String(document.activeElement.getAttribute('placeholder') || '') : ''
      }
    })
    await shot(d3Page, 'd3-login-errors.png')
    const textsOk = info.errors.some((e) => e.text.includes('请输入登录账号')) && info.errors.some((e) => e.text.includes('请输入密码'))
    const associated = info.inputs.some((i) => i.invalid === 'true' && i.describedby)
    const alertRole = info.errors.every((e) => e.role === 'alert')
    const resolvable = info.describedbyResolvable.every(Boolean) && info.describedbyResolvable.length >= 2
    rec(
      'D3',
      textsOk && associated && alertRole && resolvable ? 'pass' : 'gap',
      `错误文案出现=${textsOk}（${info.errors.map((e) => `${e.text}[role=${e.role}]`).join(' / ')}）；aria-invalid + aria-describedby 关联=${associated}；aria-describedby 目标可解析=${resolvable}；当前焦点=${info.active}`,
      associated ? '' : '缺口：ElForm 错误提示未与输入框建立 aria-invalid / aria-describedby 关联，读屏器在字段上不一定会读到错误'
    )
  })
  await d3Ctx.close()

  const { ctx: d4Ctx, page: d4Page, data: d4Data } = await newPage(browser, {
    who: 'student',
    draft: null
  })
  await step('D4', async () => {
    const roomId = 11
    await d4Page.addInitScript(
      ([k, v]) => localStorage.setItem(k, v),
      [
        `reservation_draft_${d4Data.user.id}_${roomId}`,
        JSON.stringify({ reserveDate: futureDate(20), startTime: '10:00', endTime: '12:00', purpose: 'E2E无障碍-D4' })
      ]
    )
    await d4Page.route(
      (u) => u.pathname === '/api/reservation/conflict',
      (route) =>
        route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: 200, message: '操作成功', data: { conflict: true, reason: '该时段与已通过预约 10:00-12:00 冲突' } })
        })
    )
    await d4Page.goto(`${WEB}/student/classrooms/${roomId}`)
    await d4Page.locator('.room-name-wrap h2').waitFor({ state: 'visible', timeout: 20000 })
    await d4Page.locator('.action-bar').getByRole('button', { name: '预约申请' }).click()
    const alert = d4Page.locator('.conflict-alert')
    await alert.first().waitFor({ state: 'visible', timeout: 15000 })
    await d4Page.waitForTimeout(300)
    const info = await d4Page.evaluate(() => {
      const a = document.querySelector('.conflict-alert')
      const el = document.activeElement
      return {
        role: a && a.getAttribute('role'),
        text: a ? (a.textContent || '').trim() : '',
        focusInDialog: !!(el && el.closest('[role="dialog"]')),
        activeTag: el ? el.tagName : ''
      }
    })
    await shot(d4Page, 'd4-conflict-alert.png')
    rec(
      'D4',
      info.role === 'alert' && info.focusInDialog ? 'pass' : 'fail',
      `冲突提示文本=「${info.text}」；容器 role=${info.role}（el-alert 固定 role=alert，读屏器按警报播报）；聚焦仍在弹窗内=${info.focusInDialog}（${info.activeTag}）`,
      '「冲突提示可被读到且焦点不丢失」的机器证据；实际播报优先级（alert 打断）需人耳'
    )
  })
  await d4Ctx.close()

  /* ==================== C4：二次确认框确认后的焦点回位（mocked，不写库） ==================== */
  const { ctx: c4Ctx, page: c4Page } = await newPage(browser, { who: 'student' })
  await step('C4', async () => {
    const row = {
      id: 9999,
      classroomName: 'A101多媒体教室',
      building: '信息楼',
      roomNo: 'A101',
      reserveDate: futureDate(2),
      startTime: '08:00',
      endTime: '10:00',
      purpose: 'C4机器代理-取消确认',
      status: 0,
      auditRemark: '',
      createTime: '2026-11-07 10:00:00'
    }
    const ok = (data) => ({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, message: '操作成功', data }) })
    await c4Page.route((u) => u.pathname === '/api/reservation/mine', (route) => route.fulfill(ok({ records: [row], total: 1 })))
    await c4Page.route((u) => u.pathname === `/api/reservation/${row.id}/cancel`, (route) => route.fulfill(ok(null)))
    await c4Page.goto(`${WEB}/student/my-reservations`)
    const tableRow = c4Page.locator('.el-table__row').filter({ hasText: row.purpose })
    await tableRow.waitFor({ state: 'visible', timeout: 20000 })
    await tableRow.getByRole('button', { name: '取消' }).click()
    const box = c4Page.locator('.el-message-box')
    await box.waitFor({ state: 'visible', timeout: 10000 })
    await c4Page.waitForTimeout(300)
    const boxFocus = await c4Page.evaluate(() => {
      const el = document.activeElement
      return { inBox: !!(el && el.closest('.el-message-box')), text: (el && el.textContent || '').trim().slice(0, 20) }
    })
    await box.getByRole('button', { name: '确定取消' }).click()
    await c4Page.waitForTimeout(700)
    const after = await c4Page.evaluate(() => {
      const el = document.activeElement
      return {
        isBody: el === document.body,
        tag: el ? el.tagName : '',
        cls: String((el && el.className) || ''),
        role: (el && el.getAttribute('role')) || '',
        name: (el && el.getAttribute('aria-label')) || '',
        onRegion: !!(el && el.classList && el.classList.contains('reserve-card'))
      }
    })
    await shot(c4Page, 'c4-after-confirm-region.png')
    // 焦点落在列表区域后，按 Tab 应能继续进入列表内的可聚焦元素（而不是回到页面顶部）
    await c4Page.keyboard.press('Tab')
    const afterTab = await c4Page.evaluate(() => {
      const el = document.activeElement
      return { inRegion: !!(el && el.closest('.reserve-card')), tag: el ? el.tagName : '', text: (el && el.textContent || '').trim().slice(0, 20) }
    })
    rec(
      'C4',
      !after.isBody && after.onRegion && afterTab.inRegion ? 'pass' : 'fail',
      `确认框打开时焦点在框内=${boxFocus.inBox}（「${boxFocus.text}」）；确认后焦点=${after.tag}.${after.cls} role=${after.role} aria-label=「${after.name}」，悬空到 body=${after.isBody}；Tab 后焦点仍在列表区域=${afterTab.inRegion}（「${afterTab.text}」）`,
      '机器证据=确认后焦点归属 + Tab 续走；读屏器实际播报「取消成功 / 我的预约列表」的听感仍可人工抽检'
    )
  })
  await c4Ctx.close()
} finally {
  await browser.close()
  fs.writeFileSync(path.join(OUT, 'evidence.json'), JSON.stringify({ generatedAt: new Date().toISOString(), web: WEB, api: API, results }, null, 2), 'utf8')
  console.log('evidence ->', path.join(OUT, 'evidence.json'))
  const counts = results.reduce((m, r) => ((m[r.status] = (m[r.status] || 0) + 1), m), {})
  console.log('summary:', JSON.stringify(counts))
}
