<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { probeAiRecommend } from '@/utils/aiProbe'
import { getStoredUser } from '@/utils/auth'
import { getClassroomDetail } from '@/api/classroom'
import { useAiChat } from '@/composables/useAiChat'
import AiChatMessage from '@/components/ai/AiChatMessage.vue'
import AiQuickReserve from '@/components/ai/AiQuickReserve.vue'
import AiChatComposer from '@/components/ai/AiChatComposer.vue'

const router = useRouter()
const route = useRoute()

const chat = useAiChat()
const { conversations, activeConversation, activeId, messages, hasMessages, input, sending, liveStatus, offNotice, degradedNotice } = chat

// 历史按用户隔离：同一台电脑换账号登录时各自读自己的记录
chat.init(getStoredUser() ? getStoredUser().id : 'anon')

const scrollRef = ref(null)
const composerRef = ref(null)
/** 侧栏搜索：只过滤标题，不查消息正文（会话多时也要保持瞬时响应） */
const searchQuery = ref('')
/** 上翻到历史深处时出现「回到底部」，避免用户找不到正在生成的回答 */
const showJump = ref(false)
/** 用户主动上翻读历史时不打扰：只有贴着底部才自动跟随新内容 */
let stickToBottom = true
let followFrame = 0

const title = computed(() => (activeConversation.value ? activeConversation.value.title : '新对话'))
/** 只有最后一条回答可重新生成：避免误替换历史回答 */
function canRegenerateMessage(message) {
  return !sending.value && message.role === 'ai' && message.id === lastMessageId.value
}

const quickReserveRef = ref(null)

/**
 * R15-3 页面上下文：从教室详情页的悬浮球进入时带 roomId，让 AI 听懂「这间教室」。
 * 只存在内存里，不写进会话历史——恢复历史后「这间」不应再指向在此之前浏览的教室。
 */
const pageContext = ref(null)
const contextRoomName = ref('')

async function loadPageContext(roomId) {
  const id = Number(roomId)
  if (!Number.isInteger(id) || id <= 0) {
    pageContext.value = null
    contextRoomName.value = ''
    return
  }
  pageContext.value = { classroomId: id }
  try {
    const room = await getClassroomDetail(id)
    contextRoomName.value = room && room.name ? room.name : ''
  } catch {
    // 教室查不到（已删除/已停用）就不显示名字，上下文交给后端二次校验后静默忽略
    contextRoomName.value = ''
  }
}

function removeContext() {
  pageContext.value = null
  contextRoomName.value = ''
}

const contextLabel = computed(() => {
  if (!pageContext.value) {
    return ''
  }
  return contextRoomName.value || `教室 #${pageContext.value.classroomId}`
})

watch(
  () => route.query.roomId,
  (roomId) => loadPageContext(roomId),
  { immediate: true }
)

/** 方案卡「去预约」：把方案与候选教室交给既有确认弹窗（第 3 步预填 + 冲突检测），不代提交 */
function onReserve(candidate, endTime, message) {
  if (!candidate || !quickReserveRef.value) {
    return
  }
  const plan = message && message.plan ? message.plan : {}
  // R15-2：结束时间由用户在方案卡里选的时段决定，覆盖 plan 里回填的默认档
  quickReserveRef.value.openWithPlan(endTime ? { ...plan, endTime } : plan, candidate)
}

const lastMessageId = computed(() => (messages.value.length ? messages.value[messages.value.length - 1].id : ''))
const aiOff = computed(() => offNotice.value)

/** 有内容的会话才进侧栏：空「新对话」不占列表，与 DeepSeek 一致 */
const sideConversations = computed(() => {
  const keyword = searchQuery.value.trim().toLowerCase()
  return conversations.value
    .filter((conversation) => conversation.messages.length > 0)
    .filter((conversation) => !keyword || conversation.title.toLowerCase().includes(keyword))
    .slice()
    .sort((a, b) => chat.lastActivity(b) - chat.lastActivity(a))
})

/** 按「今天 / 昨天 / 更早」分组，用最后一条消息的时间判断 */
const groupedConversations = computed(() => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000)
  const groups = [
    { key: 'today', label: '今天', items: [] },
    { key: 'yesterday', label: '昨天', items: [] },
    { key: 'earlier', label: '更早', items: [] }
  ]
  sideConversations.value.forEach((conversation) => {
    const at = chat.lastActivity(conversation)
    const day = new Date(at)
    day.setHours(0, 0, 0, 0)
    if (day.getTime() === today.getTime()) {
      groups[0].items.push({ conversation, time: formatClock(at) })
    } else if (day.getTime() === yesterday.getTime()) {
      groups[1].items.push({ conversation, time: '昨天' })
    } else {
      groups[2].items.push({ conversation, time: formatDate(at) })
    }
  })
  return groups.filter((group) => group.items.length)
})

function formatClock(value) {
  const date = new Date(value)
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

function formatDate(value) {
  const date = new Date(value)
  return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

const baseSuggestions = [
  { icon: 'spark', text: '明天下午有哪些空教室？' },
  { icon: 'calendar', text: '怎么取消一条已通过的预约？' },
  { icon: 'shield', text: '预约冲突是怎么判定的？' },
  { icon: 'clock', text: '管理员审核一般要多久？' },
  { icon: 'spark', text: '帮我约明天的机房，30 人左右' }
]

/** 带着教室上下文进来时，把「这间教室」放进建议里（仍由用户点击，不自动提问） */
const suggestions = computed(() => {
  if (!pageContext.value) {
    return baseSuggestions
  }
  return [{ icon: 'spark', text: '这间教室明天下午有空吗？' }, ...baseSuggestions]
})

async function onDeleteConversation(conversation) {
  try {
    await ElMessageBox.confirm(`删除后无法恢复，确定删除「${conversation.title}」？`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    // 用户取消，不做任何改动
    return
  }
  chat.removeConversation(conversation.id)
}


/** 只把视口钉到底部，不改变「是否跟随」的用户意图（滚动事件负责判定） */
function pinToBottom() {
  const el = scrollRef.value
  if (!el) {
    return
  }
  // 瞬时贴底：smooth 滚动会让首帧滚动事件把 stickToBottom 判成 false，跟随随即中断
  const behavior = el.style.scrollBehavior
  el.style.scrollBehavior = 'auto'
  el.scrollTop = el.scrollHeight
  el.style.scrollBehavior = behavior
  showJump.value = false
}

/** 用户主动回到底部（发送新问题、点击「回到底部」）：恢复跟随 */
function scrollToBottom() {
  pinToBottom()
  stickToBottom = true
}

function onScroll() {
  const el = scrollRef.value
  if (!el) {
    return
  }
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  stickToBottom = distance < 40
  showJump.value = distance > 160
}

/** 流式输出期间用 rAF 跟随，避免每个 delta 都同步触发布局抖动 */
function followLoop() {
  cancelAnimationFrame(followFrame)
  const step = () => {
    if (!sending.value) {
      followFrame = 0
      return
    }
    if (stickToBottom) {
      pinToBottom()
    } else {
      // 用户上翻读历史时不抢滚动条，只提示可回到底部
      showJump.value = true
    }
    followFrame = requestAnimationFrame(step)
  }
  followFrame = requestAnimationFrame(step)
}

watch(
  () => messages.value.length,
  () => nextTick(scrollToBottom)
)

// 切换/恢复历史会话后直接落在最新一条，避免用户一进来看到很早的消息
watch(activeId, () => {
  stickToBottom = true
  nextTick(scrollToBottom)
})

watch(sending, (value) => {
  if (value) {
    followLoop()
    return
  }
  cancelAnimationFrame(followFrame)
  followFrame = 0
  // 正文在流式结束时才完成最终渲染：贴底状态下补一次，避免停在半途
  nextTick(() => {
    if (stickToBottom) {
      pinToBottom()
    }
    onScroll()
  })
})

async function ask(question) {
  if (sending.value) {
    return
  }
  await chat.send(question, { onFirstDelta: scrollToBottom, context: pageContext.value })
}

function onSend() {
  const question = input.value
  if (!question.trim()) {
    return
  }
  ask(question)
  nextTick(() => composerRef.value && composerRef.value.focus())
}

/** 重新生成：删除最后一条回答并用同一提问重发，输入框保持聚焦 */
function onRegenerate() {
  chat.regenerate({ onFirstDelta: scrollToBottom, context: pageContext.value })
  nextTick(() => composerRef.value && composerRef.value.focus())
}

function onNewConversation() {
  chat.newConversation()
  scrollToBottom()
}

function onKeydown(event) {
  // Esc 与主流对话产品一致：流式中按 Esc 立即停止，不误清输入
  if (event.key === 'Escape' && sending.value) {
    event.preventDefault()
    chat.stop()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  nextTick(() => composerRef.value && composerRef.value.focus())
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  cancelAnimationFrame(followFrame)
  chat.dispose()
})

// 直接进入页面时同步一次开关状态：页面与悬浮球共用同一份探测结果
probeAiRecommend().catch(() => {})
</script>

<template>
  <section class="ai-chat-page ai-assistant">
    <p class="sr-only chat-live" role="status" aria-live="polite">{{ liveStatus }}</p>
    <aside class="ai-chat-sidebar" aria-label="会话列表">
      <div class="sidebar-head">
        <span class="sidebar-mark" aria-hidden="true">AI</span>

        <div class="sidebar-title">
          <strong>AI 预约助手</strong>
          <span>实验室预约系统</span>
        </div>
      </div>

      <button type="button" class="ai-chat-new" @click="onNewConversation">
        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <path d="M12 5v14M5 12h14" stroke="currentColor" stroke-width="1.8" fill="none" />
        </svg>
        <span>新对话</span>
      </button>

      <label class="ai-chat-search">
        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <circle cx="11" cy="11" r="6" stroke="currentColor" stroke-width="1.6" fill="none" />
          <path d="m15.6 15.6 3.4 3.4" stroke="currentColor" stroke-width="1.6" fill="none" />
        </svg>
        <input
          v-model="searchQuery"
          type="search"
          class="search-input"
          placeholder="搜索历史会话"
          aria-label="搜索历史会话"
        />
      </label>

      <nav class="ai-chat-convs" aria-label="历史会话">
        <section v-for="group in groupedConversations" :key="group.key" class="conv-group">
          <h2 class="conv-group-title">{{ group.label }}</h2>
          <ul class="conv-list">
            <li
              v-for="item in group.items"
              :key="item.conversation.id"
              class="conv-row ai-chat-conv"
              :class="{ 'is-active': item.conversation.id === activeId }"
            >
              <button
                type="button"
                class="conv-main"
                @click="chat.switchConversation(item.conversation.id)"
              >
                <span class="conv-title">{{ item.conversation.title }}</span>
              </button>
              <span class="conv-time" aria-hidden="true">{{ item.time }}</span>
              <button
                type="button"
                class="conv-delete conv-remove"
                :aria-label="`删除会话 ${item.conversation.title}`"
                @click.stop="onDeleteConversation(item.conversation)"
              >
                ×
              </button>
            </li>
          </ul>
        </section>
        <p v-if="!sideConversations.length" class="conv-empty">
          {{ searchQuery ? '没有匹配的会话' : '还没有历史会话，先提一个问题吧' }}
        </p>
      </nav>

      <p class="sidebar-foot">仅解答预约相关问题，只读不写</p>
    </aside>

    <main class="ai-chat-main">
      <header class="ai-chat-topbar">
        <button
          type="button"
          class="ai-chat-back"
          aria-label="返回教室列表"
          @click="router.push('/student/home')"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
            <path d="M14.5 5.5 8 12l6.5 6.5" stroke="currentColor" stroke-width="1.6" fill="none" />
          </svg>
        </button>
        <span class="topbar-title">{{ title }}</span>
        <span class="topbar-state">{{ sending ? '回答中…' : '就绪' }}</span>
      </header>

      <div v-if="contextLabel" class="ai-context-bar">
        <span class="ai-context-chip">
          <span class="context-mark" aria-hidden="true">◎</span>
          <span class="context-text">正在查看：{{ contextLabel }}</span>
          <button
            type="button"
            class="context-remove"
            aria-label="移除当前教室上下文"
            @click="removeContext"
          >
            ×
          </button>
        </span>
      </div>

      <p v-if="aiOff" class="ai-chat-banner">{{ aiOff }}</p>

      <div ref="scrollRef" class="ai-chat-scroll" @scroll.passive="onScroll">
        <div class="ai-chat-stream chat-body" :aria-busy="sending">
          <div v-if="!hasMessages" class="ai-chat-welcome">
            <span class="welcome-mark" aria-hidden="true">AI 助手</span>

            <h1>你好，我是 AI 预约助手</h1>
            <p>可以帮你查空教室、解释预约与审核规则，也会把思考过程摊开给你看。</p>
            <ul class="ai-chat-suggestions">
              <li v-for="item in suggestions" :key="item.text">
                <button type="button" class="suggest-item" @click="ask(item.text)">
                  <span class="suggest-icon" aria-hidden="true">
                    <svg v-if="item.icon === 'spark'" viewBox="0 0 24 24" width="16" height="16">
                      <path
                        d="M12 4.5 13.6 9l4.4 1.6-4.4 1.6L12 16.7l-1.6-4.5L6 10.6 10.4 9 12 4.5Z"
                        stroke="currentColor"
                        stroke-width="1.4"
                        fill="none"
                      />
                    </svg>
                    <svg v-else-if="item.icon === 'calendar'" viewBox="0 0 24 24" width="16" height="16">
                      <rect x="4.5" y="6" width="15" height="13" rx="2" stroke="currentColor" stroke-width="1.4" fill="none" />
                      <path d="M4.5 10h15M9 4.5v3M15 4.5v3" stroke="currentColor" stroke-width="1.4" fill="none" />
                    </svg>
                    <svg v-else-if="item.icon === 'shield'" viewBox="0 0 24 24" width="16" height="16">
                      <path d="M12 4.5 18 7v5c0 3.6-2.5 6.4-6 7.5-3.5-1.1-6-3.9-6-7.5V7l6-2.5Z" stroke="currentColor" stroke-width="1.4" fill="none" />
                    </svg>
                    <svg v-else viewBox="0 0 24 24" width="16" height="16">
                      <circle cx="12" cy="12" r="7.5" stroke="currentColor" stroke-width="1.4" fill="none" />
                      <path d="M12 8v4.4l2.6 1.6" stroke="currentColor" stroke-width="1.4" fill="none" />
                    </svg>
                  </span>
                  <span class="suggest-text">{{ item.text }}</span>
                          </button>
              </li>
            </ul>
          </div>

          <AiChatMessage
            v-for="message in messages"
            :key="message.id"
            :message="message"
            :streaming="sending && message.id === lastMessageId"
            :can-regenerate="canRegenerateMessage(message)"
            @regenerate="onRegenerate"
            @reserve="(candidate, endTime) => onReserve(candidate, endTime, message)"
          />

          <p v-if="aiOff && hasMessages" class="ai-chat-banner is-inline">{{ aiOff }}</p>
        </div>
      </div>

      <button
        v-if="showJump"
        type="button"
        class="ai-chat-jump"
        @click="scrollToBottom"
      >
        回到底部
      </button>

      <div class="ai-chat-dock">
        <p
          class="chat-tip"
          :class="{ 'is-warning': degradedNotice, 'is-info': aiOff }"
        >
          {{ degradedNotice || aiOff || 'AI 生成，仅供参考；仅解答预约相关问题' }}
        </p>
        <AiChatComposer
          ref="composerRef"
          v-model="input"
          :sending="sending"
          @send="onSend"
          @stop="chat.stop"
        />
      </div>

      <!-- R15-1：方案卡的「去预约」复用既有确认弹窗（隐藏自带触发按钮） -->
      <AiQuickReserve ref="quickReserveRef" :ai-enabled="false" />
    </main>
  </section>
</template>

<style scoped>
/* ---------- AI 对话页：左侧会话栏 + 白底正文（墨蓝门牌体系） ---------- */
.ai-chat-page {
  display: flex;
  height: calc(100vh - 60px);
  background: var(--page);
  overflow: hidden;
}

/* 左侧会话栏：白面，低对比度，不抢正文注意力 */
.ai-chat-sidebar {
  display: flex;
  flex-direction: column;
  flex: 0 0 264px;
  width: 264px;
  padding: var(--space-md) var(--space-sm) var(--space-sm);
  border-right: 1px solid var(--line);
  background: var(--surface);
}

.sidebar-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-sm) var(--space-md);
}

/* 门牌编号是这套界面唯一的记忆点：这里用 AI 作为品牌标记 */
.sidebar-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 30px;
  height: 26px;
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--brand);
  color: var(--surface);
  font-size: var(--text-sm);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.sidebar-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.3;
}

.sidebar-title strong {
  color: var(--ink);
  font-size: var(--text-base);
  font-weight: 600;
}

.sidebar-title span {
  color: var(--ink-soft);
  font-size: var(--text-xs);
}

.ai-chat-new {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  margin: 0 2px var(--space-md);
  padding: 0 var(--space-sm);
  border: 1px solid var(--brand);
  border-radius: var(--radius-control);
  background: var(--brand);
  color: var(--surface);
  font: inherit;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.16s ease;
}

.ai-chat-new:hover {
  background: var(--brand-primary-dark, var(--brand));
  border-color: var(--brand-primary-dark, var(--brand));
}

.ai-chat-new:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.ai-chat-new svg {
  opacity: 0.9;
}

.ai-chat-search {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  margin: 0 2px var(--space-sm);
  padding: 0 var(--space-sm);
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface);
  color: var(--ink-soft);
}

.ai-chat-search:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
  color: var(--ink);
}

.search-input {
  width: 100%;
  border: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: var(--text-sm);
  outline: none;
}

.search-input::placeholder {
  color: var(--ink-soft);
}

.search-input::-webkit-search-cancel-button {
  display: none;
}

.ai-chat-convs {
  flex: 1;
  overflow-y: auto;
  padding-top: var(--space-xs);
}

.conv-group {
  margin: 0 0 var(--space-sm);
}

.conv-group-title {
  margin: 0;
  padding: var(--space-xs) var(--space-sm);
  color: var(--ink-soft);
  font-size: var(--text-xs);
  font-weight: 500;
  line-height: 1.5;
}

.conv-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.conv-row {
  position: relative;
  display: flex;
  align-items: center;
  height: 36px;
  border-radius: var(--radius-sm);
}

.conv-row:hover {
  background: var(--page);
}

.conv-row.is-active {
  background: var(--brand-soft);
}

.conv-row.is-active .conv-title {
  color: var(--brand);
  font-weight: 600;
}

.conv-main {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0 var(--space-sm);
  border: 0;
  background: none;
  color: var(--ink-soft);
  font: inherit;
  font-size: var(--text-sm);
  text-align: left;
  cursor: pointer;
}

.conv-main:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: -2px;
  border-radius: var(--radius-sm);
}

.conv-title {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  flex: 0 0 auto;
  padding-right: 6px;
  color: var(--ink-soft);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}

/* 时间与删除按钮共用同一块位置：悬停时淡出时间、淡入删除，避免行宽跳动 */
.conv-delete {
  position: absolute;
  right: 4px;
  top: 50%;
  transform: translateY(-50%);
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-soft);
  font: inherit;
  font-size: var(--text-base);
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
}

.conv-row:hover .conv-delete,
.conv-row.is-active .conv-delete,
.conv-delete:focus-visible {
  opacity: 1;
  pointer-events: auto;
}

.conv-delete:hover {
  color: var(--danger);
}

.conv-delete:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 1px;
}

.conv-empty {
  margin: 0;
  padding: var(--space-md) var(--space-sm);
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.6;
}

.sidebar-foot {
  margin: var(--space-sm) 0 0;
  padding: var(--space-sm) var(--space-sm) 0;
  border-top: 1px solid var(--line);
  color: var(--ink-soft);
  font-size: var(--text-xs);
  line-height: 1.5;
}

/* ---------- 正文 ---------- */
.ai-chat-main {
  position: relative;
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  background: var(--surface);
}

.ai-chat-topbar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  height: 56px;
  padding: 0 var(--space-xl);
  border-bottom: 1px solid var(--line);
}

.ai-chat-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface);
  color: var(--ink-soft);
  cursor: pointer;
}

.ai-chat-back:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.ai-chat-back:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.topbar-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink);
  font-size: var(--text-base);
  font-weight: 600;
}

.topbar-state {
  flex: 0 0 auto;
  padding: 0 10px;
  height: 24px;
  line-height: 24px;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--text-xs);
  font-weight: 500;
}

.ai-context-bar {
  padding: var(--space-sm) var(--space-xl) 0;
}

.ai-context-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 var(--space-sm);
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--text-xs);
}

.context-mark {
  opacity: 0.7;
}

.context-text {
  font-weight: 500;
}

.context-remove {
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: var(--radius-pill);
  background: transparent;
  color: inherit;
  font: inherit;
  line-height: 1;
  cursor: pointer;
}

.context-remove:hover {
  background: var(--surface);
}

.context-remove:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 1px;
}

.ai-chat-banner {
  margin: var(--space-sm) var(--space-xl) 0;
  padding: 10px var(--space-md);
  border: 1px solid var(--warning);
  border-radius: var(--radius-control);
  background: var(--warning-soft, var(--page));
  color: var(--warning);
  font-size: var(--text-sm);
  line-height: 1.6;
}

.ai-chat-banner.is-inline {
  margin: var(--space-md) 0 0;
}

.ai-chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: var(--space-xl) var(--space-xl) var(--space-lg);
  scroll-behavior: smooth;
}

.ai-chat-stream {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
}

.ai-chat-welcome {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding-top: 6vh;
}

.welcome-mark {
  display: inline-flex;
  align-items: center;
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--brand);
  border-radius: var(--radius-sm);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--text-sm);
  font-weight: 600;
}

.ai-chat-welcome h1 {
  margin: var(--space-lg) 0 var(--space-sm);
  color: var(--ink);
  font-size: var(--text-xl);
  font-weight: 600;
  letter-spacing: -0.01em;
}

.ai-chat-welcome p {
  margin: 0;
  max-width: 520px;
  color: var(--ink-soft);
  font-size: var(--text-base);
  line-height: 1.7;
}

.ai-chat-suggestions {
  display: grid;
  width: 100%;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-sm);
  margin: var(--space-xl) 0 0;
  padding: 0;
  list-style: none;
}

.suggest-item {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  width: 100%;
  height: 56px;
  padding: 0 var(--space-md);
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  background: var(--surface);
  color: var(--ink);
  font: inherit;
  font-size: var(--text-sm);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease;
}

.suggest-item:hover {
  border-color: var(--brand);
  box-shadow: var(--shadow-raised);
}

.suggest-item:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.suggest-icon {
  display: inline-flex;
  flex: 0 0 auto;
  color: var(--brand);
}

.suggest-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai-chat-jump {
  position: absolute;
  right: var(--space-xl);
  bottom: 132px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 32px;
  padding: 0 var(--space-md);
  border: 1px solid var(--line);
  border-radius: var(--radius-pill);
  background: var(--surface);
  box-shadow: var(--shadow-float);
  color: var(--ink-soft);
  font: inherit;
  font-size: var(--text-xs);
  cursor: pointer;
}

.ai-chat-jump:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.ai-chat-jump:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.ai-chat-dock {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  width: 100%;
  max-width: 760px;
  margin: 0 auto;
  padding: var(--space-xs) var(--space-xl) var(--space-lg);
}

.chat-tip {
  margin: 0;
  padding-left: 4px;
  color: var(--ink-soft);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.chat-tip.is-warning {
  color: var(--warning);
}

.chat-tip.is-info {
  color: var(--info);
}

@media (max-width: 1024px) {
  .ai-chat-sidebar {
    flex-basis: 220px;
    width: 220px;
  }

  .ai-chat-scroll,
  .ai-chat-topbar,
  .ai-chat-dock {
    padding-left: var(--space-md);
    padding-right: var(--space-md);
  }
}

@media (max-width: 768px) {
  .ai-chat-sidebar {
    display: none;
  }

  .ai-chat-suggestions {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>