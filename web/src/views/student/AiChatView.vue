<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { probeAiRecommend } from '@/utils/aiProbe'
import { useAiChat } from '@/composables/useAiChat'
import AiChatMessage from '@/components/ai/AiChatMessage.vue'
import AiChatComposer from '@/components/ai/AiChatComposer.vue'

const router = useRouter()

const chat = useAiChat()
const { conversations, activeConversation, activeId, messages, hasMessages, input, sending, liveStatus, offNotice, degradedNotice } = chat

const scrollRef = ref(null)
/** 用户主动上翻读历史时不打扰：只有贴着底部才自动跟随新内容 */
let stickToBottom = true
let followFrame = 0

const title = computed(() => (activeConversation.value ? activeConversation.value.title : 'AI 预约助手'))
const lastMessageId = computed(() => (messages.value.length ? messages.value[messages.value.length - 1].id : ''))
const aiOff = computed(() => offNotice.value)

const suggestions = [
  '明天下午有哪些空教室？',
  '怎么取消一条已通过的预约？',
  '预约冲突是怎么判定的？',
  '管理员审核一般要多久？'
]

function atBottom() {
  const el = scrollRef.value
  if (!el) {
    return true
  }
  return el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

function scrollToBottom() {
  const el = scrollRef.value
  if (!el) {
    return
  }
  el.scrollTop = el.scrollHeight
  stickToBottom = true
}

function onScroll() {
  stickToBottom = atBottom()
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
      scrollToBottom()
    }
    followFrame = requestAnimationFrame(step)
  }
  followFrame = requestAnimationFrame(step)
}

watch(
  () => messages.value.length,
  () => nextTick(scrollToBottom)
)

watch(sending, (value) => {
  if (value) {
    followLoop()
  } else {
    cancelAnimationFrame(followFrame)
    followFrame = 0
  }
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
}

function onNewConversation() {
  chat.newConversation()
  scrollToBottom()
}

onBeforeUnmount(() => {
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
        <span aria-hidden="true">＋</span> 新对话
      </button>

      <ul class="ai-chat-convs">
        <li
          v-for="conversation in conversations"
          :key="conversation.id"
          class="ai-chat-conv"
          :class="{ 'is-active': conversation.id === activeId }"
        >
          <button type="button" class="conv-main" @click="chat.switchConversation(conversation.id)">
            <span class="conv-title">{{ conversation.title }}</span>
          </button>
          <button
            v-if="conversations.length > 1"
            type="button"
            class="conv-remove"
            :aria-label="`删除会话 ${conversation.title}`"
            @click.stop="chat.removeConversation(conversation.id)"
          >
            ×
          </button>
        </li>
      </ul>

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
        <div class="ai-chat-stream chat-body">
          <div v-if="!hasMessages" class="ai-chat-welcome">
            <h1>你好，我是 AI 预约助手</h1>
            <p>可以帮你查空教室、解释预约与审核规则，也会把思考过程摊开给你看。</p>
            <ul class="ai-chat-suggestions">
              <li v-for="item in suggestions" :key="item">
                <button type="button" @click="ask(item)">{{ item }}</button>
              </li>
            </ul>
          </div>

          <AiChatMessage
            v-for="message in messages"
            :key="message.id"
            :message="message"
            :streaming="sending && message.id === lastMessageId"
          />

          <p v-if="aiOff && hasMessages" class="ai-chat-banner is-inline">{{ aiOff }}</p>
        </div>
      </div>

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
  margin: 0 4px 10px;
  padding: 8px 10px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  color: var(--text-primary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
}

.ai-chat-new:hover {
  border-color: var(--border-color);
  background: #fff;
}

.ai-chat-new:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 1px;
}

.ai-chat-convs {
  flex: 1;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
}

.ai-chat-conv {
  display: flex;
  align-items: center;
  border-radius: var(--radius-md);
}

.ai-chat-conv:hover,
.ai-chat-conv.is-active {
  background: #efeff1;
}

.ai-chat-conv.is-active .conv-title {
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

.conv-remove {
  visibility: hidden;
  margin-right: 4px;
  border: 0;
  background: none;
  color: var(--text-placeholder);
  font: inherit;
  cursor: pointer;
}

.ai-chat-conv:hover .conv-remove,
.ai-chat-conv.is-active .conv-remove {
  visibility: visible;
}

.conv-remove:focus-visible {
  visibility: visible;
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  border-radius: var(--radius-sm);
}

.sidebar-foot {
  margin: 8px 4px 0;
  color: var(--text-placeholder);
  font-size: 11px;
}

.ai-chat-main {
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
  max-width: 760px;
  margin: 0 auto;
  padding: 28px 24px 24px;
}

.ai-chat-welcome {
  padding: 6vh 0 0;
}

.ai-chat-welcome h1 {
  margin: 0 0 10px;
  color: var(--text-primary);
  font-size: 26px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.01em;
}

.ai-chat-welcome p {
  margin: 0 0 26px;
  max-width: 520px;
  color: var(--text-regular);
  font-size: 13.5px;
  line-height: 1.7;
}

.ai-chat-suggestions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  max-width: 620px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ai-chat-suggestions button {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  color: var(--text-regular);
  font: inherit;
  font-size: 12.5px;
  line-height: 1.5;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, color 0.15s ease;
}

.ai-chat-suggestions button:hover {
  border-color: var(--brand-primary-border, #c2d4ff);
  color: var(--text-primary);
}

.ai-chat-suggestions button:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 1px;
}

.ai-chat-dock {
  padding: 10px 24px 14px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0), #fff 26%);
}

.ai-chat-dock > * {
  max-width: 760px;
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

  .ai-chat-suggestions {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-chat-scroll {
    scroll-behavior: auto;
  }
}
</style>
