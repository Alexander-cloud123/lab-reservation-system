# 高校实验室预约管理系统

[![Web CI](https://github.com/Alexander-cloud123/lab-reservation-system/actions/workflows/web-lint.yml/badge.svg?branch=main)](https://github.com/Alexander-cloud123/lab-reservation-system/actions/workflows/web-lint.yml)
[![Server CI](https://github.com/Alexander-cloud123/lab-reservation-system/actions/workflows/server-ci.yml/badge.svg?branch=main)](https://github.com/Alexander-cloud123/lab-reservation-system/actions/workflows/server-ci.yml)
[![Stack Smoke & E2E](https://github.com/Alexander-cloud123/lab-reservation-system/actions/workflows/stack-smoke.yml/badge.svg?branch=main)](https://github.com/Alexander-cloud123/lab-reservation-system/actions/workflows/stack-smoke.yml)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Java](https://img.shields.io/badge/Java-21-blue.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.10-6DB33F.svg)
![Vue](https://img.shields.io/badge/Vue-3.4-4FC08D.svg)
![Backend Tests](https://img.shields.io/badge/backend%20tests-309-brightgreen.svg)
![E2E](https://img.shields.io/badge/E2E-120%20%28Chromium%20%2B%20Edge%29-brightgreen.svg)

前后端分离的高校实验室/教室预约管理系统，覆盖 R1–R13 共 13 轮迭代。核心是预约提交的**「前端实时校验 + 后端二次冲突检测」双重校验**，以及**严格的预约状态流转**：

- 冲突检测公式：`新开始 < 旧结束 AND 新结束 > 旧开始`
- 状态流转：待审核(0) → 已通过(1) / 已驳回(2)；待审核 / 已通过 → 已取消(3)

**规模**：309 个后端测试（行覆盖 80.6%）· 41 项接口冒烟 · 120 例 Playwright E2E（Chromium + Edge）· 3 套 GitHub Actions。

## 项目预览

截图来自本机真实运行（种子账号登录，1440×900），可用 `node web/scripts/capture-screens.mjs` 复现：

<table>
  <tr>
    <td><img src="docs/images/01-login.png" width="430" /></td>
    <td><img src="docs/images/02-student-home.png" width="430" /></td>
  </tr>
  <tr>
    <td align="center">登录（角色切换 + 字段级错误提示）</td>
    <td align="center">教室列表（关键词 / 楼栋 / 类型 / 日期筛选）</td>
  </tr>
  <tr>
    <td><img src="docs/images/03-classroom-detail.png" width="430" /></td>
    <td><img src="docs/images/04-my-reservations.png" width="430" /></td>
  </tr>
  <tr>
    <td align="center">教室详情（预约申请与实时冲突检测）</td>
    <td align="center">我的预约（今日置顶 + 状态筛选 + 取消）</td>
  </tr>
  <tr>
    <td><img src="docs/images/05-calendar.png" width="430" /></td>
    <td><img src="docs/images/06-admin-dashboard.png" width="430" /></td>
  </tr>
  <tr>
    <td align="center">日历总览（FullCalendar 月 / 周视图）</td>
    <td align="center">数据看板（使用率排行 / 时段分布 / 趋势）</td>
  </tr>
  <tr>
    <td><img src="docs/images/07-admin-audit.png" width="430" /></td>
    <td></td>
  </tr>
  <tr>
    <td align="center">预约审核（单条审核 + 批量通过 / 驳回）</td>
    <td></td>
  </tr>
</table>

## 核心功能

| 模块 | 能力 |
|------|------|
| 账号与权限 | 登录注册、JWT 鉴权、学生 / 管理员双角色权限拦截、登出即撤销 |
| 教室管理 | 教室增删改查、按楼栋与条件筛选、启用停用 |
| 预约核心 | 教室浏览与详情、预约提交（双重校验）、时段冲突检测、管理员审核 |
| 个人中心 | 资料维护、收藏全链路、预约记录查询 |
| 亮点功能 | 日历总览（FullCalendar）、数据看板（ECharts） |
| 数据导出 | 预约记录 Excel 导出（EasyExcel） |
| AI 能力 | 智能推荐、自然语言预约解析、智能问答、合规校验（可一键关闭，关闭不影响核心系统） |

## 技术栈

| 端 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2.10 · MyBatis-Plus 3.5.7 · Knife4j 4.4.0 · EasyExcel 3.3.4 · Hutool 5.8.32 · spring-webflux（仅用 WebClient）· Spring Data Redis · JDK 21（统一 `jakarta.*`） |
| 前端 | Vue 3.4 · Vite 5 · Element Plus 2.7 · Pinia 2 · Vue Router 4 · ECharts 5.5 · FullCalendar 6.1 · day.js · markdown-it · dompurify |
| 数据 | MySQL 8.0（utf8mb4）· Redis 7.0（会话态 / 热点缓存，可一键启停） |
| AI | Agnes AI（国内节点，模型 `agnes-3.0-flash`，密钥仅环境变量注入，60 秒超时自动降级） |
| 质量 | JUnit 5 + Mockito · ESLint 9 + Prettier 3 · Playwright 1.63 · JaCoCo |

后端严格遵循 `controller → service → mapper → entity` 四层结构，所有接口统一返回 `Result`，并实现全局异常处理器，不向前端暴露异常堆栈。

## 架构设计

```mermaid
flowchart TB
    subgraph FE["前端 · Vue 3 单页应用（5173 / 8088）"]
        UI["页面与组件<br/>Element Plus + ECharts + FullCalendar"]
        AX["Axios 封装<br/>自动携带 JWT / 统一错误提示"]
        UI --> AX
    end

    subgraph BE["后端 · Spring Boot 3 API（8080）"]
        INT["AuthInterceptor<br/>JWT 验签 + token_version 比对"]
        CTL["Controller 层<br/>参数校验 / 统一 Result"]
        SVC["Service 层<br/>冲突检测 · 状态流转 · 缓存失效"]
        MAP["MyBatis-Plus Mapper"]
        AIC["ai 包 · Agnes 客户端<br/>限流 / 超时预算 / 本地降级"]
        INT --> CTL --> SVC --> MAP
        SVC --> AIC
    end

    subgraph ST["数据与缓存"]
        DB[("MySQL 8.0<br/>reservation")]
        RD[("Redis 7.0<br/>会话态 · 热点缓存")]
    end

    AX -->|"HTTP / JWT"| INT
    MAP --> DB
    SVC --> RD
    CI["GitHub Actions<br/>单测 + 冒烟 + E2E"] -.->|"质量门禁"| BE
```

关键取舍：

| 设计点 | 方案 | 原因 |
|--------|------|------|
| 会话撤销 | `sys_user.token_version` 版本号 + JWT 携带比对，Redis 仅做 60 秒缓存 | 登出 / 改密 / 禁用必须**服务端持久生效**；纯 Redis 黑名单在 Redis 关闭或抖动时失效，且无法证明“无 Redis 也能撤销” |
| 冲突检测 | 前端实时校验 + 后端 `新开始 < 旧结束 AND 新结束 > 旧开始` 二次检测，条件更新 + `FOR UPDATE` | 前端校验只做体验，并发正确性必须由数据库条件更新兜底，避免“先查后写”竞态 |
| AI 降级 | 超时 60 秒 / 429 / 报错一律降级为本地规则模拟；`ai.enable` + `ai_config.ai_enable` 双开关 | AI 是加分项而非依赖项，任何情况都不能阻断预约主流程；双开关保证默认关闭时零外部依赖 |

## 快速开始

环境要求：Docker（仅容器方式）；本地开发另需 JDK 21 LTS · Maven 3.9.x · Node.js 22.x。完整环境变量见 [运行与配置](docs/运行与配置.md)。

### 方式一：Docker 一键起全栈

```powershell
docker volume create reservation-mysql-data    # 首次执行：MySQL 数据卷为外部卷
docker compose up -d --build                   # 构建并启动 MySQL / Redis / api / web
docker compose ps                              # 四条服务均应为 healthy
# 系统入口 http://localhost:8088   接口文档 http://localhost:8080/doc.html
```

数据库首次初始化由容器自动执行 [init_db.sql](database/init_db.sql)（仅在数据卷为空时触发）。**存量库需执行一次幂等迁移**（会话撤销依赖 `sys_user.token_version`，重复执行安全）：

```powershell
docker compose exec -T mysql mysql -uroot -proot --default-character-set=utf8mb4 < database/migrations/20261107_add_sys_user_token_version.sql
```

### 方式二：本地开发（数据库用容器，前后端跑宿主机）

```powershell
docker compose up -d mysql redis                # 映射 3307 / 6380
mysql -h 127.0.0.1 -P 3307 -uroot -proot < database/init_db.sql

# 后端：容器端口与默认值不同，先注入环境变量（完整清单见 运行与配置）
$env:MYSQL_URL  = "jdbc:mysql://localhost:3307/reservation?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:REDIS_PORT = "6380"
mvn -f server/pom.xml spring-boot:run

# 前端（另开终端）
cd web; npm ci; npm run dev                     # http://127.0.0.1:5173
```

### 启用 AI（可选，双开关缺一不可）

```powershell
# 1. .env 设置 AI_ENABLE=true 与 AGNES_API_KEY=<密钥>，重建后端
docker compose up -d api
# 2. 打开数据库开关（基线为 false，重建数据卷后会再次关闭）
docker compose exec mysql mysql -uroot -proot -e "UPDATE reservation.ai_config SET config_value='true' WHERE config_key='ai_enable';"
# 3. 前端重新登录，AI 入口才会出现
```

演示账号：管理员 `admin / admin123`；学生 `zhangsan / 123456`（另有 `lisi`、`wangwu`、`zhaoliu`，密码均为 `123456`）。

## 质量与测试

| 层次 | 规模 | 入口 |
|------|------|------|
| 后端单元 / 集成测试 | 40 个测试类、309 个用例；行覆盖 **80.6%**（指令 81.9% / 分支 74.2%，JaCoCo） | `mvn -f server/pom.xml test` |
| 接口冒烟回归 | 41 项（鉴权 / 权限 / 预约生命周期 / 冲突 / 校验 / 看板 / 收藏 / AI 降级 / 会话撤销） | `pwsh server/scripts/smoke.ps1` |
| 前端 E2E | 17 个 spec、120 个用例 × Chromium + Edge | `npm run test:e2e:all`（默认只跑 Chromium） |
| 无障碍机器代理 | 16/16 项（人工听感按负责人决定不执行） | `node scripts/a11y-acceptance.mjs` |

```powershell
mvn -f server/pom.xml test                     # 后端单测 + 集成测试（需 MySQL/Redis 在线）
pwsh server/scripts/smoke.ps1                  # 41 项接口冒烟（需后端已启动）
cd web; npm run lint; npm run build            # 前端规范与构建
cd web; npm run test:e2e                       # Chromium E2E（webServer 自动拉起 Vite）
cd web; node scripts/capture-screens.mjs       # 采集 README 预览截图
```

## 持续集成

| 工作流 | 触发 | 内容 |
|--------|------|------|
| `web-lint.yml` | push / PR（`web/**`） | `npm ci` → `npm run lint` → `npm run build` |
| `server-ci.yml` | push / PR（`server/**`、`database/init_db.sql`）+ 手动 | 起 MySQL 8.0 / Redis 7.0 → 导入种子数据 → `mvn test`（309 项）→ 上传 JaCoCo 报告 |
| `stack-smoke.yml` | push / PR（`server/**`、`web/**`、`database/init_db.sql`）+ 手动 | **smoke**：打包 jar 起后端 → 41 项接口冒烟；**e2e**：独立栈 + Chromium → 120 例 Playwright（两 job 各自独立 MySQL / Redis，互不污染数据） |

已实测（2026-11-07）：`stack-smoke` 的 smoke **41/41**、e2e **120 passed**；`server-ci` **309 项测试**通过并上传 `jacoco-report`。

## 迭代历程（里程碑）

| 阶段 | 内容 |
|------|------|
| R1–R3 | 基础底座、管理端（用户 / 教室）、预约核心（双重校验 / 冲突检测 / 审核） |
| R4–R5 | 个人中心与收藏全链路、日历总览与数据看板（ECharts / FullCalendar） |
| R6–R10 | 联调与回归保障、AI 接入与降级、Redis 加分项、代码质量重构、E2E 基建与无障碍修复 |
| R11–R13 | AI 观测与结果缓存、智能问答 SSE 流式、停止生成 / Markdown / 多轮与结构化流式 |

逐轮变更摘要与交付文档见 [CHANGELOG.md](CHANGELOG.md) 与 [`docs/iterations/`](docs/iterations)。

## 文档导航

| 文档 | 位置 |
|------|------|
| 需求设计文档（唯一需求基准，V3.1） | [`需求设计文档.md`](需求设计文档.md) |
| 技术开发规格书 | [`spec.md`](spec.md) |
| 协作章程与红线约束 | [`AGENTS.md`](AGENTS.md) |
| 运行与配置（环境变量 / AI / Redis / 端口） | [`docs/运行与配置.md`](docs/运行与配置.md) |
| 各轮交付四件套（R1–R13） | [`docs/iterations/`](docs/iterations) |
| QA 收口报告 | [`docs/qa/`](docs/qa) |
| 软件审查报告与可视化 | [`docs/review/`](docs/review) |
| 无障碍清单 + 机器代理记录与证据 | [`docs/无障碍人工验收清单.md`](docs/无障碍人工验收清单.md) · [`docs/review/无障碍机器代理验收记录.md`](docs/review/无障碍机器代理验收记录.md) |
| 端到端测试报告与回归脚本 | [`docs/e2e/`](docs/e2e) |

## 目录结构

```
lab-reservation-system/
├── server/                # 后端（Spring Boot 3）：src/main、src/test、scripts（冒烟 / AI 验收）
├── web/                   # 前端（Vue 3）：src、scripts（截图 / 无障碍）、e2e（Playwright）
├── database/              # init_db.sql（自包含基线）+ migrations（幂等增量迁移）
├── docs/                  # 迭代交付、QA、审查、E2E 报告、预览截图、运行配置
├── materials/             # 答辩 PPT 与实训报告
├── docker-compose.yml     # MySQL / Redis / api / web 编排
├── 需求设计文档.md         # 业务需求与设计依据（V3.1）
└── CHANGELOG.md           # 版本与迭代变更记录
```

## 交付材料

| 材料 | 位置 |
|------|------|
| 答辩 PPT | [`materials/defense-ppt.pptx`](materials/defense-ppt.pptx) |
| 实训报告 | [`materials/training-report.docx`](materials/training-report.docx) |
| 核心业务演示脚本 | [`docs/iterations/R8-delivery-defense-prep/demo-script.md`](docs/iterations/R8-delivery-defense-prep/demo-script.md) |
| AI 演示脚本与兜底预案 | [`docs/iterations/R8-delivery-defense-prep/ai-demo-script.md`](docs/iterations/R8-delivery-defense-prep/ai-demo-script.md) |

## 历史与归档

git 历史完整保留 R1–R13 的逐轮提交与收口期加固提交。历史轮次源码快照（`archive/`）不再入库，避免与 `server/` + `web/` 重复；当前唯一代码源为 `server/` 与 `web/`。

## License

本项目基于 [MIT License](LICENSE) 开源。第三方依赖与 `.agents/skills/avoid-ai-design/` 目录沿用各自许可证。
贡献与修改规范见 [CONTRIBUTING.md](CONTRIBUTING.md)，提交前请按 [PR 模板](.github/PULL_REQUEST_TEMPLATE.md) 自检。
