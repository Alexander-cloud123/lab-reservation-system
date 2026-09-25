import request from '@/utils/request'

/**
 * AI 能力模块接口封装（R7，需求文档 2.5 AI 能力模块 4 个只读接口）
 * 全部 POST、登录即可访问；返回 data.enabled=false 表示 AI 未启用（前端隐藏/禁用 AI 入口）
 * 超时说明：需求文档 1.5「AI 接口响应 ≤60s，模型实测可达数十秒」，
 * 故 4 个 AI 接口单独传 { timeout: 60000 } 覆盖全局 10s 超时，避免大模型真实响应被前端截断降级；普通接口保持全局 10s。
 */

/** 智能教室推荐：基于当前登录用户历史习惯返回 Top3 教室 + 推荐理由（userId 由后端从登录态取，不信任请求体，无需传参） */
export function aiRecommend(data) {
  return request.post('/ai/recommend', data, { timeout: 60000 })
}

/** 自然语言预约解析：text → 结构化预约参数（识别失败返回 error） */
export function aiParseReservation(data) {
  return request.post('/ai/parse-reservation', data, { timeout: 60000 })
}

/** 预约智能问答：question → 场景限定回答 */
export function aiChat(data) {
  return request.post('/ai/chat', data, { timeout: 60000 })
}

/** 预约合规校验：purpose → { compliant, reason }（辅助审核，只提示不改状态） */
export function aiComplianceCheck(data) {
  return request.post('/ai/compliance-check', data, { timeout: 60000 })
}
