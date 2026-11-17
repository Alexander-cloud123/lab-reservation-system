<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  /**
   * 思考轨迹快照：
   * - state: thinking / done / stopped / degraded / error
   * - steps: [{ id, label, status: running|done|failed, elapsedMs }]（后端按真实流水线逐个下发）
   * - reasoning: 上游模型透传的推理文本（可能为空）
   * - summary: 汇总标签（命中缓存 / 范围判断完成 / 已降级为本地规则）
   */
  trace: { type: Object, required: true }
})

const expanded = ref(true)
/** 用户手动展开过：后续折叠不再自动收起，尊重用户选择 */
let userExpanded = false

const thinking = computed(() => props.trace.state === 'thinking')
const steps = computed(() => props.trace.steps || [])
const reasoning = computed(() => (props.trace.reasoning || '').trim())

/** 思考中每 0.1 秒走一次表；结束后锁定为后端给出的真实耗时（缺失时用前端兜底值） */
const liveElapsed = ref(0)
let timer = 0
function startTimer() {
  stopTimer()
  const tick = () => {
    liveElapsed.value = Math.max(0, performance.now() - (props.trace.startedAt || performance.now()))
  }
  tick()
  timer = setInterval(tick, 100)
}
function stopTimer() {
  if (timer) {
    clearInterval(timer)
    timer = 0
  }
}

const elapsed = computed(() => {
  if (thinking.value) return liveElapsed.value
  const fixed = props.trace.elapsedMs
  if (typeof fixed === 'number') return fixed
  // 后端未给出汇总耗时（异常/中断）：按前端计时器最后一次读数
  return liveElapsed.value
})

function formatDuration(ms) {
  const seconds = Math.max(0, Number(ms) || 0) / 1000
  return seconds >= 60 ? `${seconds.toFixed(0)} 秒` : `${seconds.toFixed(1)} 秒`
}

const meta = computed(() => {
  const time = formatDuration(elapsed.value)
  if (thinking.value) return time
  if (props.trace.state === 'degraded') return `已思考 ${time}`
  const suffix = props.trace.summary ? `（${props.trace.summary}）` : ''
  return ` ${time}${suffix}`
})

const headline = computed(() => {
  switch (props.trace.state) {
    case 'thinking':
      return '正在思考'
    case 'stopped':
      return '已停止'
    case 'degraded':
      return '已降级为本地规则'
    case 'error':
      return '思考中断'
    default:
      return '已思考'
  }
})

watch(
  () => props.trace.state,
  (state, prev) => {
    if (state === 'thinking') {
      expanded.value = true
      userExpanded = false
      startTimer()
      return
    }
    stopTimer()
    if (prev === 'thinking' && !userExpanded) {
      // 首个回答到达即自动折叠，让位给正文；用户手动展开过则保持展开
      expanded.value = false
    }
  },
  { immediate: true }
)

function toggle() {
  expanded.value = !expanded.value
  userExpanded = expanded.value
  // 折叠时保留计时读数，避免下次展开跳变
  liveElapsed.value = elapsed.value
}

onBeforeUnmount(stopTimer)
</script>

<template>
  <div
    class="thinking"
    :class="`is-${trace.state}`"
    role="group"
    :aria-label="`思考过程：${headline} ${meta}`"
  >
    <button
      type="button"
      class="thinking-head"
      :aria-expanded="expanded"
      aria-controls="ai-thinking-body"
      @click="toggle"
    >
      <span class="head-glyph" aria-hidden="true">
        <span v-if="thinking" class="pulse-dots"><i></i><i></i><i></i></span>
        <span v-else class="head-dot"></span>
      </span>
      <span class="head-text">{{ headline }}</span>
      <span class="head-meta">{{ meta }}</span>
      <span class="chevron" :class="{ 'is-open': expanded }" aria-hidden="true"></span>
    </button>

    <div v-show="expanded" id="ai-thinking-body" class="thinking-body">
      <ol class="steps">
        <li
          v-for="step in steps"
          :key="step.id"
          class="step"
          :class="`is-${step.status}`"
        >
          <span class="step-dot" aria-hidden="true"></span>
          <span class="step-label">{{ step.label }}</span>
          <span v-if="step.elapsedMs !== null && step.elapsedMs !== undefined" class="step-time">
            {{ formatDuration(step.elapsedMs) }}
          </span>
        </li>
      </ol>

      <div v-if="reasoning" class="reasoning">
        <p class="reasoning-label">模型推理，AI 生成，仅供参考</p>
        <p class="reasoning-text">{{ reasoning }}</p>
      </div>
      <p v-else class="reasoning-empty">当前模型未返回推理文本，以上为本次处理的实际步骤。</p>
    </div>
  </div>
</template>

<style scoped>
.thinking {
  margin: 0 0 12px;
  padding: 2px 12px 4px;
  border-radius: var(--radius-md);
  background: #f7f7f8;
  font-size: 12.5px;
  color: var(--text-regular);
}

.thinking-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 0 4px;
  border: 0;
  background: none;
  color: var(--text-regular);
  font: inherit;
  text-align: left;
  cursor: pointer;
  border-radius: var(--radius-sm);
}

.thinking-head:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
  outline-offset: 2px;
}

.head-glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
}

.pulse-dots {
  display: inline-flex;
  gap: 2px;
}

.pulse-dots i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--text-placeholder);
  animation: thinking-pulse 1.15s ease-in-out infinite;
}

.pulse-dots i:nth-child(2) {
  animation-delay: 0.16s;
}

.pulse-dots i:nth-child(3) {
  animation-delay: 0.32s;
}

.head-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--text-placeholder);
}

.thinking.is-degraded .head-dot {
  background: var(--color-warning, #a44904);
}

.head-text {
  color: var(--text-regular);
  font-weight: 500;
}

.head-meta {
  color: var(--text-placeholder);
  font-variant-numeric: tabular-nums;
}

.thinking.is-degraded .head-text {
  color: var(--color-warning, #a44904);
}

.chevron {
  margin-left: auto;
  width: 6px;
  height: 6px;
  border-right: 1.4px solid var(--text-placeholder);
  border-bottom: 1.4px solid var(--text-placeholder);
  transform: rotate(45deg);
  transition: transform 0.18s ease;
}

.chevron.is-open {
  transform: rotate(225deg);
}

.thinking-body {
  padding: 2px 0 4px;
}

.steps {
  margin: 0;
  padding: 0 0 0 2px;
  list-style: none;
}

.step {
  position: relative;
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0 3px 16px;
  color: var(--text-regular);
}

/* 时间线竖轨：把「执行轨迹」写成可见的顺序，而不是一堆装饰性编号 */
.step::before {
  content: '';
  position: absolute;
  left: 3px;
  top: 0;
  bottom: 0;
  width: 1px;
  background: #e7e7ea;
}

.step:first-child::before {
  top: 9px;
}

.step:last-child::before {
  bottom: calc(100% - 12px);
}

.step-dot {
  position: absolute;
  left: 0;
  top: 8px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  border: 1px solid #d4d4d8;
  background: #f7f7f8;
}

.step.is-running .step-dot {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
}

.step.is-done .step-dot {
  background: #a8a8b0;
  border-color: #a8a8b0;
}

.step.is-failed .step-dot {
  background: transparent;
  border-color: var(--color-warning, #a44904);
}

.step.is-done .step-label {
  color: var(--text-placeholder);
}

.step.is-failed .step-label {
  color: var(--color-warning, #a44904);
}

.step-time {
  margin-left: auto;
  color: var(--text-placeholder);
  font-variant-numeric: tabular-nums;
}

.reasoning {
  margin: 6px 0 2px 2px;
  padding: 8px 10px;
  border-left: 2px solid #dedee3;
  background: #fbfbfc;
}

.reasoning-label {
  margin: 0 0 4px;
  color: var(--text-placeholder);
  font-size: 11.5px;
}

.reasoning-text {
  margin: 0;
  color: var(--text-regular);
  line-height: 19px;
  white-space: pre-wrap;
  word-break: break-word;
}

.reasoning-empty {
  margin: 6px 0 2px 2px;
  color: var(--text-placeholder);
  font-size: 11.5px;
}

@keyframes thinking-pulse {
  0%,
  80%,
  100% {
    opacity: 0.25;
    transform: translateY(0);
  }
  40% {
    opacity: 1;
    transform: translateY(-1px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .pulse-dots i {
    animation: none;
    opacity: 0.6;
  }

  .chevron {
    transition: none;
  }
}
</style>
