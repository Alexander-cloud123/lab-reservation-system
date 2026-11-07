# 更新日志

本项目采用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录变更，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

当前交付版本为 `1.0.0`，覆盖 R1–R13 共 13 轮迭代。逐轮的完整交付文档（任务说明 / 接口清单 / 测试报告 / 启动说明）见 `docs/iterations/`。

## [未发布] - 2026-11

### 修复 / 安全加固

- **会话撤销改为数据库版本号权威**：`sys_user` 新增 `token_version`（幂等迁移脚本 [`database/migrations/20261107_add_sys_user_token_version.sql`](database/migrations/20261107_add_sys_user_token_version.sql)）。JWT 签发时携带版本号，登出 / 改密 / 管理员重置 / 启用禁用都会原子自增，正常路径下旧 Token 下一次请求即 401——Redis 关闭、后端重启、多实例部署下同样成立（删除鉴权快照失败的异常路径见下方「文档」修正条目）。
- 移除 Redis 侧「本地撤销标记」兜底（`RedisCache.removeToken` 的「撤销未完全确认」异常语义），改为 `invalidateSession` 只做清理；鉴权状态缓存升级为 `status|tokenVersion`。
- Redis 启用但连接异常时鉴权 **fail closed（401）**，不再退化为仅 JWT 放行；`REDIS_ENABLE=false` 才是明确无状态模式（无单点会话，撤销仍由版本号保证）。
- 前端 401 处理尊重 `silent`：改密后调用登出（旧 Token 已被撤销）不再误报「登录已过期」或硬跳转。
- **无障碍缺陷修复（读屏器清单机器代理验收发现）**：AI 助手 live region 改为播报 Markdown 渲染后的纯文本（原先播报 `**`/列表序号，与可见气泡不一致）；登录页错误提示补 `aria-invalid` / `aria-describedby` 关联与 `role=alert`；补齐 Element Plus zh-cn 缺失的 `dialog.close` / `drawer.close` / `messagebox.close` 键，关闭按钮不再暴露 `el.dialog.close` 这类原始 i18n key；「我的预约」取消确认后触发按钮被移除会导致焦点悬空到 `body`，修复为确认成功后聚焦列表区域（`role=region` + `aria-label="我的预约列表"`，`13-my-reservation` 已加断言）。

### 测试

- 后端 **222 项**（新增 `TokenRevocationIntegrationTest`：固定 `redis.enable=false`，验证无 Redis 也能持久撤销；新增拦截器版本号 / 状态缓存用例）。新增 4 个纯逻辑测试类补齐看板与降级链：`StatsServiceImplTest`（聚合口径 / 窗口裁剪与 14h 封顶 / 排序 / 区间校验）、`ReservationConverterTest`（批量预查短路 / 三种 VO / 状态文案）、`AiParseFallbackTest`（自然语言解析与非法输入不抛异常）、`AiRecommendFallbackTest`（规则打分与推荐理由）；行覆盖率 54% → **64.7%**。
- 前端 E2E **120 项 / 17 spec**，新增「会话撤销」spec；新增 Microsoft Edge 全量回归项目（`npm run test:e2e:edge`，默认脚本仍只跑 Chromium）。
- `server/scripts/smoke.ps1` 增至 **41 项**（含重置密码后旧 Token 失效断言），并修复脚本对 PowerShell 7 错误响应的兼容性。

### 文档

- 同步 `需求设计文档.md` §2.3.2（`sys_user.token_version`）、`docs/Redis加分项接入说明.md`、`docs/演示脚本.md` 的撤销与 fail-closed 口径，并新增数据库设计图字段。
- 新增 [`docs/无障碍人工验收清单.md`](docs/无障碍人工验收清单.md)（读屏器人工验收步骤；人工听感按项目负责人 2026-11-07 决定**不执行**，非需求项，机器代理记录见下）。
- 新增无障碍机器代理验收：脚本 [`web/scripts/a11y-acceptance.mjs`](web/scripts/a11y-acceptance.mjs)、记录 [`docs/review/无障碍机器代理验收记录.md`](docs/review/无障碍机器代理验收记录.md) 与证据 [`docs/review/a11y-evidence/`](docs/review/a11y-evidence/)；**16 项全部有机器证据并通过**（C4 修复后由机器验证）；真实播报人工听感按负责人决定不纳入交付（本机未安装 NVDA、讲述人未运行，未执行）。
- 修正会话撤销「立即生效」的绝对表述：补充「删除鉴权状态快照失败时，Redis 恢复后 ≤60s 旧快照窗口内旧 Token 仍可能被接受」的如实口径（故障期间仍 fail-closed 401；窗口结束后回源数据库即 401），同步 [`docs/Redis加分项接入说明.md`](docs/Redis加分项接入说明.md) §3/§4/§5/§6.2、[`docs/演示脚本.md`](docs/演示脚本.md)、`AuthInterceptor` 与 `RedisCache` 注释。
- README 增加「项目预览」真实运行截图（[`docs/images/`](docs/images/)，由 [`web/scripts/capture-screens.mjs`](web/scripts/capture-screens.mjs) 采集）与「架构设计」Mermaid 图、三点设计取舍表；「测试」章节补充规模统计表，方便快速了解项目与面试讲解。

### 持续集成

- 新增 [`stack-smoke.yml`](.github/workflows/stack-smoke.yml)：真实起 MySQL / Redis / 后端 / 前端，`smoke` job 跑 41 项接口冒烟、`e2e` job 跑 120 项 Playwright E2E；两个 job 各自持有独立 service 容器，避免冒烟写入的数据污染 E2E 基线。
- `server-ci.yml` 增加 JaCoCo 覆盖率报告并作为 artifact 上传（`jacoco-maven-plugin 0.8.12`，指令覆盖率 65.6% / 行覆盖率 64.7%，DTO / VO / entity 等纯数据载体不计入分母）。

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
