# 更新日志

本项目采用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录变更，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

当前交付版本为 `1.0.0`，覆盖 R1–R13 共 13 轮迭代。逐轮的完整交付文档（任务说明 / 接口清单 / 测试报告 / 启动说明）见 `docs/iterations/`。

## [未发布] - 2026-11

### 新增

- **AI 对话交互补齐（重新生成 / 回到底部 / Esc 停止 / 焦点回位）**：`useAiChat` 新增 `regenerate()`——删掉最后一条回答、用同一提问重发，不重复追加用户气泡；`AiChatMessage` 增加「重新生成」按钮（仅最后一条回答、非生成中显示，失败态显示「重试」）与「回答已停止生成」提示。对话页新增「回到底部 ↓」浮层：上翻超过 160px 才出现，点击贴底并恢复跟随；程序化贴底临时切 `scroll-behavior: auto`，避免平滑动画首帧滚动事件被误判成「用户上翻」而中断跟随。Esc 与「停止」按钮等价，流式中按下立即中断；发送/重新生成后焦点回到输入框。新增 3 条 E2E（重新生成、回到底部、Esc 停止），设计说明补进 [docs/AI对话界面.md](docs/AI对话界面.md)。

- **AI 助手改为 DeepSeek 式全屏对话界面**：原来的 380px 抽屉换成 `/student/ai` 全屏页——左侧 248px 会话栏（新建 / 切换 / 删除，标题取首问前 18 字）、760px 居中正文、底部自增高输入框（Enter 发送 / Shift + Enter 换行 / 输入法组合态不拦截）、流式中发送键变「停止」。会话只存在内存，刷新即清空；`trace` 与气泡改为 `reactive()`，修复「面板永远停在正在思考」；页面只保留一个 `role="status"` 的 live region（播报最终回答纯文本），提示条与横幅不再抢读。既有 `30-ai` E2E 通过类名兼容层继续有效。设计说明见 [docs/AI对话界面.md](docs/AI对话界面.md)。

- **AI 助手思考过程面板（R14）**：`/api/ai/chat` 流式接口新增 `thinking` / `reasoning` 两个 SSE 事件。`thinking` 按真实执行轨迹下发（场景判断 → 检索预约记录 → 调用模型 → 校验回答，缓存命中 / 降级 / 超范围 / 关闭态各有独立步骤），每步带服务端真实耗时与 `running` / `done` / `failed` 状态；上游若返回 `reasoning_content`（含 `reasoning` 别名）则原样透传，**不伪造思维链、不并入正文**。前端新增 `AiThinkingPanel`：思考中三点脉冲 + 每 100ms 刷新计时，首个正文到达自动折叠为「已思考 X.X 秒」，可展开查看步骤时间线与推理原文（固定标注「模型推理 · AI 生成，仅供参考」）；降级 / 缓存命中 / 停止 / 失败各有明确文案，模型未返回推理时明确说明「以上为本次处理的实际步骤」，不编造内容。助手滚动改为仅在距底 ≤24px 时跟随。设计说明见 [docs/AI思考过程设计.md](docs/AI思考过程设计.md)。

### 修复 / 安全加固

- **AI 默认口径调整为「容器演示默认开启」**：`docker-compose.yml` 的 api 环境默认值由 `AI_ENABLE=false` 改为 `true`，`.env.example` 同步为 `AI_ENABLE=true`，`database/init_db.sql` 种子 `ai_config.ai_enable` 改为 `true`——`docker compose up -d` 后登录即下发 `aiEnabled=true`，不再需要人工跑开关脚本。裸 `java -jar`、单元测试与 CI 仍由 `application.yml` 的 `${AI_ENABLE:false}` 与工作流显式 `AI_ENABLE=false` 保持关闭（零依赖、确定性不变）。未配置 `AGNES_API_KEY` 时两开关仍为开启，但所有 AI 回复自动降级为本地规则并在界面标注「AI 服务未配置密钥」，不阻断预约核心功能。存量数据卷（种子改动前创建、`ai_enable` 仍为 `false`）需跑一次 `pwsh server/scripts/ai-switch.ps1` 补齐；文档见 [docs/运行与配置.md](docs/运行与配置.md)。

- **会话撤销改为数据库版本号权威**：`sys_user` 新增 `token_version`（幂等迁移脚本 [`database/migrations/20261107_add_sys_user_token_version.sql`](database/migrations/20261107_add_sys_user_token_version.sql)）。JWT 签发时携带版本号，登出 / 改密 / 管理员重置 / 启用禁用都会原子自增，正常路径下旧 Token 下一次请求即 401——Redis 关闭、后端重启、多实例部署下同样成立（删除鉴权快照失败的异常路径见下方「文档」修正条目）。
- 移除 Redis 侧「本地撤销标记」兜底（`RedisCache.removeToken` 的「撤销未完全确认」异常语义），改为 `invalidateSession` 只做清理；鉴权状态缓存升级为 `status|tokenVersion`。
- Redis 启用但连接异常时鉴权 **fail closed（401）**，不再退化为仅 JWT 放行；`REDIS_ENABLE=false` 才是明确无状态模式（无单点会话，撤销仍由版本号保证）。
- 前端 401 处理尊重 `silent`：改密后调用登出（旧 Token 已被撤销）不再误报「登录已过期」或硬跳转。
- **无障碍缺陷修复（读屏器清单机器代理验收发现）**：AI 助手 live region 改为播报 Markdown 渲染后的纯文本（原先播报 `**`/列表序号，与可见气泡不一致）；登录页错误提示补 `aria-invalid` / `aria-describedby` 关联与 `role=alert`；补齐 Element Plus zh-cn 缺失的 `dialog.close` / `drawer.close` / `messagebox.close` 键，关闭按钮不再暴露 `el.dialog.close` 这类原始 i18n key；「我的预约」取消确认后触发按钮被移除会导致焦点悬空到 `body`，修复为确认成功后聚焦列表区域（`role=region` + `aria-label="我的预约列表"`，`13-my-reservation` 已加断言）。

- **上线前安全基线（Docker 部署）**：`docker-compose.yml` 的 api 固定 `SPRING_PROFILES_ACTIVE=prod`，`JWT_SECRET` 改为必填（缺失 / 为空 / 沿用开发默认值直接拒绝启动，已实测 `docker compose config` 退出 1）；容器关闭接口文档（`knife4j.enable=false` + `spring.web.resources.add-mappings=false`，`/doc.html` 不再返回文档页、`/v3/api-docs` 不再返回 schema，统一落到 `{"code":404}` 语义）与 MyBatis SQL 明细日志（`NoLoggingImpl`）；`web/nginx.conf` 增 `server_tokens off`、四条安全响应头（`/assets/` 与 `= /index.html` 各自补 `add_header`，避免继承被覆盖）、登录 / 注册 `limit_req`（10r/m，burst 9，第 11 次起 429）；CORS 白名单改由 `CORS_ALLOWED_ORIGINS` 注入，compose 默认收敛到本机 `8088` 与 Vite dev / E2E 的 `5173`。本机 `mvn spring-boot:run` 行为不变。
- 关闭静态资源映射后，未映射路径原先会被兜底异常处理器当成 500：GlobalExceptionHandler 新增 NoHandlerFoundException 处理，返回统一 {"code":404,"message":"接口不存在"}（/api/classroom 这类少写一段的路径不再报 500）。
- server/scripts/smoke.ps1 匿名访问用例从 /api/classroom 改到真实存在的 /api/classroom/list，避免依赖静态资源处理器兜出的 401 假阳性。

- **问答上下文补当前时间**：真实模型验收发现回答把 2026-11-16 说成「今天是 2026年11月14日」，相对日期整体偏移。`AiChatServiceImpl.buildContext()` 现于上下文首行下发 `【当前时间】yyyy-MM-dd HH:mm（Asia/Shanghai，星期X）`（固定东八区，格式常量 `CONTEXT_TIME_FORMATTER`），单测断言下发的用户消息包含该行与当天日期。

- **AI 开关变更后悬浮球不再"消失到底"**：前端此前只信任登录时落盘的 `reservation_ai_enabled`，开关后开时旧值 `false` 会让 `probeAiRecommend()` 直接短路（刷新无效，只能重新登录才恢复）。`web/src/utils/aiProbe.js` 改为始终向服务端探测（仅未登录时短路），`AiAssistant.vue` 探测成功后回写 store 与 localStorage，下次启动即为正确值。另新增 [`server/scripts/ai-switch.ps1`](server/scripts/ai-switch.ps1)（默认开、`-Off` 关：改 `ai_config.ai_enable`、清 `cache:ai:*`、等 5 秒开关缓存过期、校验登录接口下发的 `aiEnabled`），避免 `init_db.sql` 复位后忘记开开关导致演示时 AI 入口静默缺失。

- **修复结构化 SSE 空载荷终态（AI 入口在真实后端全隐藏）**：真实后端 `SseStructuredStreamSink.done()` 对空对象只写出 `event:done\ndata:`（没有 JSON 体），前端 `dispatchFrame` 把空 `data:` 当脏帧丢弃，`done` 永不落地 → `probeAiRecommend()` 的 Promise 一直悬空 → 推荐卡、悬浮球、AI 快速预约在**真机**上全部不出现（E2E 用 mock 的 `data:{}` 掩盖了该缺陷）。前端改为把「空载荷事件」按终态分发（`done` 回调收到 `null`），并同步 `web/e2e/helpers/ai.js` 的 mock 线格式为真实空 `data:`，新增回归用例「结构化流空载荷 done 帧仍是终态」。修复后实测推荐卡与悬浮球立即出现。

### 测试

- 后端 **323 项**（+14）：新增 `AiThinkingTraceTest`（步骤计时 / closed 后静默丢弃 / 写失败抛 UncheckedIOException）、`AiChatStreamServiceTest` 5 例（成功轨迹顺序、缓存命中、降级 failed+fallback、关闭态无轨迹、reasoning 透传且不并入正文）、`AiStreamFramesTest` 2 例（`reasoning_content` 与别名 `reasoning`）、`AgnesClientStreamOrchestrationTest` 2 例（推理端口隔离）。
- 前端 E2E **131 例 / 17 spec**（AI 助手思考过程新增 6 例、AI 入口自愈 2 例、本轮交互补 3 例（重新生成 / 回到底部 / Esc 停止）：思考中计时与步骤 → 自动折叠 → 展开推理、无推理说明、降级标记、缓存命中、关闭态不渲染面板；「思考中」瞬时态用 `patchStreamingChat` 逐帧推流，不调用真实模型）。
- 后端 **309 项**（新增 `TokenRevocationIntegrationTest`：固定 `redis.enable=false`，验证无 Redis 也能持久撤销；新增拦截器版本号 / 状态缓存用例）。新增 4 个纯逻辑测试类补齐看板与降级链：`StatsServiceImplTest`（聚合口径 / 窗口裁剪与 14h 封顶 / 排序 / 区间校验）、`ReservationConverterTest`（批量预查短路 / 三种 VO / 状态文案）、`AiParseFallbackTest`（自然语言解析与非法输入不抛异常）、`AiRecommendFallbackTest`（规则打分与推荐理由）；另补 2 个业务分支覆盖类 `ReservationServiceImplCoverageTest`（参数校验/授权/导出上限/日历区间与可见性/关键词拼接）与 `ClassroomServiceImplCoverageTest`（CRUD 校验/删除保护/启停幂等/学生端缓存与占用时段/状态标签），以及 `UserServiceImplCoverageTest`（登录/注册/资料/改密全分支）、`RedisCacheObjectCacheTest`（对象缓存与代次失效降级）、`AiJsonSupportTest`（模型 JSON 容错解析）、`FavoriteServiceImplCoverageTest`（收藏上限与幽灵卡片过滤），行覆盖率 54% → **80.6%**（指令 81.9% / 分支 74.2%，ReservationServiceImpl 98.1%、ClassroomServiceImpl 99.1%、UserServiceImpl 97.5%、RedisCache 87.0%）。
- 前端 E2E **120 项 / 17 spec**，新增「会话撤销」spec；新增 Microsoft Edge 全量回归项目（`npm run test:e2e:edge`，默认脚本仍只跑 Chromium）。
- `server/scripts/smoke.ps1` 增至 **41 项**（含重置密码后旧 Token 失效断言），并修复脚本对 PowerShell 7 错误响应的兼容性。
- 10-auth.spec.js 的「即将开始提醒」用例在 22:00 之后会把窗口算到跨午夜（如 23:35-00:35），被后端判「开始时间必须早于结束时间」而误报失败；补充「窗口不跨日、落在 08:00-22:00」的跳过条件。
- `ReservationServiceImplCoverageTest` 的日期字面量（2026-11-08）随系统日期过期导致 2 例失败，改用 `LocalDate.now().plusDays(1)` 生成未来日期，用例不再随时间漂移。

### 文档

- 新增 [`server/scripts/ai-switch.ps1`](server/scripts/ai-switch.ps1)：AI 动态开关的一键切换与校验；README「启用 AI」与 [`docs/运行与配置.md`](docs/运行与配置.md) §2 同步改写，并补「悬浮球不出现」排查表（静态/动态双开关、`docker compose restart` 不重读环境变量、前端每次启动以服务端探测为准）。
- 新增可靠性证据：[`docs/可靠性验证.md`](docs/可靠性验证.md) 汇总故障演练与性能基准实测 —— 故障演练脚本 [`server/scripts/chaos-drill.ps1`](server/scripts/chaos-drill.ps1)（Redis fail closed 401 / MySQL 统一错误包 30s / 恢复自愈 / 数据复原，15 项；`-IncludeSlow` 补 60s 会话撤销窗口）、接口基准 [`web/scripts/bench-api.mjs`](web/scripts/bench-api.mjs)（7 个接口 p50/p95/p99，冲突检测 p95 ≈ 16.5 ms 对 500 ms 上限）、页面基准 [`web/scripts/bench-page.mjs`](web/scripts/bench-page.mjs)（7 个页面首屏就绪 p95 54–137 ms 对 2 s 上限）；README 质量表与文档导航同步。
- 新增 [`docs/安全基线.md`](docs/安全基线.md) 与 [`server/scripts/security-check.ps1`](server/scripts/security-check.ps1)（8 项自动检查：响应头 / Server 版本 / 接口文档端点 / 默认密钥伪造 Token / 未登录 401 / 学生越权 403 / 登录与注册限流）；README「快速开始」补 `.env` 复制与密钥生成步骤、质量表增一行，[`docs/运行与配置.md`](docs/运行与配置.md) 环境变量表补 `SPRING_PROFILES_ACTIVE` / `CORS_ALLOWED_ORIGINS` 并更新 `JWT_SECRET` / `KNIFE4J_ENABLE` / `MYBATIS_LOG_IMPL` 口径。
- 新增仓库「演示」物料：40 秒 GIF [`docs/images/demo-walkthrough.gif`](docs/images/demo-walkthrough.gif) 与 72 秒完整版 MP4（GitHub Release `v1.1.0` 资产），README 增 `## 演示` 区块与文档导航入口；录制脚本 [`web/scripts/capture-demo-video.mjs`](web/scripts/capture-demo-video.mjs) 以 Playwright 驱动真实浏览器（合成光标 + 页内字幕条），录制前自动复位种子库并按固定锚点造冲突 / 待审核数据，`web/package.json` 增 `demo:gif` / `demo:full`；分镜与验收见 [`docs/演示视频分镜.md`](docs/演示视频分镜.md)。
- 新增 AI 演示片段：`--mode=ai` 录制 29 秒「AI 助手思考过程」视频（`.devtmp/demo/demo-ai-thinking.mp4`，GitHub Release `v1.1.0` 资产），README「演示」区块增链接，`web/package.json` 增 `demo:ai`；[`docs/演示视频分镜.md`](docs/演示视频分镜.md) 增「AI 助手思考片段分镜」一节与预热缓存、真实模型耗时兜底说明。
- 同步 `需求设计文档.md` §2.3.2（`sys_user.token_version`）、`docs/Redis加分项接入说明.md`、`docs/演示脚本.md` 的撤销与 fail-closed 口径，并新增数据库设计图字段。
- 新增 [`docs/无障碍人工验收清单.md`](docs/无障碍人工验收清单.md)（读屏器人工验收步骤；人工听感按项目负责人 2026-11-07 决定**不执行**，非需求项，机器代理记录见下）。
- 新增无障碍机器代理验收：脚本 [`web/scripts/a11y-acceptance.mjs`](web/scripts/a11y-acceptance.mjs)、记录 [`docs/review/无障碍机器代理验收记录.md`](docs/review/无障碍机器代理验收记录.md) 与证据 [`docs/review/a11y-evidence/`](docs/review/a11y-evidence/)；**16 项全部有机器证据并通过**（C4 修复后由机器验证）；真实播报人工听感按负责人决定不纳入交付（本机未安装 NVDA、讲述人未运行，未执行）。
- 修正会话撤销「立即生效」的绝对表述：补充「删除鉴权状态快照失败时，Redis 恢复后 ≤60s 旧快照窗口内旧 Token 仍可能被接受」的如实口径（故障期间仍 fail-closed 401；窗口结束后回源数据库即 401），同步 [`docs/Redis加分项接入说明.md`](docs/Redis加分项接入说明.md) §3/§4/§5/§6.2、[`docs/演示脚本.md`](docs/演示脚本.md)、`AuthInterceptor` 与 `RedisCache` 注释。
- README 重构为作品页结构（250 行）：预览截图前置、迭代历程压缩为 4 个里程碑，环境变量 / AI / Redis / 端口细节迁移至 [`docs/运行与配置.md`](docs/运行与配置.md)；新增 `.gitattributes` 统一换行为 LF（13 个文本文件 renormalize，无内容改动）。
- README 增加「项目预览」真实运行截图（[`docs/images/`](docs/images/)，由 [`web/scripts/capture-screens.mjs`](web/scripts/capture-screens.mjs) 采集）与「架构设计」Mermaid 图、三点设计取舍表；「测试」章节补充规模统计表，方便快速了解项目与面试讲解。

### 持续集成

- 新增 [`stack-smoke.yml`](.github/workflows/stack-smoke.yml)：真实起 MySQL / Redis / 后端 / 前端，`smoke` job 跑 41 项接口冒烟、`e2e` job 跑 120 项 Playwright E2E；两个 job 各自持有独立 service 容器，避免冒烟写入的数据污染 E2E 基线。
- `server-ci.yml` 增加 JaCoCo 覆盖率报告并作为 artifact 上传（`jacoco-maven-plugin 0.8.12`，指令覆盖率 81.9% / 行覆盖率 80.6% / 分支 74.2%，DTO / VO / entity 等纯数据载体不计入分母）。

## [1.0.0] - 2026-10

### 新增

- **R1 基础底座**：数据库建库建表与基线数据、前后端工程骨架、登录注册、JWT 鉴权与角色权限拦截。
- **R2 管理端**：用户管理（增删改查、启停用）与教室管理。
- **R3 预约核心**：教室浏览与详情、预约提交（前端实时校验 + 后端二次冲突检测）、管理员审核。
- **R4 体验升级**：个人中心、收藏全链路、预约记录与体验细节打磨。
- **R5 亮点功能**：日历总览（FullCalendar）与数据看板（ECharts）。
- **R7 AI 模块**：Agnes AI 接入（推荐 / 需求解析 / 智能问答 / 合规校验），限流与超时自动降级为本地规则。
- **R8 答辩准备**：实训报告、答辩演示材料与演示脚本、Redis 加分项接入（Token 与热点数据缓存，一键启停）。
- **R10 最终交付收口**：Playwright E2E 基建（16 个 spec / 118 个用例）、后端逻辑冻结与完整性复核、前端视觉改版、无障碍修复。
- **R11 AI 观测补全与结果缓存**：token 用量与结束原因观测、只读结果缓存。
- **R12 智能问答 SSE 流式输出**：`/api/ai/chat?stream=true` 打字机体验与收尾打磨。
- **R13 AI 体验增强**：停止生成、Markdown 渲染、会话内多轮上下文、WebClient 上游改造、推荐 / 解析 / 合规三个接口结构化 SSE 化。

### 变更

- **R6 联调优化**：全流程贯通与回归保障。
- **R9 代码质量重构与防御加固**：分层与命名规范化，补充边界与并发防护。

### 修复

- **R9 / R10**：预约状态流转口径与实现对齐、并发场景下的行锁与条件更新加固、前端 AI 痕迹与无障碍问题修复。

### 文档

- 每轮同步产出任务说明、接口清单、测试报告与启动说明四件套，归档于 `docs/iterations/`。
- QA 收口报告、软件审查报告、端到端测试报告分别归档于 `docs/qa/`、`docs/review/`、`docs/e2e/`。
