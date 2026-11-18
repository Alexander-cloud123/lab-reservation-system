import { onUnmounted, ref, watch } from 'vue'

/**
 * 数字入场：0 → 目标值（默认 320ms，rAF 驱动，tabular 数字不跳位）。
 * 只在「首次拿到数据」时播放一次；用户偏好减少动效时直接落位。
 * @param {import('vue').Ref<number>|number} source 目标值（响应式或普通数字）
 * @returns {import('vue').Ref<number>} 显示值
 */
export function useCountUp(source, duration = 320) {
  const display = ref(0)
  let frame = null

  function currentTarget() {
    const value = typeof source === 'object' && source !== null ? source.value : source
    const number = Number(value)
    return Number.isFinite(number) ? number : 0
  }

  function cancel() {
    if (frame !== null) {
      cancelAnimationFrame(frame)
      frame = null
    }
  }

  function play() {
    cancel()
    const target = currentTarget()
    const reduced =
      typeof window !== 'undefined' &&
      window.matchMedia &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reduced || duration <= 0) {
      display.value = target
      return
    }
    const from = display.value
    const started = performance.now()
    const step = (time) => {
      const progress = Math.min(1, (time - started) / duration)
      // easeOutCubic：快进慢收，数字收尾不拖沓
      const eased = 1 - (1 - progress) ** 3
      display.value = Math.round(from + (target - from) * eased)
      if (progress < 1) {
        frame = requestAnimationFrame(step)
      } else {
        display.value = target
        frame = null
      }
    }
    frame = requestAnimationFrame(step)
  }

  watch(
    () => currentTarget(),
    () => play(),
    { immediate: true }
  )
  onUnmounted(cancel)

  return display
}
