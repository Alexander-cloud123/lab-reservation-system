# 接口清单（R11 · AI 观测补全与结果缓存）

> 本轮**无新增接口**、**无接口签名变更**；仅扩展一个既有只读接口的响应字段，并明确缓存 Key 口径。

## 一、响应体字段扩展：`GET /api/stats/ai/metrics`

权限不变：路径落在 `Constants.ADMIN_API_PREFIXES` 的 `/api/stats` 前缀内，由 `AuthInterceptor` 自动校验管理员角色（学生访问 HTTP 403，实测通过）。

新增字段（原有字段含义不变）：

| 字段 | 类型 | 含义与口径 |
|---|---|---|
| `totalPromptTokens` | long | 输入 token 累计；上游未返回 `usage` 时保持 0（表示不可得，不伪造） |
| `totalCompletionTokens` | long | 输出 token 累计；口径同上 |
| `totalTokens` | long | 合计 token 累计；上游未给 total 时按「输入+输出」兜底 |
| `finishReasonByReason` | Map<String,Long> | 结束原因分桶，固定 4 键：`stop` / `length`（触达 max_tokens 被截断）/ `content_filter`（内容策略拦截）/ `unknown`（缺失或上游其他取值） |
| `cacheHitCount` | long | 结果缓存命中次数（命中即未产生上游调用） |
| `cacheMissCount` | long | 结果缓存未命中次数 |

口径说明：
1. token 用量与 `finishReason` **仅统计拿到 HTTP 200 响应的调用**；限流 / 无密钥 / 超时 / 上游 5xx 未走到解析链路，不计入这两类计数（仍计入 `modelCallCount` 与 `degradeByReason`）。
2. 计数器为**进程内累加**，应用重启即清零（`startedAt` 标明本次统计起点）。

## 二、结果缓存 Key 口径

Key 形态：`cache:ai:{namespace}:{version}:{fingerprint}`（与既有 `cache:stats:` / `cache:classroom:list:` 同族命名）

| 接口 | namespace | fingerprint（输入指纹） | TTL | 版本段组成 |
|---|---|---|---|---|
| `POST /api/ai/compliance-check` | `compliance` | `purpose` 的哈希（**不含 userId**：purpose→结论为纯函数，管理员间可共享） | 300s | 合规 Prompt 版本 + 违规关键词库哈希 + 生效模型名 |
| `POST /api/ai/recommend` | `recommend` | `userId + 推荐基准日期` 的哈希 | 60s | 推荐 Prompt 版本 + 生效模型名 |
| `POST /api/ai/chat` | `chat` | `userId + 问题` 的哈希 | 30s | 问答 Prompt 版本 + 生效模型名 |
| `POST /api/ai/parse-reservation` | —— | 不缓存（输入唯一 + Prompt 含日期后缀，跨天必错） | —— | —— |

设计口径：
1. **版本段并入配置哈希**：Prompt 内容、违规关键词库、生效模型任一变化，Key 即变，旧缓存自然不再命中——因此**不挂代次失效**，仅靠短 TTL 自愈。
2. **仅成功分支写缓存**：由 `AiInvoker` 模板在成功分支统一写入，降级分支永不写，避免把本地规则结果固化成「模型结果」。
3. **缓存对前端完全透明**：命中与否都不改变返回体结构（`enabled=true`、`message` 为空），前端无需改动；命中情况仅在指标 `cacheHitCount` 中体现。
4. **降级纪律**：缓存读写全部委托既有 `RedisCache`，`redis.enable=false` 或 Redis 异常时「读回源、写空转、绝不抛异常」，AI 链路不会因缓存故障而阻断。
5. **TTL 不进配置**：TTL 属策略常量（定义于 `AiConstants`），不新增 `application.yml` 键，避免扩大配置面与基线口径。