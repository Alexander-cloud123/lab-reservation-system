# AI 对话界面（DeepSeek 式布局）

> 适用范围：学生端「AI 助手」入口。对应提交 `feat(ai): AI 助手改为 DeepSeek 式全屏对话界面`。
> 后端协议未变（`/api/ai/chat?stream=true` 的 `thinking` / `reasoning` / `meta` / `delta` / `reset` / `done` / `error`），
> 交互语义见 [`AI思考过程设计.md`](AI思考过程设计.md)。

## 1. 为什么改

旧版是 380px 宽的抽屉（`el-drawer`），在作品集里有两个问题：

1. **空间浪费**：思考步骤时间线 + 推理原文 + 回答正文挤在 380px 里，展开思考面板后正文只剩半屏；
2. **不像「AI 产品」**：主流对话产品（DeepSeek / ChatGPT）都是「左侧会话列表 + 居中正文 + 底部大输入框」，
   抽屉形态更像客服插件。这次只改交互容器，**不动模型、不动 Prompt、不动事件协议**。

## 2. 布局

| 区域 | 规格 |
| --- | --- |
| 左栏 | 248px，`#f7f7f8`；品牌标 + 「AI 预约助手」+「新对话」+ 会话列表（标题为第一个问题前 18 字）+ 底部「仅解答预约相关问题 · 只读不写」 |
| 顶栏 | 46px；返回箭头（回 `/student/home`）、当前会话标题、「回答中… / 就绪」状态字 |
| 正文 | 最大 760px 居中；欢迎屏一句 `你好，我是 AI 预约助手` + 4 个建议问题（2 列）；消息流即对话 |
| 输入区 | 底部 dock，自增高 textarea（最高 148px）+ 30px 圆形发送按钮；流式中按钮变「停止」 |
| 提示条 | dock 上方一行：默认「AI 生成，仅供参考 · 仅解答预约相关问题」；降级时换后端 `message`（warning 色）；AI 关闭时 info 色 |
| 窄屏 | `<900px` 隐藏左栏、建议问题改单列；`<560px` 页面边距 12px |

截图：[`docs/images/08-ai-chat.png`](images/08-ai-chat.png)（`node web/scripts/capture-screens.mjs` 第 8 张，1440×900）。

路由：`/student/ai`（`StudentAiChat`），需要登录（`meta.requiresAuth`，`roles: [0]`）。
入口球保留在教室列表右下（48px，点击后进入该页），座席式入口「AI 助手」出现在顶栏菜单（`aiReady` 为真时）。

## 3. 状态与滚动

- **会话只存在内存**：`web/src/composables/useAiChat.js` 管理 `conversations / activeId / messages`，
  刷新即清空（不落库、不写 localStorage）——避免把用户与模型的完整对话留在浏览器里。
- `trace` 与 AI 气泡对象必须 `reactive()` 包裹：流式回调通过局部变量改写状态，裸对象会绕过响应式 set 陷阱，
  面板会永远停在「正在思考」（这是本次实现踩到的真实坑，已写进注释）。
- 滚动采用「跟随但不打扰」：距底小于 40px 才自动跟随；用户上滚阅读历史时，新 delta 不抢滚动位置。
  上翻超过 160px 时浮出「回到底部 ↓」按钮（绝对定位在输入区上方），点击即贴底并恢复跟随。
- 程序化贴底会把 `scroll-behavior` 临时切成 `auto`：`smooth` 动画的首帧滚动事件会把「距底」判成大值，
  跟随随即被误判中断（实现时踩到过，注释已写明）。
- 渲染继续是「流式拼文本 + rAF 节流」，`done` 后再做一次全量 Markdown 渲染（代码块围栏在增量期可能被切碎）。

## 4. 键盘与可访问性

- Enter 发送、Shift + Enter 换行；中文输入法组合态（`isComposing`）不拦截回车。
- Esc 与「停止」按钮等价：流式中按 Esc 立即中断，已收到的片段保留并标「回答已停止生成，可重新生成或再次提问」。
- 回答下方操作行：复制 / 重新生成（仅最后一条回答、且不在生成中显示）/ 失败时「重试」。
  重新生成沿用同一条提问、不追加重复的用户气泡，只替换最后一条回答（`useAiChat.regenerate`）。
- 发送、重新生成后焦点回到输入框，连续追问不用再点一次；流式结束不移动焦点，避免打断阅读。
- 发送按钮有 `aria-label="发送"`，流式中换成文本「停止」，两者都是真实 `<button>`，可 Tab 到达。
- 思考面板：`role="group"` + `aria-label`，折叠按钮带 `aria-expanded` / `aria-controls`，`prefers-reduced-motion` 关闭动画。
- 读屏播报：页面只有一个 `role="status"` 的 `sr-only` live region，内容为**最终回答**的纯文本
  （`DOMPurify` 渲染后取 `textContent`），不逐字播报 delta，也不播报思考面板（面板可主动展开阅读）。
  提示条与横幅不再挂 `role="status"`，避免同一时刻多个 live region 抢读。
- 焦点：进入页面后输入框可聚焦；流式结束不移动焦点，避免打断正在阅读的用户。

## 5. 与思考面板的衔接

- 提问后立即渲染思考面板（`state: thinking`），后端 `thinking` 帧到达即更新步骤；首个正文到达自动折叠为
  「已思考 X.X 秒」，用户手动展开过则不再自动收起（`userExpanded`）。
- 降级（`summary.label = 已降级为本地规则`）→ 面板 `is-degraded`、hover 头显示降级原因；停止 → `已停止`；
  关闭态（`meta.enabled=false`）**不渲染面板**（没有过程可展示，留空壳反而是噪音）。
- 推理原文只有上游真的返回 `reasoning_content` 才显示；本机实测 `agnes-3.0-flash` 不返回该字段，
  面板因此长期处于「真实轨迹」形态，这是预期行为，不是数据丢失。

## 6. 对既有 E2E 的兼容

既有 `30-ai.spec.js` 用旧抽屉的选择器写断言，本次在不改断言主体的前提下做了类名兼容层：

| 旧选择器 | 新结构 |
| --- | --- |
| `.chat-body` | 消息容器保留 `ai-chat-stream chat-body` |
| `.chat-msg.user` / `.chat-msg.ai` | article 同时带 `chat-msg is-user user` / `chat-msg is-assistant ai` |
| `.chat-bubble`（用户） | `ai-chat-bubble chat-bubble` |
| `.chat-bubble` 内的 `.markdown-body` | 回答气泡 `ai-chat-answer chat-bubble` 内嵌 `.markdown-body` |
| `.assistant-drawer` | 不再存在，改为断言 `/student/ai` 与 `.ai-chat-page` |
| `.ai-assistant p[role="status"]` | 页面根带 `ai-assistant`，仅剩一个 live region |

停止用例的计数改为：首次停止后仅余用户提问（1 条），第二次停止后 2 条。

本轮新增 3 条 E2E：重新生成替换最后一条回答、上翻后「回到底部」可见且点击后贴底、Esc 停止后立即恢复可发送。

## 7. 验证命令

```powershell
# 前端静态检查与构建（Windows 下 esbuild 临时目录需指向工作区）
cd web
npm run lint
$env:TEMP="$PWD\..\.devtmp\tmp"; $env:TMP=$env:TEMP; npm run build

# AI 相关用例（Playwright 自动起 Vite；后端需在 8080）
npx playwright test specs/30-ai.spec.js --project=chromium

# 全量 Chromium 回归
npx playwright test --project=chromium
```

## 8. 已知边界

- 会话不持久化：刷新丢失，属刻意取舍（隐私 + 无表结构变更）；若要持久化需新增会话表，超出当前需求边界。
- 左栏会话列表只在本次页面生命周期内有效；标题截断到 18 字，未做重命名（`renamed` 字段已预留）。
- 移动端为「隐藏左栏 + 单列建议」，未做独立手势交互。
- 真实模型下的推理原文取决于上游是否返回 `reasoning_content`；未返回时面板只展示真实执行轨迹。
