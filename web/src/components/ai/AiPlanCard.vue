<template>
  <div class="ai-plan" role="group" :aria-label="`预约方案：${plan.date || ''} ${plan.startTime || ''}-${currentEnd}`">
    <div class="plan-head">
      <p class="plan-title">预约方案</p>
      <p class="plan-meta">{{ detail }}</p>
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
        <div class="plan-room">
          <span class="room-name">{{ candidate.name }}</span>
          <span class="room-sub">
            {{ candidate.building }}<template v-if="candidate.roomNo"> {{ candidate.roomNo }}</template>
            {{ typeText(candidate.type) }}，{{ candidate.capacity }} 人
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
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  background: var(--bg-card);
  padding: 14px 16px 12px;
  margin: 10px 0 14px;
}
.plan-head {
  margin-bottom: 10px;
}
.plan-title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.plan-meta {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--text-regular);
}
.plan-time-options {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0 0 10px;
}
.plan-time-chip {
  border: 1px solid var(--border-color-light);
  background: var(--bg-subtle);
  color: var(--text-regular);
  font-size: 12.5px;
  padding: 4px 10px;
  border-radius: 999px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}
.plan-time-chip:hover {
  border-color: var(--brand-primary-border, #c2d4ff);
  color: var(--brand-primary);
}
.plan-time-chip.is-active {
  border-color: var(--brand-primary);
  background: var(--brand-primary-lighter, #f0f4ff);
  color: var(--brand-primary);
  font-weight: 600;
}
.plan-time-chip.is-empty {
  opacity: 0.55;
  cursor: not-allowed;
}
.plan-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.plan-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 8px;
  border-radius: var(--radius-sm);
}
.plan-row:hover {
  background: var(--bg-subtle);
}
.plan-room {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.room-name {
  font-size: 14px;
  color: var(--text-primary);
}
.room-sub,
.room-reason {
  font-size: 12px;
  color: var(--text-placeholder);
}
.plan-go {
  flex: none;
  border: 1px solid var(--brand-primary-border, #c2d4ff);
  background: var(--brand-primary-lighter, #f0f4ff);
  color: var(--brand-primary);
  font-size: 13px;
  padding: 5px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;
}
.plan-go:hover {
  background: var(--brand-primary);
  color: #fff;
}
.plan-go:focus-visible {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
}
.plan-empty,
.plan-clarify {
  margin: 6px 0;
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-regular);
}
.plan-more,
.plan-guess {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-placeholder);
}
.plan-guess {
  color: var(--text-regular);
}
.plan-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-placeholder);
}
</style>
