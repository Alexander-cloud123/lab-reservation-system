<template>
  <!-- 全局悬浮 AI 预约助手（需求文档 2.4：学生端所有页面右下角悬浮球 → 侧边对话窗口） -->
  <div class="ai-assistant">
    <!-- AI 未启用时悬浮球不渲染，系统完全退化为纯预约系统 -->
    <el-button
      v-if="aiEnabled"
      class="assistant-ball"
      type="primary"
      circle
      aria-label="打开 AI 预约助手"
      :icon="ChatDotRound"
      @click="drawerVisible = true"
    />

    <el-drawer v-model="drawerVisible" title="AI 预约助手" size="380px" class="assistant-drawer" @close="handleClose">
      <div class="chat-body" ref="chatBodyRef">
        <div v-for="(msg, index) in messages" :key="index" :class="['chat-msg', msg.role]">
          <!-- AI 气泡：Markdown 渲染（R13-2），html 仅来自 renderMarkdown（markdown-it 转义 + DOMPurify 消毒） -->
          <div v-if="msg.role === 'ai'" :class="['chat-bubble', { 'is-typing': msg.typing }]">
            <div class="markdown-body" v-html="msg.html || ''"></div><span
              v-if="msg.typing"
              class="typing-cursor"
              aria-hidden="true"
            ></span>
          </div>
          <!-- 用户气泡保持纯文本：不渲染 Markdown，防格式错位与注入 -->
          <div v-else class="chat-bubble">{{ msg.content }}</div>
        </div>
      </div>
      <div class="chat-footer">
        <div class="chat-input">
          <el-input
            v-model="inputText"
            placeholder="问我：怎么预约 / 怎么取消 / 审核要多久…"
            @keyup.enter="send"
          />
          <!-- 发送中切换为「停止」：点击中断 SSE 接收（R13-1）；
               中性描边样式而非危险红——停止是常规操作，不暗示出错 -->
          <el-button v-if="!sending" type="primary" @click="send">发送</el-button>
          <el-button v-else :icon="VideoPause" @click="stopGenerating">停止</el-button>
        </div>
        <div class="chat-tip">
          <!-- 降级时透出后端原因：此时回答来自本地 FAQ 规则，标注「AI 生成」不准确（AGENTS 4.4 标注口径） -->
          <el-text v-if="degradeNotice" type="warning" size="small">{{ degradeNotice }}</el-text>
          <!-- AI 双开关关闭：不是降级，不适用「AI 生成」标注，单独以 info 文案区分 -->
          <el-text v-else-if="aiOffNotice" type="info" size="small">{{ aiOffNotice }}</el-text>
          <el-text v-else type="info" size="small">AI 生成，仅供参考 · 仅解答预约相关问题</el-text>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref, nextTick, onBeforeUnmount } from 'vue'
import { ChatDotRound, VideoPause } from '@element-plus/icons-vue'
import { aiChatStream } from '@/api/ai'
import { probeAiRecommend } from '@/utils/aiProbe'
import { renderMarkdown } from '@/utils/markdown'

/**
 * 全局悬浮「AI 预约助手」（R7，需求文档 2.4）
 * 场景绝对限定：仅解答预约/教室/个人记录相关问题，无关问题由后端返回预设话术；
 * 当前会话不持久化（组件内存态，刷新即清）；ai.enable=false 时悬浮球隐藏
 */
const aiEnabled = ref(false)
const drawerVisible = ref(false)
const inputText = ref('')
const sending = ref(false)
const chatBodyRef = ref(null)
/** 最近一次回答的降级提示（后端 message，非空 = 当前为本地 FAQ 规则回答） */
const degradeNotice = ref('')
/** 最近一次回答时 AI 总开关处于关闭态（meta.enabled=false，与限流降级区分，不套 warning 样式） */
const aiOffNotice = ref('')
const WELCOME_TEXT = '你好，我是 AI 预约助手。可以问我如何预约教室、如何取消预约、审核流程、我的预约记录等问题。'
const messages = ref([{ role: 'ai', content: WELCOME_TEXT, html: renderMarkdown(WELCOME_TEXT) }])

/** 当前流式请求的中断器（发送中关闭抽屉/卸载组件时中断，避免向已销毁界面写数据） */
let activeController = null

/**
 * Markdown 增量渲染调度（R13-2）：
 * delta 到达时只按帧合并（rAF 防抖，不逐字符重排）；
 * 流终（finally）必须再用完整文本同步渲染一次，保证半截代码块/列表最终闭合。
 */
let renderRaf = 0
function scheduleRender(bubble) {
  if (renderRaf) {
    cancelAnimationFrame(renderRaf)
  }
  renderRaf = requestAnimationFrame(() => {
    renderRaf = 0
    bubble.html = renderMarkdown(bubble.content)
  })
}

/** 终态全量渲染（done/error/关闭文案/兜底文案后统一收敛，未闭合块在此刻补全） */
function renderFinal(bubble) {
  if (renderRaf) {
    cancelAnimationFrame(renderRaf)
    renderRaf = 0
  }
  bubble.html = renderMarkdown(bubble.content)
}

/**
 * 探测 AI 可用性（推荐接口返回 enabled=false 时隐藏悬浮球）。
 * N5：统一走 probeAiRecommend（按 userId 缓存，与 ClassroomList 合并同一次调用；
 * 不再直接调 aiRecommend({ userId })，后端只认 UserContext）
 */
onMounted(async () => {
  const res = await probeAiRecommend()
  aiEnabled.value = !!(res && res.enabled === true)
})

async function send() {
  const question = inputText.value.trim()
  if (!question || sending.value) {
    return
  }
  // R13-3 多轮上下文：在 push 当前问题之前，取欢迎语之后最近 10 条有内容的对话；
  // 被停止而保留的半截回答也是真实历史，照常携带；空内容气泡已在收尾时移除，无需特判
  const history = messages.value
    .slice(1)
    .filter((m) => m.content && m.content.trim())
    .slice(-10)
    .map((m) => ({ role: m.role === 'user' ? 'user' : 'assistant', content: m.content }))
  messages.value.push({ role: 'user', content: question })
  inputText.value = ''
  // 先推入空 AI 气泡：meta/delta 到达前即有占位，delta 直接追加（打字机）
  messages.value.push({ role: 'ai', content: '', html: '', typing: true })
  const bubbleIndex = messages.value.length - 1
  scrollToBottom()

  sending.value = true
  const ctrl = new AbortController()
  activeController = ctrl
  let degradedMessage = ''
  let disabledMessage = ''
  // 保存气泡对象引用：主动中断空气泡被 splice 后，按下标写入会误伤其他消息
  const bubble = messages.value[bubbleIndex]

  /** 向当前 AI 气泡追加片段并跟随滚动；Markdown 按帧防抖渲染（半截语法到流末统一收敛） */
  const appendDelta = (text) => {
    bubble.content += text
    scheduleRender(bubble)
    scrollToBottom()
  }

  try {
    await aiChatStream(
      { question, history },
      {
        onMeta: (meta) => {
          if (!meta) {
            return
          }
          if (meta.enabled === false) {
            // AI 双开关关闭：meta 之后只有 done 没有 delta，气泡直接展示关闭文案，
            // 与「服务异常/限流降级」区分，也避免落入「暂时无法回答」兜底
            disabledMessage = meta.message || 'AI 服务未启用，当前为纯预约系统模式'
            bubble.content = disabledMessage
            return
          }
          // 降级时底栏切换为后端原因文案（与非流式时代 message 口径完全一致）
          if (meta.degraded && meta.message) {
            degradedMessage = meta.message
          }
        },
        onDelta: appendDelta,
        onDone: (info) => {
          // 双保险：异常时序下 meta 未带出关闭态时，按 finishReason=disabled 补关闭文案
          if (info && info.finishReason === 'disabled' && !bubble.content) {
            disabledMessage = 'AI 服务未启用，当前为纯预约系统模式'
            bubble.content = disabledMessage
          }
        },
        onError: (msg) => {
          bubble.content = msg || '抱歉，服务暂时不可用，请稍后再试'
        }
      },
      ctrl.signal
    )
    if (!bubble.content) {
      if (ctrl.signal.aborted) {
        // 用户主动中断且尚未收到任何内容：移除空 AI 气泡，仅保留用户提问
        messages.value.splice(bubbleIndex, 1)
      } else {
        // 流正常结束但没有任何文本（异常形态）：沿用旧兜底文案，避免空气泡
        bubble.content = '抱歉，暂时无法回答，请稍后再试'
      }
    }
    degradeNotice.value = degradedMessage
    aiOffNotice.value = disabledMessage
  } catch {
    // aiChatStream 内部已处理错误与中断；此处仅在非主动中断时兜底
    if (!ctrl.signal.aborted && !bubble.content) {
      bubble.content = '抱歉，服务暂时不可用，请稍后再试'
    }
  } finally {
    // 流终强制全量重渲染一次：完成代码块/列表等块级语法闭合（两段式渲染的收敛点）；
    // 空气泡已被上面的 abort/兜底分支 splice 时，渲染游离对象无副作用
    renderFinal(bubble)
    bubble.typing = false
    sending.value = false
    activeController = null
    scrollToBottom()
  }
}

/**
 * 停止生成（R13-1）：中断进行中的流式接收。
 * 仅 abort，不做其他状态操作——send() 的收尾流程在 AbortError 后统一执行：
 * 已收到片段的气泡原样保留（半截真实回答），尚无内容的空气泡被移除，输入框立即恢复可用。
 */
function stopGenerating() {
  if (activeController) {
    activeController.abort()
  }
}

/** 关闭抽屉：中断进行中的流式接收（已收到的片段保留在会话中） */
function handleClose() {
  if (activeController) {
    activeController.abort()
    activeController = null
  }
}

onBeforeUnmount(() => {
  if (activeController) {
    activeController.abort()
    activeController = null
  }
  if (renderRaf) {
    cancelAnimationFrame(renderRaf)
    renderRaf = 0
  }
})

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
  width: 48px;
  height: 48px;
  font-size: 20px;
  background: var(--brand-primary) !important;
  border: none !important;
  transition: background-color 0.15s ease;
}
.assistant-ball:hover {
  background: var(--brand-primary-hover) !important;
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
  padding: 12px;
  border-radius: var(--radius-lg);
  font-size: 14px;
  line-height: 22px;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-msg.ai .chat-bubble {
  background: var(--bg-fill);
  color: var(--text-regular);
  border-top-left-radius: var(--radius-sm);
  /* Markdown 块级元素自带间距与换行，pre-wrap 会与 <p>/<br> 重叠 */
  white-space: normal;
}

/* ===== Markdown 正文（R13-2）：样式全部映射项目 token，不引第三方皮肤 ===== */
.markdown-body {
  font-size: 14px;
  line-height: 22px;
  color: var(--text-regular);
  word-break: break-word;
}

.markdown-body :deep(p) {
  margin: 0 0 6px;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  margin: 10px 0 6px;
  color: var(--text-primary);
  font-weight: 600;
  line-height: 24px;
}

.markdown-body :deep(h1) {
  font-size: 16px;
}

.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4) {
  font-size: 15px;
}

.markdown-body :deep(h1:first-child),
.markdown-body :deep(h2:first-child),
.markdown-body :deep(h3:first-child),
.markdown-body :deep(h4:first-child) {
  margin-top: 0;
}

.markdown-body :deep(strong) {
  color: var(--text-primary);
  font-weight: 600;
}

.markdown-body :deep(a) {
  color: var(--brand-primary);
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 4px 0;
  padding-left: 22px;
}

.markdown-body :deep(li) {
  margin: 2px 0;
}

.markdown-body :deep(li > ul),
.markdown-body :deep(li > ol) {
  margin: 2px 0;
}

/* 行内代码：浅灰底 + 1px 描边 + 小圆角（无阴影、无高饱和色块） */
.markdown-body :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Consolas, 'Liberation Mono', monospace;
  font-size: 12.5px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-sm);
  padding: 1px 5px;
}

.markdown-body :deep(pre) {
  margin: 6px 0;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-color-light);
  border-radius: var(--radius-md);
  overflow-x: auto;
}

.markdown-body :deep(pre code) {
  display: block;
  padding: 0;
  background: none;
  border: none;
  border-radius: 0;
  font-size: 12.5px;
  line-height: 20px;
  white-space: pre;
}

.markdown-body :deep(blockquote) {
  margin: 6px 0;
  padding: 2px 0 2px 10px;
  border-left: 3px solid var(--brand-primary-border);
  color: var(--text-secondary);
}

.markdown-body :deep(hr) {
  margin: 10px 0;
  border: none;
  border-top: 1px solid var(--border-color-light);
}

.markdown-body :deep(table) {
  margin: 6px 0;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 4px 8px;
  border: 1px solid var(--border-color-light);
  text-align: left;
}

.markdown-body :deep(th) {
  background: var(--bg-subtle);
  color: var(--text-primary);
  font-weight: 600;
}

/* 打字中：末段落为 inline，使光标停在最后一行文本基线后（块级结尾时光标自然落到下一行） */
.chat-bubble.is-typing .markdown-body :deep(p:last-child) {
  display: inline;
}

.chat-msg.user .chat-bubble {
  background: var(--brand-primary);
  color: #fff;
  border-top-right-radius: var(--radius-sm);
}

.chat-footer {
  border-top: 1px solid var(--border-color-light);
  padding-top: 12px;
}

.chat-input {
  display: flex;
  gap: 8px;
}

.chat-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-placeholder);
  text-align: center;
}

.typing-cursor {
  display: inline-block;
  width: 6px;
  height: 1em;
  margin-left: 2px;
  background: currentColor;
  vertical-align: text-bottom;
  animation: ai-caret-blink 0.9s steps(1) infinite;
}

@keyframes ai-caret-blink {
  50% {
    opacity: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .typing-cursor {
    animation: none;
  }
}
</style>
