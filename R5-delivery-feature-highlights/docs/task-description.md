# R5 轮次任务说明 —— 亮点功能（日历总览 / 数据看板）

> 基线：R4-delivery-experience-upgrade（git commit 7b03e59），本轮从 R4 目录复制工程到本目录 `code/` 内继续开发。
> R1/R2/R3/R4 交付目录只读，未做任何修改；数据库表结构未变更（五表沿用：sys_user / classroom / reservation / user_favorite / ai_config）。

## 一、本轮范围

| 模块 | 内容 |
| --- | --- |
| 日历总览页 | 学生端第 5 页（路由 `/student/calendar`）：FullCalendar 月/周视图、按教室/日期展示预约（四状态色块）、点击色块查看详情、点击空白时段快速预约（复用 POST /api/reservation + 前后端双重冲突校验） |
| 数据看板页 | 管理端看板（路由 `/admin/dashboard`）：ECharts 三图（教室使用率排行柱状图 / 月度预约趋势折线图 / 热门时段分布环形图）+ 时间筛选（近 7/30/90 天/本年 + 自定义区间），图表数据全部来自后端只读统计接口 |
| 回归保障 | R1 21 + R2 50 + R3 54 + R4 28 = 153 条基线用例零回归失败 + 本轮新增 27 条 = 180 条全部通过 |
| 交付物 | code/ + database/ + docs/（任务说明/接口清单/测试报告/测试脚本）+ startup-guide.md，目录与文件名不使用中文 |

## 二、规则与决策标注（依据三份文档，无自行新增业务规则）

### 决策 1：新增 4 个只读接口（需求文档 2.4 口径，项目负责人已批准）
- `GET /api/reservation/calendar`（登录即可）：日历区间查询，`classroomId` 可选、`startDate`/`endDate` 必填、跨度 ≤366 天；返回区间内全部状态预约并批量补全教室展示字段。
- `GET /api/stats/usage-rate`（管理员）：教室使用率排行（柱状图数据源）。
- `GET /api/stats/trend`（管理员）：月度预约趋势（折线图数据源）。
- `GET /api/stats/time-distribution`（管理员）：热门时段分布（环形图数据源）。
- 均为只读统计/查询接口，**不引入任何写接口**；权限矩阵见 `api-list.md`。

### 决策 2：看板统计口径（已批准，测试按此断言与库一致性）
- **使用率** = 区间内已通过预约占用小时 ÷（区间天数 × 14h/天）× 100，保留 1 位小数；全部 12 间教室参与排行，按使用率倒序，无预约教室为 0。
  - 开放时段 08:00-22:00（14 小时）；占用小时 = Σ(结束-开始) 按已通过(status=1) 预约计算。
- **趋势** = 按 `reserve_date` 归属自然月统计区间内**全部状态**预约条数，月份升序。
- **时段分布** = 按已通过预约开始时间分六桶：`08:00-10:00 / 10:00-12:00 / 14:00-16:00 / 16:00-18:00 / 19:00-21:00 / 其他`，统计各桶条数与占比 %（保留 1 位小数）。
- **时间筛选**：缺省近 30 天；快捷近 7/30/90 天/本年 + 自定义区间；跨度上限 366 天。
- 日期区间按**包含首尾**计算天数（如 08-13~09-11 为 30 天）。

### 决策 3：本轮只做 2 页（项目负责人已批准）
- R5 完成日历总览页 + 数据看板页 2 页；需求设计文档第 12 页「预约记录页」留待 R6。
- R5 后页面数：学生端 5 页（教室列表/详情/我的预约/个人中心/日历总览）+ 管理端 5 页（首页/用户/教室/审核/看板）+ 登录/注册 = 12 页，13 页闭环明确留待 R6（按负责人批复，不提前实现）。

### 决策 4：日历色块状态口径与 R3/R4 状态常量一致
- 待审核=橙、已通过=绿、已驳回=红、已取消=灰（前端图例与后端状态常量一一对应，`Constants` 统一）。

### 决策 5：快速预约双端校验（硬约束，禁止绕过）
- 点击空白日期 → 弹窗预填日期 → 前端实时调用 `GET /api/reservation/conflict` 做前端校验（冲突时显示红色提示并禁用提交按钮）→ 提交复用 `POST /api/reservation`，后端二次校验冲突（与已通过预约时间重叠拒绝）并返回友好 message。

### 决策 6：新增前端依赖锁定（spec 第 7 节点名，仅两个）
- `echarts` 锁定 `5.5.1`、`fullcalendar` 锁定 `6.1.15`；既有依赖（Vue 3.4.38 / Element Plus 2.7.8 / Vue Router 4.4.3 / Pinia 2.1.7 / Vite 5.4.21）保持不动，未引入其他新依赖。
- FullCalendar 6 集成要点：无独立 CSS（样式 JS 内联注入）；子包 default 导出（dayGridPlugin / interactionPlugin / zhCnLocale）。

### 决策 7：权限矩阵（新增接口）
- `/api/stats/*` 位于拦截器管理员前缀（`Constants.ADMIN_API_PREFIXES` 含 `/api/stats`）→ 管理员 200、学生 403、未登录 401。
- `/api/reservation/calendar` 只需登录即可访问（学生/管理员均可）→ 未登录 401。

## 三、主要实现说明

### 后端（`code/reservation-server`，基于 R4 增量 4 个接口）

新增类：
- `vo/CalendarVO` —— 日历项（id/classroomId/classroomName/building/roomNo/reserveDate/startTime/endTime/purpose/status/auditRemark）
- `vo/UsageRateVO` —— 教室使用率（classroomId/name/building/roomNo/type/capacity/approvedHours/usageRate）
- `vo/TrendVO` —— 月度趋势（month/count）
- `vo/TimeDistributionVO` —— 时段分布（slot/count/percentage）
- `service/StatsService` + `impl/StatsServiceImpl` —— 三统计口径实现（内嵌 DateRange 日期区间校验）
- `controller/StatsController` —— `@RequestMapping("/api/stats")` 三个只读接口

改造类：
- `service/ReservationService` + `impl/ReservationServiceImpl` —— 新增 `listCalendar()`：参数校验（必填/跨度/倒序）→ 区间查询全状态预约 → 批量补全教室字段
- `controller/ReservationController` —— 新增 `GET /api/reservation/calendar`
- `common/Constants` —— 新增日历常量（CALENDAR_MAX_DAYS=366、必填/跨度提示语）与看板常量（DAILY_AVAILABLE_HOURS=14、OPEN_START=08:00、OPEN_END=22:00、STATS_DEFAULT_DAYS=30、STATS_MAX_DAYS=366、TIME_SLOT_LABELS 六桶）

安全要点：
- 统计与日历接口均不返回 `password` 字段（白名单 VO，R5 用例 T156/T182 显式断言）；
- 接口层统一 Result 返回 + 全局异常 + `jakarta.*` 包名（沿用基线规范）。

### 前端（`code/reservation-web`）

新增页面/文件：
- `views/student/CalendarOverview.vue` —— 日历总览页：FullCalendar 月视图（默认）/周视图切换、教室筛选下拉（复用 listClassrooms）、四状态图例、点击色块 → 详情弹窗、点击空白 → 快速预约弹窗（预填日期 + 实时 conflict 检测 + 提交复用 POST /api/reservation）
- `views/admin/Dashboard.vue` —— 数据看板页：ECharts 三图（使用率排行柱状图 / 月度趋势折线图 / 时段分布环形图）+ 快捷时间筛选（近 7/30/90 天/本年）+ 自定义日期区间，三图 `Promise.all` 并发拉取 stats 接口，图表数据不伪造
- `api/stats.js` —— 三个统计接口封装

改造文件：
- `api/reservation.js` —— 新增 `getCalendarReservations`
- `router/index.js` —— 学生端新增 `calendar` 子路由、管理端新增 `dashboard` 子路由
- `views/student/StudentLayout.vue` —— 顶部导航新增「日历总览」
- `views/admin/AdminHome.vue` —— 左侧菜单新增「数据看板」（DataAnalysis 图标）
- `package.json` —— echarts/fullcalendar 精确锁定（其余依赖不动）

### 体验细节（验收逐项核对）
1. 日历月/周视图切换：默认月视图，可切周视图，日期导航（今天/前后）可用；
2. 色块四状态颜色与后端状态常量一致（待审核橙/已通过绿/已驳回红/已取消灰）；
3. 点击色块 → 预约详情弹窗（教室/日期/时段/状态/用途/驳回备注）；
4. 点击空白日期 → 快速预约弹窗（预填日期）；教室下拉可选全部 12 间；
5. 快速预约冲突检测：与已通过预约时间重叠 → 红色冲突提示 + 提交按钮禁用；不重叠 → 绿色「该时段可预约」；
6. 看板三图数据全部来自后端 stats 接口；时间筛选切换后日期范围与图表数据同步刷新；
7. 看板入口位于管理端左侧菜单；日历入口位于学生端顶部导航。

## 四、测试与回归

- 复用 R4 `docs/test-script.ps1`（T01-T155 共 153 条）并追加 R5 段（T156-T182 共 27 条），总计 **180 条**。
- R5 用例覆盖：正常流程 10 / 边界场景 6 / 异常操作 7 / 权限校验 7（含库一致性 3 条 SQL 对照）。
- 库一致性断言：使用率（SQL 计算 B101 区间占用小时 → 期望使用率 1.8 与 API 对比）、
  月度趋势（SQL 按月 COUNT 与 API 对比）、时段分布（SQL 六桶 COUNT/占比与 API 对比）。
- 测试数据策略：R5 段开头清理 R1-R4 测试遗留数据（预约 id>13、收藏 id>6、临时用户），使统计口径回到基线 13 条，保证与库一致性断言精确。
- 清理策略：结束后恢复数据库初始基线——预约 13 条四状态（id 1-13）、收藏 6 条（id 1-6）、用户 5 人、教室 12 间；脚本可重复运行。
- 运行方式见 `startup-guide.md`。

## 五、数据库

- 无表结构变更、无新增表、无数据脚本变更（`database/init_db.sql` 与 R4 一致，自包含）。
- 统计与日历均为只读查询，不产生任何数据写入。