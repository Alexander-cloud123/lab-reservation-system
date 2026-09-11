# R6 交付启动说明 —— 基于双重校验机制的高校实验室预约管理系统

> 环境基线：JDK 21 LTS · IDEA 内置 Maven 3.9.x · Node.js 22.x · Docker 运行 MySQL 8.0
> 本轮交付目录：`R6-delivery-integration-optimization`（从 R5 commit 7b5fa31 复制工程增量开发）

## 一、目录结构

```
R6-delivery-integration-optimization/
├── code/
│   ├── reservation-server/     # 后端 Spring Boot 3.2.10（MyBatis-Plus 3.5.7）
│   └── reservation-web/        # 前端 Vue 3.4 + Vite 5 + Element Plus 2.7 + ECharts 5.5.1（按需引入）+ FullCalendar 6.1.15
├── database/
│   └── init_db.sql             # 数据库初始化脚本（自包含，含 13 条预约 / 6 条收藏基线数据）
├── docs/
│   ├── task-description.md     # 本轮任务说明（范围 / 决策标注 / 实现说明 / 修复记录）
│   ├── api-list.md             # 接口清单（R6 新增导出 + manage 教室筛选 + 30 个复用接口）
│   ├── test-script.ps1         # 测试脚本（R1-R5 180 条 + R6 30 条 = 210 条，可复跑）
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
   13 条预约（待审核3/已通过6/已驳回2/已取消2）、6 条收藏；AI 配置 ai.enable=false（R7 前保持关闭）。

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
> 生产构建验证（可选）：
> ```powershell
> npm run build    # 无警告；index 6.77kB / Dashboard 4.86kB / echarts 独立 517kB（按需引入 + 分包）
> ```

访问 http://localhost:5173：

**学生端**（zhangsan/123456，5 页）：
- 教室列表（首页）/ 教室详情 / 我的预约 / 个人中心 / 日历总览：
  - 预约提交含前后端双重冲突校验（实时检测「该时段可预约/已冲突」）；
  - 日历月/周视图、四状态色块、点击空白时段快速预约；
  - 我的预约：状态 Tab 筛选、今日预约置顶、三态标签、取消（开始前 1 小时内禁止取消）。

**管理端**（admin/admin123，6 页）：
- 首页 / 用户管理 / 教室资源管理 / 预约审核 / **预约记录** / 数据看板：
  - 预约记录页（R6 新增）：日期范围 / 教室 / 用户关键词 / 状态下拉多条件筛选 + 分页 + **导出 Excel**（xlsx，中文文件名含时间戳）；
  - 数据看板：使用率排行 / 月度趋势 / 时段分布三图 + 时间筛选；
  - 审核通过/驳回带二次确认；批量审核/启用停用可用。

**13 页闭环**：登录 + 注册 + 学生端 5 页 + 管理端 6 页全部可达、可操作。

## 五、回归测试（210 条，可复跑）

前置：后端已启动（8080）、MySQL 容器运行、数据库处于初始基线。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docs\test-script.ps1
```

- 脚本自动完成：登录 admin/zhangsan → 依次执行 R1 21 + R2 50 + R3 54 + R4 28 + R5 27 + R6 30 条用例 →
  汇总输出「用例总数/通过/失败」+ 失败明细。
- R6 段覆盖：预约记录页 manage（教室/状态/日期/关键词组合、空数据、非法参数、超大分页、权限）、
  导出（全量/筛选/库一致性/内容/空结果/权限/不含 password/xlsx 文件头）、13 页闭环数据锚点。
- 脚本自带清理：结束后恢复数据库初始基线（预约 13 条四状态、收藏 6 条、用户 5 人、教室 12 间），可重复运行。

## 六、常见问题

| 现象 | 处理 |
| --- | --- |
| 8080 被占用 | 找到旧 dev 进程（java.exe）结束即可；禁止动数据库 |
| 5173 被占用 | 结束旧的 node/vite 进程后重新 `npm run dev` |
| 容器停止 | 仅 `docker start reservation-mysql`，禁止重建/重导 |
| 测试中接口 401 但已登录 | 确认 Token 已注入请求头；测试脚本自动处理 |
| 前端首次启动报 ENOENT | 确认工作目录存在 `.devtmp`（调试日志目录），不存在则手动创建 |
| git push 报代理错误 | 改用一次性直连：`git -c http.proxy= -c https.proxy= push origin main`，禁止修改全局代理配置 |
