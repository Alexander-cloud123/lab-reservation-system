<template>
  <div class="ai-plan" role="group" :aria-label="`预约方案：${plan.date || ''} ${plan.startTime || ''}-${currentEnd}`">
    <div class="plan-head">
      <div class="plan-head-main">
        <p class="plan-title">预约方案</p>
        <p class="plan-meta">{{ detail }}</p>
      </div>
      <span class="plan-badge">AI 生成，仅供参考</span>
    </div>

    <!-- R15-2：用户没说结束时间时，这里给出可选时段；切换只在本卡片内完成，不再发请求 -->
    <div v-if="options.length > 1" class="plan-time-options" role="group" aria-label="可选结束时间">
      <button
        v-for="(option, index) in options"
        :key="option.endTime"
        type="button"
        class="plan-time-chip"
        :class="{ 'is-active': index === activeIndex, 'is-empty': !option.availableCount }"
        :aria-pressed="index === activeIndex"
        :aria-label="`${option.startTime}-${option.endTime}，${option.availableCount} 间可用`"
        @click="selectOption(index)"
      >
        {{ option.startTime }}–{{ option.endTime }} {{ option.availableCount }} 间
      </button>
    </div>

    <ol v-if="candidates.length" class="plan-list">
      <li v-for="candidate in candidates" :key="candidate.classroomId" class="plan-row">
        <span class="plate plan-room-plate">{{ candidate.roomNo || candidate.name }}</span>
        <div class="plan-room">
          <span class="room-name">{{ candidate.name }}</span>
          <span class="room-sub">
            {{ candidate.building }}<template v-if="candidate.roomNo"> {{ candidate.roomNo }}</template>，{{ typeText(candidate.type) }}，{{ candidate.capacity }} 人
          </span>
          <span class="room-reason">{{ candidate.reason }}</span>
        </div>
        <button
          type="button"
          class="plan-go"
          :aria-label="`去预约 ${candidate.name}，时段 ${currentEnd}`"
          @click="emit('reserve', candidate, currentEnd)"
        >
          去预约
        </button>
      </li>
    </ol>

    <p v-else class="plan-empty">{{ plan.clarify || '这个时段没有可预约的教室。' }}</p>

    <p v-if="current.moreAvailable || plan.moreAvailable" class="plan-more">
      另有 {{ current.moreAvailable || plan.moreAvailable }} 间同条件的教室可用
    </p>
    <p v-if="plan.endTimeGuessed" class="plan-guess">
      未说明结束时间，已按 {{ plan.guessHours || 2 }} 小时估算，可在上方切换时段
    </p>
    <p v-if="candidates.length && plan.clarify" class="plan-clarify">{{ plan.clarify }}</p>
    <p class="plan-note">AI 只给出方案，点「去预约」后需在确认页手动提交</p>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { typeText } from '@/utils/dict'

const props = defineProps({
  /**
   * 后端 plan 事件载荷：{ date, startTime, endTime, capacity, roomType, purpose, candidates,
   * moreAvailable, clarify, endTimeGuessed, guessHours, timeOptions }
   */
  plan: { type: Object, required: true }
})
const emit = defineEmits(['reserve'])

/** 可选结束时间档位（endTime 明确时为空数组） */
const options = computed(() => (props.plan && props.plan.timeOptions) || [])

/** 默认选中后端回填的默认档（按 endTime 对齐），找不到就用第一档 */
const activeIndex = ref(0)
watch(
  () => props.plan,
  (plan) => {
    const list = (plan && plan.timeOptions) || []
    const target = list.findIndex((option) => option.endTime === (plan && plan.endTime))
    activeIndex.value = target >= 0 ? target : 0
  },
  { immediate: true }
)

/** 当前生效的时段：有档位用档位数据，否则回落到 plan 本身（旧载荷/明确时段） */
const current = computed(() => options.value[activeIndex.value] || props.plan || {})
const candidates = computed(() => current.value.candidates || [])
const currentEnd = computed(() => current.value.endTime || (props.plan && props.plan.endTime) || '')

function selectOption(index) {
  const option = options.value[index]
  if (!option || !option.availableCount) {
    return
  }
  activeIndex.value = index
}

/** 方案条件副标题：日期 + 时段 + 人数/类型（缺项不占位） */
const detail = computed(() => {
  const plan = props.plan || {}
  const parts = []
  if (plan.date) {
    parts.push(
      plan.startTime && currentEnd.value
        ? `${plan.date} ${plan.startTime}-${currentEnd.value}`
        : plan.date
    )
  }
  if (plan.capacity) {
    parts.push(`${plan.capacity} 人`)
  }
  if (plan.roomType) {
    parts.push(plan.roomType)
  }
  return parts.join('，')
})
</script>

<style scoped>
.ai-plan {
  margin: 10px 0 14px;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius-card);
  background: var(--surface);
  box-shadow: var(--shadow-surface);
}

.plan-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-md);
}

.plan-head-main {
  min-width: 0;
}

.plan-title {
  margin: 0;
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
}

.plan-meta {
  margin: var(--space-xs) 0 0;
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--ink-soft);
}

.plan-badge {
  display: inline-flex;
  flex: none;
  align-items: center;
  height: 24px;
  padding: 0 8px;
  border-radius: var(--radius-sm);
  background: var(--brand-soft);
  color: var(--brand);
  font-size: var(--text-xs);
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
}

.plan-time-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
  margin: 0 0 var(--space-md);
}

.plan-time-chip {
  height: 32px;
  padding: 0 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface);
  color: var(--ink-soft);
  font: inherit;
  font-size: var(--text-sm);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease;
}

.plan-time-chip:hover {
  border-color: var(--brand);
  color: var(--brand);
}

.plan-time-chip.is-active {
  border-color: var(--brand);
  background: var(--brand);
  color: var(--surface);
  font-weight: 600;
}

.plan-time-chip.is-empty {
  opacity: 0.55;
  cursor: not-allowed;
}

.plan-time-chip:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.plan-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: 0;
  padding: 0;
  list-style: none;
}

.plan-row {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md);
  border-radius: var(--radius-control);
  transition: background-color 0.16s ease;
}

.plan-row:hover {
  background: var(--page);
}

.plan-room-plate {
  flex: none;
  height: 26px;
  font-size: var(--text-sm);
}

.plan-room {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: var(--space-xs);
  min-width: 0;
}

.room-name {
  font-size: var(--text-base);
  font-weight: 600;
  line-height: 1.4;
  color: var(--ink);
}

.room-sub {
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--ink-soft);
}

.room-reason {
  font-size: var(--text-xs);
  line-height: 1.5;
  color: var(--info);
}

.plan-go {
  flex: none;
  height: 32px;
  padding: 0 16px;
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  background: var(--brand-soft);
  color: var(--brand);
  font: inherit;
  font-size: var(--text-sm);
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.16s ease, color 0.16s ease;
}

.plan-go:hover {
  background: var(--brand);
  color: var(--surface);
}

.plan-go:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}

.plan-empty {
  margin: 0;
  padding: var(--space-xl) 0;
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--ink-soft);
}

.plan-clarify {
  margin: var(--space-sm) 0;
  font-size: var(--text-sm);
  line-height: 1.7;
  color: var(--ink-soft);
}

.plan-more,
.plan-guess {
  margin: var(--space-sm) 0 0;
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--ink-soft);
}

.plan-note {
  margin: var(--space-md) 0 0;
  font-size: var(--text-xs);
  line-height: 1.6;
  color: var(--ink-soft);
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.ai-plan {
  background: var(--glass-strong);
  -webkit-backdrop-filter: blur(14px) saturate(140%);
  backdrop-filter: blur(14px) saturate(140%);
  border: 1px solid var(--glass-line);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-surface), var(--shadow-raised), var(--glass-sheen);
}
.plan-go {
  background-image: var(--grad);
  color: #fff;
  border: 0;
  box-shadow: var(--shadow-brand);
}
.plan-go:hover {
  filter: brightness(1.06);
  color: #fff;
}
.plan-row {
  border-top: 1px solid var(--line);
}
</style>
