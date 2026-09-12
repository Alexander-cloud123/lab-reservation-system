# R7 轮次任务说明 —— AI 模块（Agnes 接入 / 4 个只读接口 / 前端 AI 交互 / 降级容错 / 开关退化）

> 基线：R6-delivery-integration-optimization（git commit 5bf179b，已推送），本轮从 R6 目录复制工程到本目录 `code/` 内继续开发。
> R1-R6 交付目录只读，未做任何修改；数据库表结构未变更（五表沿用：sys_user / classroom / reservation / user_favorite / ai_config），ai_config 5 行基线值未改动。

## 一、本轮范围（spec.md 第 7 节 R7 行）

| 模块 | 内容 |
| --- | --- |
| Agnes AI 客户端封装 | `ai` 包低耦合封装：配置类（读 application.yml `ai.*`）、Agnes 客户端（RestClient 调 OpenAI v1 兼容 `/chat/completions`，默认模型 agnes-2.0-flash，支持 system/user 消息与 JSON 结构化输出）、降级规则引擎（超时 3s / 报错 / 限流 / 无密钥自动切换本地规则模拟，不阻断业务） |
| 4 个 AI 只读接口 | POST /api/ai/recommend（智能推荐 Top3）、POST /api/ai/parse-reservation（自然语言解析）、POST /api/ai/chat（场景限定问答）、POST /api/ai/compliance-check（合规校验），全部只读不写 |
| 前端 AI 交互 | 教室列表页「AI 为你推荐」卡片区 +「AI 快速预约」入口；全局悬浮「AI 预约助手」；预约审核页「AI 校验」标签（违规红色高亮 + 悬浮原因，只提示不改状态）；全部内嵌既有页面，不新增路由页 |
| 降级容错 | 无密钥 / 限流（RPM=20）/ 超时 / 连接失败 / 服务异常时自动切换本地规则模拟，HTTP 200 无 500、不阻断业务；降级原因透出到响应 message 可观测 |
| 开关退化 | `ai.enable=false`（交付默认）时 4 接口返回 enabled=false + 友好提示，前端三处 AI 入口全部隐藏/禁用，系统完全退化为 R6 纯预约系统（13 页闭环不回退） |
| 回归保障 | R1-R6 共 210 条基线用例零回归失败 + 本轮新增用例（正常/边界/异常/权限/降级/开关）全部通过 |
| 交付物 | code/ + database/ + docs/（任务说明/接口清单/测试报告/测试脚本）+ startup-guide.md，目录与文件名不使用中文 |

## 二、方案确认（负责人已拍板）

### 决策 1：`/api/ai/*` 权限口径 = 登录即可（按 AGENTS.md 流程上报确认）
- 需求文档未明示 AI 接口权限，按流程上报后确认：学生端助手/推荐/解析 + 管理端合规校验均需访问，故四个 AI 接口统一「登录即可」（仅 401 校验，不区分角色）。
- 落实方式：将 `/api/ai` 从 `Constants.ADMIN_API_PREFIXES` 移除，**未影响任何既有接口的权限矩阵**（R6 权限用例零回归）。

### 决策 2：AI 可用性 = 双开关 AND 口径
- `ai.enable`（application.yml，交付默认 false）AND `ai_config.ai_enable`（数据库，基线 false）。
- 两个开关都为 true 时 AI 功能才可用；任一为 false 时接口返回 `enabled=false` + 友好提示（如「AI 服务未启用，当前为纯预约系统模式」），前端按 enabled 隐藏/禁用 AI 入口。

### 决策 3：依赖约束（spec.md 第 7 节 R7 行未点名新依赖）
- **后端零新依赖**：Agnes 调用用 spring-web 自带 RestClient，JSON 用已有 Jackson；RPM 限流为自实现固定窗口计数（无外部限流库）。
- **前端零新依赖**：对话/悬浮球/标签/弹窗全部用 Element Plus 既有组件实现；既有依赖版本未动。

### 决策 4：AI 绝对只读不写
- 四个接口均为只读：推荐基于历史习惯与实时占用计算；解析返回结构化参数；问答仅返回文本；合规仅返回判定结果。
- AI 不直接执行任何业务操作（取消/审核/提交均由用户手动走既有流程与双重冲突校验）；前端不绕过既有提交/审核流程。

## 三、主要实现说明

### 后端（`code/reservation-server/src/main/java/com/example/reservation/ai/`，新增约 17 个文件）

| 包 | 类 | 说明 |
| --- | --- | --- |
| ai/config | `AiConstants` | 配置键 / AI 开关消息 / 限流窗口 / 降级话术常量收敛 |
| ai/config | `AiProperties` | `@ConfigurationProperties(prefix="ai")`：enable/base-url/api-key/model/timeout-seconds/rpm-limit |
| ai/config | `AgnesClient` | RestClient 调 `{base-url}/chat/completions`（OpenAI v1 兼容：Bearer 密钥、messages(system/user)、temperature=0.3、response_format json_object）；超时=timeoutSeconds；固定 60s 窗口 RPM 计数；**所有异常/空密钥/超时/限流统一返回 `AgnesResponse(ok=false, reason)`，不抛异常** |
| ai/dto | 4 Request + 4 VO | VO 均带 `enabled`/`message` 字段；关闭时走 `XxxVO.disabled()` |
| ai/service | `AiConfigService` | 读 ai_config 表 5 键；双开关口径 `isAiEnabled()` |
| ai/service | `AiFallbackEngine` | 降级规则引擎：解析正则+关键词模板、推荐打分排序、FAQ 关键词库、合规关键词库 |
| ai/service | 4 接口 + 4 实现 | 见下 |
| ai/controller | `AiController` | `/api/ai/*` 4 个 POST |

四个接口口径：

| 接口 | 入参 | 主路径 | 降级路径 |
| --- | --- | --- | --- |
| POST /api/ai/recommend | userId | 按用户历史习惯（楼栋/类型/时段/平均容量）预筛候选 → 大模型排序 Top3（附理由）；候选按「明日已通过预约占用」计算全天空闲/占用 | 模型失败/非法输出 → 规则打分排序 Top3 |
| POST /api/ai/parse-reservation | text | Prompt 模板取 `ai_config.prompt_parse`，模型返回结构化 JSON | 模型失败/非法 JSON → 正则降级（今天/明天/后天/周X/月日/年-月-日、HH:mm/下午N点/半、N人、机房/实验室/普通教室、用途关键词） |
| POST /api/ai/chat | question | System Prompt 场景绝对限定 + 用户最近预约做检索增强，模型回答 | 模型失败 → FAQ 关键词库；未命中 → 场景外预设话术 |
| POST /api/ai/compliance-check | purpose | Prompt 模板取 `ai_config.prompt_compliance` | 模型失败 → `compliance_keywords` 本地关键词判定（命中返回 reason=「包含违规关键词：X」） |

- 修改：`common/Constants.java`（/api/ai 移出管理员前缀）、`src/main/resources/application.yml`（ai.* 配置块，R6 已预置，本轮仅联调时临时调整并已恢复交付值）。
- **密钥零硬编码**：`api-key: ${AGNES_API_KEY:}`，仅从环境变量读取；前端零接触密钥。

### 前端（`code/reservation-web/src/`，新增 4 个文件 + 修改 3 个既有页面）

| 文件 | 说明 |
| --- | --- |
| `api/ai.js`（新增） | 4 个接口封装（Token 注入走 request.js 既有逻辑） |
| `components/ai/AiRecommendCard.vue`（新增） | 推荐卡片（props 驱动，点击直达教室详情） |
| `components/ai/AiQuickReserve.vue`（新增） | 三步弹窗：描述需求 → AI 解析（结构化参数预填，可手动修改）→ 选教室（按类型+容量过滤）→ 确认提交；**复用既有 checkConflict + submitReservation 契约，提交仍走前后端双重冲突校验** |
| `components/ai/AiAssistant.vue`（新增） | 全局悬浮球 → el-drawer 对话窗口，场景限定，当前会话不持久化，enabled=false 时悬浮球不渲染 |
| `views/student/ClassroomList.vue`（修改） | 顶部推荐卡区 + 搜索栏旁「AI 快速预约」按钮；onMounted 统一探测一次 aiEnabled |
| `views/student/StudentLayout.vue`（修改） | 挂载 AiAssistant 悬浮球（学生端所有页面可见） |
| `views/admin/AuditManage.vue`（修改） | 待审核记录「AI 校验」标签：违规红色高亮（el-tag--danger）+ el-tooltip 悬浮原因；只提示不改状态；complianceMap 仅内存缓存不写库 |

- 所有 AI 生成内容标注「AI 生成，仅供参考」；AI 结果提供「重新描述 / 修改」入口，不强制接受。

## 四、联调发现并修复的问题（R7 范围内）

| # | 问题 | 根因 | 修复 |
| --- | --- | --- | --- |
| 1 | recommend 接口 500 | `AiFallbackEngine.rankAndTop` 对 `stream().toList()` 不可变列表调 sort → `UnsupportedOperationException` | 改为 `.collect(Collectors.toList())`，recommend 实测 53ms 返回 Top3+理由 |
| 2 | 降级原因不可观测（限流/无密钥/超时静默返回规则结果） | 4 个 ServiceImpl 降级分支未把 `resp.reason()` 写入 VO.message | 降级时统一写 `vo.setMessage(resp.reason())`，降级原因可观测（测试断言依赖此修复） |
| 3 | 测试脚本 T155 晚间运行失败（R6 基线用例时间依赖） | 「2 小时后」预约在 20:00 后越过营业时间边界（22:00）创建失败 | 脚本内适配：2 小时后超出 8:00-22:00 时改用明日 8:00（仍在 24h「即将开始」窗口内），消除时间依赖保证可复跑 |

## 五、验收标准对照（逐项自查，详见 spec.md 第 8 节 Checklist）

| 验收项 | 结果 |
| --- | --- |
| Agnes 客户端封装完成、配置可读、密钥零硬编码、前端零接触密钥 | ✅ |
| 4 个 AI 接口全部实现且只读不写；标准演示场景（自然语言预约→智能推荐→合规校验→智能助手）实测流畅 | ✅ |
| 降级机制实测生效：无密钥/限流/超时/连接失败自动切换规则模拟，无 500、不阻断业务 | ✅ |
| 业务边界合规：AI 结果仅提示/预填/建议，业务操作由用户手动执行并走既有校验流程 | ✅ |
| ai.enable=false 时核心系统完全正常（13 页闭环 + 210 条基线零回归）；前端 AI 入口正确隐藏/禁用 | ✅ |
| 权限校验：/api/ai/* 登录即可（上报确认口径）；既有权限矩阵零回归 | ✅ |
| 代码规范符合 AGENTS 4.3（四层结构、统一 Result、jakarta.、命名、注释、魔法值常量收敛）；AI 代码独立 ai 包不侵入业务 | ✅ |
| R1-R6 210 条用例回归通过 + 本轮新增用例覆盖（正常/边界/异常/权限/降级/开关） | ✅ |

## 六、交付物清单

```
R7-delivery-ai-integration/
├── code/
│   ├── reservation-server/   # Spring Boot 后端（含 ai 包）
│   └── reservation-web/      # Vue 3 前端
├── database/
│   └── init_db.sql           # 自包含初始化脚本（五表 + 基线数据，与 R1-R6 一致）
├── docs/
│   ├── task-description.md   # 本文件
│   ├── api-list.md           # 接口清单（R6 31 端点 + R7 4 个 AI 只读接口）
│   ├── test-report.md        # 测试报告（交付配置 228/228 + 联调模式 234/234 + 浏览器实测 + 降级实测）
│   └── test-script.ps1       # 全量测试脚本（T01-T241，可复跑，自动恢复基线）
└── startup-guide.md          # 启动说明
```
