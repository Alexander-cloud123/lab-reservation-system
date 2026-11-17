<script setup>
import { computed, ref } from 'vue'
import AiThinkingPanel from './AiThinkingPanel.vue'
import AiPlanCard from './AiPlanCard.vue'

const props = defineProps({
  message: { type: Object, required: true },
  /** 最后一条 AI 回答在流式过程中才显示光标 */
  streaming: { type: Boolean, default: false },
  /** 仅最后一条回答允许重新生成，避免历史回答被误替换 */
  canRegenerate: { type: Boolean, default: false }
})

const emit = defineEmits(['regenerate', 'reserve'])

const copied = ref(false)
let copiedTimer = 0

const isUser = computed(() => props.message.role === 'user')
const stateClass = computed(() => (props.message.state ? `is-${props.message.state}` : ''))

async function copyAnswer() {
  const text = props.message.content || ''
  if (!text) {
    return
  }
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    // 非安全上下文/无权限时退化为选中文本，不弹额外错误打断对话
    const holder = document.createElement('textarea')
    holder.value = text
    holder.setAttribute('readonly', '')
    holder.style.position = 'fixed'
    holder.style.opacity = '0'
    document.body.appendChild(holder)
    holder.select()
    document.execCommand('copy')
    document.body.removeChild(holder)
  }
  copied.value = true
  clearTimeout(copiedTimer)
  copiedTimer = setTimeout(() => {
    copied.value = false
  }, 1600)
}
</script>

<template>
  <article v-if="isUser" class="ai-chat-msg chat-msg is-user user">
    <div class="ai-chat-bubble chat-bubble">{{ message.content }}</div>
  </article>

  <article v-else class="ai-chat-msg chat-msg is-assistant ai" :class="stateClass">
    <AiThinkingPanel v-if="message.thinking" :trace="message.thinking" />
    <div class="ai-chat-answer chat-bubble">
      <div class="markdown-body" v-html="message.html || ''"></div>
    </div>
    <AiPlanCard
      v-if="message.plan && !streaming"
      :plan="message.plan"
      @reserve="(candidate) => emit('reserve', candidate)"
    />
    <span v-if="streaming" class="typing-cursor" aria-hidden="true"></span>

    <p v-if="message.state === 'stopped'" class="ai-chat-stopped">回答已停止生成，可重新生成或再次提问。</p>

    <div v-if="!streaming && (message.content || message.state === 'error')" class="ai-chat-actions">
      <button v-if="message.content" type="button" class="ai-chat-copy" :aria-label="copied ? '已复制' : '复制回答'" @click="copyAnswer">
        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <rect x="9" y="9" width="10" height="10" rx="1.6" stroke="currentColor" stroke-width="1.4" fill="none" />
          <path d="M15 6.5V5.6A1.6 1.6 0 0 0 13.4 4H6.6A1.6 1.6 0 0 0 5 5.6v6.8A1.6 1.6 0 0 0 6.6 14h.9" stroke="currentColor" stroke-width="1.4" fill="none" />
        </svg>
        <span>{{ copied ? '已复制' : '复制' }}</span>
      </button>
      <button
        v-if="canRegenerate"
        type="button"
        class="ai-chat-copy ai-chat-regen"
        @click="emit('regenerate')"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">
          <path d="M19 12a7 7 0 1 1-2.4-5.3M19 5v4.4h-4.4" stroke="currentColor" stroke-width="1.5" fill="none" />
        </svg>
        <span>{{ message.state === 'error' ? '重试' : '重新生成' }}</span>
      </button>
      <span class="ai-chat-badge">AI 生成，仅供参考</span>
    </div>
  </article>
</template>

<style scoped>
.ai-chat-msg {
  display: flex;
  flex-direction: column;
  margin-bottom: 22px;
}

.ai-chat-msg.is-user {
  align-items: flex-end;
}

.ai-chat-bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 16px;
  background: var(--bg-fill, #eff0f1);
  color: var(--text-primary);
  font-size: 14.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-chat-msg.is-assistant {
  align-items: flex-start;
}

.ai-chat-answer {
  width: 100%;
  color: var(--text-primary);
  font-size: 15px;
  line-height: 1.78;
  word-break: break-word;
}

/* 正文里的标题/列表密度按对话场景收紧，避免网页文章式的大段留白 */
.ai-chat-answer :deep(h1),
.ai-chat-answer :deep(h2),
.ai-chat-answer :deep(h3) {
  margin: 18px 0 8px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1.5;
}

.ai-chat-answer :deep(p) {
  margin: 0 0 10px;
}

.ai-chat-answer :deep(ul),
.ai-chat-answer :deep(ol) {
  margin: 0 0 10px;
  padding-left: 20px;
}

.ai-chat-answer :deep(li) {
  margin: 3px 0;
}

.ai-chat-answer :deep(strong) {
  font-weight: 600;
}

.ai-chat-answer :deep(code) {
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: var(--bg-fill, #eff0f1);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}

.ai-chat-answer :deep(pre) {
  margin: 0 0 10px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  background: #f7f7f8;
  overflow-x: auto;
}

.ai-chat-answer :deep(pre code) {
  padding: 0;
  background: none;
  font-size: 13px;
  line-height: 1.6;
}

.ai-chat-answer :deep(blockquote) {
  margin: 0 0 10px;
  padding: 2px 0 2px 12px;
  border-left: 2px solid var(--border-color-light);
  color: var(--text-regular);
}

.ai-chat-answer :deep(table) {
  width: 100%;
  margin: 0 0 10px;
  border-collapse: collapse;
  font-size: 13.5px;
}

.ai-chat-answer :deep(th),
.ai-chat-answer :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--border-color-light);
  text-align: left;
}

.ai-chat-answer :deep(th) {
  background: var(--bg-subtle, #f8f9fa);
  font-weight: 600;
}

.ai-chat-answer :deep(a) {
  color: var(--brand-primary);
  text-decoration: none;
}

.ai-chat-answer :deep(a:hover) {
  text-decoration: underline;
}

.ai-chat-answer :deep(hr) {
  margin: 14px 0;
  border: 0;
  border-top: 1px solid var(--border-color-light);
}

.typing-cursor {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-top: -2px;
  border-radius: 1px;
  background: var(--text-regular);
  vertical-align: middle;
  animation: typing-blink 1s steps(2, start) infinite;
}

.ai-chat-stopped {
  margin: 8px 0 0;
  color: var(--text-placeholder);
  font-size: 12px;
}

.ai-chat-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  color: var(--text-placeholder);
  font-size: 12px;
}

.ai-chat-copy {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 6px;
  border: 0;
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-placeholder);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.ai-chat-copy:hover {
  background: var(--bg-subtle, #f8f9fa);
  color: var(--text-primary);
}

.ai-chat-copy:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 1px;
}

.ai-chat-badge::before {
  content: '·';
  margin-right: 6px;
}

.ai-chat-msg.is-degraded .ai-chat-answer {
  color: var(--text-regular);
}

@keyframes typing-blink {
  50% {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .typing-cursor {
    animation: none;
    opacity: 0.75;
  }
}
</style>
