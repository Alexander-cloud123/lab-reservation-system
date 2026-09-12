<template>
  <!-- 全局悬浮 AI 预约助手（需求文档 2.4：学生端所有页面右下角悬浮球 → 侧边对话窗口） -->
  <div class="ai-assistant">
    <!-- AI 未启用时悬浮球不渲染，系统完全退化为纯预约系统 -->
    <el-button v-if="aiEnabled" class="assistant-ball" type="warning" circle :icon="ChatDotRound" @click="drawerVisible = true" />

    <el-drawer v-model="drawerVisible" title="AI 预约助手" size="380px" class="assistant-drawer">
      <div class="chat-body" ref="chatBodyRef">
        <div v-for="(msg, index) in messages" :key="index" :class="['chat-msg', msg.role]">
          <div class="chat-bubble">{{ msg.content }}</div>
        </div>
      </div>
      <div class="chat-footer">
        <div class="chat-input">
          <el-input
            v-model="inputText"
            placeholder="问我：怎么预约 / 怎么取消 / 审核要多久…"
            @keyup.enter="send"
          />
          <el-button type="primary" :loading="sending" @click="send">发送</el-button>
        </div>
        <div class="chat-tip">AI 生成，仅供参考 · 仅解答预约相关问题</div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, nextTick } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { aiChat, aiRecommend } from '@/api/ai'
import { useUserStore } from '@/stores/user'

/**
 * 全局悬浮「AI 预约助手」（R7，需求文档 2.4）
 * 场景绝对限定：仅解答预约/教室/个人记录相关问题，无关问题由后端返回预设话术；
 * 当前会话不持久化（组件内存态，刷新即清）；ai.enable=false 时悬浮球隐藏
 */
const userStore = useUserStore()

const aiEnabled = ref(false)
const drawerVisible = ref(false)
const inputText = ref('')
const sending = ref(false)
const chatBodyRef = ref(null)
const messages = ref([
  { role: 'ai', content: '你好，我是 AI 预约助手。可以问我如何预约教室、如何取消预约、审核流程、我的预约记录等问题。' }
])

/** 探测 AI 可用性（推荐接口返回 enabled=false 时隐藏悬浮球） */
onMounted(async () => {
  const userId = userStore.userInfo ? userStore.userInfo.id : null
  if (!userId) {
    return
  }
  try {
    const res = await aiRecommend({ userId })
    aiEnabled.value = !!(res.data && res.data.enabled === true)
  } catch (e) {
    aiEnabled.value = false
  }
})

async function send() {
  const question = inputText.value.trim()
  if (!question || sending.value) {
    return
  }
  messages.value.push({ role: 'user', content: question })
  inputText.value = ''
  scrollToBottom()
  sending.value = true
  try {
    const res = await aiChat({ question })
    const answer = res.data && res.data.answer ? res.data.answer : '抱歉，暂时无法回答，请稍后再试'
    messages.value.push({ role: 'ai', content: answer })
  } catch (e) {
    // 统一错误提示已由 request.js 处理
    messages.value.push({ role: 'ai', content: '抱歉，服务暂时不可用，请稍后再试' })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (chatBodyRef.value) {
      chatBodyRef.value.scrollTop = chatBodyRef.value.scrollHeight
    }
  })
}
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2000;
}

.assistant-ball {
  width: 52px;
  height: 52px;
  font-size: 22px;
  box-shadow: 0 4px 14px rgba(230, 162, 60, 0.45);
}

.assistant-drawer {
  display: flex;
  flex-direction: column;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 2px 16px;
}

.chat-msg {
  display: flex;
}

.chat-msg.user {
  justify-content: flex-end;
}

.chat-msg.ai {
  justify-content: flex-start;
}

.chat-bubble {
  max-width: 80%;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-msg.ai .chat-bubble {
  background: #f4f4f5;
  color: #303133;
}

.chat-msg.user .chat-bubble {
  background: #e6a23c;
  color: #fff;
}

.chat-footer {
  border-top: 1px solid #ebeef5;
  padding-top: 12px;
}

.chat-input {
  display: flex;
  gap: 8px;
}

.chat-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #c0c4cc;
  text-align: center;
}
</style>
