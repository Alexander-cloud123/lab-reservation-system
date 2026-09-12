# R3 轮次任务说明 —— 预约核心（教室浏览 / 预约提交+冲突检测 / 我的预约+取消 / 审核+批量审核）

- 交付目录：`R3-delivery-reservation-core/`
- 基线：R2（`R2-delivery-admin-management`，git commit `80123a6`），工程整体复制后在本目录内继续开发
- 依据：需求设计文档.md（业务唯一基准）、spec.md 第 7 节 R3 行（技术规格）、AGENTS.md（协作章程与红线）
- 三份决策已由项目负责人确认，见下文「规则与决策标注」

---

## 一、本轮范围

| 模块 | 后端 | 前端 |
|---|---|---|
| 教室浏览（学生端） | `GET /api/classroom/list`（分页+多条件筛选+实时状态标签）、`GET /api/classroom/{id}`（详情+指定日期占用） | `ClassroomList.vue`（替换原 StudentHome 占位首页）、`ClassroomDetail.vue`（时段占用可视化+预约弹窗） |
| 预约提交 + 冲突检测（双重校验） | `GET /api/reservation/conflict`、`POST /api/reservation`（后端二次冲突检测兜底） | 预约弹窗提交前实时冲突校验，冲突禁用提交 |
| 我的预约 + 取消 | `GET /api/reservation/mine`、`PUT /api/reservation/{id}/cancel` | `MyReservation.vue`（状态分类+取消二次确认+空状态） |
| 审核 + 批量审核（管理员） | `GET /api/reservation/manage`、`PUT /api/reservation/{id}/audit`、`POST /api/reservation/batch-audit` | `AuditManage.vue`（筛选+审核+驳回快捷原因+批量审核） |

## 二、规则与决策标注（负责人已确认）

### 决策 1：审核类接口权限判定方式
单条审核 `PUT /api/reservation/{id}/audit` 与批量审核 `POST /api/reservation/batch-audit` 的路径**不在** `/api/reservation/manage` 前缀下，因此扩展 `AuthInterceptor.isAdminPath` 做**精确匹配**：
- 前缀匹配：`/api/reservation/manage`（含 `/api/reservation/manage?...`）
- 精确路径：`/api/reservation/batch-audit`
- 正则：`^/api/reservation/\d+/audit$`
学生 Token 访问以上任意路径返回 **403**（测试 T122/T123/T124 覆盖）。

### 决策 2：学生端仅展示可用教室
教室列表（学生端）只返回 `status = 1`（可用）的教室；停用教室学生不可见、不可预约（测试 T80 停用教室学生不可见、T101 停用教室提交被拒覆盖）。

### 决策 3：实时状态标签口径（写进交付说明的标注）
- **「使用中」**：指定日期当天存在**已通过**预约，且**当前时刻 ∈ [预约开始, 预约结束)**（开始含、结束不含）；
- **「当前空闲」**：其余情况；
- 列表接口的 `date` 参数**仅用于返回该日期的已通过预约占用时段列表**（`occupiedSlots`），不改变状态标签口径（状态标签永远按当天计算）；
- 详情接口 `GET /api/classroom/{id}?date=` 的 `date` 缺省为当天，返回该日期已通过预约的占用时段列表，供前端时段轴展示与冲突预校验。

### 冲突检测公式（硬约束，禁止简化）
同一教室、同一日期下，新预约与**已通过(1)** 预约时间段重叠即冲突，判定公式：

> **新开始 < 旧结束 AND 新结束 > 旧开始**

边界用例（测试 T83-T86 覆盖）：
- 首尾相接（新结束 = 旧开始）→ **不冲突**（如旧 08:00-10:00，新 10:00-12:00）
- 完全包含 → **冲突**
- 部分重叠 → **冲突**
- 无重叠 → **不冲突**

### 状态流转（硬约束）
- 提交成功 → 待审核(0)
- 审核：仅待审核(0) 可审核；通过 → 已通过(1)；驳回 → 已驳回(2) 且**必填审核备注**；记录 `auditorId` / `auditTime`
- 取消：仅本人可取消；待审核(0)/已通过(1) → 已取消(3)；**开始前 1 小时内禁止取消**（返回 400「预约开始前 1 小时内禁止取消…」）；已驳回(2)/已取消(3) 不可再取消
- 批量审核：仅待审核(0) 记录可参与（混合列表只更新待审核部分），返回**实际更新条数**

### 前后端双重校验（硬约束）
- 前端：预约弹窗在日期/时段变化时调用 `GET /api/reservation/conflict`（或比对详情接口返回的当日已通过时段）实时校验，冲突则**禁用提交按钮**并提示；前端校验仅作体验优化
- 后端：`POST /api/reservation` 提交时**再次执行冲突检测**，冲突即拒绝（消息含冲突时段，如「该时段与『08:00-10:00』的已通过预约冲突」）——绕过前端直接调用仍被拒绝（测试 T96 覆盖）

### 分页与筛选
- 分页参数 `page`（默认 1）、`size`（默认 10，MyBatis-Plus 分页插件上限 500）
- 教室列表（学生端）：`keyword`（名称/编号 LIKE）、`building`、`type`、`date`（可选）
- 我的预约：`status`（0/1/2/3，可选）
- 管理端全量：`status`、`startDate`、`endDate`（含边界）、`keyword`（用户账号/姓名、教室名称/楼栋/编号），按 `create_time DESC`

## 三、主要实现说明

### 后端（`code/reservation-server`，基于 R2 增量）
- 新增：
  - `controller/ReservationController.java`（6 个端点）、`controller/ClassroomStudentController.java`（2 个端点）
  - `service/ReservationService.java` + `impl/ReservationServiceImpl.java`（冲突检测/提交/我的/取消/管理查询/审核/批量审核）
  - `service/ClassroomService.java` 扩展 + `impl/ClassroomServiceImpl.java`（学生列表+详情）
  - `vo/`：`ClassroomVO`（含 `statusLabel`/`occupiedSlots`）、`OccupiedSlotVO`、`ConflictVO`、`ReservationVO`（含教室展示字段）、`ReservationManageVO`（继承 + 用户/审核人信息）
  - `dto/`：`ReservationDTO`、`AuditDTO`、`BatchAuditDTO`
  - `common/TimeUtil.java`（日期/时间解析，非法即 400）
- 修改：
  - `common/Constants.java`：教室类型、预约状态 0-3、`RESERVATION_CANCEL_HOURS=1`、状态标签文案、管理员审核路径常量
  - `common/AuthInterceptor.java`：`isAdminPath` 精确匹配（见决策 1）
  - `common/GlobalExceptionHandler.java`：增补 `MissingServletRequestParameterException` → 400「缺少必要参数：xxx」（测试 T88 覆盖）
- 沿用（禁止重写）：`Result`/`PageResult` 统一返回、全局异常、JWT 登录、MyBatis-Plus 自动填充与分页插件、用户/教室管理既有接口
- 密码始终 BCrypt 加密存储；任何列表/详情接口不返回 `password` 字段

### 前端（`code/reservation-web`）
- 新增页面：`views/student/StudentLayout.vue`（顶栏+导航）、`ClassroomList.vue`（搜索栏+卡片列表+分页+实时状态标签）、`ClassroomDetail.vue`（信息+08:00-22:00 横向时段轴+占用明细+预约弹窗）、`MyReservation.vue`（状态 tabs+表格+取消二次确认+空状态）、`views/admin/AuditManage.vue`（筛选+表格+单条审核二次确认+驳回快捷原因弹窗+复选框批量审核）
- 新增 `api/reservation.js`；扩展 `api/classroom.js`
- `router/index.js`：学生端 `StudentLayout`（`/student/home`=ClassroomList 替换原占位首页、`/student/classrooms/:id`、`/student/my-reservations`）；管理端新增 `/admin/audits`
- 删除被替代的 `views/student/StudentHome.vue`；`AdminHome.vue` 导航增「预约审核」
- 依赖严格沿用 package.json 锁定版本（Vue 3.4.38 / Element Plus 2.7.8 / Vue Router 4.4.3 / Pinia 2.1.7 / Vite 5.4.x），未升级大版本

## 四、测试与回归

- 测试脚本 `docs/test-script.ps1`：R2 脚本（T01-T71 = R1 21 + R2 50）原样保留，追加 R3 用例 **T72-T127 共 56 条**（教室浏览 11 / 管理端全量查询基准 4 / 冲突四边界+异常+权限 8 / 提交预约 8 / 审核 6 / 取消 6 / 批量审核 5 / 我的预约 3 / 权限全覆盖 5）
- **最终结果：125/125 通过（含 R1+R2 71 条回归零失败）**
- 可复跑：脚本自带清理段（`DELETE FROM reservation WHERE id > 13` 恢复 13 条四状态基线；删除 R2/R3 段创建的测试教室；清理带时间戳的临时注册用户），运行结束数据库恢复初始基线
- 已知环境适配：PS 5.1 下 `Invoke-WebRequest.Content` 按 ANSI 解码 UTF-8 中文响应导致 JSON 解析错乱，脚本内已改为 UTF-8 显式解码响应字节并以 UTF-8 BOM 保存；`ConvertFrom-Json` 对单对象标量 `.Count` 返回空，断言改用 `$null` 判定

## 五、数据库

- `database/init_db.sql`：从 R2 复制，自包含（五表结构 + 测试数据 12 教室 / 13 预约 / 6 收藏 / 管理员 admin/admin123、学生 zhangsan/123456 等），**未修改表结构**
- 运行期数据库：`reservation-mysql` 容器（localhost:3306，库名 reservation，utf8mb4，root/root），`--restart unless-stopped`；容器停止只需 `docker start reservation-mysql`，禁止重建或重新导数据
