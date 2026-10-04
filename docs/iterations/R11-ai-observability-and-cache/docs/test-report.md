# 测试报告（R11 · AI 观测补全与结果缓存）

> 日期：2026-10-04　环境：JDK 21 · Spring Boot 3.2.10 · MySQL 8.0（容器 reservation-mysql）· Redis 7（容器 reservation-redis）· 真实 Agnes 上游

## 一、后端单元/集成测试

`mvn -B test` → **Tests run: 54, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `ai.support.AiResponseParserTest`（新增） | 8 | 完整响应 / usage 缺失 / total 缺失兜底 / `finish_reason=length` / 空 choices / 内容缺失 / 非 JSON 体 / 空白与 null 输入 |
| `ai.support.AiMetricsTest`（新增） | 6 | token 累计与零值 / finish_reason 四桶归位 / 耗时均值与最大值 / 降级分桶与限流计数 / 缓存命中与未命中 |
| `ai.support.AiResultCacheTest`（新增） | 6 | Key 组装格式 / 对 RedisCache 的读写委托 / 缓存命中短路（`verifyNoInteractions`） / 成功回写 / **降级不写缓存** / 输出非法不写缓存 |
| 既有 9 个测试类 | 34 | 回归全绿（含 RateLimiterTest、ConcurrencyIntegrationTest、AuthAndValidationSmokeTest 等） |

新增 20 例全部为可重复执行的纯单测（Mockito 打桩 + 自注入 ObjectMapper），不起 Spring 上下文、不消耗上游额度。

## 二、真实模型冒烟（端到端，逐项实测）

串行调用、间隔 ≥13s，全程未触发上游 429（`upstream429=0`）。

| 编号 | 验证目标 | 操作 | 实测结果 | 结论 |
|---|---|---|---|---|
| A | Token 用量与结束原因可观测 | 合规校验首次调用（缓存未命中，真实打上游） | `totalPromptTokens=123`、`totalCompletionTokens=53`、`totalTokens=176`、`finishReasonByReason.stop=1`、`modelCallCount=1`、`cacheMissCount=1` | **通过**（同时证实 Agnes 响应体确实带 `usage` 与 `finish_reason`，原先的待实测假设成立） |
| B | 缓存命中即免上游调用 | 同一 purpose 立即再调一次 | `cacheHitCount` 0→1、`modelCallCount` 保持 1、`totalTokens` 未增长；两次结果一致（`compliant=False`） | **通过** |
| C | 降级结果不入缓存 | 将 `ai_config.ai_model` 改为非法模型（7s 后生效），同一 purpose 连调 2 次 | 两次均返回 `AI 服务暂时不可用，已自动切换为本地规则模式`（本地关键词兜底）；`modelCallCount` 1→3（**+2**）、`SERVICE_ERROR` 0→2、`cacheMissCount` +2、`cacheHitCount` 不变、`totalTokens` 未增长 | **通过**（若降级结果被缓存，第二次必被短路，`modelCallCount` 只会 +1） |
| D | Prompt 变更即令旧缓存失效 | 用 `CONCAT` 就地为 `prompt_compliance` 追加 ASCII 变体后缀，同一 purpose 再调 | Redis 中并存两个 Key：`…:6c9af625-68219463-agnes-3.0-flash:a8e161e2` 与 `…:e70395de-68219463-agnes-3.0-flash:a8e161e2`——**输入指纹相同、Prompt 版本段不同**；该次调用 `modelCallCount` 3→4、`cacheMissCount` +1 | **通过** |

冒烟期间指标累计（`startedAt=2026-10-04 20:29:09`）：`modelCallCount=14`、`totalTokens=2655`（输入 2091 / 输出 564）、`finishReasonByReason.stop=12`、`SERVICE_ERROR=2`、`RATE_LIMITED=15`（其中 `rateLimitRejected=15`、`upstream429=0`）、`cacheHitCount=30`、`cacheMissCount=29`。

> 缓存价值的直接体现：全量 E2E + 冒烟合计 59 次 AI 请求中，**30 次由缓存直接命中**（未消耗上游额度），15 次被本地限流拦下，仅 14 次真正打到上游。

## 三、全量端到端回归（Playwright）

`npx playwright test` → **106 passed / 3 skipped / 0 failed**（共 109 例，2.2 分钟）

3 例跳过均为 spec 内置的**时段窗口条件跳过**（非失败、与本次改动无关，当前时刻 20:35 全部命中条件）：

| 用例 | 跳过条件 | 当前时刻代入 |
|---|---|---|
| `10-auth.spec.js` 登录后即将开始提醒 | 需造出「今日且 24h 内」合法预约（`now+150min ≤ 22:00`） | 20:35+150min = 23:05，越出 22:00 |
| `13-my-reservation.spec.js` 今日预约置顶 | `now+3h` 须落在可约时段（`hour ≤ 21`） | 20:35+3h = 23:35，hour 23 > 21 |
| `40-rules.spec.js` 取消时限 | 安全窗口限定 `08:00–18:59` | 20:35 > 18 |

## 四、覆盖范围与基线复核

覆盖：观测字段解析（含全部畸形输入）、指标计数口径、缓存命中/未命中/降级不写/Prompt 版本失效、三接口缓存接入、权限（学生 403 / 管理员 200）、既有业务与越权回归。

基线复核（清理 E2E 残留后）：`sys_user=5 / classroom=12 / reservation=13 / user_favorite=6 / ai_config=5`，预约状态分布 `0:3 1:6 2:2 3:2`，`E2E%` 预约残留 0、`e2e_` 账号残留 0；`ai_config` 五行内容未被改动（临时改动均按 MD5 校验还原）；`application.yml` 无新增键；Redis 中测试产生的 `cache:ai:*` Key 已清理。

## 五、遗留问题与未证实项（如实记录）

1. **`RATE_LIMITED` 的 15 次来自 E2E 高频调用**，属预期行为（本地限流器按设计拦下、`upstream429=0`），非缺陷。
2. **未做序列化兼容性回归**：`AgnesResponse` record 新增 `usage` 字段，仅服务端内部使用、不参与对外 JSON，故无兼容性影响；未额外编写针对性用例。
3. **未验证 Redis 故障时的缓存降级路径**：该路径由既有 `RedisCache`（已有既有测试与降级纪律保证）承担，本轮未重复注入 Redis 故障做端到端验证。
4. **`finish_reason=length` 未在真实模型下触发**：该分支由 `AiResponseParserTest` 单测覆盖（构造 `length` 响应），真实模型因 `max_tokens=1024` 足够且 Prompt 收窄输出而未出现截断，属预期。
5. 缓存命中的**业务语义取舍**（已写入方案并获批）：短 TTL 内（30–300s）返回的推荐/问答可能基于稍早的个人上下文快照；合规结论为纯函数无此问题。前端不做「来自缓存」提示。