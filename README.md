# 高校实验室预约管理系统（基于双重校验机制）

前后端分离的高校实验室预约管理系统实训项目（8 轮迭代完整交付）。预约提交采用「前端实时校验 + 后端二次冲突检测」双重校验机制，
冲突检测公式：`新开始 < 旧结束 AND 新结束 > 旧开始`；预约状态流转：待审核(0) → 已通过(1)/已驳回(2)，待审核/已通过 → 已取消(3)。

## 技术栈

| 端 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2.10 · MyBatis-Plus 3.5.7 · Knife4j 4.4.0 · EasyExcel 3.3.4 · Hutool 5.8.32 · JDK 21（统一 `jakarta.*`） |
| 前端 | Vue 3.4.38 · Vite 5 · Element Plus 2.7.8 · Pinia 2.1.7 · Vue Router 4.4.3 · ECharts 5.5.1 · FullCalendar 6.1.15 · day.js 1.11.13 |
| 数据库 | MySQL 8.0（Docker 容器 reservation-mysql，库名 reservation，utf8mb4） |
| AI 服务 | Agnes AI（国内节点 https://api.agnes-ai.cn/v1，模型 agnes-2.0-flash，密钥仅环境变量注入，可一键启停，超时 60 秒自动降级） |

## 目录结构

```
lab-reservation-system/
├── server/                    # 后端工程（Spring Boot 3.2.10，唯一代码源）
├── web/                       # 前端工程（Vue 3.4 + Vite 5，唯一代码源）
├── database/
│   └── init_db.sql            # 建库建表 + 基线数据（自包含，可重复执行）
├── materials/                 # 答辩材料：defense-ppt.pptx / training-report.docx
├── docs/
│   ├── iterations/R1~R8/      # 各轮 任务说明/接口清单/测试报告/启动说明（按轮次归档）
│   └── review/                # 软件审查报告 / 审查提示词 / 审查可视化
├── archive/R1~R7/             # 历史轮次代码快照（git 历史已完整保留，仅作留存）
├── AGENTS.md                  # 协作章程与红线约束
├── README.md                  # 本文件
├── 需求设计文档.md            # 业务需求与设计依据（唯一需求基准，V3.1）
├── spec.md                    # 技术开发规格书
└── prompts.md                 # 各轮新对话提示词
```

## 快速启动

环境：JDK 21 LTS · Maven 3.9.x（`mvn` 不在 PATH 时使用 IDEA 内置 Maven：
`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd`）·
Node.js 22.x · Docker（运行 MySQL 8.0）。

```powershell
# 1. 启动数据库（容器已创建，勿重建、勿重新导数据）
docker start reservation-mysql

# 2. 后端（端口 8080，接口文档 http://localhost:8080/doc.html）
cd server
mvn spring-boot:run

# 3. 前端（端口 5173）
cd web
npm install    # 首次
npm run dev    # 浏览器访问 http://localhost:5173
```

- 初始化空库（需要时）：执行 `database\init_db.sql`（自包含 DROP+CREATE+INSERT，CURDATE() 动态日期）。
- 测试账号：管理员 `admin / admin123`；学生 `zhangsan / 123456`（另有 lisi/wangwu/zhaoliu，密码均 123456）。

## AI 模块（双开关，默认关闭）

- 总开关：`server/src/main/resources/application.yml` 中 `ai.enable`（默认 `false`，关闭不影响核心系统）；
  数据库开关：`ai_config` 表中 `ai_enable`。两者均为 `true` 时 AI 功能可用。
- 接入：国内节点 `https://api.agnes-ai.cn/v1`，模型 `agnes-2.0-flash`，密钥通过环境变量
  `AGNES_API_KEY` 注入（前端零接触）；读取超时 60s（连接超时固定 10s），超时/报错/限流（RPM≈20）
  自动降级为本地规则模拟，不阻断业务。
- 只读不写：AI 仅查询数据、解析需求、给出建议，所有业务操作必须用户手动确认；生成内容前端标注「AI 生成，仅供参考」。
- 演示模式：不配置真实密钥时，推荐/解析/问答/合规全部由本地规则引擎输出，功能完整可演示。

## 答辩要点

| 材料 | 位置 |
|------|------|
| 答辩 PPT | `materials/defense-ppt.pptx` |
| 实训报告 | `materials/training-report.docx` |
| 核心业务演示脚本 | `docs/iterations/R8-delivery-defense-prep/demo-script.md` |
| AI 演示脚本 + 兜底预案 | `docs/iterations/R8-delivery-defense-prep/ai-demo-script.md` |
| R8 测试报告（228 条回归全过） | `docs/iterations/R8-delivery-defense-prep/test-report.md` |
| 软件审查报告 + 可视化 | `docs/review/` |

## 轮次进度

| 轮次 | 内容 | 状态 |
|------|------|------|
| R1 | 基础底座：数据库、前后端骨架、登录注册、权限拦截 | ✅ 已完成 |
| R2 | 管理端：用户管理 + 教室管理 | ✅ 已完成 |
| R3 | 预约核心：教室浏览、预约与冲突检测、审核 | ✅ 已完成 |
| R4 | 体验升级：个人中心、收藏全链路、体验细节 | ✅ 已完成 |
| R5 | 亮点功能：日历总览 + 数据看板（ECharts/FullCalendar） | ✅ 已完成 |
| R6 | 联调优化：全流程贯通与回归保障 | ✅ 已完成 |
| R7 | AI 模块：Agnes 接入与降级容错 | ✅ 已完成 |
| R8 | 答辩准备：实训报告、演示材料、防御加固 | ✅ 已完成 |

## 历史与归档

- git 历史完整保留 8 个 commit（R1~R8 每轮一条）+ R9 防御加固，全部可追溯。
- `archive/` 保留 R1~R7 各轮旧代码快照（`docs/iterations/` 同步归档各轮任务说明/接口清单/测试报告/启动说明）；
  当前唯一代码源为 `server/` + `web/`（R8 最终版 + R9 加固），后续维护只在这两处。
