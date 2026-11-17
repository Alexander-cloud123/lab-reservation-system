<template>
  <div class="ai-plan" role="group" :aria-label="`预约方案：${plan.date || ''} ${plan.startTime || ''}-${plan.endTime || ''}`">
    <div class="plan-head">
      <p class="plan-title">预约方案</p>
      <p class="plan-meta">{{ detail }}</p>
    </div>

    <ol v-if="candidates.length" class="plan-list">
      <li v-for="candidate in candidates" :key="candidate.classroomId" class="plan-row">
        <div class="plan-room">
          <span class="room-name">{{ candidate.name }}</span>
          <span class="room-sub">
            {{ candidate.building }}<template v-if="candidate.roomNo"> · {{ candidate.roomNo }}</template>
            · {{ typeText(candidate.type) }} · {{ candidate.capacity }} 人
          </span>
          <span class="room-reason">{{ candidate.reason }}</span>
        </div>
        <button
          type="button"
          class="plan-go"
          :aria-label="`去预约 ${candidate.name}`"
          @click="emit('reserve', candidate)"
        >
          去预约
        </button>
      </li>
    </ol>

    <p v-else class="plan-empty">{{ plan.clarify || '这个时段没有可预约的教室。' }}</p>

    <p v-if="plan.moreAvailable" class="plan-more">另有 {{ plan.moreAvailable }} 间同条件的教室可用</p>
    <p v-if="candidates.length && plan.clarify" class="plan-clarify">{{ plan.clarify }}</p>
    <p class="plan-note">AI 只给出方案，点「去预约」后需在确认页手动提交</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { typeText } from '@/utils/dict'

const props = defineProps({
  /** 后端 plan 事件载荷：{ date, startTime, endTime, capacity, roomType, purpose, candidates, moreAvailable, clarify } */
  plan: { type: Object, required: true }
})
const emit = defineEmits(['reserve'])

const candidates = computed(() => (props.plan && props.plan.candidates) || [])

/** 方案条件副标题：日期 + 时段 + 人数/类型（缺项不占位） */
const detail = computed(() => {
  const plan = props.plan || {}
  const parts = []
  if (plan.date) {
    parts.push(plan.startTime && plan.endTime ? `${plan.date} ${plan.startTime}-${plan.endTime}` : plan.date)
  }
  if (plan.capacity) {
    parts.push(`${plan.capacity} 人`)
  }
  if (plan.roomType) {
    parts.push(plan.roomType)
  }
  return parts.join(' · ')
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
.plan-more {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--text-placeholder);
}
.plan-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--text-placeholder);
}
</style>
