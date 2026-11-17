/**
 * AI 对话历史本地持久化（浏览器 localStorage，按用户隔离）
 *
 * 只存「原始文本 + 思考轨迹数据」，不存渲染后的 HTML：
 * 恢复时重新走 renderMarkdown，既避免 XSS 面也不把 DOMPurify 结果当可信数据。
 * 任何读写异常都不向外抛：历史丢失可以接受，聊天不可用不可接受。
 */

const KEY_PREFIX = 'reservation_ai_conversations:'
const VERSION = 1

/** 容量上限：单用户本地只保留最近 40 个会话，单会话最多 60 条消息 */
const MAX_CONVERSATIONS = 40
const MAX_MESSAGES = 60
/** 单条正文与推理文本上限，防止 localStorage 配额被一次长回答吃满 */
const MAX_CONTENT = 12000
const MAX_REASONING = 4000
/** 序列化后超过该体积时按最近活动淘汰旧会话 */
const MAX_BYTES = 2 * 1024 * 1024

const ROLES = ['user', 'ai']
const TRACE_STATES = ['thinking', 'done', 'stopped', 'degraded', 'error']
const MESSAGE_STATES = ['', 'stopped', 'error', 'degraded', 'disabled']

function storageKey(userId) {
  return `${KEY_PREFIX}${userId || 'anon'}`
}

function clip(value, limit) {
  const text = typeof value === 'string' ? value : ''
  return text.length > limit ? `${text.slice(0, limit)}…` : text
}

function toTimestamp(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/** 数字字段归一：非法/缺失返回 null，避免方案卡渲染出 "undefined 人" */
function toNumber(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function sanitizeTrace(raw) {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  // 刷新时若某条回答还停在 thinking，一律收敛为「已停止」：恢复后不再补请求，
  // 也不能让面板永远转圈。
  const state = raw.state === 'thinking' ? 'stopped' : TRACE_STATES.includes(raw.state) ? raw.state : 'stopped'
  const steps = Array.isArray(raw.steps)
    ? raw.steps
        .filter((step) => step && typeof step === 'object')
        .slice(0, 20)
        .map((step) => ({
          id: typeof step.id === 'string' ? step.id : '',
          label: clip(step.label, 80),
          status: ['running', 'done', 'failed'].includes(step.status) ? step.status : 'done',
          elapsedMs: typeof step.elapsedMs === 'number' ? step.elapsedMs : null
        }))
    : []
  return {
    state,
    steps,
    reasoning: clip(raw.reasoning, MAX_REASONING),
    summary: clip(raw.summary, 60),
    startedAt: typeof raw.startedAt === 'number' ? raw.startedAt : 0,
    elapsedMs: typeof raw.elapsedMs === 'number' ? raw.elapsedMs : null
  }
}

function sanitizeMessage(raw) {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const role = ROLES.includes(raw.role) ? raw.role : null
  if (!role) {
    return null
  }
  const content = clip(raw.content, MAX_CONTENT)
  if (!content && raw.state !== 'error') {
    return null
  }
  return {
    id: typeof raw.id === 'string' ? raw.id : `m-${Math.random().toString(36).slice(2, 10)}`,
    role,
    content,
    html: '',
    typing: false,
    thinking: role === 'ai' ? sanitizeTrace(raw.thinking) : null,
    plan: role === 'ai' ? sanitizePlan(raw.plan) : null,
    state: MESSAGE_STATES.includes(raw.state) ? raw.state : '',
    at: toTimestamp(raw.at, Date.now())
  }
}

/**
 * R15-1 预约方案：只保留渲染所需字段，候选最多 10 条。
 * 方案只是展示数据（点「去预约」会重新打开确认弹窗并实时判冲突），
 * 因此字段全部做长度截断，避免历史膨胀与脏数据渲染。
 */
function sanitizePlan(raw) {
  if (!raw || typeof raw !== 'object') {
    return null
  }
  const candidates = Array.isArray(raw.candidates)
    ? raw.candidates
        .filter((item) => item && typeof item === 'object')
        .slice(0, 10)
        .map(toCandidate)
    : []
  const timeOptions = Array.isArray(raw.timeOptions)
    ? raw.timeOptions
        .filter((item) => item && typeof item === 'object')
        .slice(0, 3)
        .map((item) => ({
          startTime: clip(item.startTime, 10),
          endTime: clip(item.endTime, 10),
          availableCount: toNumber(item.availableCount) || 0,
          excludedCount: toNumber(item.excludedCount) || 0,
          candidates: Array.isArray(item.candidates)
            ? item.candidates
                .filter((candidate) => candidate && typeof candidate === 'object')
                .slice(0, 10)
                .map(toCandidate)
            : []
        }))
    : []
  return {
    enabled: raw.enabled !== false,
    date: clip(raw.date, 20),
    startTime: clip(raw.startTime, 10),
    endTime: clip(raw.endTime, 10),
    capacity: toNumber(raw.capacity),
    roomType: clip(raw.roomType, 10),
    purpose: clip(raw.purpose, 40),
    clarify: clip(raw.clarify, 120),
    moreAvailable: toNumber(raw.moreAvailable) || 0,
    excludedCount: toNumber(raw.excludedCount) || 0,
    endTimeGuessed: raw.endTimeGuessed === true,
    guessHours: toNumber(raw.guessHours) || 0,
    timeOptions,
    candidates
  }
}

/** 方案候选教室：字段截断 + 数值兜底（历史恢复时直接渲染） */
function toCandidate(item) {
  return {
    classroomId: toNumber(item.classroomId),
    name: clip(item.name, 40),
    building: clip(item.building, 20),
    roomNo: clip(item.roomNo, 20),
    type: toNumber(item.type),
    capacity: toNumber(item.capacity),
    reason: clip(item.reason, 80)
  }
}

function sanitizeConversation(raw) {
  if (!raw || typeof raw !== 'object' || typeof raw.id !== 'string') {
    return null
  }
  const messages = Array.isArray(raw.messages)
    ? raw.messages
        .map(sanitizeMessage)
        .filter(Boolean)
        .slice(-MAX_MESSAGES)
    : []
  if (!messages.length) {
    return null
  }
  const createdAt = toTimestamp(raw.createdAt, messages[0].at)
  return {
    id: raw.id,
    title: clip(raw.title, 40) || '新对话',
    renamed: raw.renamed === true,
    messages,
    createdAt
  }
}

/** 会话活动时间：最后一条消息时间，空会话用创建时间 */
function lastActivityOf(conversation) {
  const last = conversation.messages[conversation.messages.length - 1]
  return last ? last.at : conversation.createdAt
}

function sortByActivity(list) {
  return list.slice().sort((a, b) => lastActivityOf(b) - lastActivityOf(a))
}

function removeStorage(key) {
  try {
    window.localStorage.removeItem(key)
  } catch {
    // 隐私模式等场景下 localStorage 可能不可写，静默即可
  }
}

/**
 * 读取某个用户的会话历史。
 * 返回 `{ activeId: string, conversations: [] }`；无记录 / 数据损坏都返回空结构。
 */
export function loadAiConversations(userId) {
  const key = storageKey(userId)
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) {
      return { activeId: '', conversations: [] }
    }
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object' || parsed.version !== VERSION) {
      removeStorage(key)
      return { activeId: '', conversations: [] }
    }
    const conversations = sortByActivity(
      (Array.isArray(parsed.conversations) ? parsed.conversations : [])
        .map(sanitizeConversation)
        .filter(Boolean)
    ).slice(0, MAX_CONVERSATIONS)
    const activeId =
      typeof parsed.activeId === 'string' && conversations.some((item) => item.id === parsed.activeId)
        ? parsed.activeId
        : conversations[0]
          ? conversations[0].id
          : ''
    return { activeId, conversations }
  } catch {
    removeStorage(key)
    return { activeId: '', conversations: [] }
  }
}

/**
 * 写入某个用户的会话历史（已在外层做过防抖）。
 * 超配额时先淘汰最旧会话重试一次，仍失败就放弃持久化，不影响当前对话。
 */
export function saveAiConversations(userId, { activeId = '', conversations = [] } = {}) {
  const key = storageKey(userId)
  let list = sortByActivity(
    conversations.map(sanitizeConversation).filter(Boolean)
  )
  while (list.length > MAX_CONVERSATIONS) {
    list.pop()
  }
  if (!list.length) {
    removeStorage(key)
    return
  }
  const payload = () => JSON.stringify({ version: VERSION, activeId, conversations: list })
  try {
    const text = payload()
    if (text.length > MAX_BYTES) {
      list = list.slice(0, Math.max(1, Math.floor(list.length / 2)))
    }
    window.localStorage.setItem(key, text.length > MAX_BYTES ? payload() : text)
  } catch {
    try {
      // 配额告急：砍掉一半旧会话再试一次，保住最近几次对话
      list = list.slice(0, Math.max(1, Math.floor(list.length / 2)))
      window.localStorage.setItem(key, payload())
    } catch {
      // 依旧失败：放弃，聊天功能本身不受影响
    }
  }
}

/** 清除某个用户的会话历史 */
export function clearAiConversations(userId) {
  removeStorage(storageKey(userId))
}

/** 清除所有用户的会话历史（登出时调用，避免同机换号串数据） */
export function clearAllAiConversations() {
  try {
    const keys = []
    for (let i = 0; i < window.localStorage.length; i += 1) {
      const key = window.localStorage.key(i)
      if (key && key.startsWith(KEY_PREFIX)) {
        keys.push(key)
      }
    }
    keys.forEach(removeStorage)
  } catch {
    // 读取键列表失败时无可清理，忽略
  }
}
