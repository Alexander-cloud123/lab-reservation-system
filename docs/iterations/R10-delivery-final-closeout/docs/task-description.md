# R10 最终交付打包 —— 任务说明书（task-description）

> 轮次：R10-delivery-final-closeout（阶段5 最终交付打包）｜日期：2026-09-25｜基线提交：11862a9（阶段5 无障碍修复）
> 依据：《需求设计文档.md》（V3.1）第 1.3 / 1.4 / 1.5 / 2.2 / 2.4 / 2.5 / 3.3 / 3.4 节与第四章「冲优秀答辩核心策略」；AGENTS.md 第 3 节「单轮标准迭代工作流」、第 4.3–4.5 节开发规范、第 5 节交付物标准。
> 说明：本轮所称「阶段1–5」是项目收尾期的内部阶段划分，**需求设计文档未单列对应章节**（需求文档 3.3 为 8 周迭代计划），故本说明对每项工作标注其真实需求依据来源，不虚构章节号。

## 一、任务范围（阶段 1–5 逐条）

本次最终交付打包覆盖项目收尾的 5 个阶段，逐条列明范围与产出：

| 阶段 | 内容 | 实际产出 | 需求/规范依据 |
| --- | --- | --- | --- |
| 阶段1：Playwright E2E 基建 | 在 `web/e2e/` 建立浏览器级回归基建：Playwright 配置（串行 1 worker、Chromium、baseURL 前端 dev server、webServer 自动拉起前端、globalSetup 后端探活）、helpers（api/auth/admin/ui/ai/data/stable-seed）、15 个 spec 文件 | `web/e2e/` 全套基建 + 15 个 spec，覆盖 13 个页面与全部业务流程，共 98 条用例 | 需求文档 1.3（13 页功能清单）、1.4（核心业务规则）、2.4（13 页页面详细设计）；AGENTS.md 第 3 节第 4 步「测试验证」（全量验证：正常流程、边界场景、异常操作、权限校验） |
| 阶段2：后端冻结 | 后端功能与接口契约冻结，打标签 `v1.0-backend-freeze`；冻结后后端仅做质量重构与缺陷修复，不再新增接口语义 | Git 标签 `v1.0-backend-freeze`（仓库内已存在） | 需求文档 2.5（接口模块划分）、3.4（AI 模块专属开发约束）；AGENTS.md 4.3（代码架构规范） |
| 阶段3：完整性复核 + 3 项前端修复 | 对 13 页功能点与需求清单做完整性复核，修复复核中发现的 3 项前端缺陷：① AI 校验入口消失；② 消息已读状态不响应式（点击后列表不刷新）；③ E2E 教室夹具字段与数据库实际数据不一致 | commit `8a8d024`（AI 校验入口消失、消息已读不刷新，同时把登录提醒改为顶部非阻塞提示）+ commit `651506d`（教室夹具收缩为纯编号索引，移除与库不一致的展示字段） | 需求文档 1.3（学生端「消息通知中心」「AI 预约合规校验」、公共页「登录状态提醒」） |
| 阶段4：前端视觉改版（飞书工作台设计体系） | 全站视觉改版为「飞书工作台设计体系」，统一设计 token；改版后按 AGENTS.md 4.5 执行 avoid-ai-design 自检与 web-design-guidelines 合规审计 | commit `5a3b614`，**19 个文件**（`src/assets/main.css` + 18 个 .vue：登录/注册/学生端 5 页/管理端 6 页/公共布局/AI 组件 3 个） | AGENTS.md 4.5「前端 UI 去 AI 味规范」（含 4.5.2 设计计划流程、4.5.3 avoid-ai-design 自检、4.5.5 Element Plus 主题定制、4.5.6 web-design-guidelines 合规审计） |
| 阶段5：无障碍修复 + 最终交付打包 | ① 修复阶段4 遗留的 P1 可访问性问题：为 div 式可点击卡片/列表项补键盘可达性；② 产出 R10 交付包（代码与数据库快照 + 4 份文档） | commit `11862a9`（新增全局指令 `web/src/directives/clickable.js`，在 `main.js` 注册，为 **6 个既有文件 9 类共 19 处** 补 `role="button"` + `tabindex="0"` + Enter/空格激活）；本交付目录 `R10-delivery-final-closeout/` | 需求文档 1.5（易用性：核心操作流程 ≤3 步、关键操作二次确认）；AGENTS.md 4.5.6（web-design-guidelines 100+ 条最佳实践合规审计，可访问性/焦点状态为其中一类）。**需求设计文档未单列「无障碍」章节**，本阶段依据为上述规范条款 |

阶段5 可访问性修复的逐处清单（6 个既有文件、9 类、19 处）：

| 文件 | 选择器/位置 | 处数 |
| --- | --- | --- |
| `web/src/views/admin/AdminWelcome.vue` | `.quick-card` | 5 |
| `web/src/views/admin/AuditManage.vue` | `.ai-tag-clickable`、`.reason-tag` | 2 |
| `web/src/components/ai/AiRecommendCard.vue` | `.recommend-item` | 1 |
| `web/src/components/ai/AiQuickReserve.vue` | `.room-pick-item` | 1 |
| `web/src/views/student/Profile.vue` | `.quick-item`、`.settings-row.clickable`、`.fav-item`、`.msg-item` | 4 + 4 + 1 + 1 = 10 |
| 合计 | 9 类 | 19 |

- 指令行为：`role="button"` + （无 tabindex 时补）`tabindex="0"`，Enter 与空格键等价于鼠标点击（空格阻止页面默认滚动）；焦点样式复用 `main.css` 既有 `[tabindex]:focus-visible` 规则，未新增 CSS。
- 主动排除项：`ClassroomList.vue` 的 `.room-card` **未**加 `role="button"` —— 该卡片内部已有真实按钮，外挂按钮语义会造成嵌套交互与双 Tab 停靠，属主动排除而非遗漏（见「六、已知限制与说明」）。

## 二、需求依据（引用真实章节）

| 引用位置 | 章节标题（《需求设计文档.md》V3.1） | 本轮对应内容 |
| --- | --- | --- |
| 1.3 | 功能需求清单（13 页完整版，分层标注） | 阶段1 E2E 覆盖的 13 页功能点、阶段3 完整性复核逐页核对依据 |
| 1.4 | 核心业务规则（基础规则 + AI 业务规则） | 冲突检测公式、状态流转、取消时限、收藏上限、批量审核约束；AI 只读不写、降级容错、内容标注 |
| 1.5 | 非功能需求 | 响应时间（冲突检测 ≤500ms、AI ≤60s）、兼容性（Chrome/Edge）、易用性（≤3 步、二次确认） |
| 2.2 | 最终技术栈（适配 JDK 21 + Node 22 + IDEA 内置 Maven 3.9.x） | 交付工程技术栈核验：Spring Boot 3.2.10 / Vue 3.4 / Vite 5 / Element Plus 2.7 / Playwright（测试工具，非运行时依赖） |
| 2.4 | 前端页面详细设计（13 页总量不变） | 13 页与 E2E spec 的对应关系（见 test-report 第四节） |
| 2.5 | 接口模块划分（5 大模块，RESTful 风格） | 最终版接口清单枚举口径（见 api-list.md） |
| 3.3 | 8 周迭代计划（AI 后置，核心优先） | 项目整体节奏背景；「阶段1–5」未在此单列 |
| 3.4 | AI 模块专属开发约束 | AI 密钥零硬编码、`ai.enable` 一键启停、超时 60s 降级、内容标注 |
| 第四章 | 冲优秀答辩核心策略 | 交付打包与验收口径：核心业务闭环优先、AI 压轴、零核心 Bug |
| AGENTS.md 第 3 节 | 单轮标准迭代工作流（闭环） | 本轮「交付验收」前先完成测试验证（阶段1 E2E 全量回归） |
| AGENTS.md 4.3 / 4.4 / 4.5 | 代码架构规范 / AI 模块专属约束 / 前端 UI 去 AI 味规范 | 交付规范核验；阶段4 视觉改版与阶段5 无障碍修复的依据 |

## 三、验收标准

| # | 验收项 | 判定标准 | 实测结果 |
| --- | --- | --- | --- |
| 1 | 前端静态检查 | `npm run lint`（ESLint flat config，`--max-warnings 0`）零告警 | 0 error / 0 warning ✅ |
| 2 | 前端生产构建 | `npm run build`（Vite 5）成功且无报错 | 构建成功，2101 modules transformed ✅ |
| 3 | 浏览器级全量回归 | 全部 spec 用例通过 | 98 passed / 98 total，耗时 1.9m，1 worker，Chromium ✅ |
| 4 | 数据库基线 | 回归后数据回到交付基线 | 预约 13 / 用户 5 / 教室 12 / E2E 临时教室残留 0 ✅ |
| 5 | 13 页功能完整 | 需求文档 1.3 功能点全覆盖，E2E 用例逐页对应 | 见 test-report 第四节覆盖映射 ✅ |
| 6 | 核心规则 100% 实现 | 冲突检测公式、状态流转、取消时限、越权 401/403、参数校验均有对应用例 | 见 test-report 第六节 ✅ |
| 7 | 交付物完整性 | 代码快照（后端/前端）+ 数据库脚本 + 4 份文档齐备，无占位内容 | 本目录 ✅ |
| 8 | 交付规范 | 统一返回 `Result`、`jakarta.*` 包名、分层结构、密钥零硬编码 | 未改动后端代码，快照沿用阶段2 冻结版本 ✅ |

## 四、最终验证结果（负责人实测，原样引用）

```
npm run lint（web）      : 0 error / 0 warning
npm run build（web）     : Vite 5 构建成功，2101 modules transformed
全量 E2E（web/e2e）      : 98 passed / 98 total，耗时 1.9m，1 worker，Chromium
数据库基线（回归后）      : 预约 13 / 用户 5 / 教室 12 / E2E 临时教室残留 0
```

> 以上数字为最终交付口径，不放大、不改写；明细与用例分布见 `docs/test-report.md`。

## 五、交付清单

```
R10-delivery-final-closeout/
├── code/
│   ├── reservation-server/   # 后端快照（= 项目根 server/，排除 target/ 与日志）
│   └── reservation-web/      # 前端快照（= 项目根 web/，排除 node_modules/ dist/ e2e-report/ test-results/ 与日志）
├── database/
│   └── init_db.sql           # 数据库初始化脚本（与项目根 database/init_db.sql SHA256 一致）
├── docs/
│   ├── task-description.md   # 本文件
│   ├── api-list.md           # 最终版接口清单（源码实地枚举，38 个端点）
│   └── test-report.md        # 最终测试报告（测试用例/覆盖范围/通过情况/遗留问题）
└── startup-guide.md          # 启动说明（环境/数据库/后端/前端/验证/AI 开关/常见故障）
```

- 本轮为收尾打包轮：**未修改 `server/` 与 `web/src/` 下任何代码**，交付快照即为阶段5 完成后的代码状态（HEAD 11862a9）。
- 快照不含构建产物与运行日志；前端 `node_modules/` 需按 `startup-guide.md` 执行 `npm install` 还原。

## 六、已知限制与说明

1. **`.room-card` 主动排除**：`ClassroomList.vue` 的教室卡片内部已有真实按钮，未加 `role="button"` 以避免嵌套交互与双 Tab 停靠；这是主动设计决策，不是遗漏。若后续调整该卡片内部结构（移除内部按钮），需重新评估是否补键盘语义。
2. **无障碍覆盖边界**：阶段5 仅解决「div 式可点击元素键盘不可达」这一类问题（role/tabindex/Enter/空格）；未做完整 WCAG 审计（如对比度、读屏全流程脚本化验证），属已知未覆盖范围。
3. **AI 为可降级外部依赖**：`ai.enable=false`（交付默认）时 AI 接口返回 `enabled=false` 友好提示，前端隐藏 AI 入口，核心系统不受影响；启用后无密钥/限流/超时自动降级本地规则，链路不阻断。
4. **E2E 环境依赖**：E2E 依赖本地容器 MySQL/Redis 与已启动后端（脚本不自动启动后端），端口不符会导致探活失败；本轮实测环境为 1 worker 串行。
5. **需求文档无「阶段」章节**：如本说明开篇所述，「阶段1–5」为项目内部收尾阶段划分，需求文档未单列，依据已逐条对应到 1.3 / 1.4 / 1.5 / 2.4 / 2.5 / 3.3 / 3.4 与 AGENTS.md 相关条款。