# AI 助手思考过程设计

> 目标：让「AI 预约助手」的等待过程可解释、可回看，同时不虚构模型思维链。

## 1. 为什么这么做

主流 AI 网页端（DeepSeek 等）的对话体验有三个共同点：

1. **思考过程可见、可折叠**——用户知道系统在做什么，而不是只看到一个转圈；
2. **思考有耗时**——等待时间被量化，用户对「还要多久」有预期；
3. **思考中有反馈**——轻量动画填补空窗期，减少焦虑。

本项目的 AI 助手在此之前只有一段「正在输入」光标：用户看不到后端在判断场景、查预约记录、调用模型，也无法判断卡住是网络慢还是模型慢。

### 设计原则：真实轨迹 + 推理透传，不伪造思维链

常见的取巧做法是让模型「先输出一段思考」，再输出答案。本项目不做，原因：

- **不诚实**：伪造的「思考」是模型先生成的表演文本，与真实执行无关，出问题时反而误导排查；
- **不可控**：多一段生成意味着更多 token、更高超时与降级概率；
- **无法验证**：真实轨迹的每一步都有服务端计时，可被单测与故障演练验证。

因此思考面板展示两类信息：

| 来源 | 内容 | 何时有 |
|------|------|--------|
| 服务端执行轨迹 | 场景判断 / 检索预约记录 / 调用模型 / 校验回答 / 缓存 / 降级 | 每次请求都有（AI 关闭态除外） |
| 上游模型推理 | 若网关返回 `reasoning_content`（或别名 `reasoning`）则原样透传 | 取决于模型与网关；没有时面板明确说明 |

## 2. 事件协议（SSE）

在既有 `meta / delta / reset / done / error` 基础上新增两个事件，顺序约束如下：

```
thinking(scene running) → thinking(scene done) → thinking(retrieve running) → …
→ meta → reasoning* → thinking(summary) → delta* → thinking(verify) → done
```

- `thinking` **允许先于 `meta`**（`meta` 仍必须先于任何 `delta`）；前端因此可以在首字节到达前就开始计时。
- `thinking(summary)` 一定先于首个 `delta`：正文开始输出时，头部即可显示「已思考 X.X 秒」。
- `thinking(verify)` 在最后一个 `delta` 之后、`done` 之前。
- `reasoning` 帧可出现在任意位置，前端按到达顺序拼接。
- **`done` 无业务载荷**：结构化接口的 `done` 帧线上是 `event:done\ndata:`（空 data，没有 JSON 体），问答接口的 `done` 带 `{finishReason}`。前端解析器必须把「空载荷事件」当作合法终态分发，否则 `done` 会被当成脏帧丢弃、调用方永久等待（真实后端下 AI 入口曾因此全隐藏，回归用例见 `30-ai.spec.js`「结构化流空载荷 done 帧仍是终态」）。

### `thinking` 负载

```json
{ "id": "retrieve", "label": "检索你的预约记录", "status": "running", "elapsedMs": null }
{ "id": "retrieve", "label": "检索你的预约记录", "status": "done",    "elapsedMs": 12 }
```

| 字段 | 取值 | 说明 |
|------|------|------|
| `id` | `scene` / `retrieve` / `generate` / `verify` / `cache` / `fallback` / `summary` | 步骤标识，前端用于去重与排序 |
| `label` | 中文短句 | 服务端下发，前端不自行翻译 |
| `status` | `running` / `done` / `failed` | `running` 时 `elapsedMs` 为 `null` |
| `elapsedMs` | 整数毫秒 | 该步真实耗时；`summary` 的值为从请求开始到该帧的总耗时 |

### `reasoning` 负载

```json
{ "text": "用户问的是取消规则，先确认是否属于预约范围。" }
```

逐帧拼接到面板的推理区；**不并入正文**，也不参与结果校验与缓存（缓存只存最终回答）。

## 3. 计时口径

- 服务端在 `streamChat` 入口记录 `System.nanoTime()`，每步 `begin/end` 各自计时；
- `summary` 的耗时 = trace 创建到发出 summary 的墙钟时间，即「用户实际等待到首字的时间」；
- 前端在 `thinking` 态每 100 ms 刷新一次显示（1 秒内保留一位小数），离开 `thinking` 后改用服务端下发的 `elapsedMs`，避免前后端口径不一致；
- 降级时仍保留已发生步骤的真实耗时（例如「调用模型生成回答 · 失败 · 1.2 秒」）。

## 4. 后端实现

| 文件 | 职责 |
|------|------|
| `ai/dto/AiStreamThinking.java` | `thinking` 事件负载 record |
| `ai/dto/AiStreamReasoning.java` | `reasoning` 事件负载 record |
| `ai/support/AiThinkingTrace.java` | 步骤编排与计时；写失败即置 closed，后续事件静默丢弃（辅助信息不阻断主流程） |
| `ai/support/ChatStreamSink.java` | 新增 `thinking` / `reasoning` **default 方法**，老测试桩无需改动 |
| `ai/support/SseChatStreamSink.java` | 映射为同名 SSE 事件 |
| `ai/support/AiStreamFrames.java` | 解析 `delta.reasoning_content`（回退 `delta.reasoning`） |
| `ai/config/AgnesClient.java` | `chatStream(...)` 增加 `reasoningConsumer`，推理不并入 `fullText` |
| `ai/service/impl/AiChatServiceImpl.java` | 按真实流水线发步骤，见下方分支 |

分支与步骤：

| 场景 | 发出的步骤 |
|------|------------|
| 正常 | scene → retrieve → generate → summary →（delta*）→ verify → done |
| 缓存命中 | scene → cache → summary（label「命中缓存」）→ 重放缓存回答 → done |
| 超出范围 | scene → summary（「范围判断完成」）→ 预设话术 → done |
| 上游失败/限流 | scene → retrieve → generate(failed) → fallback → summary（「已降级为本地规则」）→ 降级回答 → done |
| AI 关闭 | 不发任何 thinking/reasoning，直接 meta(disabled) + done |

### 问答上下文注入当前时间

模型看不到系统时钟，只看到预约日期字面量；上下文若不给当前时间，它会自行猜测「今天」。真实链路验收时模型把 2026-11-16 说成「今天是 2026年11月14日」，相对日期全部偏移。

因此 `AiChatServiceImpl.buildContext()` 在上下文首行固定下发：

```
【当前时间】2026-11-16 14:12（Asia/Shanghai，星期日）
```

时间取 `ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))`，格式常量 `CONTEXT_TIME_FORMATTER`（`yyyy-MM-dd HH:mm`），与业务时区口径一致；单测 `AiChatStreamServiceTest#successStreamsAndCaches` 断言下发的用户消息包含该行与当天日期。

## 5. 前端实现

`web/src/components/ai/AiThinkingPanel.vue`：

- **思考中**：三点脉冲动画 + `正在思考 0.4s`，步骤随事件逐条淡入，运行中的步骤有旋转圆点；
- **首个正文到达**：自动折叠为 `已思考 1.6 秒`，折叠动画 220ms；
- **手动展开优先**：用户点击过展开后，正文到达不再强制折叠；
- **展开内容**：竖轨时间线（步骤名 + 状态点 + 每步耗时）与推理原文；推理区固定标注「模型推理 · AI 生成，仅供参考」；
- **没有推理文本时**：显示「当前模型未返回推理文本，以上为本次处理的实际步骤。」，不编造内容；
- **四态提示**：`已降级为本地规则` / `命中缓存` / `已停止` / 生成失败。

无障碍与动效：

- 头部是真正的按钮，`aria-expanded` + `aria-controls`；`:focus-visible` 有可见描边；
- `prefers-reduced-motion: reduce` 下关闭脉冲与折叠过渡；
- 屏幕阅读器仍只播报最终回答（`sr-only` live region 不变），避免逐步朗读步骤打断用户。

滚动策略：仅在用户距底部 ≤ 24 px 时自动跟随；发送时强制回到底部。

## 6. 验证

```powershell
# 后端：思考事件顺序、计时、reasoning 透传、降级/缓存/关闭分支
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' -f server/pom.xml test

# 前端：思考中 → 折叠 → 展开、无推理说明、降级、缓存、关闭态
cd web; npx playwright test specs/30-ai.spec.js --project=chromium
```

E2E 的「思考中」瞬时态用 `web/e2e/helpers/ai.js` 的 `patchStreamingChat()` 逐帧推送真实 `text/event-stream`，
不调用真实模型；真实模型链按以下顺序单独验收（Agnes 免费额度紧，间隔 ≥100 秒）：

```powershell
docker compose build api; docker compose up -d api
node .devtmp\verify-ai-thinking.mjs   # 登录 → POST /api/ai/chat?stream=true → 解析 SSE
```

实测（2026-11-16，agnes-3.0-flash）：`thinking` 9 帧（scene 0 ms、retrieve 17 ms、generate 3790 ms、summary 3813 ms、verify 0 ms）→ `meta` → 30 个 `delta` → `done`，总耗时 4.6 s；上游未返回 `reasoning_content`，面板按设计回退为「只有真实轨迹」。


## 7. 已知边界

1. **推理文本取决于上游**：只有 Agnes 网关返回 `reasoning_content`（或别名 `reasoning`）才有推理区内容。本机用 `agnes-3.0-flash` 实测未返回该字段，故当前实际展示的是「真实执行轨迹」，推理区显示固定的缺省说明，不编造内容；
2. **只覆盖 AI 助手聊天**：结构化流式入口（推荐 / 解析预约 / 合规检查）仍是一行阶段提示，避免同轮改动面过大；
3. **不缓存思考过程**：缓存命中时只重放回答，步骤重新走一遍（scene → cache → summary），保证面板始终反映本次请求的真实情况；
4. **思考事件不是业务数据**：写入失败只影响面板，绝不影响回答输出与降级链路。
