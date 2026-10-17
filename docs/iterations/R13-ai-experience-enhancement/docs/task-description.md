# 本轮任务说明（R13 · AI 体验增强）

> 轮次：R13　模块：AI 能力模块　日期：2026-10-17
> 唯一依据：《需求设计文档.md》；技术规格：spec.md；协作章程：AGENTS.md
> 前置基线：R12「智能问答 SSE 流式输出」已完成并并入基线（`POST /api/ai/chat?stream=true`）

## 一、任务范围

在 R12「问答真流式（打字机）」基础上，把 AI 体验从「能流」提升到「好用、可信、可解释」，共六个步骤：

| 步骤 | 内容 | 一句话目标 |
|---|---|---|
| R13-1 | 停止生成 | 流式发送中可随时中断，不追加错误文案、不影响输入框可用性 |
| R13-2 | Markdown 渲染 | AI 回答按 Markdown 渲染（标题/列表/代码/链接），`v-html` 前强制 XSS 清洗 |
| R13-3 | 多轮对话 | 同一会话内携带最近上下文（最多 5 轮），不建表、不持久化 |
| R13-4 | WebClient 上游改造 | AI 上游流式消费改为 WebClient + JDK HttpClient，仅换上游客户端、不换服务端栈 |
| R13-5 | 结构化 3 接口 SSE 化 | recommend / parse-reservation / compliance-check 支持 `?stream=true` 进度事件流 |
| R13-6 | 最终全量回归 | 后端全量单测 + 前端 lint/build + Playwright 全量 |

**改动面纪律（冻结红线）**：全部改动限定在 AI 独立包（`server/.../reservation/ai`）+ AI 前端组件 + AI 相关 E2E + 两份构建清单（`server/pom.xml`、`web/package.json`）。核心业务（冲突检测、状态流转、权限、并发锁）零改动；不新建/修改数据库表；不提交 git。

## 二、需求依据

| 需求/规格条款 | 位置 | 本轮如何满足 |
|---|---|---|
| AI 业务规则：场景绝对限定、只读不写、结果可质疑、内容标注 | 需求设计文档 1.4「AI 业务规则」 | 未改动任何只读语义；停止生成对应「结果可质疑」（用户可中止、不强制接受）；生成内容仍标注「AI 生成，仅供参考」 |
| AI 接口响应 ≤60s | 需求设计文档 1.5「非功能需求」 | 结构化 SSE 用进度事件缓解等待感；上游超时/降级口径不变（连接 10s / 读取 60s、SSE 70s） |
| AI 预约智能助手（全局悬浮，当前会话不持久化） | 需求设计文档 1.3 / 2.4 | 多轮对话仅做**会话内**上下文，刷新即清空，严格遵守「不持久化」 |
| AI 模块专属约束：低耦合、超时控制、Prompt 限定、可开关、内容标注 | 需求设计文档 3.4 第 2/4/5/6 条 | 新增代码全部落 `ai` 包；`ai.enable` 双开关一键启停；关闭态前端隐藏入口且不发探测请求 |
| 接口模块划分：AI 能力模块（独立，不侵入业务） | 需求设计文档 2.5 | 新增流式端点仅在 AI 包内，`AiController` 分流，其余业务 Controller 零触点 |
| 接入要点：OpenAI v1 兼容、SSE、`response_format=json_object` | spec.md 6.1 | 上游流式消费改 WebClient（`accept: text/event-stream`），非流式 4 路仍走 `RestClient` |
| 四个 AI 能力 Prompt 规格 | spec.md 6.2 | 结构化 3 接口仍用已验证的 JSON 模式非流式调用，仅向浏览器推送真实本地阶段进度 |
| 降级兜底必须实现；`ai.enable=false` 系统完全退化 | spec.md 6.3 | 4 桶降级口径一字未改；结构化流式协议含关闭态（meta.enabled=false → result → done） |
| 禁止引入技术栈外依赖 / 中间件 | AGENTS.md 第 7 节红线、4.2 技术栈表 | 仅新增 3 个点名授权依赖：`spring-webflux`（后端，非 starter）、`markdown-it`、`dompurify`（前端）；明确不引 `spring-boot-starter-webflux` / `reactor-netty` |

## 三、技术裁决（本轮核心，逐项落地）

1. **依赖清单**：后端加 `org.springframework:spring-webflux`（版本由 spring-boot-parent 3.2.10 BOM 管理，仅用其 WebClient；传递引入 `reactor-core`）；前端加 `markdown-it@^14`、`dompurify@^3`。明确不引 `spring-boot-starter-webflux`（不切容器）、`reactor-netty`、`highlight.js`、`github-markdown-css`、任何测试新依赖。
2. **WebFlux 落地姿势**：服务端仍是 Spring MVC + Tomcat + `SseEmitter`，全仓其他 Controller 零影响；仅 `AgnesClient.chatStream` 的上游 HTTP 由阻塞 `RestClient.exchange` 改为 **WebClient + `JdkClientHttpConnector`**（基于 JDK 21 自带 `java.net.http.HttpClient`，**不引入 Netty**）；在 `aiStreamExecutor` 线程内以 `blockLast` 桥接回现有同步编排；取消链路：浏览器断开 → sink 写失败 → `dispose()` 上游订阅 → JDK HttpClient 关闭上游连接。非流式 4 路调用（含 3 个结构化接口与非流式 chat）继续走 `RestClient`，一行不动。
3. **结构化接口「流式化」的诚实定义**：是**进度事件流**，不逐字吐 JSON token。统一事件协议 V2：`meta{enabled,degraded,message}`（唯一）→ `phase{phase,text}*`（仅缓存未命中，0..1）→ `result{data}`（恰好 1 个，`data` 与对应非流式 VO **完全同构**）→ `done`；致命错误 `error` 终态。phase 是**后端真实本地阶段**（推荐/理解/合规校验）而非假动画；上游模型调用仍用 JSON 模式非流式调用（与 chat 的「人类可读文本真流式」性质如实区分，不夸口「4 接口全 token 流」）。
4. **多轮对话**：请求 DTO 增加可选 `history:[{role,content}]`；不带该字段行为与今日完全一致。后端不信任前端：role 白名单、最多 10 条（5 轮）、单条截断（assistant 500 字 / user 200 字），超限取最近；组装顺序 `system(CHAT_STREAM 规则) → 历史 → 当前问题`；缓存 Key 指纹并入 history 哈希；降级 FAQ 口径不变；非流式 chat 同步支持 history。
5. **Markdown 渲染**：统一入口 `web/src/utils/markdown.js`（markdown-it：`html` 关、`linkify` 关 → DOMPurify 清洗 → 外链强制 `target=_blank rel=noopener`），全项目只此一个渲染器；仅 AI 气泡渲染（`v-html` + `markdown-body`），用户气泡保持纯文本；两段式渲染（delta 期间 rAF 防抖轻渲染，done 后用完整累计文本全量重渲染一次）；样式 scoped 手写并映射项目设计 token。
6. **停止生成**：发送中「发送」按钮切换为「停止」（plain/neutral 样式，不用危险红）；点击即 `abort()`；已有内容保留、typing 光标消失、输入框立即可用；尚无内容则移除空气泡；不追加错误文案、不弹 Message。后端无需新增逻辑（复用 R12 断开契约 + R13-4 的 `dispose()` 上游取消）。

## 四、验收标准与结论

| 验收项 | 结论 |
|---|---|
| R13-1 发送中可停止且不残留错误文案 | 通过：E2E「发送中点停止中断接收，空气泡移除且可立即再次发送」 |
| R13-2 Markdown 正确渲染且 XSS 不执行 | 通过：E2E 断言标题/加粗/列表/链接/代码块渲染正确、`<script>`/`<img onerror>` 注入被转义 |
| R13-3 第二轮请求携带第一轮问答作为 history | 通过：E2E 断言第 2 轮请求体含 history、首轮不带 |
| R13-4 上游流式由 WebClient 承载且无 Netty | 通过：`mvn dependency:tree` 确认无 `reactor-netty`/`netty` 传递引入；后端全量单测通过 |
| R13-5 三接口 `?stream=true` 事件顺序 `meta→phase→result→done` | 通过：后端单测（含缓存命中无 phase）+ E2E 帧序断言 |
| R13-5 非流式契约零变化 | 通过：`params="stream!=true"` 分流保留原 `Result<VO>` 结构；旧用例零修改通过 |
| R13-5 降级态 / 关闭态口径不变 | 通过：降级 `meta(degraded=true,message)` + 本地规则 VO；关闭 `meta(enabled=false)` + disabled VO |
| 既有功能零回归 | 通过：后端 `mvn test` 118/118；前端 lint/build 通过；全量 E2E 118 passed / 0 failed |
| 冻结红线未越界 | 通过：仅动 AI 包 + AI 组件 + AI E2E + 两份构建清单；核心业务零改动；未建表；未提交 git |

## 五、明确不在本轮范围（含理由）

| 项 | 理由 |
|---|---|
| 结构化接口逐字 token 流（真 JSON 流） | 对用户无价值且显著增加解析失败面；进度事件流已达成「可解释的等待体验」 |
| 会话持久化 / 会话表 / 会话 ID | 违背需求「当前会话不持久化」；且触犯「不改表结构」红线 |
| `highlight.js` 代码高亮 | 预约问答场景无代码高亮需求，纯增依赖与体积 |
| AI 生成内容入库 / 审计留痕 | 违背「只读不写」定位，无需求支撑 |
| 上游 429 请求内重试 | 已由 R11 实测证伪（退避跨不过 60s 窗口），保留原结论 |
| 服务端栈迁移到 WebFlux（Netty） | 属容器迁移，风险高、收益低；只换上游客户端即可成立答辩叙事 |
