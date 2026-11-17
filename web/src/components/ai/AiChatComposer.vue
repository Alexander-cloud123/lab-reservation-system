<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  sending: { type: Boolean, default: false },
  /** AI 双层开关关闭时禁用输入，但仍可浏览历史消息 */
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'send', 'stop'])

const inputRef = ref(null)

function updateValue(event) {
  emit('update:modelValue', event.target.value)
  resize()
}

/** 输入框随内容增高，最多 6 行；超出后内部滚动，避免把消息区挤扁 */
function resize() {
  const el = inputRef.value
  if (!el) {
    return
  }
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 148)}px`
}

function submit() {
  if (props.sending || props.disabled || !props.modelValue.trim()) {
    return
  }
  emit('send')
  nextTick(resize)
}

/** Enter 发送、Shift+Enter 换行（与主流对话产品一致）；中文输入法组合态不拦截 */
function onKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing) {
    return
  }
  event.preventDefault()
  submit()
}

watch(
  () => props.modelValue,
  (value) => {
    if (!value) {
      // 发送后清零高度，回到单行
      nextTick(() => {
        if (inputRef.value) {
          inputRef.value.style.height = 'auto'
        }
      })
    }
  }
)

function focus() {
  inputRef.value && inputRef.value.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="ai-chat-composer">
    <div class="composer-box" :class="{ 'is-disabled': disabled }">
      <textarea
        ref="inputRef"
        id="ai-chat-input"
        class="composer-input"
        :value="modelValue"
        :disabled="disabled"
        rows="1"
        placeholder="问我：怎么预约 / 怎么取消 / 审核要多久…"
        aria-label="向 AI 预约助手提问"
        @input="updateValue"
        @keydown="onKeydown"
      ></textarea>

      <div class="composer-bar">
        <span class="composer-hint">只读，不提交预约</span>
        <span class="composer-kbd">Enter 发送，Shift+Enter 换行</span>
        <button
          v-if="sending"
          type="button"
          class="composer-stop"
          @click="emit('stop')"
        >
          停止
        </button>
        <button
          v-else
          type="button"
          class="composer-send"
          :disabled="disabled || !modelValue.trim()"
          aria-label="发送"
          @click="submit"
        >
          <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
            <path
              d="M4.5 11.2 19 4.5c.8-.4 1.6.4 1.2 1.2l-6.7 14.5c-.4.8-1.5.7-1.8-.1l-1.4-4.6c-.1-.4-.4-.7-.8-.8l-4.6-1.4c-.8-.3-.9-1.4-.4-1.8Z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>
    </div>
    <p class="composer-note">AI 生成内容仅供参考，预约结果以系统记录与管理员审核为准。</p>
  </div>
</template>

<style scoped>
.ai-chat-composer {
  width: 100%;
}

.composer-box {
  padding: 10px 10px 8px 14px;
  border: 1px solid var(--border-color, #d0d3d6);
  border-radius: 16px;
  background: var(--bg-card, #fff);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.composer-box:focus-within {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(20, 86, 240, 0.1);
}

.composer-box.is-disabled {
  background: var(--bg-subtle, #f8f9fa);
  border-color: var(--border-color-light);
}

.composer-input {
  display: block;
  width: 100%;
  min-height: 24px;
  max-height: 148px;
  padding: 2px 2px 6px;
  border: 0;
  background: transparent;
  color: var(--text-primary);
  font: inherit;
  font-size: 14.5px;
  line-height: 1.6;
  resize: none;
  outline: none;
}

.composer-input::placeholder {
  color: var(--text-placeholder);
}

.composer-input:disabled {
  cursor: not-allowed;
  color: var(--text-disabled);
}

.composer-bar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.composer-hint {
  color: var(--text-regular);
  font-size: 12px;
}

.composer-kbd {
  color: var(--text-placeholder);
  font-size: 11px;
}

.composer-stop,
.composer-send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 34px;
  margin-left: auto;
  border-radius: var(--radius-md);
  font: inherit;
  font-size: 12.5px;
  cursor: pointer;
}

.composer-stop {
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--border-color);
  background: var(--bg-card);
  color: var(--text-regular);
}

.composer-stop:hover {
  border-color: var(--text-placeholder);
  color: var(--text-primary);
}

.composer-send {
  width: 34px;
  border-radius: 999px;
  border: 1px solid transparent;
  background: var(--brand-primary);
  color: #fff;
}

.composer-send:hover {
  background: var(--brand-primary-hover, #336df4);
}

.composer-send:disabled {
  background: var(--bg-fill, #eff0f1);
  color: var(--text-placeholder);
  cursor: not-allowed;
}

.composer-stop:focus-visible,
.composer-send:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 2px;
}

.composer-note {
  margin: 8px 2px 0;
  color: var(--text-placeholder);
  font-size: 11.5px;
  text-align: center;
}

@media (max-width: 720px) {
  .composer-kbd {
    display: none;
  }
}
</style>
