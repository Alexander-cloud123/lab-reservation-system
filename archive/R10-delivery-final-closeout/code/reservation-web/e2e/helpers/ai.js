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
export function aiDisabledPayload(path) {
  return { enabled: false, message: 'AI 功能已关闭' }
}

/**
 * 挂载 AI 拦截。必须在 page.goto 之前调用。
 *
 * 注意路由匹配用 pathname 前缀而非 glob '**\/api\/**'——
 * Vite 的源码模块路径 /src/api/*.js 也会命中 glob，导致模块加载被劫持成 JSON（MIME 报错、白屏）。
 *
 * @returns {Record<string, number>} 各接口命中次数（可变对象，用例可读；用于断言"未真实调用模型"）
 */
export async function mockAi(page, overrides = {}) {
  const payloads = { ...defaultAiPayloads(), ...overrides }
  const calls = {}

  await page.route(
    (url) => Object.values(AI_PATHS).includes(url.pathname),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      calls[path] = (calls[path] || 0) + 1
      const payload = payloads[path] ?? aiDisabledPayload(path)
      // 模拟真实模型的"有延迟才出结果"，避免用例把 loading 态当成最终态断言
      await new Promise((r) => setTimeout(r, 120))
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 200, message: '操作成功', data: payload })
      })
    }
  )

  return calls
}

/** 断言某条 AI 接口被命中过（证明前端确实发起了该 AI 调用） */
export function aiCallCount(calls, path) {
  return calls[path] || 0
}

/** 今日日期（部分 AI 解析断言需要） */
export { today }