<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { probeAiRecommend } from '@/utils/aiProbe'
import { saveAiEnabled } from '@/utils/auth'

const router = useRouter()
const userStore = useUserStore()

const aiEnabled = computed(() => userStore.aiEnabled === true)
const hinted = ref(false)
let hintTimer = 0

/**
 * 探测入口可用性：与对话页共用 aiProbe 的按用户缓存；
 * 探测成功即回写持久化标记，避免刷新后首屏悬浮球闪烁。
 */
onMounted(() => {
  probeAiRecommend()
    .then((result) => {
      if (!result) {
        return
      }
      const enabled = result.enabled === true
      if (enabled !== userStore.aiEnabled) {
        userStore.aiEnabled = enabled
        saveAiEnabled(enabled)
      }
    })
    .catch(() => {})
})

function openChat() {
  // R15-3：从教室详情页进入对话时带上「正在查看的教室」，AI 才能听懂「这间教室」
  const current = router.currentRoute.value
  const roomId = current.name === 'ClassroomDetail' ? current.params.id : ''
  router.push({
    path: '/student/ai',
    query: roomId ? { roomId: String(roomId) } : {}
  })
}

/** 鼠标悬停显示一行极简文字提示（不遮挡内容，不打断阅读） */
function showHint() {
  clearTimeout(hintTimer)
  hinted.value = true
}

function hideHint() {
  clearTimeout(hintTimer)
  hintTimer = setTimeout(() => {
    hinted.value = false
  }, 120)
}

onBeforeUnmount(() => clearTimeout(hintTimer))
</script>

<template>
  <div v-if="aiEnabled" class="assistant-entry" @mouseenter="showHint" @mouseleave="hideHint">
    <span v-if="hinted" class="assistant-hint" aria-hidden="true">AI 预约助手</span>
    <button
      type="button"
      class="assistant-ball"
      aria-label="打开 AI 预约助手"
      @click="openChat"
    >
      <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true">
        <path
          d="M12 3.4 13.9 9l5.7 1.9-5.7 1.9L12 18.5l-1.9-5.7L4.4 10.9 10.1 9 12 3.4Z"
          fill="currentColor"
        />
        <path
          d="M18.6 3.2 19.2 4.9l1.7.6-1.7.6-.6 1.7-.6-1.7-1.7-.6 1.7-.6.6-1.7Z"
          fill="currentColor"
          opacity=".7"
        />
      </svg>
    </button>
  </div>
</template>

<style scoped>
.assistant-entry {
  position: fixed;
  right: var(--space-lg);
  bottom: var(--space-lg);
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.assistant-hint {
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  background: var(--surface);
  box-shadow: var(--shadow-float);
  color: var(--ink-soft);
  font-size: var(--text-xs);
  white-space: nowrap;
  animation: hint-in 0.14s ease-out;
}

/* 悬浮球：墨蓝实底 + 白字门牌，不用渐变/发光，靠留白与投影层次 */
.assistant-ball {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border: 1px solid var(--brand);
  border-radius: var(--radius-pill);
  background: var(--brand);
  color: var(--surface);
  font: inherit;
  cursor: pointer;
  box-shadow: var(--shadow-float);
  transition:
    background-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.assistant-ball:hover {
  background: var(--brand-primary-dark, var(--brand));
  box-shadow: var(--shadow-overlay);
  transform: translateY(-1px);
}

.assistant-ball:active {
  transform: translateY(0);
}

.assistant-ball:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 3px;
}

@keyframes hint-in {
  from {
    opacity: 0;
    transform: translateX(4px);
  }

  to {
    opacity: 1;
    transform: translateX(0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .assistant-ball,
  .assistant-hint {
    transition: none;
    animation: none;
  }
}

/* ---------- 第四轮（晨光玻璃）覆盖 ---------- */
.assistant-ball {
  background: var(--grad);
  color: #fff;
  box-shadow: 0 12px 32px rgba(11, 42, 91, 0.32), 0 0 0 1px rgba(255, 255, 255, 0.24) inset;
}
.assistant-ball:hover {
  filter: brightness(1.06);
  box-shadow: 0 16px 38px rgba(11, 42, 91, 0.38);
}
</style>