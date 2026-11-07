import request, { redirectToLogin } from '@/utils/request'
import { getToken, clearAuth } from '@/utils/auth'

/**
 * AI 能力模块接口封装（R7，需求文档 2.5 AI 能力模块 4 个只读接口）
 * 全部 POST、登录即可访问；返回 data.enabled=false 表示 AI 未启用（前端隐藏/禁用 AI 入口）
 * 超时说明：需求文档 1.5「AI 接口响应 ≤60s，模型实测可达数十秒」，
 * 故 4 个 AI 接口单独传 { timeout: 60000 } 覆盖全局 10s 超时，避免大模型真实响应被前端截断降级；普通接口保持全局 10s。
 * R12：chat 提供 SSE 流式；R13-5：recommend/parse-reservation/compliance-check 同步 SSE 结构化流式。
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
 * AI 接口通用 SSE POST（R12 沉淀 / R13-5 复用于 4 个流式端点）
 * axios 无法逐块读取响应体，故用原生 fetch + ReadableStream 手动解析 SSE 帧；
 * 鉴权头/401 处理与 axios 封装同口径（getToken + redirectToLogin），不允许行为分叉。
 *
 * @param {string} path 接口路径（不含 /api 前缀与 query，如 '/ai/chat'）
 * @param {object|null} body 请求体（推荐等无入参接口可传 null，不带请求体）
 * @param {Object.<string,Function>} handlers 以 SSE 事件名命名的回调表：
 *        meta({enabled,degraded,message}) / delta({text}) / phase({phase,text}) /
 *        result(结构化 VO，与非流式 data 同构) / done(无业务载荷) / error({message})；
 *        error 为终态事件（收到后不会再有后续事件，调用方自行复位发送态）。
 *        回调应只做组件状态更新，不要依赖组件仍挂载。
 * @param {AbortSignal} [signal] 抽屉关闭/组件卸载/停止生成/重复发送时中断
 */
export async function ssePost(path, body, handlers = {}, signal) {
  const controller = new AbortController()
  const timeoutMs = 60000
  let timeoutId = null
  let reader = null
  let responseReceived = false
  let timedOut = false
  let terminal = ''

  /** 成功/失败/取消共用唯一终态闸门；主动取消保持既有静默语义，其余终态最多回调一次 */
  const finish = (type, payload) => {
    if (terminal) return
    terminal = type
    if (type === 'done' && handlers.done) handlers.done(payload)
    if (type === 'error' && handlers.error) handlers.error(payload)
  }
  const fail = (message) => finish('error', { message })
  const finishAborted = () => {
    if (timedOut) {
      fail('请求超时，请稍后重试')
    } else {
      finish('abort')
    }
  }
  const onExternalAbort = () => controller.abort()
  if (signal) {
    if (signal.aborted) {
      onExternalAbort()
    } else {
      signal.addEventListener('abort', onExternalAbort, { once: true })
    }
  }

  // 包装终态事件；reset 等未来事件仍按事件名透传给调用方，不改变现有协议
  const streamHandlers = {
    ...handlers,
    done: data => finish('done', data),
    error: data => fail((data && data.message) || '服务暂时不可用，请稍后再试')
  }

  try {
    if (controller.signal.aborted) {
      finishAborted()
      return
    }
    // 绝对超时从建链前开始，覆盖响应头等待、错误体读取和整个读流过程
    timeoutId = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, timeoutMs)

    const token = getToken()
    const response = await fetch(`/api${path}?stream=true`, {
      method: 'POST',
      headers: {
        ...(body ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      // 无入参接口（如推荐）不带请求体，避免向后端发送无意义的空 JSON
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal
    })
    responseReceived = true
    if (controller.signal.aborted) {
      finishAborted()
      return
    }

    if (!response.ok) {
      if (response.status === 401) {
        clearAuth()
        redirectToLogin()
        fail('登录已过期，请重新登录')
        return
      }
      let message = '服务暂时不可用，请稍后再试'
      try {
        const errBody = await response.json()
        message = errBody.message || message
      } catch {
        // 非 JSON 错误体（如网关 HTML）：使用默认文案；取消/超时由外层统一收尾
      }
      if (controller.signal.aborted) {
        finishAborted()
      } else {
        fail(message)
      }
      return
    }

    if (!response.body) {
      fail('服务暂时不可用，请稍后再试')
      return
    }

    // 建链前的入参校验/全局异常可能返回 HTTP 200 + Result JSON，必须作为错误终态而非静默等待 EOF
    const contentType = response.headers.get('content-type') || ''
    if (contentType.indexOf('text/event-stream') === -1) {
      let message = '服务暂时不可用，请稍后再试'
      try {
        const result = await response.json()
        message = result.message || message
      } catch {
        // 非 JSON 响应体：使用默认文案；取消/超时由外层统一收尾
      }
      if (controller.signal.aborted) {
        finishAborted()
      } else {
        fail(message)
      }
      return
    }

    reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    while (!terminal) {
      const { done, value } = await reader.read()
      if (done) {
        // 兼容末帧缺少末尾空行；仍未收到 done/error 的 EOF 明确按失败处理，避免调用方永久等待
        buffer += decoder.decode().replace(/\r\n/g, '\n')
        if (buffer.trim()) dispatchFrame(buffer, streamHandlers)
        if (!terminal) fail('连接意外结束，请稍后重试')
        break
      }

      // SSE 事件以空行分隔；统一归一为 LF 后切帧（防御网关/代理改写 CRLF）
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n')
      let sep
      while (!terminal && (sep = buffer.indexOf('\n\n')) !== -1) {
        const rawFrame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        dispatchFrame(rawFrame, streamHandlers)
      }
    }
  } catch {
    if (controller.signal.aborted) {
      finishAborted()
    } else {
      fail(responseReceived ? '服务暂时不可用，请稍后再试' : '网络异常，请稍后重试')
    }
  } finally {
    if (timeoutId !== null) clearTimeout(timeoutId)
    if (signal) signal.removeEventListener('abort', onExternalAbort)
    if (reader) {
      // 释放连接不等待 Promise：异常流若让 cancel 永久 pending，也不能突破 60 秒绝对终态约束。
      try {
        const cancellation = reader.cancel()
        if (cancellation && typeof cancellation.catch === 'function') cancellation.catch(() => {})
      } catch {
        // 忽略释放异常，不覆盖已经确定的唯一终态
      }
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
  // 回调以事件名命名（meta/delta/phase/result/done/error）；未知事件丢弃
  const callback = handlers[eventName]
  if (callback) {
    callback(data)
  }
}

/**
 * 结构化流式端点通用封装（R13-5：recommend / parse-reservation / compliance-check）
 * 事件序列：meta → phase（0 或 1 个，缓存未命中时）→ result（与非流式 data 同构 VO）→ done。
 *
 * @param {string} path 接口路径（不含 /api 前缀）
 * @param {object|null} body 请求体
 * @param {{onMeta?:Function,onPhase?:Function,onResult?:Function,onDone?:Function,onError?:Function}} handlers
 *        onMeta({enabled,degraded,message}) / onPhase({phase,text}) /
 *        onResult(VO) / onDone() / onError(message)；error 为终态
 * @param {AbortSignal} [signal]
 */
export function aiStructuredStream(path, body, handlers = {}, signal) {
  const { onMeta, onPhase, onResult, onDone, onError } = handlers
  return ssePost(path, body, {
    meta: d => onMeta && onMeta(d),
    phase: d => onPhase && onPhase(d),
    // result 帧经后端 @JsonValue 展平，data 即 VO 本身
    result: vo => onResult && onResult(vo),
    done: () => onDone && onDone(),
    error: d => onError && onError(d.message || '服务暂时不可用，请稍后再试')
  }, signal)
}

/** 智能教室推荐 · SSE 结构化流式（无请求体；phase 仅缓存未命中时出现） */
export function aiRecommendStream(handlers, signal) {
  return aiStructuredStream('/ai/recommend', null, handlers, signal)
}

/** 自然语言预约解析 · SSE 结构化流式（无缓存，phase 必发） */
export function aiParseReservationStream(data, handlers, signal) {
  return aiStructuredStream('/ai/parse-reservation', data, handlers, signal)
}

/** 预约合规校验 · SSE 结构化流式（phase 仅缓存未命中时出现） */
export function aiComplianceCheckStream(data, handlers, signal) {
  return aiStructuredStream('/ai/compliance-check', data, handlers, signal)
}

/**
 * 预约智能问答 · SSE 流式（POST /api/ai/chat?stream=true，R12）
 *
 * @param {{question:string, history?:Array<{role:string,content:string}>}} data 请求体；
 *        history 为会话内历史（R13-3，按时间正序、不含当前问题），空数组/缺省退化为单轮
 * @param {{onMeta?:Function,onDelta?:Function,onReset?:Function,onDone?:Function,onError?:Function}} handlers 事件回调
 *        onMeta({enabled,degraded,message}) / onDelta(text) / onReset({text,message}) / onDone({finishReason}) / onError(message)
 * @param {AbortSignal} signal 抽屉关闭/组件卸载/停止生成/重复发送时中断
 */
export function aiChatStream({ question, history }, handlers, signal) {
  const { onMeta, onDelta, onReset, onDone, onError } = handlers || {}
  return ssePost('/ai/chat',
    // 空历史不带字段：后端缺省即单轮，请求体保持最小
    { question, history: history && history.length ? history : undefined },
    {
      meta: d => onMeta && onMeta(d),
      delta: d => onDelta && onDelta(d.text || ''),
      reset: d => onReset && onReset({ text: d.text || '', message: d.message || '' }),
      done: d => onDone && onDone(d),
      error: d => onError && onError(d.message || '服务暂时不可用，请稍后再试')
    },
    signal)
}
