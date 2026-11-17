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
    <span class="msg-avatar msg-avatar-user" aria-hidden="true">我</span>
  </article>

  <article v-else class="ai-chat-msg chat-msg is-assistant ai" :class="stateClass">
    <span class="msg-avatar plate msg-avatar-ai" aria-hidden="true">AI</span>
    <div class="ai-chat-body">
      <AiThinkingPanel v-if="message.thinking" :trace="message.thinking" />
      <div class="ai-chat-answer chat-bubble">
        <div class="markdown-body" v-html="message.html || ''"></div>
      </div>
      <AiPlanCard
        v-if="message.plan && !streaming"
        :plan="message.plan"
        @reserve="(candidate, endTime) => emit('reserve', candidate, endTime)"
      />
      <span v-if="streaming" class="typing-cursor" aria-hidden="true"></span>

      <p v-if="message.state === 'stopped'" class="ai-chat-stopped">回答已停止生成，可重新生成或再次提问。</p>

      <div v-if="!streaming && (message.content || message.state === 'error')" class="ai-chat-actions">
        <button v-if="message.content" type="button" class="ai-chat-copy" :aria-label="copied ? '已复制' : '复制回答'" @click="copyAnswer">
          <span>{{ copied ? '已复制' : '复制' }}</span>
        </button>
        <button
          v-if="canRegenerate"
          type="button"
          class="ai-chat-copy ai-chat-regen"
          @click="emit('regenerate')"
        >
          <span>{{ message.state === 'error' ? '重试' : '重新生成' }}</span>
        </button>
        <span class="ai-chat-badge">AI 生成，仅供参考</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.ai-chat-msg {
  display: flex;
  align-items: flex-start;
  gap: var(--space-md);
  margin-bottom: 22px;
}

.ai-chat-msg.is-user {
  justify-content: flex-end;
}

.ai-chat-msg.is-assistant {
  align-items: flex-start;
}

.ai-chat-bubble {
  max-width: 78%;
  padding: 12px 16px;
  border-radius: var(--radius-card);
  background: var(--brand);
  color: var(--surface);
  font-size: var(--text-base);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-chat-body {
  flex: 1;
  min-width: 0;
  width: 100%;
}

.ai-chat-answer {
  width: 100%;
  color: var(--ink);
  font-size: var(--text-base);
  line-height: 1.8;
  word-break: break-word;
}

/* 正文里的标题/列表密度按对话场景收紧，避免网页文章式的大段留白 */
.ai-chat-answer :deep(h1),
.ai-chat-answer :deep(h2),
.ai-chat-answer :deep(h3) {
  margin: 18px 0 8px;
  font-size: var(--text-base);
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
  margin: var(--space-xs) 0;
}

.ai-chat-answer :deep(strong) {
  font-weight: 600;
}

.ai-chat-answer :deep(code) {
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: var(--surface-sunken, var(--page));
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: var(--text-sm);
}

.ai-chat-answer :deep(pre) {
  margin: 0 0 10px;
  padding: 12px 14px;
  border-radius: var(--radius-control);
  background: var(--page);
  overflow-x: auto;
}

.ai-chat-answer :deep(pre code) {
  padding: 0;
  background: none;
  font-size: var(--text-sm);
  line-height: 1.6;
}

.ai-chat-answer :deep(blockquote) {
  margin: 0 0 10px;
  padding: 2px 0 2px 12px;
  border-left: 3px solid var(--line);
  color: var(--ink-soft);
}

.ai-chat-answer :deep(table) {
  width: 100%;
  margin: 0 0 10px;
  border-collapse: collapse;
  font-size: var(--text-sm);
}

.ai-chat-answer :deep(th),
.ai-chat-answer :deep(td) {
  padding: 6px 10px;
  border: 1px solid var(--line);
  text-align: left;
}

.ai-chat-answer :deep(th) {
  background: var(--page);
  font-weight: 600;
}

.ai-chat-answer :deep(a) {
  color: var(--brand);
  text-decoration: none;
}

.ai-chat-answer :deep(a:hover) {
  text-decoration: underline;
}

.ai-chat-answer :deep(hr) {
  margin: 14px 0;
  border: 0;
  border-top: 1px solid var(--line);
}

.msg-avatar {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border-radius: var(--radius-control);
  font-size: var(--text-sm);
  font-weight: 600;
}

.msg-avatar-user {
  background: var(--brand);
  color: var(--surface);
}

.msg-avatar-ai {
  width: 32px;
  min-width: 32px;
  height: 32px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink);
}

.typing-cursor {
  display: inline-block;
  width: 2px;
  height: 1.1em;
  margin-top: -2px;
  background: var(--brand);
  vertical-align: middle;
  animation: typing-blink 1s steps(2, start) infinite;
}

.ai-chat-stopped {
  margin: var(--space-sm) 0 0;
  color: var(--ink-soft);
  font-size: var(--text-xs);
}

.ai-chat-actions {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
  margin-top: var(--space-sm);
  color: var(--ink-soft);
  font-size: var(--text-xs);
  opacity: 0.6;
  transition: opacity 0.16s ease;
}

.ai-chat-actions:hover {
  opacity: 1;
}

.ai-chat-copy {
  display: inline-flex;
  align-items: center;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: none;
  color: inherit;
  font: inherit;
  font-size: var(--text-xs);
  cursor: pointer;
  transition: color 0.16s ease;
}

.ai-chat-copy:hover {
  color: var(--brand);
}

.ai-chat-copy:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.ai-chat-badge {
  margin-left: auto;
}

.ai-chat-msg.is-degraded .ai-chat-answer {
  color: var(--ink-soft);
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
