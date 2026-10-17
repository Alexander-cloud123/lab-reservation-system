# 测试报告（R13 · AI 体验增强）

> 日期：2026-10-17　环境：JDK 21 · Spring Boot 3.2.10 · MySQL 8.0（容器 `reservation-mysql`，宿主 3307）· Redis 7（容器 `reservation-redis`，宿主 6380）· Node 22 · 真实 Agnes 上游（降级链路为主）

## 一、后端单元/集成测试

`mvn -B test` → **Tests run: 118, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**

AI 包合计 **84 例**全绿；本轮 R13 新增 **24 例**：

| 测试类 | 用例数 | 覆盖 |
|---|---|---|
| `ai.support.AiInvokerBeforeCallTest`（新增，R13-5） | 5 | 「调用前回调」重载：未命中时回调先于上游且写缓存 / 命中跳过回调 / 回调抛异常中止上游 / 无 cacheSpec 仍回调 / 旧 9 参签名回归 |
| `ai.service.impl.AiRecommendStreamServiceTest`（新增，R13-5） | 6 | 未命中发 phase / 命中无 phase（直接 result） / 空候选短路 / 关闭态 / userId 校验 / 非流式回归 |
| `ai.service.impl.AiParseStreamServiceTest`（新增，R13-5） | 7 | 成功帧序 / 关闭短路 / 降级透出 / 识别失败 error / 入参 200–201 字边界 / phase 前断开 / 非流式回归 |
| `ai.service.impl.AiComplianceStreamServiceTest`（新增，R13-5） | 6 | 未命中+写缓存 / 缓存命中无 phase / 关闭 / 降级不写缓存 / 入参 255–256 字边界 / phase 前断开 |
| `ai.support.AiStreamFramesTest`（R13-4） | 5 | 上游 JSON 帧 → delta 纯函数解析 |
| `ai.support.AiStreamTextTest`（R12/R13） | 9 | 文本分段与代码围栏剥离 |
| `ai.service.impl.AiChatStreamServiceTest`（R12） | 13 | 流式问答编排（meta/delta/done、降级分段、关闭态） |
| `ai.config.AgnesClientStreamOrchestrationTest`（R13-4） | 9 | WebClient 流式编排与取消 |
| `ai.support.AiResponseParserTest` / `AiMetricsTest` / `AiResultCacheTest` / `RateLimiterTest`（R11） | 24 | 响应解析 / 运行指标 / 结果缓存 / 限流 |
| 非 AI 既有测试（业务、并发、鉴权冒烟等） | 34 | 核心业务回归全绿（含 `ConcurrencyIntegrationTest` 3、`AuthAndValidationSmokeTest` 6、`ReservationServiceImplTest` 10 等） |

> 说明：R13-5 新增的流式单测均使用**录制型假 sink**（`RecordingStructuredSink`）断言事件帧序，不起 Spring 上下文、不消耗上游额度，可重复执行。

## 二、前端静态检查与构建

| 检查 | 命令 | 结果 |
|---|---|---|
| 代码规范 | `npm run lint`（`eslint src --max-warnings 0`） | **通过**（零错误、零告警） |
| 生产构建 | `npm run build`（Vite 5） | **通过**，`✓ built in 9.61s`，产物正常输出至 `dist/` |

## 三、全量端到端回归（Playwright，浏览器级）

`npx playwright test` → **118 passed / 0 failed**（共 118 例，3.2 分钟，串行 workers=1）

分 spec 分布：

| spec | 用例数 | 结果 |
|---|---|---|
| 00-smoke | 3 | 通过 |
| 10-auth | 11 | 通过 |
| 11-classroom-list | 7 | 通过 |
| 12-classroom-detail | 8 | 通过 |
| 13-my-reservation | 5 | 通过 |
| 14-calendar | 4 | 通过 |
| 15-profile | 7 | 通过 |
| 20-admin-home | 3 | 通过 |
| 21-classroom-manage | 9 | 通过 |
| 22-audit-manage | 5 | 通过 |
| 23-user-manage | 4 | 通过 |
| 24-reservation-record | 2 | 通过 |
| 25-dashboard | 4 | 通过 |
| **30-ai**（AI 三入口 + 关闭态 + 降级 + 流式） | **18** | 通过 |
| 40-rules（核心业务规则） | 21 | 通过 |
| 41-a11y（键盘可达性） | 7 | 通过 |

### 3.1 本轮（R13）E2E 新增/迁移要点

- **流式帧序断言**：`30-ai.spec.js` 新增辅助 `eventNamesOf` / `sseDataOf`，断言真实序列：
  - 推荐主用例：`meta → phase → result → done`，并校验 `phase.phase=recommend`、`result` 与非流式 VO `toMatchObject` 同构；
  - **新增 2.1「缓存命中无 phase」**：断言 `meta → result → done`；
  - parse 主用例：同构 VO 回填；
  - compliance 主用例：帧序 + 结果只提示不改状态。
- **新增 4.2「SSE 建链前返回 Result JSON 时识别为失败，入口保持可重试」**：覆盖建链前入参校验失败→JSON 回落→前端 `onError` 分支。
- 迁移的旧 AI 用例改为按 `?stream=true` 走 SSE 消费（helpers 统一产出整段 mock SSE 响应体），**非流式 JSON mock 保留**。
- chat 既有 8 个流式/降级/关闭态/Markdown/多轮用例断言不动，全部通过。

### 3.2 mock 帧格式纪律

E2E mock 的 SSE 帧严格对齐真实 `SseEmitter` 线格式 `event:{name}\ndata:{json}\n\n`（冒号后**无空格**）—— 这是 R12 踩过的坑，本轮 `helpers/ai.js` 与前端 `ssePost` 解析均同时兼容「无空格帧」与「标准带空格帧」。

## 四、真实链路与降级口径

- 本轮真实模型调用以**降级链路 + mock 为主**（Agnes 免费档 429 严格，`RPM≈20`）；AI 双开关（`ai.enable` + `ai_config.ai_enable`）与关闭态行为由 E2E 与后端单测双重锁定。
- **降级口径未改**：4 桶降级（超时 / 服务异常 / 限流 / 输出非法）仍切换本地规则模式，进度事件流在降级态返回 `meta(degraded=true,message)` + 本地规则 VO。
- **关闭态未改**：`meta(enabled=false)` + disabled VO；前端隐藏 AI 入口、不发探测请求。

## 五、冻结红线复核

| 复核项 | 结论 |
|---|---|
| 改动文件范围 | 仅 `server/.../reservation/ai` 包 + AI 前端组件（`AiAssistant.vue`/`AiQuickReserve.vue`/`AiRecommendCard.vue`）+ `web/src/api/ai.js`、`web/src/utils/*` + AI E2E + `server/pom.xml`、`web/package.json` |
| 核心业务逻辑 | **零改动**（冲突检测、状态流转、权限、并发锁未触碰） |
| 数据库 | **未建表、未改表结构、无新 SQL** |
| git | **未提交**（沿用项目约定，由负责人决定提交时机） |
| 依赖 | 仅新增点名的 3 项（`spring-webflux` / `markdown-it` / `dompurify`），无 Netty/`reactor-netty` 传递引入 |

## 六、遗留与未证实项（如实记录）

1. **`finish_reason=length` 未在真实模型下触发**：该分支由既有 `AiResponseParserTest` 单测覆盖，真实模型 `max_tokens=1024` 足够且 Prompt 收窄输出，属预期。
2. **结构化接口「逐字 token 流」未实现**：本轮为**进度事件流 + 非流式 JSON 上游调用**，非逐字吐 JSON；此为设计取舍（见任务说明第三节裁决 3），非遗留缺陷。
3. **多轮对话缓存命中可能返回稍早上下文快照**：受短 TTL（问答 30s）影响，属已知语义取舍，前端不做「来自缓存」提示。
4. **本次未做真实 WebClient 断链下游连接释放的抓包级验证**：取消链路由 `AiInvokerBeforeCallTest` + 流式 service 单测（phase 前断开中止上游）覆盖；真实环境中断表现为「客户端断开」debug 日志而非异常栈。
