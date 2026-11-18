<template>
  <section class="time-band" :class="{ 'is-compact': compact }" role="img" :aria-label="ariaLabel">
    <span class="band-grid" aria-hidden="true"></span>

    <div class="band-inner">
      <div class="band-top">
        <p class="band-date">{{ dateText }}</p>
        <p v-if="summary" class="band-summary">{{ summary }}</p>
      </div>

      <p class="band-clock">{{ clockText }}</p>

      <div class="band-ruler" aria-hidden="true">
        <span class="ruler-closed ruler-closed--pre" :style="closedPreStyle"></span>
        <span class="ruler-closed ruler-closed--post" :style="closedPostStyle"></span>

        <span
          v-for="tick in ticks"
          :key="tick.hour"
          class="ruler-tick"
          :class="{ 'is-major': tick.major }"
          :style="{ left: tick.percent + '%' }"
        >
          <i v-if="tick.label" class="ruler-label">{{ tick.label }}</i>
        </span>

        <span class="ruler-needle needle-in" :style="{ left: needlePercent + '%' }">
          <i class="needle-cap" aria-hidden="true"></i>
        </span>
      </div>

      <p class="band-window">开放时段 08:00 至 22:00，{{ windowHint }}</p>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { useNow } from '@/composables/useNow'

/** 每天开放的起止小时（与后端 Constants.DAILY_SLOT_START_HOUR / 结束 22:00 一致） */
const OPEN_START = 8
const OPEN_END = 22
const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

const props = defineProps({
  /** 覆盖当前时间（测试用；默认取全局 useNow） */
  now: { type: Date, default: null },
  /** 右侧一句真实摘要，如「此刻可用 8 / 12 间」 */
  summary: { type: String, default: '' },
  /** 精简版：管理端欢迎页使用，去掉刻度数字 */
  compact: { type: Boolean, default: false }
})

const sharedNow = useNow()
const current = computed(() => props.now || sharedNow.value)

const pad2 = (n) => String(n).padStart(2, '0')

const dateText = computed(() => {
  const d = current.value
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} 星期${WEEKDAYS[d.getDay()]}`
})

const clockText = computed(() => {
  const d = current.value
  return `${pad2(d.getHours())}:${pad2(d.getMinutes())}`
})

const hourFloat = computed(() => {
  const d = current.value
  return d.getHours() + d.getMinutes() / 60
})
const inWindow = computed(() => hourFloat.value >= OPEN_START && hourFloat.value < OPEN_END)
const windowHint = computed(() => {
  if (inWindow.value) {
    return '现在正处在可预约时间'
  }
  return hourFloat.value < OPEN_START ? '今天还没到开放时间' : '今天已过开放时间'
})

/** 指针位置按 0–24 小时的全天域计算，夜间闭馆段会被压暗 */
const needlePercent = computed(() => Math.min(100, Math.max(0, (hourFloat.value / 24) * 100)))
const closedPreStyle = computed(() => ({ left: '0%', width: `${(OPEN_START / 24) * 100}%` }))
const closedPostStyle = computed(() => ({
  left: `${(OPEN_END / 24) * 100}%`,
  width: `${((24 - OPEN_END) / 24) * 100}%`
}))

/** 刻度：每小时一个短刻度，偶数小时为长刻度；只给 8/12/16/20 标数字 */
const LABEL_HOURS = [8, 12, 16, 20]
const ticks = computed(() => {
  const list = []
  for (let hour = 0; hour <= 24; hour += 1) {
    list.push({
      hour,
      percent: (hour / 24) * 100,
      major: hour % 2 === 0,
      label: props.compact ? '' : LABEL_HOURS.includes(hour) ? `${hour}:00` : ''
    })
  }
  return list
})

const ariaLabel = computed(
  () =>
    `当前时间 ${clockText.value}，${dateText.value}，开放时段 08:00 至 22:00` +
    (props.summary ? `，${props.summary}` : '')
)
</script>

<style scoped>
/* 主视觉条：渐变主场（夜蓝→天光蓝→薄荷青）+ 96px 网格光 + 右上角光环 */
.time-band {
  position: relative;
  overflow: hidden;
  margin: 0 32px;
  border-radius: var(--radius-lg);
  background: var(--grad);
  color: #fff;
  box-shadow: 0 18px 48px rgba(11, 42, 91, 0.28);
}
.time-band::after {
  content: '';
  position: absolute;
  top: -240px;
  right: -140px;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  pointer-events: none;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.3), transparent 62%);
}

.band-grid {
  position: absolute;
  inset: 0;
  background-image: repeating-linear-gradient(
      to right,
      var(--grid-ink) 0 1px,
      transparent 1px 96px
    ),
    repeating-linear-gradient(to bottom, var(--grid-ink) 0 1px, transparent 1px 96px);
  pointer-events: none;
}

.band-inner {
  position: relative;
  z-index: 1;
  max-width: 1240px;
  margin: 0 auto;
  padding: 26px 32px 24px;
}

.band-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
}

.band-date {
  font-size: var(--text-sm);
  font-weight: 500;
  color: rgba(255, 255, 255, 0.82);
  font-variant-numeric: tabular-nums;
}

.band-summary {
  font-size: var(--text-sm);
  font-weight: 500;
  color: rgba(255, 255, 255, 0.82);
  font-variant-numeric: tabular-nums;
}

/* 主时钟：全站仅此一处使用 --text-display-xl */
.band-clock {
  margin-top: 8px;
  font-family: var(--font-num);
  font-size: var(--text-display-xl);
  font-weight: 600;
  line-height: 1;
  letter-spacing: -0.03em;
  font-variant-numeric: tabular-nums;
  color: #fff;
  text-shadow: 0 2px 24px rgba(4, 20, 46, 0.28);
}

.band-ruler {
  position: relative;
  height: 34px;
  margin-top: 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
}

.ruler-closed {
  position: absolute;
  top: 0;
  bottom: 0;
  background: rgba(4, 18, 40, 0.22);
}

.ruler-tick {
  position: absolute;
  top: 0;
  width: 1px;
  height: 6px;
  background: rgba(255, 255, 255, 0.3);
}

.ruler-tick.is-major {
  height: 11px;
  background: rgba(255, 255, 255, 0.5);
}

.ruler-label {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  font-family: var(--font-num);
  font-size: var(--text-xs);
  font-style: normal;
  color: rgba(255, 255, 255, 0.7);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 「此刻」指针：唯一高亮，晨光橙小帽 + 白色细线 + 一圈光晕 */
.ruler-needle {
  position: absolute;
  top: -6px;
  bottom: -6px;
  width: 1px;
  background: rgba(255, 255, 255, 0.94);
}

.needle-cap {
  position: absolute;
  top: -4px;
  left: 50%;
  width: 8px;
  height: 8px;
  transform: translateX(-50%) rotate(45deg);
  background: var(--sun);
  box-shadow: 0 0 0 3px rgba(255, 154, 60, 0.24), 0 0 16px rgba(255, 154, 60, 0.7);
}

.band-window {
  margin-top: 12px;
  font-size: var(--text-xs);
  color: rgba(255, 255, 255, 0.66);
  font-variant-numeric: tabular-nums;
}

/* 精简版：管理端欢迎页（在页面容器内，不需要外边距） */
.time-band.is-compact {
  margin: 0;
  border-radius: var(--radius-card);
}

.time-band.is-compact .band-clock {
  font-size: var(--text-lg);
  font-weight: 600;
  letter-spacing: 0.01em;
  text-shadow: none;
}

.time-band.is-compact .band-inner {
  padding: 20px 24px 18px;
}

.time-band.is-compact .band-ruler {
  height: 20px;
  margin-top: 12px;
}

.time-band.is-compact .band-window {
  margin-top: 8px;
}

@media (max-width: 768px) {
  .time-band {
    margin: 0 16px;
  }

  .band-inner {
    padding: 20px 18px 18px;
  }

  .band-clock {
    font-size: var(--text-xl);
  }

  .band-top {
    flex-direction: column;
    gap: 2px;
  }
}
</style>