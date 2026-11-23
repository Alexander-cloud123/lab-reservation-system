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
        <span class="composer-hint">Enter 发送，Shift+Enter 换行</span>
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
          @click="submit"
        >
          发送
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai-chat-composer {
  width: 100%;
}

.composer-box {
  padding: 12px 12px 8px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  background: var(--surface);
  box-shadow: var(--shadow-surface);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.composer-box:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px var(--brand-soft);
}

.composer-box.is-disabled {
  background: var(--page);
  border-color: var(--line);
}

.composer-input {
  display: block;
  width: 100%;
  min-height: 44px;
  max-height: 148px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: var(--text-base);
  line-height: 1.6;
  resize: none;
  outline: none;
}

.composer-input::placeholder {
  color: var(--ink-soft);
}

.composer-input:disabled {
  cursor: not-allowed;
  color: var(--ink-soft);
}

.composer-bar {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.composer-hint {
  color: var(--ink-soft);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.composer-stop,
.composer-send {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  margin-left: auto;
  font: inherit;
  font-size: var(--text-sm);
  cursor: pointer;
}

.composer-stop {
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface);
  color: var(--ink-soft);
  font-weight: 500;
  transition: border-color 0.16s ease, color 0.16s ease;
}

.composer-stop:hover {
  border-color: var(--danger);
  color: var(--danger);
}

.composer-send {
  padding: 0 18px;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  background: var(--brand);
  color: var(--surface);
  font-weight: 600;
  transition: background-color 0.16s ease, opacity 0.16s ease;
}

.composer-send:hover:not(:disabled) {
  background: var(--brand-primary-hover);
}

.composer-send:disabled {
  background: var(--line);
  color: var(--ink-soft);
  cursor: not-allowed;
}

.composer-stop:focus-visible,
.composer-send:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

@media (max-width: 720px) {
  .composer-hint {
    display: none;
  }
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.composer-box {
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-lg);
  background: var(--glass-strong);
  -webkit-backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  backdrop-filter: blur(var(--glass-blur)) saturate(150%);
  box-shadow: var(--shadow-float), var(--glass-sheen);
}
.composer-box:focus-within {
  border-color: rgba(29, 111, 224, 0.45);
  box-shadow: var(--shadow-float), var(--ring), var(--glass-sheen);
}
.composer-send {
  background-image: var(--grad);
  border: 0;
  color: #fff;
  box-shadow: var(--shadow-brand);
}
.composer-send:hover {
  filter: brightness(1.06);
  color: #fff;
}
.composer-hint {
  color: var(--ink-mute);
}
</style>
