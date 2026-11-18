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
  margin: 0 0 var(--space-md);
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface);
  color: var(--ink-soft);
  overflow: hidden;
  transition: border-color 0.16s ease;
  animation: reveal-up var(--dur-2) var(--ease-out) both;
}

.thinking:hover {
  border-color: var(--brand);
}

.thinking.is-degraded {
  border-color: var(--warning);
}

.thinking-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  width: 100%;
  height: 36px;
  padding: 0 12px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font: inherit;
  font-size: var(--text-sm);
  text-align: left;
  cursor: pointer;
}

.thinking-head:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: -2px;
}

.head-glyph {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.pulse-dots {
  display: inline-flex;
  gap: 6px;
}

.pulse-dots i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--brand);
  animation: thinking-pulse 1.15s ease-in-out infinite;
}

.pulse-dots i:nth-child(2) {
  animation-delay: 0.16s;
}

.pulse-dots i:nth-child(3) {
  animation-delay: 0.32s;
}

.head-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-soft);
}

.thinking.is-degraded .head-dot {
  background: var(--warning);
}

.head-text {
  color: var(--ink);
  font-size: var(--text-sm);
  font-weight: 500;
}

.thinking.is-degraded .head-text {
  color: var(--warning);
}

.head-meta {
  margin-left: auto;
  color: var(--ink-soft);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}

.chevron {
  flex: none;
  width: 6px;
  height: 6px;
  border-right: 1px solid var(--ink-soft);
  border-bottom: 1px solid var(--ink-soft);
  transform: rotate(45deg);
  transition: transform 0.18s ease;
}

.chevron.is-open {
  transform: rotate(225deg);
}

.thinking-body {
  padding: 16px 16px 4px;
}

.steps {
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 步骤逐条出现：每次新增一条都淡入，已存在的不会重播 */
.step {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  height: 28px;
  color: var(--ink);
  animation: reveal-up var(--dur-2) var(--ease-out) both;
}

.step.is-running .step-dot {
  animation: thinking-pulse 1.15s ease-in-out infinite;
}

.step-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ink-soft);
}

.step.is-running .step-dot {
  background: var(--brand);
}

.step.is-done .step-dot {
  background: var(--success);
}

.step.is-failed .step-dot {
  background: var(--danger);
}

.step-label {
  font-size: var(--text-sm);
  color: var(--ink);
}

.step.is-failed .step-label {
  color: var(--danger);
}

.step-time {
  margin-left: auto;
  color: var(--ink-soft);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
}

.reasoning {
  margin: var(--space-md) 0 var(--space-xs);
  padding: 12px 16px;
  border-radius: var(--radius-control);
  background: var(--page);
}

.reasoning-label {
  margin: 0 0 var(--space-xs);
  color: var(--ink-soft);
  font-size: var(--text-xs);
  line-height: 1.5;
}

.reasoning-text {
  margin: 0;
  color: var(--ink-soft);
  font-size: var(--text-sm);
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.reasoning-empty {
  margin: var(--space-md) 0 var(--space-xs);
  color: var(--info);
  font-size: var(--text-xs);
  line-height: 1.6;
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

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.thinking {
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.72);
  box-shadow: var(--shadow-surface), var(--glass-sheen);
}
.reasoning {
  background: var(--grad-soft);
  border-left: 2px solid var(--sky);
  border-radius: var(--radius-sm);
}
.step-dot {
  box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.9);
}
</style>
