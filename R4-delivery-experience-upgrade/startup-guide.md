# R4 交付启动说明 —— 基于双重校验机制的高校实验室预约管理系统

> 环境基线：JDK 21 LTS · IDEA 内置 Maven 3.9.x · Node.js 22.x · Docker 运行 MySQL 8.0
> 本轮交付目录：`R4-delivery-experience-upgrade`（从 R3 commit 9aa8ecc 复制工程增量开发）

## 一、目录结构

```
R4-delivery-experience-upgrade/
├── code/
│   ├── reservation-server/     # 后端 Spring Boot 3.2.10（MyBatis-Plus 3.5.7）
│   └── reservation-web/        # 前端 Vue 3.4 + Vite 5 + Element Plus 2.7
├── database/
│   └── init_db.sql             # 数据库初始化脚本（自包含，含 13 条预约 / 6 条收藏基线数据）
├── docs/
│   ├── task-description.md     # 本轮任务说明（范围 / 决策标注 / 实现说明）
│   ├── api-list.md             # 接口清单（R4 新增 5 个）
│   ├── test-script.ps1         # 测试脚本（R1 21 + R2 50 + R3 54 + R4 28 = 153 条，可复跑）
│   └── test-report.md          # 测试报告
└── startup-guide.md            # 本文档
```

## 二、数据库准备

1. MySQL 容器（reservation-mysql，映射 localhost:3306，库 reservation，root/root，utf8mb4）：
   ```powershell
   docker start reservation-mysql   # 若容器停止只需启动，禁止重建或重新导数据
   ```
2. 首次部署需执行初始化脚本（若库中已有同基线数据可跳过，**禁止覆盖基线**）：
   ```powershell
   docker exec -i reservation-mysql mysql -uroot -proot reservation < database\init_db.sql
   ```
   基线数据：管理员 admin/admin123；学生 zhangsan/123456 等 5 用户、12 间教室、
   13 条预约（待审核3/已通过6/已驳回2/已取消2）、6 条收藏。

## 三、后端启动（8080）

```powershell
cd code\reservation-server
# 方式一：IDEA 直接打开工程运行 ReservationApplication
# 方式二：命令行（mvn 不在 PATH，使用 IDEA 内置 Maven）
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
```
启动成功标志：`Tomcat started on port 8080`。接口文档：http://localhost:8080/doc.html

## 四、前端启动（5173，已代理 /api 到 8080）

```powershell
cd code\reservation-web
npm install        # 有 package-lock，精确锁定依赖版本（首次或 node_modules 缺失时）
npm run dev        # Vite dev server，http://localhost:5173
```
访问 http://localhost:5173 ，学生账号 zhangsan/123456 可体验：
- 个人中心（导航第 3 项）：数据概览 / 常用教室（收藏）/ 消息通知 / 个人信息 / 修改密码；
- 教室详情页：星标收藏按钮、预约弹窗草稿自动保存；
- 教室列表：筛选条件记忆（刷新/返回自动还原）；
- 我的预约：今日预约置顶高亮 + 「今日」标签；
- 登录后若存在 24h 内即将开始的已通过预约会弹出提醒。

## 五、回归测试（153 条，可复跑）

前置：后端已启动（8080）、MySQL 容器运行、数据库处于初始基线。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docs\test-script.ps1
```

- 脚本自动完成：登录 admin/zhangsan → 依次执行 R1 21 + R2 50 + R3 54 + R4 28 条用例 →
  汇总输出「用例总数/通过/失败」+ 失败明细。
- 脚本自带清理：结束后恢复数据库初始基线（预约 13 条四状态、收藏 6 条、用户 5 人、教室 12 间），可重复运行。

## 六、常见问题

| 现象 | 处理 |
| --- | --- |
| 8080 被占用 | 找到旧 dev 进程（java.exe）结束即可（R3 遗留进程）；禁止动数据库 |
| 5173 被占用 | 结束旧的 node vite 进程后重新 `npm run dev` |
| 容器停止 | 仅 `docker start reservation-mysql`，禁止重建/重导 |
| 测试中接口 401 但已登录 | 确认 Token 已注入请求头；测试脚本自动处理 |
