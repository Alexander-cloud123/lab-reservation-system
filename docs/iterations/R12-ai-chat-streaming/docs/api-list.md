# 接口清单（R12 · 智能问答 SSE 流式输出）

> 本轮为**既有端点新增流式承载**：`POST /api/ai/chat` 通过 `?stream=true` 分流，
> **非流式契约（`Result<AiChatVO>`）零变化**。其余 3 个 AI 端点（推荐/解析/合规）本轮**未改动**。
> 路径前缀：`/api/ai`（无版本号）；权限沿用既有拦截（未登录 HTTP 401）。

## 一、路由分流

同一路径通过查询参数 `stream` 分流，映射互斥、互不影响：

| 路径 | 非流式（原有契约不变） | 流式（本轮新增） |
|---|---|---|
| `POST /api/ai/chat` | `params=stream!=true` → `Result<AiChatVO>` | `params=stream=true` → SSE（**R12 新增**） |

其余端点（`/recommend`、`/parse-reservation`、`/compliance-check`）**本轮仅保留非流式**；
其 `?stream=true` 结构化流式为 *后续演进*（R13-5），不属于本轮。

线格式：`text/event-stream`，帧为 `event:{name}\ndata:{json}\n\n`（冒号后**无空格**，与 Spring `SseEmitter` 一致）。

## 二、流式事件协议

**事件顺序**（正常路径）：`meta`（**每流唯一**，先于一切 `delta`）→ `delta*`（0..N）→ `done`（恰好 1）；
致命错误以 `error` 终态收尾（收到 `error` 后不再有 `delta`/`done`）。

| 事件 | data payload | 出现时机与口径 |
|---|---|---|
| `meta` | `{"enabled":bool,"degraded":bool,"message":string\|null}` | 流首，**仅 1 个**；于上游 200 响应头到达（`onReady`）时发出。非降级 `message=null`；关闭态 `enabled=false, degraded=true` |
| `delta` | `{"text":string}` | 文本片段，前端直接追加即完整答案；真实模型流即收即发，降级 FAQ / 缓存重放按 20 字切片 |
| `done` | `{"finishReason":string}` | 正常收尾；`finishReason` 取值见下 |
| `error` | `{"message":string}` | 终态：70s 超时 / 线程池繁忙 / 流建立后服务异常 |

`finishReason` 取值：

| 值 | 含义 |
|---|---|
| `stop` / `length` / `content_filter` / `unknown` | 上游模型返回的结束原因（`unknown` 为兜底） |
| `fallback` | 本地规则降级（FAQ 兜底） |
| `disabled` | AI 双开关关闭（短路应答） |

## 三、降级 / 关闭口径（与非流式业务语义一致，仅承载方式不同）

| 情形 | 事件序列 |
|---|---|
| 正常（上游成功） | `meta(enabled=true,degraded=false)` → `delta*`（真实模型文本） → `done(stop/length/…)` |
| 缓存命中重放 | `meta(enabled=true,degraded=false)` → `delta*`（缓存答案按 20 字切片，**无人工延迟**） → `done(stop)` |
| 建链前降级（无 key/限流/非 200/建链异常） | `meta(enabled=true,degraded=true,message=降级原因)` → `delta*`（本地 FAQ 分段） → `done(fallback)` |
| 200 建链后空内容 / 全脏帧 | `meta(enabled=true,degraded=false)`（**不重发 meta**） → `delta*`（本地 FAQ 分段） → `done(fallback)` |
| 200 建链后读体中断、已有半截 delta | `meta(enabled=true,degraded=false)` → `delta*`（半截真实文本，不拼 FAQ） → `done(模型原因\|unknown)` |
| AI 关闭 | `meta(enabled=false,degraded=true,message=关闭文案)` → `done(disabled)`（**中间无 delta**） |
| 客户端中途断开 | 静默结束：不发降级 meta、不推 FAQ、不写缓存、不发 done/error |

> `meta` 唯一性：无论何种收尾，**每流至多发 1 个 meta**。建链后降级只能通过 `finishReason=fallback` 反映兜底内容，原因无法再经 meta 透出（契约取舍）。

## 四、SSE 建链前入参校验

流式端点在**请求线程**先做入参校验（SSE 建立之前）：

- 校验通过 → 正常建立 SSE 流；
- 校验失败（空问题 / 超长） → 抛 `BusinessException`，由**全局异常处理器**返回 `Result` JSON
  （`Content-Type: application/json`，HTTP 200 + 业务码），与非流式端点完全一致。
- 前端 `aiChatStream` 对非 `text/event-stream` 响应按 `Result` JSON 解析并走 `onError` 终态（覆盖建链前失败场景）。

校验规则（流式/非流式共用 `validateQuestion`，口径一致）：

| 约束 | 规则 |
|---|---|
| 非空 | 空/空白 → 「请输入您的问题」 |
| 长度 | 超过 `QUESTION_MAX_LENGTH` → 「问题过长（不超过 N 字）」 |

## 五、上游调用与线程池

| 项 | R12 口径 |
|---|---|
| 上游客户端 | `AgnesClient.chatStream`：`RestClient` 阻塞字节流 + `AiSseLineParser` 逐行解析（`data:` 帧、`[DONE]` 哨兵） |
| 上游请求 | OpenAI 兼容：`stream=true`，**不传 `response_format`**（要求纯文本直出）；System Prompt = `CHAT_STREAM` |
| 超时 | 连接 10s / 读取 60s；SSE 侧 70s（`STREAM_TIMEOUT_MS`，略大于上游读超时） |
| 线程池 | `aiStreamExecutor`（`ThreadPoolTaskExecutor`，核心 2 / 最大 8 / 队列 100 / `AbortPolicy` / daemon），参数外置 `ai.stream.executor.*` |
| 线程池打满 | controller 捕获 `TaskRejectedException` → 回 `error`「AI 服务繁忙，请稍后再试」→ `complete()` |

## 六、后续演进（非本轮范围，标记归档边界）

- `POST /api/ai/chat` 请求体 **R13-3 新增可选 `history`**（会话内多轮，最多 5 轮、不落库）；R12 请求体仅 `{question}`。
- `chat` 上游 **R13-4 由 `RestClient` 阻塞字节流替换为 WebClient + JDK `HttpClient`**；R12 线格式/事件序列不变。
- 推荐 / 解析 / 合规三端点 **R13-5 新增 `?stream=true`** 结构化进度事件流；R12 仅 `chat` 有流式。
