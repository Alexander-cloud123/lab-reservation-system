# 本轮任务说明（R12 · 智能问答 SSE 流式输出）

> 轮次：R12　模块：AI 能力模块　日期：2026-10-15
> 唯一依据：《需求设计文档.md》；技术规格：spec.md；协作章程：AGENTS.md
> 前置基线：R11「AI 观测补全与结果缓存」已完成并并入基线
> **归档说明**：本四件套为 R12 交付后**回补归档**，内容依据提交 `f413bc8`
> 「feat(AI): 智能问答 SSE 流式输出（R12）与四项收尾打磨」如实记录；
> 文中凡标注「后续演进」者均为 R13 对该能力的扩展，**不属于 R12 范围**。

## 一、任务范围

在 R7~R11 已具备「只读、可降级、带观测与结果缓存」的 AI 问答基础上，
本轮把问答从「一次返回整段答案」升级为**真流式打字机**（服务端 SSE 推送、前端逐段追加），
并随主体一并交付收尾打磨：

| 项 | 内容 | 一句话目标 |
|---|---|---|
| 主体 | SSE 流式问答 | `POST /api/ai/chat?stream=true` 事件序列 `meta → delta* → done`，前端打字机逐段追加 |
| 收尾 1 | SSE 超时收尾 | 70s `onTimeout` 补发 `error` 帧再 `complete()`，避免前端打字机光标永驻 |
| 收尾 2 | 关闭态与降级区分 | AI 双开关关闭时**气泡直接展示关闭文案**、底栏用 info 样式，与限流降级的 warning 严格区分 |
| 收尾 3 | 清理死代码 | 删除前端未使用的 `aiChat` 非流式包装，问答入口统一走流式 |
| 收尾 4 | 鉴权同口径 | 原生 fetch 的 401 处理与 axios 封装一致（`clearAuth` + `redirectToLogin`），不允许行为分叉 |

**改动面纪律**：全部改动限定在 AI 独立包 + AI 前端组件 + AI 相关 E2E +
`web/src/api/ai.js`、`web/src/utils/request.js`、`server/src/main/resources/application.yml`（AI 段）。
核心业务（冲突检测、状态流转、权限、并发锁）零改动；不新建/修改数据库表；不提交 git。

## 二、需求依据

| 需求/规格条款 | 位置 | 本轮如何满足 |
|---|---|---|
| AI 接口响应 ≤60s | 需求设计文档 1.5「非功能需求」 | SSE 超时取 70s（略大于上游读超时 60s，避免容器先于上游断开）；超时以 `error` 帧如实收尾 |
| AI 预约智能助手（全局悬浮，当前会话不持久化） | 需求设计文档 1.3 / 2.4 | 流式问答**不落库、不建表**；会话内容仅存在于前端内存，刷新即清空 |
| AI 模块专属约束：低耦合、超时控制、Prompt 限定、可开关、内容标注 | 需求设计文档 3.4 第 2/4/5/6 条 | 流式代码全部落 `ai` 包；`ai.enable` 双开关一键启停；关闭态前端隐藏入口且不发探测请求；生成内容仍标注「AI 生成，仅供参考」 |
| 接口模块划分：AI 能力模块（独立，不侵入业务） | 需求设计文档 2.5 | 流式端点仅在 AI 包内，`AiController` 同路径分流，其余业务 Controller 零触点 |
| 接入要点：OpenAI v1 兼容、SSE、`response_format=json_object` | spec.md 6.1 | 流式问答上游走 OpenAI 兼容 SSE（`data:` 帧 + `[DONE]` 哨兵）；**流式不传 `response_format`**（要求纯文本直出） |
| 四个 AI 能力 Prompt 规格 | spec.md 6.2 | 新增流式专用 `CHAT_STREAM` Prompt：业务规则与 `CHAT` 逐字一致，仅输出要求改为纯文本（禁 JSON/Markdown 包裹） |
| 降级兜底必须实现；`ai.enable=false` 系统完全退化 | spec.md 6.3 | 4 桶降级口径不改；降级 FAQ 与缓存重放统一以分段 `delta` 推送；关闭态以 `meta(enabled=false)` 起、`done(disabled)` 收 |
| 禁止引入技术栈外依赖 / 中间件 | AGENTS.md 第 7 节红线、4.2 技术栈表 | **本轮零新增依赖**：线程池用框架自带 `ThreadPoolTaskExecutor`，前端用浏览器原生 `fetch`/`ReadableStream` |

## 三、关键设计裁决

1. **同路径分流**：`@PostMapping(value="/chat", params="stream!=true")` 保留原 `Result<AiChatVO>`；
   `params="stream=true"` 新增 `SseEmitter`。两映射互斥，旧调用方零感知。
2. **上游仍走 `RestClient` 阻塞字节流**（`AgnesClient.chatStream` 逐行 `BufferedReader` 读取）。
   *后续演进*：R13-4 将上游替换为 WebClient + JDK 21 `HttpClient`（不引 Netty）；本轮不涉及。
3. **独立线程池 `aiStreamExecutor`**：仅承载 `chat?stream=true`；有界队列 + `AbortPolicy`（打满时 controller 捕获拒绝并回 `error` 事件，不压垮 Tomcat 请求线程）；参数外置 `ai.stream.executor.*`（缺省 2/8/100），daemon 线程。
4. **`meta` 每流唯一且先于一切 `delta`**：调上游时在收到 200 响应头（`onReady`）才发出；建链后再降级**不重发 meta**（半截真实文本优于拼接 FAQ）。
5. **分段 `delta` 口径**：降级 FAQ / 缓存命中重放按固定步长 `AI_STREAM_CHUNK_SIZE = 20` 字符切分，**纯展示分段、不做人为延迟**；真实模型流则即收即发。
6. **缓存独立性**：流式缓存版本段取 `CHAT_STREAM`（纯文本 Prompt），与非流式 `CHAT`（JSON）版本哈希不同，两路缓存**各自独立、不共享**；成功分支才写缓存，降级结果永不入缓存（沿用 R11 口径）。
7. **中断契约**：客户端断开 → `sink.send` 抛 `IOException` → 包装为 `UncheckedIOException` 快速中止上游读取 → `AgnesClient` 返回 aborted 句柄 → 静默结束（不发降级 meta、不推 FAQ、不写缓存、不发 done/error）。

## 四、验收标准与结论

| 验收项 | 结论 |
|---|---|
| `chat?stream=true` 事件序列 `meta → delta* → done` | 通过：后端 `AiChatStreamServiceTest` 帧序断言 + E2E 多帧拼接为完整答案 |
| 非流式 `chat` 契约零变化 | 通过：`params="stream!=true"` 保留原 `Result<AiChatVO>`；旧用例零修改通过 |
| 降级 FAQ / 缓存重放以分段 delta 呈现 | 通过：`AiChatStreamServiceTest` 覆盖降级分段与缓存重放分支 |
| 关闭态与限流降级可区分 | 通过：E2E 断言关闭态气泡展示关闭文案、底栏 `info` 文案且不出现「AI 生成」标注 |
| SSE 超时以 error 帧收尾 | 通过：`onTimeout` 补发 `error` → `complete()`（`safeSendError` 吞掉二次失败） |
| 线程池打满不压垮请求线程 | 通过：`AbortPolicy` + controller `TaskRejectedException` 捕获回 `error`（"AI 服务繁忙，请稍后再试"） |
| 既有功能零回归 | 通过：后端 **88/88** 单测；前端 ESLint/构建通过；全量 E2E **110 通过 + 3 守卫跳过 + 0 失败** |
| 冻结红线未越界 | 通过：仅动 AI 包 + AI 组件 + AI E2E + 前端两文件 + `application.yml` AI 段；核心业务零改动；未建表；未提交 git |

## 五、明确不在本轮范围（后续演进项）

| 项 | 归属轮次 | 理由 |
|---|---|---|
| Markdown 渲染（`markdown-it` + `dompurify`） | R13-2 | 本轮聚焦「流式通道打通」，渲染增强独立成步以降风险 |
| 多轮对话 `history` 字段 | R13-3 | 会话追问体验增强，需求明确「当前会话不持久化」，独立实施 |
| 结构化 3 接口（推荐/解析/合规）SSE 化 | R13-5 | 与问答「人类可读文本真流式」性质不同（进度事件流），拆分实施 |
| 发送中「停止生成」按钮 | R13-1 | 复用本轮已建立的断开契约，作为独立交互增强步骤 |
| 上游改 WebClient（去阻塞） | R13-4 | 属上游客户端替换，非本轮（R12 仍为 `RestClient` 阻塞字节流） |
