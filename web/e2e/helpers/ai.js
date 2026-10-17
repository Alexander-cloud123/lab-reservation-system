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
 * 把问答 VO（{enabled,message,answer}）转换为流式 SSE 响应体：
 * meta（degraded 取 message 是否非空）→ answer 按 8 字切多个 delta → done。
 * Playwright route.fulfill 一次性返回整段响应体（无法按时间推块），
 * 「逐帧按序拼接」由前端解析器保证，由最终文本断言覆盖。
 */
export function chatStreamBody(payload) {
  const disabled = payload.enabled === false
  const degraded = !!payload.message
  let body = sseFrame('meta', {
    enabled: !disabled,
    // 与真实后端 AiChatServiceImpl 关闭分支一致：meta(false,true,message) → done('disabled')，中间无 delta
    degraded,
    message: payload.message ?? null
  })
  const answer = payload.answer || ''
  for (let i = 0; i < answer.length; i += 8) {
    body += sseFrame('delta', { text: answer.slice(i, i + 8) })
  }
  const finishReason = disabled ? 'disabled' : degraded ? 'fallback' : 'stop'
  body += sseFrame('done', { finishReason })
  return body
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
  // done 无业务载荷（后端发空对象）
  body += sseFrame('done', {})
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