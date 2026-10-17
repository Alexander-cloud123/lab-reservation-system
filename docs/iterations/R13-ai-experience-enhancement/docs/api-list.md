# 接口清单（R13 · AI 体验增强）

> 本轮**新增 3 个流式端点**（recommend / parse-reservation / compliance-check 的 `?stream=true`），
> **非流式端点契约全部保留、零变更**；另为一个既有端点（`POST /api/ai/chat`）**新增可选请求字段 `history`**。
> 路径前缀：`/api/ai`（无版本号）；权限沿用既有拦截（未登录 HTTP 401）。

## 一、路由分流总览

同一路径通过查询参数 `stream` 分流，映射互斥、互不影响：

| 路径 | 非流式（原有契约不变） | 流式（本轮新增/既有） | 说明 |
|---|---|---|---|
| `POST /api/ai/recommend` | `params=stream!=true` → `Result<AiRecommendVO>` | `params=stream=true` → SSE（**R13-5 新增**） | 无请求体入参；userId 取当前登录态 |
| `POST /api/ai/parse-reservation` | `params=stream!=true` → `Result<AiParseVO>` | `params=stream=true` → SSE（**R13-5 新增**） | 请求体 `{text}` |
| `POST /api/ai/compliance-check` | `params=stream!=true` → `Result<AiComplianceVO>` | `params=stream=true` → SSE（**R13-5 新增**） | 请求体 `{purpose}` |
| `POST /api/ai/chat` | `params=stream!=true` → `Result<AiChatVO>` | `params=stream=true` → SSE（R12 既有，本轮**协议不变**，仅请求体新增 `history`） | 请求体 `{question, history?}` |

线格式：`text/event-stream`，帧为 `event:{name}\ndata:{json}\n\n`（冒号后无空格，与 Spring `SseEmitter` 一致）。

## 二、结构化流式事件协议（V2，R13-5）

**事件顺序**（正常路径）：`meta`（唯一）→ `phase`（0 或 1 个）→ `result`（恰好 1 个）→ `done`；致命错误以 `error` 终态收尾。

| 事件 | data payload | 出现时机与口径 |
|---|---|---|
| `meta` | `{"enabled":bool,"degraded":bool,"message":string\|null}` | 流首，仅 1 个。关闭态为 `enabled=false, degraded=true, message=关闭文案` |
| `phase` | `{"phase":string,"text":string}` | **仅缓存未命中、即将调用上游前**发出；缓存命中直接出 `result`，不发 `phase` |
| `result` | `AiRecommendVO` / `AiParseVO` / `AiComplianceVO` 本体 | 恰好 1 个，**与非流式 `Result.data` 完全同构**（经 `@JsonValue` 展平，线上 data 即 VO 本身，非 `{data:{…}}`） |
| `done` | `{}`（空对象，无业务载荷） | 正常收尾（成功 / 降级 / 关闭统一收尾信号） |
| `error` | `{"message":string}` | 终态：70s 超时 / 线程池繁忙 / 服务异常 |

阶段码与文案（`AiConstants`，前端可直接展示）：

| 接口 | `phase` | `text` |
|---|---|---|
| recommend | `recommend` | 正在分析你的预约习惯与明日教室空闲 |
| parse-reservation | `parse` | 正在理解你的预约需求 |
| compliance-check | `compliance` | 正在校验预约用途的合规性 |

**降级 / 关闭口径**（与非流式业务语义完全一致，仅承载方式不同）：

| 情形 | 事件序列 |
|---|---|
| 正常（缓存未命中） | `meta(enabled=true,degraded=false)` → `phase` → `result(真实模型 VO)` → `done` |
| 正常（缓存命中） | `meta(enabled=true,degraded=false)` → `result(VO)` → `done` |
| 上游降级 | `meta(enabled=true,degraded=true,message=降级原因)` → `result(本地规则 VO)` → `done` |
| AI 关闭 | `meta(enabled=false,degraded=true,message=关闭文案)` → `result(enabled=false 的关闭 VO)` → `done` |

## 三、SSE 建链前入参校验（与非流式一致）

`parse-reservation` / `compliance-check` 的流式端点在**请求线程**先做入参校验（SSE 建立之前）：

- 校验通过 → 正常建立 SSE 流；
- 校验失败 → 抛 `BusinessException`，由**全局异常处理器**返回 `Result` JSON（`Content-Type: application/json`，HTTP 200 + 业务码），与非流式端点完全一致。
- 前端 `ssePost` 在 `content-type` 非 `text/event-stream` 时按 `Result` JSON 解析并走 `onError` 终态（覆盖建链前失败场景）。

## 四、请求体新增字段：`POST /api/ai/chat` 的 `history`（R13-3）

```jsonc
{
  "question": "明天下午有空的教室吗？",          // 必填，场景绝对限定
  "history": [                                   // 可选；缺省/空 = 单轮问答（旧调用方零感知）
    { "role": "user",      "content": "帮我看看明天" },
    { "role": "assistant", "content": "好的，明天你想……" }
  ]
}
```

后端不信任前端，统一二次裁剪：

| 约束 | 规则 |
|---|---|
| 角色白名单 | 仅 `user` / `assistant`，其余值在归一化时丢弃 |
| 条数 | 最多 10 条（5 轮），超限取最近 |
| 单条长度 | `assistant` 截断至 500 字、`user` 截断至 200 字（保留开头） |
| 组装顺序 | `system(CHAT_STREAM 规则) → 历史 → 当前问题`（个人预约上下文仍拼在当前问题侧） |
| 缓存 Key | 指纹并入 history 哈希，历史不同则 Key 不同 |
| 持久化 | **不落库、不建表**；刷新即清空（符合需求「当前会话不持久化」） |

非流式 `POST /api/ai/chat` 同步支持 `history`（两路行为一致）。

## 五、其余说明

- 本轮**无新增数据库表/字段**，无新增 `application.yml` 键。
- 本轮**无指标接口变更**：`GET /api/stats/ai/metrics`（R11 引入）字段与口径不变。
- `?stream=true` 对旧调用方完全透明：不带该参数即走原有 `Result<VO>` 契约，curl / 文档 / 前端非流式路径不受影响。
