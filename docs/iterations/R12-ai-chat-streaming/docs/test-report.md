# 测试报告（R12 · 智能问答 SSE 流式输出）

> 日期：2026-10-15　环境：JDK 21 · Spring Boot 3.2.10 · MySQL 8.0（容器 `reservation-mysql`，宿主 3307）· Redis 7（容器 `reservation-redis`，宿主 6380）· Node 22 · 真实 Agnes 上游（降级链路为主）
> **归档说明**：本报告为 R12 交付后**回补归档**，数据取自提交 `f413bc8` 的验证结论。

## 一、后端单元/集成测试

`mvn -B test` → **Tests run: 88, Failures: 0, Errors: 0，全部通过**

本轮新增 4 个测试类，覆盖流式问答全链路：

| 测试类（新增，R12） | 覆盖 |
|---|---|
| `ai.support.AiSseLineParserTest` | 上游 OpenAI 兼容 SSE 帧解析：`data:` 前缀与可选前导空格、多行 data 拼接、`:` 注释行忽略、空行事件边界、`[DONE]` 哨兵 |
| `ai.support.AiStreamTextTest` | 文本分段 `chunks(text, 20)` 与代码围栏剥离 `stripCodeFence`（写缓存值用，不改写已显示文本） |
| `ai.service.impl.AiChatStreamServiceTest` | 流式编排（**录制型假 sink** 断言帧序）：`meta→delta*→done`、缓存重放分段、建链前降级 `meta(degraded)`+FAQ 分段、建链后空内容、关闭态 `meta(false,true)`+`done(disabled)`、客户端断开静默结束 |
| `ai.config.AgnesClientStreamDispatchTest` | 上游流式派发：`onReady` 于 200 响应头触发唯一 meta、`onDelta` 即收即发、中断契约（sink 写失败 → `UncheckedIOException` → aborted 句柄） |

> 说明：流式单测全部使用假 sink（不起 Spring 上下文、不消耗上游额度），可重复执行。

## 二、前端静态检查与构建

| 检查 | 命令 | 结果 |
|---|---|---|
| 代码规范 | `npm run lint`（`eslint src --max-warnings 0`） | **通过**（零错误、零告警） |
| 生产构建 | `npm run build`（Vite 5） | **通过**，产物正常输出至 `dist/` |

## 三、全量端到端回归（Playwright，浏览器级）

`npx playwright test` → **110 passed + 3 守卫跳过 + 0 failed**

本轮在 `30-ai.spec.js` **新增 3 个流式用例**（其余用例随「问答入口由非流式切为流式」同步迁移，非流式 JSON mock 保留）：

| 新增用例 | 断言要点 |
|---|---|
| 「AI 助手流式：请求带 stream=true，多帧拼接为完整答案」 | 走 `?stream=true`；多帧按序拼接后文本**完整等于**目标答案（若 SSE 解析/拼接错误则不通过）；`pathname` 计数仍归 `/api/ai/chat`；回答区保留「AI 生成，仅供参考」标注 |
| 「AI 助手流式降级：`meta.degraded=true` 时底栏切换为降级原因，FAQ 文本完整渲染」 | 降级 FAQ 分段文本完整可见；底栏含后端降级原因、且**不出现**「AI 生成，仅供参考」标注 |
| 「AI 助手流式关闭态：`meta.enabled=false` 时气泡展示关闭文案，与降级/AI 生成标注区分」 | 气泡直接展示后端关闭文案（不落入「暂时无法回答」兜底）；打字机光标消失；底栏为关闭文案（info），既非降级 warning 也无「AI 生成」标注 |

既有「AI 预约助手」主用例同步升级为流式断言：接收期间 `.typing-cursor` 可见、最终答案尚不可见 → 多帧拼接后答案可见 → 光标清零。

### 3.1 mock 帧格式纪律

E2E mock 的 SSE 帧严格对齐真实 `SseEmitter` 线格式 `event:{name}\ndata:{json}\n\n`（冒号后**无空格**）；
`helpers/ai.js` 新增 `sseFrame` / `chatStreamBody`（一次性返回整段响应体，逐帧按序拼接由前端解析器保证）。

## 四、真实链路与降级口径

- 本轮真实模型调用以**降级链路 + mock 为主**（Agnes 免费档 429 严格）；AI 双开关（`ai.enable` + `ai_config.ai_enable`）与关闭态行为由 E2E 与后端单测双重锁定。
- **降级口径未改**：4 桶降级仍切换本地规则模式；降级 FAQ 与缓存重放统一以分段 `delta` 推送。
- **关闭态未改**：`meta(enabled=false)` + `done(disabled)`；前端隐藏 AI 入口、不发探测请求。

## 五、冻结红线复核

| 复核项 | 结论 |
|---|---|
| 改动文件范围 | 仅 `server/.../reservation/ai` 包 + AI 前端组件（`AiAssistant.vue`）+ `web/src/api/ai.js`、`web/src/utils/request.js` + AI E2E + `application.yml`（AI 段） |
| 核心业务逻辑 | **零改动**（冲突检测、状态流转、权限、并发锁未触碰） |
| 数据库 | **未建表、未改表结构、无新 SQL** |
| git | **未提交**（沿用项目约定，由负责人决定提交时机） |
| 依赖 | **零新增依赖**（线程池用框架自带组件；前端用浏览器原生 `fetch`） |

## 六、遗留与未证实项（如实记录）

1. **流式上游仍为 `RestClient` 阻塞读取**：占用 `ai-stream` 线程直至上游响应结束，靠独立线程池隔离；异步化改进属 *后续演进*（R13-4）。
2. **建链后降级的降级原因不可透出**：受 `meta` 每流唯一契约限制，200 建链后空内容降级只能以 `done(fallback)` 反映，原因无法再经 meta 传递（契约取舍，非遗留缺陷）。
3. **多轮上下文未实现**：本轮请求体仅 `{question}`，会话追问能力属 *后续演进*（R13-3）。
4. **未做真实上游断链的连接释放抓包级验证**：取消链路由 `AgnesClientStreamDispatchTest` + `AiChatStreamServiceTest`（断开静默结束）覆盖；真实环境表现为 debug 日志而非异常栈。
