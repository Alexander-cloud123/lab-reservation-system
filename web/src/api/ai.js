import request, { redirectToLogin } from '@/utils/request'
import { getToken, clearAuth } from '@/utils/auth'

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

/** 预约合规校验：purpose → { compliant, reason }（辅助审核，只提示不改状态） */
export function aiComplianceCheck(data) {
  return request.post('/ai/compliance-check', data, { timeout: 60000 })
}

/**
 * 预约智能问答 · SSE 流式（POST /api/ai/chat?stream=true，R12）
 * axios 无法逐块读取响应体，故用原生 fetch + ReadableStream 手动解析 SSE 帧；
 * 鉴权头/401 处理与 axios 封装同口径（getToken + redirectToLogin），不允许行为分叉。
 *
 * @param {{question:string}} data 请求体
 * @param {{onMeta?:Function,onDelta?:Function,onDone?:Function,onError?:Function}} handlers 事件回调
 *        onMeta({enabled,degraded,message}) / onDelta(text) / onDone({finishReason}) / onError(message)
 *        error 为终态事件（收到后不会再有 delta/done，调用方自行复位发送态）；
 *        回调应只做组件状态更新，不要依赖组件仍挂载。
 * @param {AbortSignal} signal 抽屉关闭/组件卸载/重复发送时中断
 */
export async function aiChatStream({ question }, handlers, signal) {
  const { onMeta, onDelta, onDone, onError } = handlers || {}

  let response
  try {
    response = await fetch('/api/ai/chat?stream=true', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {})
      },
      body: JSON.stringify({ question }),
      signal
    })
  } catch (e) {
    // 主动中断静默；其余网络错误交调用方显示兜底文案
    if (e.name !== 'AbortError') {
      onError && onError('网络异常，请稍后重试')
    }
    return
  }

  if (!response.ok) {
    if (response.status === 401) {
      clearAuth()
      redirectToLogin()
      return
    }
    let message = '服务暂时不可用，请稍后再试'
    try {
      const errBody = await response.json()
      message = errBody.message || message
    } catch {
      // 非 JSON 错误体（如网关 HTML）：使用默认文案
    }
    onError && onError(message)
    return
  }

  if (!response.body) {
    onError && onError('服务暂时不可用，请稍后再试')
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const pump = async () => {
    const { done, value } = await reader.read()
    if (done) {
      return
    }
    // SSE 事件以空行分隔；统一归一为 LF 后按 \n\n 切帧（后端 SseEmitter 只发 LF，归一仅为防御网关/代理改写）
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
    let sep
    while ((sep = buffer.indexOf('\n\n')) !== -1) {
      const rawFrame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      dispatchFrame(rawFrame, { onMeta, onDelta, onDone, onError })
    }
    return pump()
  }

  try {
    await pump()
  } catch (e) {
    if (e.name !== 'AbortError') {
      onError && onError('服务暂时不可用，请稍后再试')
    }
  } finally {
    // 出错/中断后主动释放连接；正常读完 cancel 已完成流无副作用，自身异常吞掉
    try {
      await reader.cancel()
    } catch {
      // 忽略释放异常
    }
  }
}

/** 解析单个 SSE 帧（event 行 + 一个或多个 data 行）并分发回调；脏帧直接丢弃 */
function dispatchFrame(rawFrame, handlers) {
  let eventName = 'message'
  const dataLines = []
  for (let rawLine of rawFrame.split('\n')) {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line.startsWith('event:')) {
      // 注意 'event:' 为 6 字符、'data:' 为 5 字符，两者前缀长度不同，slice 下标不可混用
      // slice(6)：真实帧 "event:meta" → "meta"；带空格帧 "event: meta" → " meta"，trim 兜底
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      // slice(5)：真实 SseEmitter 帧 "data:{...}" 直接得 JSON；"data: {...}" 由 replace 去掉一个可选空格
      dataLines.push(line.slice(5).replace(/^ /, ''))
    }
  }
  if (!dataLines.length) {
    return
  }
  let data
  try {
    data = JSON.parse(dataLines.join('\n'))
  } catch {
    return
  }
  if (eventName === 'meta') {
    handlers.onMeta && handlers.onMeta(data)
  } else if (eventName === 'delta') {
    handlers.onDelta && handlers.onDelta(data.text || '')
  } else if (eventName === 'done') {
    handlers.onDone && handlers.onDone(data)
  } else if (eventName === 'error') {
    handlers.onError && handlers.onError(data.message || '服务暂时不可用，请稍后再试')
  }
}
