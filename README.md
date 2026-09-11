# 高校实验室预约管理系统（基于双重校验机制）

前后端分离的高校实验室预约管理系统实训项目。预约提交采用「前端实时校验 + 后端二次冲突检测」双重校验机制，
冲突检测公式：`新开始 < 旧结束 AND 新结束 > 旧开始`。

## 技术栈

| 端 | 技术 |
|----|------|
| 后端 | Spring Boot 3.2.10 · MyBatis-Plus 3.5.7 · Knife4j 4.4.0 · EasyExcel 3.3.4 · Hutool 5.8.32 · JDK 21 |
| 前端 | Vue 3.4 · Vite 5 · Element Plus 2.7 · Pinia · Vue Router 4 · ECharts 5.5 · FullCalendar 6.1 |
| 数据库 | MySQL 8.0（Docker 容器 reservation-mysql，库名 reservation，utf8mb4） |
| AI 服务 | Agnes AI（国内节点，密钥仅环境变量注入，可开关，超时 3 秒降级） |

## 目录结构

```
reservation-system/
├── 需求设计文档.md          # 业务需求与设计依据
├── spec.md                  # 技术开发规格书
├── AGENTS.md                # 协作章程与红线约束
├── prompts.md               # 各轮新对话提示词
└── R1-delivery-base-foundation/
    ├── code/reservation-server/   # 后端工程（Spring Boot 3.2.10）
    ├── code/reservation-web/      # 前端工程（Vue 3.4 + Vite 5）
    ├── database/init_db.sql       # 建库建表 + 测试数据（可重复执行）
    ├── docs/                      # 任务说明 / 接口清单 / 测试报告 / 回归脚本
    └── startup-guide.md           # 环境要求 / 启动步骤 / 验证方法
```

## 快速启动

1. 启动 MySQL 容器：`docker start reservation-mysql`（未创建时先按 `database/init_db.sql` 与 spec.md 4.0 创建）。
2. 导入数据：执行 `R1-delivery-base-foundation/database/init_db.sql`。
3. 后端：`cd R1-delivery-base-foundation/code/reservation-server && mvn spring-boot:run`，接口文档 `http://localhost:8080/doc.html`。
4. 前端：`cd R1-delivery-base-foundation/code/reservation-web && npm install && npm run dev`，访问 `http://localhost:5173`。

测试账号：管理员 `admin/admin123`，学生 `zhangsan/123456` 等（见 `database/init_db.sql`）。

## 轮次进度

| 轮次 | 内容 | 状态 |
|------|------|------|
| R1 | 基础底座：数据库、前后端骨架、登录注册、权限拦截 | ✅ 已完成 |
| R2 | 管理端基础：用户管理 + 教室管理 | 待开发 |
| R3~R8 | 预约核心 / 体验升级 / 日历看板 / 联调优化 / AI 模块 / 答辩准备 | 待开发 |
