import { computed, reactive, ref } from 'vue'
import { aiChatStream } from '@/api/ai'
import { renderMarkdown } from '@/utils/markdown'

/** 多轮上下文上限：与后端 normalizeHistory 的截断口径一致（最近 10 条） */
const HISTORY_LIMIT = 10
/** 会话标题取自首个提问的前 N 个字符 */
const TITLE_LIMIT = 18

/** AI 双层开关都关闭时，把「未启用」与「限流/上游降级」区分开（AGENTS 4.4 标注口径） */
const DISABLED_TEXT = 'AI 服务未启用，当前为纯预约系统模式'

function createId() {
  return `c-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

/**
 * AI 对话状态机（页面与抽屉共用同一套契约）：
 * - 会话仅存于内存（刷新即清），不做服务端持久化；
 * - 流式回调 onThinking/onReasoning/onMeta/onDelta/onReset/onDone/onError 与会话气泡解耦；
 * - 前端只负责展示真实执行轨迹与计时，不伪造步骤或推理文本。
 */
export function useAiChat() {
  const conversations = ref([])
  const activeId = ref('')
  const input = ref('')
  const sending = ref(false)
  /** 只播报最终回答，避免流式 delta 让读屏器逐字重复 */
  const liveStatus = ref('')
  /** 最近一次回答的降级原因（后端 message，非空 = 本地 FAQ 规则回答） */
  const degradedNotice = ref('')
  /** 最近一次回答时 AI 双层开关处于关闭态（不套降级样式） */
  const offNotice = ref('')

  let controller = null
  const renderFrames = new WeakMap()

  const activeConversation = computed(
    () => conversations.value.find((item) => item.id === activeId.value) || null
  )
  const messages = computed(() => (activeConversation.value ? activeConversation.value.messages : []))
  const hasMessages = computed(() => messages.value.length > 0)

  /** 会话列表时间线：最后一条消息时间，无消息时用创建时间 */
  function lastActivity(conversation) {
    const last = conversation.messages[conversation.messages.length - 1]
    return last ? last.at : conversation.createdAt
  }

  function createConversation() {
    const conversation = {
      id: createId(),
      title: '新对话',
      renamed: false,
      messages: [],
      createdAt: Date.now()
    }
    conversations.value.unshift(conversation)
    activeId.value = conversation.id
    return conversation
  }

  function ensureConversation() {
    if (activeConversation.value) {
      return activeConversation.value
    }
    return createConversation()
  }

  function newConversation() {
    stop()
    input.value = ''
    offNotice.value = ''
    degradedNotice.value = ''
    liveStatus.value = ''
    createConversation()
  }

  function switchConversation(id) {
    if (id === activeId.value) {
      return
    }
    stop()
    input.value = ''
    offNotice.value = ''
    degradedNotice.value = ''
    liveStatus.value = ''
    activeId.value = id
  }

  function removeConversation(id) {
    const index = conversations.value.findIndex((item) => item.id === id)
    if (index === -1) {
      return
    }
    if (id === activeId.value) {
      stop()
    }
    conversations.value.splice(index, 1)
    if (!conversations.value.length) {
      createConversation()
    } else if (!activeConversation.value) {
      activeId.value = conversations.value[Math.min(index, conversations.value.length - 1)].id
    }
  }

  /** 终态全量渲染：完成代码块/列表等块级语法闭合 */
  function renderFinal(message) {
    const frame = renderFrames.get(message)
    if (frame) {
      cancelAnimationFrame(frame)
      renderFrames.delete(message)
    }
    message.html = renderMarkdown(message.content)
  }

  /** 增量渲染按帧合并（rAF 防抖），不逐字符重排 */
  function scheduleRender(message) {
    const frame = renderFrames.get(message)
    if (frame) {
      cancelAnimationFrame(frame)
    }
    renderFrames.set(
      message,
      requestAnimationFrame(() => {
        renderFrames.delete(message)
        message.html = renderMarkdown(message.content)
      })
    )
  }

  /** 播报用纯文本与可见气泡同源：Markdown 渲染后取 textContent，读屏器不会念出符号 */
  function toPlainText(markdownText) {
    const holder = document.createElement('div')
    holder.innerHTML = renderMarkdown(markdownText || '')
    return (holder.textContent || '').replace(/\s+/g, ' ').trim()
  }

  /**
   * 发送一条提问。onFirstDelta 在首个正文片段到达时回调（页面用它强制贴底滚动）。
   * 返回 Promise，流结束或中断后 resolve。
   */
  async function send(question, { onFirstDelta, appendUser = true } = {}) {
    const text = (question || '').trim()
    if (!text || sending.value) {
      return
    }
    const conversation = ensureConversation()
    input.value = ''
    liveStatus.value = ''
    offNotice.value = ''
    degradedNotice.value = ''

    // 重新生成时不追加用户气泡，历史需排除当前这条提问，避免发给模型两份同样的问题
    const historySource = appendUser ? conversation.messages : conversation.messages.slice(0, -1)
    const history = historySource
      .filter((message) => message.content && message.content.trim())
      .slice(-HISTORY_LIMIT)
      .map((message) => ({
        role: message.role === 'user' ? 'user' : 'assistant',
        content: message.content
      }))

    if (!conversation.renamed) {
      conversation.title = text.length > TITLE_LIMIT ? `${text.slice(0, TITLE_LIMIT)}…` : text
      conversation.renamed = true
    }

    if (appendUser) {
      conversation.messages.push({ id: createId(), role: 'user', content: text, at: Date.now() })
    }

    // 必须用 reactive 包一层：流式回调通过局部变量原样改写状态，若保持裸对象
    // 会绕过响应式 set 陷阱，面板永远停在「正在思考」。
    const trace = reactive({
      state: 'thinking',
      steps: [],
      reasoning: '',
      summary: '',
      startedAt: performance.now(),
      elapsedMs: null
    })
    const bubble = reactive({
      id: createId(),
      role: 'ai',
      content: '',
      html: '',
      typing: true,
      thinking: trace,
      state: '',
      at: Date.now()
    })
    conversation.messages.push(bubble)
    liveStatus.value = ''

    sending.value = true
    const ctrl = new AbortController()
    controller = ctrl
    let degradedMessage = ''
    let disabledMessage = ''

    const appendDelta = (piece) => {
      if (bubble.thinking && bubble.thinking.state === 'thinking') {
        bubble.thinking.state = 'done'
        bubble.thinking.elapsedMs = performance.now() - bubble.thinking.startedAt
      }
      bubble.content += piece
      scheduleRender(bubble)
      if (onFirstDelta) {
        onFirstDelta()
      }
    }

    try {
      await aiChatStream(
        // 首轮不带 history 字段（字段缺省，与后端契约定)；后续轮次按最近 10 条回传
        history.length ? { question: text, history } : { question: text },
        {
          onThinking: (payload) => {
            if (!payload || !bubble.thinking) {
              return
            }
            const current = bubble.thinking
            if (payload.id === 'summary') {
              // 汇总事件给出「入口 → 首个 delta」的真实墙钟耗时，覆盖前端兜底读数
              current.summary = payload.label || ''
              if (typeof payload.elapsedMs === 'number') {
                current.elapsedMs = payload.elapsedMs
              }
              if (current.state === 'thinking') {
                current.state = current.summary === '已降级为本地规则' ? 'degraded' : 'done'
              }
              return
            }
            const step = current.steps.find((item) => item.id === payload.id)
            if (payload.status === 'running') {
              if (step) {
                step.label = payload.label || step.label
                step.status = 'running'
              } else {
                current.steps.push({
                  id: payload.id,
                  label: payload.label || '',
                  status: 'running',
                  elapsedMs: null
                })
              }
              return
            }
            const target =
              step ||
              current.steps[
                current.steps.push({
                  id: payload.id,
                  label: payload.label || '',
                  status: 'done',
                  elapsedMs: null
                }) - 1
              ]
            target.label = payload.label || target.label
            target.status = payload.status === 'failed' ? 'failed' : 'done'
            target.elapsedMs = typeof payload.elapsedMs === 'number' ? payload.elapsedMs : null
          },
          onReasoning: (piece) => {
            if (bubble.thinking && piece) {
              bubble.thinking.reasoning += piece
            }
          },
          onMeta: (meta) => {
            if (!meta) {
              return
            }
            if (meta.enabled === false) {
              // 关闭态 meta 之后只有 done，没有 delta：直接展示关闭文案并移除空思考面板
              disabledMessage = meta.message || DISABLED_TEXT
              bubble.content = disabledMessage
              bubble.thinking = null
              bubble.state = 'disabled'
              return
            }
            if (meta.degraded && meta.message) {
              degradedMessage = meta.message
            }
          },
          onDelta: appendDelta,
          onReset: ({ text: fallbackText, message }) => {
            // 上游半截回答失败后，后端用完整本地 FAQ 全量替换，不能把两种来源拼接
            if (bubble.thinking) {
              bubble.thinking.state = 'degraded'
              if (bubble.thinking.elapsedMs === null) {
                bubble.thinking.elapsedMs = performance.now() - bubble.thinking.startedAt
              }
            }
            bubble.state = 'degraded'
            bubble.content = fallbackText || '暂时无法回答，请稍后重试'
            degradedMessage = message || '上游响应中断，已切换为本地规则回答'
            scheduleRender(bubble)
            if (onFirstDelta) {
              onFirstDelta()
            }
          },
          onDone: (info) => {
            // 双保险：异常时序下 meta 未带出关闭态时，按 finishReason=disabled 补关闭文案
            if (info && info.finishReason === 'disabled' && !bubble.content) {
              disabledMessage = DISABLED_TEXT
              bubble.content = disabledMessage
              bubble.thinking = null
              bubble.state = 'disabled'
            }
          },
          onError: (message) => {
            if (bubble.thinking && bubble.thinking.state === 'thinking') {
              bubble.thinking.state = 'error'
              bubble.thinking.elapsedMs = performance.now() - bubble.thinking.startedAt
            }
            bubble.state = 'error'
            bubble.content = message || '抱歉，服务暂时不可用，请稍后再试'
          }
        },
        ctrl.signal
      )

      if (!bubble.content) {
        if (ctrl.signal.aborted) {
          // 用户主动中断且未收到任何内容：移除空 AI 气泡，仅保留提问
          const index = conversation.messages.indexOf(bubble)
          if (index !== -1) {
            conversation.messages.splice(index, 1)
          }
        } else {
          bubble.content = '抱歉，暂时无法回答，请稍后再试'
        }
      }
      degradedNotice.value = degradedMessage
      offNotice.value = disabledMessage
    } catch {
      if (!ctrl.signal.aborted && !bubble.content) {
        bubble.content = '抱歉，服务暂时不可用，请稍后再试'
      }
    } finally {
      // 主动停止/异常结束时仍未收到正文：思考面板收尾为「已停止」，不留「正在思考」的假象
      if (bubble.thinking && bubble.thinking.state === 'thinking') {
        bubble.thinking.state = ctrl.signal.aborted ? 'stopped' : 'done'
        bubble.thinking.elapsedMs = performance.now() - bubble.thinking.startedAt
      }
      if (ctrl.signal.aborted && bubble.content) {
        bubble.state = 'stopped'
      }
      renderFinal(bubble)
      bubble.typing = false
      liveStatus.value = toPlainText(bubble.content)
      sending.value = false
      controller = null
    }
  }

  /**
   * 重新生成：删掉最后一条回答，用同一提问重发（DeepSeek 的「重新生成」语义）。
   * 用户提问只保留一条，不重复追加气泡。
   */
  async function regenerate({ onFirstDelta } = {}) {
    if (sending.value) {
      return
    }
    const conversation = ensureConversation()
    const index = conversation.messages.map((message) => message.role).lastIndexOf('user')
    if (index === -1) {
      return
    }
    const question = conversation.messages[index].content
    conversation.messages.splice(index + 1)
    await send(question, { onFirstDelta, appendUser: false })
  }

  /** 停止生成：中断流式接收；已收到的片段原样保留，空气泡在 send() 收尾时移除 */
  function stop() {
    if (controller) {
      controller.abort()
    }
  }

  function dispose() {
    stop()
    controller = null
  }

  createConversation()

  return {
    conversations,
    activeId,
    activeConversation,
    messages,
    hasMessages,
    input,
    sending,
    liveStatus,
    degradedNotice,
    offNotice,
    lastActivity,
    send,
    regenerate,
    stop,
    newConversation,
    switchConversation,
    removeConversation,
    dispose
  }
}
