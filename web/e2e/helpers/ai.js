/**
 * AI 入口 mock（零额度策略）
 *
 * 在浏览器层拦截 /api/ai/*，请求不出浏览器 → 后端不会被调用 → 真实模型零调用。
 * 固定返回与后端 VO 结构一致的假数据，用于验证前端 AI 交互链路（而非模型效果）。
 *
 * 覆盖边界（需知）：本方式只覆盖前端 AI 交互；后端 /api/ai/* 自身逻辑
 * 由 HTTP 级断言（server/scripts/smoke.ps1、docs/e2e/regression-r1r5.py）负责。
 */
import { futureDate, today } from './data'

export const AI_PATHS = {
  recommend: '/api/ai/recommend',
  parse: '/api/ai/parse-reservation',
  chat: '/api/ai/chat',
  compliance: '/api/ai/compliance-check'
}

/**
 * 构造一个 SSE 事件帧（event + JSON data，以空行结尾）。
 * 线格式与真实生产者 Spring SseEmitter 完全一致：冒号后【无空格】
 * （SseEmitter 的 data 常量即 "data:"；event 为 "event:"）。
 * 勿擅自加空格——mock 与真实线格式漂移会让前端解析缺陷在 E2E 中漏检。
 */
export function sseFrame(event, data) {
  return `event:${event}\ndata:${JSON.stringify(data)}\n\n`
}

/**
 * 无 JSON 载荷的 SSE 帧（真实后端 done 事件的线格式：`event:done\ndata:` + 空行）。
 * 与 {@link sseFrame} 分开，是因为 JSON.stringify(undefined) 会得到 undefined 字符串拼接错误；
 * 真实 SseEmitter 对空对象也会写成空 data，前端必须能把它当终态而不是脏帧丢弃。
 */
export function sseEmptyFrame(event) {
  return `event:${event}\ndata:\n\n`
}

/**
 * 把问答 VO（{enabled,message,answer}）转换为流式 SSE 响应体：
 * meta（degraded 取 message 是否非空）→ answer 按 8 字切多个 delta → done。
 * Playwright route.fulfill 一次性返回整段响应体（无法按时间推块），
 * 「逐帧按序拼接」由前端解析器保证，由最终文本断言覆盖。
 */
export function chatStreamBody(payload) {
  const disabled = payload.enabled === false
  const degraded = !!payload.message
  // _think 为 mock 专用控制字段：映射后端真实 thinking/reasoning 事件序列（R14）
  const think = disabled ? null : payload._think
  let body = ''
  if (think) {
    for (const step of think.steps || []) {
      body += sseFrame('thinking', {
        id: step.id,
        label: step.label,
        status: 'running',
        elapsedMs: null
      })
      body += sseFrame('thinking', {
        id: step.id,
        label: step.label,
        status: step.status || 'done',
        elapsedMs: step.elapsedMs ?? 0
      })
    }
  }
  body += sseFrame('meta', {
    enabled: !disabled,
    // 与真实后端 AiChatServiceImpl 关闭分支一致：meta(false,true,message) → done('disabled')，中间无 delta
    degraded,
    message: payload.message ?? null
  })
  if (think) {
    for (const piece of think.reasoning || []) {
      body += sseFrame('reasoning', { text: piece })
    }
    body += sseFrame('thinking', {
      id: 'summary',
      label: think.summary ?? null,
      status: 'done',
      elapsedMs: think.elapsedMs ?? 0
    })
  }
  const answer = payload.answer || ''
  for (let i = 0; i < answer.length; i += 8) {
    body += sseFrame('delta', { text: answer.slice(i, i + 8) })
  }
  const finishReason = disabled ? 'disabled' : degraded ? 'fallback' : 'stop'
  body += sseFrame('done', { finishReason })
  return body
}

/**
 * 逐帧流式返回的假 AI 问答（R14「思考中」瞬时态断言用）。
 *
 * mockAi 的 route.fulfill 只能一次性返回整段响应体，无法让前端停在「正在思考」；
 * 这里在页面加载前替换 fetch，用真正的 ReadableStream 按 delay 推帧，
 * 使「思考中 → 折叠」这一时间维度可被 E2E 观察。仅拦截 /api/ai/chat?stream=true。
 *
 * @param {import('@playwright/test').Page} page
 * @param {{steps?:Array, reasoning?:string[], answer?:string, summary?:string|null,
 *          elapsedMs?:number, thinkDelayMs?:number, deltaDelayMs?:number}} opts
 */
export async function patchStreamingChat(page, opts = {}) {
  const {
    steps = [],
    reasoning = [],
    answer = '',
    summary = null,
    elapsedMs = 1600,
    thinkDelayMs = 420,
    deltaDelayMs = 90
  } = opts
  const frames = []
  for (const step of steps) {
    frames.push({
      delay: thinkDelayMs,
      text: sseFrame('thinking', { id: step.id, label: step.label, status: 'running', elapsedMs: null })
    })
    frames.push({
      delay: 40,
      text: sseFrame('thinking', {
        id: step.id,
        label: step.label,
        status: step.status || 'done',
        elapsedMs: step.elapsedMs ?? 200
      })
    })
  }
  frames.push({ delay: 60, text: sseFrame('meta', { enabled: true, degraded: false, message: null }) })
  for (const piece of reasoning) {
    frames.push({ delay: 70, text: sseFrame('reasoning', { text: piece }) })
  }
  frames.push({
    delay: 60,
    text: sseFrame('thinking', { id: 'summary', label: summary, status: 'done', elapsedMs })
  })
  for (let i = 0; i < answer.length; i += 8) {
    frames.push({ delay: deltaDelayMs, text: sseFrame('delta', { text: answer.slice(i, i + 8) }) })
  }
  frames.push({ delay: 60, text: sseFrame('done', { finishReason: 'stop' }) })

  await page.addInitScript(({ frames }) => {
    const nativeFetch = window.fetch.bind(window)
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url
      if (!url.includes('/ai/chat?stream=true')) {
        return nativeFetch(input, init)
      }
      const signal = init && init.signal
      let cancelled = false
      const encoder = new TextEncoder()
      const body = new ReadableStream({
        start(controller) {
          const push = async () => {
            for (const frame of frames) {
              if (cancelled) return
              await new Promise((resolve) => setTimeout(resolve, frame.delay))
              if (cancelled) return
              try {
                controller.enqueue(encoder.encode(frame.text))
              } catch {
                return
              }
            }
            try {
              controller.close()
            } catch {
              // 已关闭（取消）时忽略
            }
          }
          push()
        },
        cancel() {
          cancelled = true
        }
      })
      if (signal) {
        signal.addEventListener(
          'abort',
          () => {
            cancelled = true
          },
          { once: true }
        )
      }
      return Promise.resolve(
        new Response(body, {
          status: 200,
          headers: { 'content-type': 'text/event-stream;charset=UTF-8' }
        })
      )
    }
  }, { frames })
}

/**
 * 把结构化 VO（recommend/parse/compliance）转换为 SSE 响应体（R13-5）：
 * meta → phase（仅缓存未命中；payload._streamPhase===false 或关闭态不发）→ result（VO 本体）→ done。
 * 与后端 StructuredStreamEvents/SseStructuredStreamSink 的线序列严格一致：
 *  - 启用态 meta 固定 (true,false,null)，降级原因只在 result.message 体现；
 *  - 关闭态 meta(false,true,message) → result(关闭 VO) → done，中间无 phase；
 *  - result 帧 data 经后端 @JsonValue 展平为 VO 本体（不是 {data:{...}}）。
 * @param {'recommend'|'parse'|'compliance'} kind 接口 kind（用于 phase code/文案）
 * @param {object} payload VO 同构假数据；_streamPhase:false 模拟缓存命中（无 phase），不会进入 result
 */
export function structuredStreamBody(kind, payload) {
  const phaseMeta = {
    recommend: { phase: 'recommend', text: '正在分析你的预约习惯与明日教室空闲' },
    parse: { phase: 'parse', text: '正在理解你的预约需求' },
    compliance: { phase: 'compliance', text: '正在校验预约用途的合规性' }
  }
  const disabled = payload.enabled === false
  // 控制字段只用于 mock 编排，必须从 result VO 中剔除（真实 VO 无此字段）
  const vo = { ...payload }
  delete vo._streamPhase
  const sendPhase = payload._streamPhase !== false && !disabled

  let body = sseFrame('meta', {
    enabled: !disabled,
    degraded: disabled,
    message: disabled ? (payload.message ?? null) : null
  })
  if (sendPhase) {
    body += sseFrame('phase', phaseMeta[kind])
  }
  body += sseFrame('result', vo)
  // done 无业务载荷：真实后端 SseEmitter 对 new Object() 只写出 "event:done\ndata:"（没有 JSON 体），
  // 与 sseFrame 的 JSON.stringify 不同——mock 必须照抄真实线格式，否则会把前端「空载荷终态」缺陷漏检。
  body += sseEmptyFrame('done')
  return body
}

/** 默认假数据（结构对齐后端 VO；字段名不一致会在前端表现为"解析成功但表单未回填"） */
export function defaultAiPayloads() {
  const date = futureDate(1)
  return {
    [AI_PATHS.recommend]: {
      enabled: true,
      message: null,
      date,
      recommendations: [
        {
          classroomId: 11,
          name: 'C301',
          building: 'C栋',
          roomNo: 'C301',
          type: 3,
          capacity: 80,
          reason: '与你常约的机房类型一致，明日 14:00-16:00 空闲'
        },
        {
          classroomId: 7,
          name: 'B201',
          building: 'B栋',
          roomNo: 'B201',
          type: 3,
          capacity: 60,
          reason: '同为机房，可容纳你近期预约的人数规模'
        },
        {
          classroomId: 4,
          name: 'A301',
          building: 'A栋',
          roomNo: 'A301',
          type: 3,
          capacity: 50,
          reason: '上午时段空闲较多，适合课程实验'
        }
      ]
    },
    [AI_PATHS.parse]: {
      enabled: true,
      message: null,
      date,
      startTime: '14:00',
      endTime: '16:00',
      capacity: 40,
      roomType: '机房',
      purpose: '课程实验',
      error: null
    },
    [AI_PATHS.chat]: {
      enabled: true,
      message: null,
      answer: '你在明日 14:00-16:00 暂无预约，C301（机房，80 人）该时段空闲，可直接在教室列表页发起预约。'
    },
    [AI_PATHS.compliance]: {
      enabled: true,
      message: null,
      compliant: false,
      reason: '用途描述过于笼统，建议补充具体课程或实验内容'
    }
  }
}

/** AI 关闭时的返回（enabled=false，前端据此隐藏全部 AI 入口） */
export function aiDisabledPayload() {
  return { enabled: false, message: 'AI 功能已关闭' }
}

/**
 * 以「AI 全局关闭」挂载拦截（四个入口都返回 enabled=false）。
 * 用于需要隐藏 AI 入口的确定性用例：R14 起前端每次启动都会向服务端探测开关，
 * 仅靠 localStorage 旧值已不能阻止探测，故这些用例统一显式 mock 关闭态，
 * 既保证页面没有 AI 元素，也避免本地已配真实 Key 时误打上游消耗额度。
 */
export async function mockAiOff(page) {
  return mockAi(page, {
    [AI_PATHS.recommend]: aiDisabledPayload(),
    [AI_PATHS.parse]: aiDisabledPayload(),
    [AI_PATHS.chat]: aiDisabledPayload(),
    [AI_PATHS.compliance]: aiDisabledPayload()
  })
}

/**
 * 挂载 AI 拦截。必须在 page.goto 之前调用。
 *
 * 注意路由匹配用 pathname 前缀而非 glob '**\/api\/**'——
 * Vite 的源码模块路径 /src/api/*.js 也会命中 glob，导致模块加载被劫持成 JSON（MIME 报错、白屏）。
 *
 * @returns {Record<string, number>} 各接口命中次数（可变对象，用例可读；用于断言"未真实调用模型"）
 * @param {(streamInfo:{path:string, body:string, stream:boolean}) => void} [opts.onStream]
 *        每次响应后回调（JSON/SSE 均可），供用例在网络层断言 SSE 帧序列（phase/result/done 顺序）
 */
export async function mockAi(page, overrides = {}, opts = {}) {
  const payloads = { ...defaultAiPayloads(), ...overrides }
  const calls = {}

  await page.route(
    (url) => Object.values(AI_PATHS).includes(url.pathname),
    async (route) => {
      const reqUrl = new URL(route.request().url())
      const path = reqUrl.pathname
      calls[path] = (calls[path] || 0) + 1
      const rawPayload = payloads[path] ?? aiDisabledPayload()
      // 覆盖项支持函数形式：(requestBody) => payload，供同一接口按请求内容动态返回（如多轮问答分流）
      let payload = rawPayload
      if (typeof rawPayload === 'function') {
        let reqBody = {}
        const postData = route.request().postData()
        if (postData) {
          try {
            reqBody = JSON.parse(postData)
          } catch {
            // 非 JSON 请求体：保持空对象，函数覆盖项自行兜底
          }
        }
        payload = rawPayload(reqBody)
      }
      // 模拟真实模型的"有延迟才出结果"，避免用例把 loading 态当成最终态断言
      await new Promise((r) => setTimeout(r, 120))
      const wantStream = reqUrl.searchParams.get('stream') === 'true'
      // 流式请求（R12 chat / R13-5 三个结构化接口）：回 text/event-stream（pathname 不变，计数口径不变）
      if (wantStream && path === AI_PATHS.chat) {
        const body = chatStreamBody(payload)
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream;charset=UTF-8',
          body
        })
        opts.onStream && opts.onStream({ path, body, stream: true })
        return
      }
      if (wantStream && STRUCTURED_KINDS[path]) {
        const body = structuredStreamBody(STRUCTURED_KINDS[path], payload)
        await route.fulfill({
          status: 200,
          contentType: 'text/event-stream;charset=UTF-8',
          body
        })
        opts.onStream && opts.onStream({ path, body, stream: true })
        return
      }
      const jsonBody = JSON.stringify({ code: 200, message: '操作成功', data: payload })
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: jsonBody
      })
      opts.onStream && opts.onStream({ path, body: jsonBody, stream: false })
    }
  )

  return calls
}

/** 结构化 SSE 接口路径 → kind（structuredStreamBody 的 phase 元信息索引） */
const STRUCTURED_KINDS = {
  [AI_PATHS.recommend]: 'recommend',
  [AI_PATHS.parse]: 'parse',
  [AI_PATHS.compliance]: 'compliance'
}

/** 断言某条 AI 接口被命中过（证明前端确实发起了该 AI 调用） */
export function aiCallCount(calls, path) {
  return calls[path] || 0
}

/** 今日日期（部分 AI 解析断言需要） */
export { today }