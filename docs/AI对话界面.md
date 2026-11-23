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
| 左栏 | 248px，`#f7f8fa`；品牌标 +「新对话」（品牌浅底主按钮）+ 搜索框（`aria-label="搜索历史会话"`）+ 会话列表按「今天 / 昨天 / 更早」分组（标题为第一个问题前 18 字，hover 出现删除）+ 底部「只读不写，预约操作需你手动提交」 |
| 顶栏 | 46px；返回箭头（回 `/student/home`）、当前会话标题、「回答中… / 就绪」状态字 |
| 正文 | 最大 768px 居中；欢迎屏星芒标 + `你好，我是 AI 预约助手` + 建议问题横排 2 列（默认 3 条，「更多建议」展开全部 5 条）；消息流即对话 |
| 输入区 | 底部 dock，16px 圆角容器 + 自增高 textarea（最高 148px）+ 34px 圆形发送按钮（流式中变「停止」）；箱内快捷键提示「Enter 发送，Shift+Enter 换行」 |
| 提示条 | dock 上方一行：默认「AI 生成，仅供参考；仅解答预约相关问题」；降级时换后端 `message`（warning 色）；AI 关闭时 info 色 |
| 窄屏 | `<900px` 隐藏左栏、建议问题改单列；`<560px` 页面边距 12px |

截图：[`docs/images/08-ai-chat.png`](images/08-ai-chat.png)（`node web/scripts/capture-screens.mjs` 第 8 张，1440×900）。

路由：`/student/ai`（`StudentAiChat`），需要登录（`meta.requiresAuth`，`roles: [0]`）。
入口球保留在教室列表右下（48px，点击后进入该页），座席式入口「AI 助手」出现在顶栏菜单（`aiReady` 为真时）。

## 3. 状态与滚动

- **历史存在浏览器本地**：`web/src/utils/aiChatStorage.js` 把会话写到
  `localStorage` 的 `reservation_ai_conversations:<userId>`，结构 `{ version, activeId, conversations[] }`。
  - 不存渲染后的 HTML：恢复时用 Markdown 重新渲染（示例用 DOMPurify 白名单，避免把历史 HTML 当可信内容）。
  - 容量：最多 40 个会话、每会话 60 条消息、单条回答 12000 字、推理文本 4000 字；序列化超过 2MB 按活动时间淘汰，`QuotaExceededError` 时再淘汰一次，仍失败就静默跳过（聊天可用优先）。
  - 写入节奏：`watch(conversations, activeId)` 400ms 防抖落盘；新建 / 切换 / 删除 / 发送结束（`finally`）立即落盘。
  - 刷新恢复：思考步骤与「已思考 X.X 秒」一起恢复；`state: thinking` 的消息一律收敛为「已停止」，不会自动补发请求。
  - 退出登录：`clearAuth()` 同时清空本地会话（同机换号不串数据）；多标签页为「后写覆盖」，不做跨标签同步。
  - 坏数据：JSON 解析失败或 `version` 不符时清掉该 key 再按空态启动，不抛出到页面。
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

## 6. 预约方案卡（R15-1 / R15-2）

「帮我约明天下午的机房」这类问题不再只是文字建议：后端在对话流里插入一个 `plan` 帧，前端渲染成方案卡。
方案卡只负责「提议」，点「去预约」会打开既有的确认弹窗（第 3 步预填 + 实时冲突校验），最终仍由用户点「提交预约」。

- 结构：`AiPlanCard.vue`，`role="group"` + `aria-label="预约方案：日期 时段"`；候选行含教室名、楼栋/编号、类型/容量、
  排序理由与「去预约」按钮（`aria-label` 带教室名和所选时段，避免多个「去预约」不可区分）；空候选只给澄清文案，不给按钮。
- **结束时间兜底（R15-2）**：只说「明天下午两点」也能出方案——后端按 1/2/3 小时估出最多三档，方案卡顶部是
  `.plan-time-chip` 时段按钮（「14:00–15:00 · 2 间」），默认选中候选最多的那一档；切换只替换本地候选，不再发请求。
  选中档的结束时间会随「去预约」写进确认页（`reserve` 事件带 `endTime`），用户仍可手动改。
  估时说明写在 `.plan-guess`（「未说明结束时间，已按 2 小时估算，可在上方切换时段」），不假装用户说过。
- 落点：方案挂在最后一条 AI 消息的 `plan` 字段上，渲染在正文之后、光标之前，只在该回答结束后出现。
- 持久化：`aiChatStorage.sanitizePlan` 只保留展示字段（候选 ≤10、可选档 ≤3、文本截断）；恢复历史后按钮仍可用，
  但点击会重新打开确认弹窗并**重新判一次冲突**，不复用旧结论。
- 边界：方案不写库、不缓存（规划分支跳过结果缓存），每次提问都重新解析与检索；可选档位最多三档且不越过 22:00。
- 设计依据与意图/排序口径见 [`docs/AI智能体设计.md`](AI智能体设计.md)。

## 7. 页面上下文（R15-3）

在教室详情页点悬浮球，进入对话时会带 `?roomId=<id>`，让 AI 知道「你在看哪间教室」：

- 来源与展示：`AiChatView` 读取 `route.query.roomId`，校验为正整数后拉一次教室详情；成功后顶栏下方出现
  `.ai-context-chip`（「正在查看：A101 多媒体教室」+ ×），教室不存在 / 停用 / 请求失败时静默不显示。
  欢迎屏会把首个建议换成「这间教室明天下午有空吗？」。
- 请求：`context: { classroomId }` 只带结构化 id（不带任意文本，避免 Prompt 注入）；点 × 移除后，后续请求不再带该字段；
  刷新页面会按 URL 恢复上下文；历史会话本身不存上下文（避免「这间」指向过期对象）。
- 后端：只接收 `AiChatContext.classroomId`，查库校验存在且启用后，才在模型上下文里写「【用户当前查看】…（楼栋 编号 类型 容量）」，
  并提示「用户说这间 / 这个教室时指它」；规划分支里，这间教室只要满足容量 / 类型 / 时段就直接排第一（理由写「你正在看这间」），
  不满足则照常排序，不硬塞。
- 隐私：教室信息本就是公开资源列表，上下文只用于这一次请求，不落库、不写历史、不进日志。

## 8. 对既有 E2E 的兼容

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
历史持久化另有 `web/e2e/specs/32-ai-history.spec.js` 4 条：刷新后会话与思考面板恢复、两条会话按最近活动排序、
删除（二次确认）后刷新消失、退出登录清空本地历史。

## 9. 验证命令

```powershell
# 前端静态检查与构建（Windows 下 esbuild 临时目录需指向工作区）
cd web
npm run lint
$env:TEMP="$PWD\..\.devtmp\tmp"; $env:TMP=$env:TEMP; npm run build

# AI 相关用例（Playwright 自动起 Vite；后端需在 8080）
npx playwright test specs/30-ai.spec.js specs/32-ai-history.spec.js specs/33-ai-plan.spec.js specs/34-ai-context.spec.js --project=chromium

# 全量 Chromium 回归（跑前复位种子，避免历史预约干扰造数）
npx playwright test --project=chromium
```

## 10. 已知边界

- 会话只存浏览器本地（`localStorage` 单机单浏览器），不落库、不跨设备；换浏览器或清缓存即丢失。
  未新增会话表：`需求设计文档.md` 没有消息中心/会话存储条款，动表结构需先变更需求。
- 容量上限见第 3 节：40 个会话 / 每会话 60 条 / 单条 12000 字；超限按活动时间淘汰，淘汰不提示。
- 多标签页同时打开同一账号时「后写覆盖」，不做跨标签同步或冲突合并。
- 标题截断到 18 字，未做重命名（`renamed` 字段已预留）；未做分享、导出、置顶。
- 移动端为「隐藏左栏 + 单列建议」，未做独立手势交互。
- 真实模型下的推理原文取决于上游是否返回 `reasoning_content`；未返回时面板只展示真实执行轨迹。
