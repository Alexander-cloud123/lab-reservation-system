import request from '@/utils/request'

/**
 * AI 能力模块接口封装（R7，需求文档 2.5 AI 能力模块 4 个只读接口）
 * 全部 POST、登录即可访问；返回 data.enabled=false 表示 AI 未启用（前端隐藏/禁用 AI 入口）
 */

/** 智能教室推荐：userId → Top3 教室 + 推荐理由 */
export function aiRecommend(data) {
  return request.post('/ai/recommend', data)
}

/** 自然语言预约解析：text → 结构化预约参数（识别失败返回 error） */
export function aiParseReservation(data) {
  return request.post('/ai/parse-reservation', data)
}

/** 预约智能问答：question → 场景限定回答 */
export function aiChat(data) {
  return request.post('/ai/chat', data)
}

/** 预约合规校验：purpose → { compliant, reason }（辅助审核，只提示不改状态） */
export function aiComplianceCheck(data) {
  return request.post('/ai/compliance-check', data)
}
