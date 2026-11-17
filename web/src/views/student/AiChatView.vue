<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { probeAiRecommend } from '@/utils/aiProbe'
import { getStoredUser } from '@/utils/auth'
import { useAiChat } from '@/composables/useAiChat'
import AiChatMessage from '@/components/ai/AiChatMessage.vue'
import AiQuickReserve from '@/components/ai/AiQuickReserve.vue'
import AiChatComposer from '@/components/ai/AiChatComposer.vue'

const router = useRouter()

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

const suggestions = [
  { icon: 'spark', text: '明天下午有哪些空教室？' },
  { icon: 'calendar', text: '怎么取消一条已通过的预约？' },
  { icon: 'shield', text: '预约冲突是怎么判定的？' },
  { icon: 'clock', text: '管理员审核一般要多久？' },
  { icon: 'spark', text: '帮我约明天的机房，30 人左右' }
]

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
  await chat.send(question, { onFirstDelta: scrollToBottom })
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
  chat.regenerate({ onFirstDelta: scrollToBottom })
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
        <span class="sidebar-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18">
            <path
              d="M12 3.2 14 9l5.8 2-5.8 2-2 5.8-2-5.8L4.2 11 10 9l2-5.8Z"
              fill="currentColor"
              opacity=".9"
            />
          </svg>
        </span>
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

      <p class="sidebar-foot">仅解答预约相关问题 · 只读不写</p>
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

      <p v-if="aiOff" class="ai-chat-banner">{{ aiOff }}</p>

      <div ref="scrollRef" class="ai-chat-scroll" @scroll.passive="onScroll">
        <div class="ai-chat-stream chat-body" :aria-busy="sending">
          <div v-if="!hasMessages" class="ai-chat-welcome">
            <span class="welcome-mark" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="26" height="26">
                <path
                  d="M12 3.2 14 9l5.8 2-5.8 2-2 5.8-2-5.8L4.2 11 10 9l2-5.8Z"
                  fill="currentColor"
                />
              </svg>
            </span>
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
                  <span class="suggest-arrow" aria-hidden="true">→</span>
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
        回到底部 ↓
      </button>

      <div class="ai-chat-dock">
        <p
          class="chat-tip"
          :class="{ 'is-warning': degradedNotice, 'is-info': aiOff }"
        >
          {{ degradedNotice || aiOff || 'AI 生成，仅供参考 · 仅解答预约相关问题' }}
        </p>
        <AiChatComposer
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
.ai-chat-page {
  /* 顶栏 56px 之外整屏铺满；父级 el-main 有 24px 内边距，用负外边距抵消 */
  display: flex;
  height: calc(100vh - 56px);
  margin: -24px;
  background: var(--bg-card, #fff);
  overflow: hidden;
}

/* 左侧会话栏：低对比度，不抢正文注意力 */
.ai-chat-sidebar {
  display: flex;
  flex-direction: column;
  width: 248px;
  flex: 0 0 248px;
  padding: 14px 10px 10px;
  border-right: 1px solid var(--border-color-light);
  background: #f7f7f8;
}

.sidebar-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 8px 12px;
}

.sidebar-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-md);
  background: var(--brand-primary-light, #e0e9ff);
  color: var(--brand-primary);
}

.sidebar-title {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
}

.sidebar-title strong {
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 600;
}

.sidebar-title span {
  color: var(--text-placeholder);
  font-size: 11px;
}

.ai-chat-new {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 2px 8px;
  padding: 9px 10px;
  border: 1px solid var(--brand-primary-border, #c2d4ff);
  border-radius: var(--radius-md);
  background: var(--brand-primary-lighter, #f0f4ff);
  color: var(--brand-primary);
  font: inherit;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.ai-chat-new:hover {
  background: var(--brand-primary-light, #e0e9ff);
}

.ai-chat-new:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 1px;
}

.ai-chat-search {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 2px 10px;
  padding: 6px 8px;
  border-radius: var(--radius-md);
  background: #ececee;
  color: var(--text-placeholder);
}

.ai-chat-search:focus-within {
  background: #fff;
  box-shadow: inset 0 0 0 1px var(--brand-primary-border, #c2d4ff);
  color: var(--text-regular);
}

.search-input {
  width: 100%;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  font-size: 12.5px;
  outline: none;
}

.search-input::placeholder {
  color: var(--text-placeholder);
}

/* 搜索框自带的清除按钮在深色侧栏里不搭，隐藏 */
.search-input::-webkit-search-cancel-button {
  display: none;
}

.ai-chat-convs {
  flex: 1;
  overflow-y: auto;
}



.conv-group {
  margin: 0 0 10px;
}

.conv-group-title {
  margin: 0 0 2px;
  padding: 4px 8px;
  color: var(--text-placeholder);
  font-size: 11.5px;
  font-weight: 400;
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
  border-radius: var(--radius-md);
}

.conv-row:hover,
.conv-row.is-active {
  background: #e9e9ec;
}

.conv-row.is-active .conv-title {
  color: var(--text-primary);
  font-weight: 500;
}

.conv-main {
  flex: 1;
  min-width: 0;
  padding: 7px 8px;
  border: 0;
  background: none;
  color: var(--text-regular);
  font: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
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
  color: var(--text-placeholder);
  font-size: 11px;
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
  background: transparent;
  color: var(--text-placeholder);
  font: inherit;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
}

.conv-row:hover .conv-delete,
.conv-row.is-active .conv-delete {
  opacity: 1;
  pointer-events: auto;
  background: #e2e2e6;
  color: var(--text-regular);
}

.conv-delete:hover {
  color: var(--color-danger, #c02a26);
}

.conv-row:hover .conv-time,
.conv-row.is-active .conv-time {
  visibility: hidden;
}

.conv-delete:focus-visible {
  opacity: 1;
  pointer-events: auto;
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
}

.conv-empty {
  margin: 6px 8px;
  color: var(--text-placeholder);
  font-size: 12px;
  line-height: 1.6;
}

.sidebar-foot {
  margin: 8px 4px 0;
  color: var(--text-placeholder);
  font-size: 11px;
}

.ai-chat-main {
  position: relative;
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
}

.ai-chat-topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 46px;
  padding: 0 16px;
  border-bottom: 1px solid var(--border-color-light);
}

.ai-chat-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--radius-md);
  background: none;
  color: var(--text-regular);
  cursor: pointer;
}

.ai-chat-back:hover {
  background: var(--bg-fill, #eff0f1);
}

.ai-chat-back:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 1px;
}

.topbar-title {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13.5px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-state {
  margin-left: auto;
  color: var(--text-placeholder);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.ai-chat-banner {
  margin: 0;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border-color-light);
  background: var(--bg-subtle, #f8f9fa);
  color: var(--text-regular);
  font-size: 12.5px;
}

.ai-chat-banner.is-inline {
  margin: 0 0 18px;
  padding: 10px 12px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
}

.ai-chat-scroll {
  flex: 1;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.ai-chat-stream {
  width: 100%;
  max-width: 768px;
  margin: 0 auto;
  padding: 28px 24px 24px;
}

.ai-chat-welcome {
  padding: 9vh 0 0;
}

.welcome-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  margin-bottom: 18px;
  border-radius: 14px;
  background: var(--brand-primary-lighter, #f0f4ff);
  color: var(--brand-primary);
}

.ai-chat-welcome h1 {
  margin: 0 0 8px;
  color: var(--text-primary);
  font-size: 28px;
  font-weight: 600;
  line-height: 1.32;
  letter-spacing: -0.02em;
}

.ai-chat-welcome p {
  margin: 0 0 28px;
  max-width: 520px;
  color: var(--text-regular);
  font-size: 14px;
  line-height: 1.7;
}

.ai-chat-suggestions {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 620px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.suggest-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 11px 12px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--text-regular);
  font: inherit;
  font-size: 13.5px;
  line-height: 1.55;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.12s ease, color 0.12s ease;
}

.suggest-item:hover {
  background: var(--bg-subtle, #f8f9fa);
  color: var(--text-primary);
}

.suggest-item:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 1px;
}

.suggest-icon {
  display: inline-flex;
  flex: 0 0 auto;
  color: var(--text-placeholder);
}

.suggest-item:hover .suggest-icon {
  color: var(--brand-primary);
}

.suggest-text {
  flex: 1;
  min-width: 0;
}

.suggest-arrow {
  flex: 0 0 auto;
  color: var(--text-placeholder);
  opacity: 0;
  transition: opacity 0.12s ease;
}

.suggest-item:hover .suggest-arrow {
  opacity: 1;
}

.ai-chat-jump {
  position: absolute;
  right: 32px;
  bottom: 132px;
  z-index: 5;
  padding: 6px 12px;
  border: 1px solid var(--border-color-light);
  border-radius: 999px;
  background: #fff;
  box-shadow: var(--shadow-raised, 0 2px 8px rgba(31, 35, 41, 0.08));
  color: var(--text-regular);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.ai-chat-jump:hover {
  color: var(--text-primary);
  border-color: var(--border-color);
}

.ai-chat-dock {
  padding: 10px 24px 14px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #fff 26%);
}

.ai-chat-dock > * {
  max-width: 768px;
  margin-left: auto;
  margin-right: auto;
}

.chat-tip {
  margin: 0 0 8px;
  padding: 2px 2px 0;
  color: var(--text-placeholder);
  font-size: 11.5px;
  line-height: 1.6;
}

.chat-tip.is-warning {
  color: var(--color-warning, #a44904);
}

.chat-tip.is-info {
  color: var(--text-regular);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 560px) {
  .ai-chat-page {
    margin: -12px;
  }
}

@media (max-width: 900px) {
  .ai-chat-sidebar {
    display: none;
  }

  .ai-chat-stream {
    padding: 20px 16px;
  }

  .ai-chat-dock {
    padding: 8px 16px 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-chat-scroll {
    scroll-behavior: auto;
  }
}
</style>
