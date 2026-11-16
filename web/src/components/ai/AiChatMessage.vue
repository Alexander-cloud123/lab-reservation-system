<script setup>
import { computed, ref } from 'vue'
import AiThinkingPanel from './AiThinkingPanel.vue'

const props = defineProps({
  message: { type: Object, required: true },
  /** 最后一条 AI 回答在流式过程中才显示光标 */
  streaming: { type: Boolean, default: false }
})

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
    <span v-if="streaming" class="typing-cursor" aria-hidden="true"></span>

    <div v-if="!streaming && message.content" class="ai-chat-actions">
      <button type="button" class="ai-chat-copy" @click="copyAnswer">
        {{ copied ? '已复制' : '复制' }}
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
  border-radius: 12px;
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

.ai-chat-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  color: var(--text-placeholder);
  font-size: 12px;
}

.ai-chat-copy {
  padding: 2px 8px;
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-sm);
  background: none;
  color: var(--text-regular);
  font: inherit;
  cursor: pointer;
}

.ai-chat-copy:hover {
  border-color: var(--border-color);
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
