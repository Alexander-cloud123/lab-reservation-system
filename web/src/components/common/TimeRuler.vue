<template>
  <div class="time-ruler" :class="{ 'is-compact': compact }" role="group" :aria-label="groupLabel">
    <div class="ruler-track">
      <span class="ruler-window" aria-hidden="true"></span>

      <span
        v-for="tick in ticks"
        :key="tick.hour"
        class="ruler-tick"
        :class="{ 'is-major': tick.major }"
        :style="{ left: tick.percent + '%' }"
        aria-hidden="true"
      />

      <span
        v-for="(slot, index) in blockList"
        :key="`${slot.startTime}-${slot.endTime}-${index}`"
        class="ruler-block timeline-slot"
        :class="{ 'is-mine': slot.mine }"
        :style="{ left: slot.left + '%', width: slot.width + '%' }"
        :title="slotLabel(slot)"
      >
        <span v-if="!compact" class="block-text">{{ slot.startTime }}-{{ slot.endTime }}</span>
      </span>

      <button
        v-for="gap in gapList"
        :key="`gap-${gap.start}-${gap.end}`"
        type="button"
        class="ruler-gap"
        :style="{ left: gap.left + '%', width: gap.width + '%' }"
        :aria-label="`选择 ${gap.start} 至 ${gap.end} 这个时段`"
        @click="emit('pick', { start: gap.start, end: gap.end })"
      >
        <span class="gap-hint">{{ gap.start }}-{{ gap.end }}</span>
      </button>

      <span
        v-if="showNeedle"
        class="ruler-needle needle-in"
        :style="{ left: needlePercent + '%' }"
        aria-hidden="true"
      />
    </div>

    <div v-if="!compact" class="ruler-scale" aria-hidden="true">
      <span v-for="hour in scaleHours" :key="hour" :style="{ left: (hour / 24) * 100 + '%' }">
        {{ hour }}:00
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useNow } from '@/composables/useNow'

/** 与后端一致：开放时段 08:00–22:00 */
const OPEN_START_HOUR = 8
const OPEN_END_HOUR = 22
const DAY_HOURS = 24

const props = defineProps({
  /** [{ startTime:'09:00', endTime:'11:00', mine?:boolean, purpose?:string }] */
  slots: { type: Array, default: () => [] },
  /** 覆盖当前时间（默认取全局 useNow） */
  now: { type: Date, default: null },
  /** 精简版：我的预约页今日条，不显示刻度数字与块内文字 */
  compact: { type: Boolean, default: false },
  /** 是否把空档渲染为可点击按钮（教室详情页启用） */
  clickable: { type: Boolean, default: false },
  /** 是否显示「此刻」指针（查看非当天日期时传 false） */
  showNow: { type: Boolean, default: true }
})

const emit = defineEmits(['pick'])
const sharedNow = useNow()
const current = computed(() => props.now || sharedNow.value)

const toHour = (text) => {
  if (typeof text !== 'string' || !text.includes(':')) {
    return null
  }
  const [h, m] = text.split(':').map((n) => Number(n))
  if (!Number.isFinite(h) || !Number.isFinite(m)) {
    return null
  }
  return h + m / 60
}

const percent = (hour) => (hour / DAY_HOURS) * 100

/** 规范化占用块：裁剪到 0–24 天域，过滤非法值 */
const blockList = computed(() =>
  props.slots
    .map((slot) => {
      const start = toHour(slot.startTime)
      const end = toHour(slot.endTime)
      if (start === null || end === null || end <= start) {
        return null
      }
      const left = Math.max(0, percent(start))
      const right = Math.min(100, percent(end))
      return {
        ...slot,
        start,
        end,
        left,
        width: Math.max(0, right - left)
      }
    })
    .filter(Boolean)
)

/** 空档：只在开放时段内、扣掉已占用块之后剩余的区间；首尾相接不算空隙 */
const gapList = computed(() => {
  if (!props.clickable) {
    return []
  }
  const sorted = blockList.value.slice().sort((a, b) => a.start - b.start)
  const gaps = []
  let cursor = OPEN_START_HOUR
  sorted.forEach((slot) => {
    if (slot.start > cursor) {
      push(cursor, Math.min(slot.start, OPEN_END_HOUR))
    }
    cursor = Math.max(cursor, slot.end)
  })
  if (cursor < OPEN_END_HOUR) {
    push(cursor, OPEN_END_HOUR)
  }
  function push(start, end) {
    if (end <= start) {
      return
    }
    const left = percent(start)
    gaps.push({
      start: `${String(Math.floor(start)).padStart(2, '0')}:00`,
      end: `${String(Math.floor(end)).padStart(2, '0')}:00`,
      left,
      width: Math.max(2, percent(end) - left)
    })
  }
  return gaps
})

const hourFloat = computed(() => {
  const d = current.value
  return d.getHours() + d.getMinutes() / 60
})
const showNeedle = computed(() => {
  if (!props.showNow) {
    return false
  }
  const d = current.value
  const today = new Date()
  const sameDay =
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate()
  return sameDay && hourFloat.value >= OPEN_START_HOUR && hourFloat.value < OPEN_END_HOUR
})
const needlePercent = computed(() => percent(hourFloat.value))

const ticks = computed(() => {
  const list = []
  for (let hour = OPEN_START_HOUR; hour <= OPEN_END_HOUR; hour += 1) {
    list.push({ hour, percent: percent(hour), major: hour % 2 === 0 })
  }
  return list
})
const scaleHours = [8, 12, 16, 20, 22]

const groupLabel = computed(() =>
  props.compact ? '今日预约时段' : '时段占用刻度（08:00 至 22:00）'
)

function slotLabel(slot) {
  const person = slot.mine ? '我的预约' : '已通过预约'
  return `${slot.startTime}-${slot.endTime} ${person}${slot.purpose ? ` ${slot.purpose}` : ''}`
}
</script>

<style scoped>
.time-ruler {
  --ruler-h: 40px;
}

/* 轨：玻璃底 + 1px 白光边；闭馆段用深色调压暗 */
.ruler-track {
  position: relative;
  height: var(--ruler-h);
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid var(--line);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
  overflow: hidden;
}

.ruler-window,
.ruler-closed-surface {
  position: absolute;
  top: 0;
  bottom: 0;
}

.ruler-track::before,
.ruler-track::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  background: rgba(15, 42, 80, 0.06);
}

.ruler-track::before {
  left: 0;
  width: 33.3333%;
}

.ruler-track::after {
  left: 91.6667%;
  right: 0;
}

.ruler-window {
  display: none;
}

.ruler-tick {
  position: absolute;
  top: 0;
  width: 1px;
  height: 5px;
  background: rgba(29, 111, 224, 0.18);
}

.ruler-tick.is-major {
  height: 9px;
  background: rgba(29, 111, 224, 0.34);
}

/* 占用块：从左生长一次。我的预约走渐变主色，他人预约走夜蓝半透明 */
.ruler-block {
  position: absolute;
  top: 7px;
  bottom: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-sm);
  background: rgba(11, 42, 91, 0.62);
  color: #fff;
  font-family: var(--font-num);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  transform-origin: left center;
  animation: block-grow var(--dur-3) var(--ease-out) both;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(11, 42, 91, 0.2);
}

.ruler-block.is-mine {
  background: var(--grad);
  box-shadow: 0 4px 14px rgba(29, 111, 224, 0.32);
}

.block-text {
  padding: 0 7px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@keyframes block-grow {
  from {
    transform: scaleX(0);
    opacity: 0.4;
  }
  to {
    transform: scaleX(1);
    opacity: 1;
  }
}

/* 空档：悬停才显形（背景浅蓝 + 虚线描边 + 时段时间） */
.ruler-gap {
  position: absolute;
  top: 7px;
  bottom: 7px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-sm);
  background: transparent;
  cursor: pointer;
  color: var(--brand-deep);
  font-family: var(--font-num);
  font-size: var(--text-xs);
  font-variant-numeric: tabular-nums;
  transition: background-color var(--dur-1) var(--ease-out);
}

.ruler-gap:hover,
.ruler-gap:focus-visible {
  background: rgba(29, 111, 224, 0.1);
  outline: 1px dashed var(--brand);
  outline-offset: -1px;
}

.ruler-gap:focus-visible {
  box-shadow: var(--ring);
}

.gap-hint {
  opacity: 0;
  transition: opacity var(--dur-1) var(--ease-out);
  white-space: nowrap;
}

.ruler-gap:hover .gap-hint,
.ruler-gap:focus-visible .gap-hint {
  opacity: 1;
}

/* 此刻指针：晨光橙细线 + 顶部小帽，压在块之上 */
.ruler-needle {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--sun);
  box-shadow: 0 0 10px rgba(255, 154, 60, 0.6);
  z-index: 2;
}

.ruler-needle::before {
  content: '';
  position: absolute;
  top: -1px;
  left: 50%;
  width: 7px;
  height: 7px;
  transform: translateX(-50%) rotate(45deg);
  background: var(--sun);
}

.ruler-scale {
  position: relative;
  height: 18px;
  margin-top: 4px;
}

.ruler-scale span {
  position: absolute;
  transform: translateX(-50%);
  font-family: var(--font-num);
  font-size: var(--text-xs);
  color: var(--ink-mute);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.time-ruler.is-compact .ruler-track {
  --ruler-h: 30px;
}

.time-ruler.is-compact .ruler-block {
  top: 5px;
  bottom: 5px;
}
</style>