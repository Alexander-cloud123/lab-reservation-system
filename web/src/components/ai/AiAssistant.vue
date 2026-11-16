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
  router.push('/student/ai')
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
  right: 24px;
  bottom: 26px;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 8px;
}

.assistant-hint {
  padding: 4px 9px;
  border-radius: var(--radius-md);
  background: var(--bg-card, #fff);
  box-shadow: var(--shadow-raised, 0 2px 8px rgba(31, 35, 41, 0.1));
  color: var(--text-regular);
  font-size: 11.5px;
  white-space: nowrap;
  animation: hint-in 0.14s ease-out;
}

.assistant-ball {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border: 1px solid var(--brand-primary-border, #c2d4ff);
  border-radius: 50%;
  background: var(--brand-primary, #1456f0);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(20, 86, 240, 0.28);
  transition: transform 0.16s ease, box-shadow 0.16s ease, background 0.16s ease;
}

.assistant-ball:hover {
  background: var(--brand-primary-hover, #336df4);
  transform: translateY(-2px);
  box-shadow: 0 8px 22px rgba(20, 86, 240, 0.34);
}

.assistant-ball:active {
  transform: translateY(0);
  background: var(--brand-primary-active, #0442d2);
}

.assistant-ball:focus-visible {
  outline: 2px solid var(--brand-primary-border, #c2d4ff);
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
</style>
