import { onUnmounted, readonly, ref } from 'vue'

/**
 * 全局「当前时间」：全站共用一个定时器（30s 一跳），按引用计数自动启停。
 * 用于时刻带 / 刻度尺的「此刻」指针；避免每个页面各自 setInterval。
 */
const now = ref(new Date())
let timer = null
let watchers = 0
const TICK_MS = 30_000

function start() {
  if (timer !== null) {
    return
  }
  timer = setInterval(() => {
    now.value = new Date()
  }, TICK_MS)
}

function stop() {
  if (timer === null) {
    return
  }
  clearInterval(timer)
  timer = null
}

/**
 * 在组件内使用：组件卸载时自动减引用，最后一个使用者离开后停表。
 * @returns {import('vue').Ref<Date>} 只读的当前时间（每 30s 更新一次）
 */
export function useNow() {
  watchers += 1
  now.value = new Date()
  start()
  onUnmounted(() => {
    watchers -= 1
    if (watchers <= 0) {
      watchers = 0
      stop()
    }
  })
  return readonly(now)
}
