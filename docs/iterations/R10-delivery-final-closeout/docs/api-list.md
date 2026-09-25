# R10 最终版接口清单（源码实地枚举，共 38 个端点）

> 适用工程：`archive/R10-delivery-final-closeout/code/reservation-server`（Spring Boot 3.2.10；Knife4j 在线文档：`http://localhost:8080/doc.html`）
> 枚举口径：逐一读取 `server/src/main/java/com/example/reservation/controller/` 下 7 个控制器 + `ai/controller/AiController.java` 的映射注解与入参类型，**以源码为唯一事实来源**；随后与 `docs/iterations/R7-delivery-ai-integration/docs/api-list.md`、`docs/iterations/R8-delivery-defense-prep/接口清单.md` 对照，补齐 R8/R9/阶段3 的新增与调整（见第十节）。
> 权限口径以 `common/Constants.java` 的 `ADMIN_API_PREFIXES`（`/api/user/manage`、`/api/classroom/manage`、`/api/reservation/manage`、`/api/reservation/export`、`/api/stats`）+ `AuthInterceptor` 精确路径校验（单条审核、批量审核）为准。

## 一、通用约定

| 项 | 约定 |
| --- | --- |
| 基路径 | `http://localhost:8080/api` |
| 请求/响应 | JSON（`Content-Type: application/json; charset=utf-8`；导出接口返回文件流） |
| 鉴权 | 除「登录 / 注册」外，均需请求头 `Authorization: Bearer <token>` |
| 统一返回 | `Result`：`{ "code": 200, "message": "操作成功", "data": ... }`；`code != 200` 时前端统一提示 |
| 错误码 | 401 未登录/Token 失效、403 无权限或账号禁用、400 业务校验失败、500 系统异常 |
| 分页 | 请求 `page`（默认 1）、`size`（默认 10，上限 500）；返回 `PageResult`：`{ records, total, page, size }` |
| 时间格式 | 日期 `yyyy-MM-dd`；时间 `HH:mm`；日期时间 `yyyy-MM-dd HH:mm:ss`（Jackson 已关闭时间戳数组输出） |
| 字典值 | 角色 0-学生 1-管理员；用户状态 0-禁用 1-正常；教室类型 **1-普通教室 2-实验室 3-机房**；教室状态 0-停用 1-可用；预约状态 0-待审核 1-已通过 2-已驳回 3-已取消 |
| 权限标注 | 「公开」= 无需 Token；「登录」= 任意角色登录即可；「管理员」= 需管理员角色（学生 Token → 403，未登录 → 401） |

## 二、接口总览

| 模块 | 控制器 | 端点数 | 权限构成 |
| --- | --- | --- | --- |
| 用户模块 | `UserController` | 10 | 公开 2 / 登录 5 / 管理员 3 |
| 教室模块（学生端） | `ClassroomStudentController` | 2 | 登录 2 |
| 教室模块（管理端） | `ClassroomController` | 6 | 管理员 6 |
| 预约模块 | `ReservationController` | 9 | 登录 5 / 管理员 4 |
| 收藏模块 | `FavoriteController` | 2 | 登录 2 |
| 统计模块 | `StatsController` | 4 | 管理员 4 |
| 系统配置模块 | `ConfigController` | 1 | 登录 1 |
| AI 能力模块 | `AiController` | 4 | 登录 4（只读） |
| **合计** | 8 个控制器 | **38** | — |

## 三、用户模块 `/api/user`

| # | 方法 | 路径 | 说明 | 权限 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | POST | `/api/user/login` | 双角色登录（成功签发 Token） | 公开 | body：`username` / `password` / `role` | `LoginVO`（Token + 用户信息） |
| 2 | POST | `/api/user/logout` | 退出登录（删除服务端 Redis 会话，当前 Token 立即失效） | 登录 | - | `null` |
| 3 | POST | `/api/user/register` | 学生自主注册（账号唯一、两次密码一致、BCrypt 存储） | 公开 | body：`username` / `password` / `confirmPassword` / `name` / `studentNo` / `phone`(选填) / `email`(选填) | `null` |
| 4 | GET | `/api/user/info` | 当前登录用户信息 | 登录 | - | `UserVO`（不含 password） |
| 5 | GET | `/api/user/stats` | 个人预约数据概览 | 登录 | - | `UserStatsVO` |
| 6 | PUT | `/api/user/info` | 修改个人信息（姓名/学号/邮箱/手机号，不含密码字段） | 登录 | body：`name` / `studentNo` / `phone` / `email` | `null` |
| 7 | PUT | `/api/user/password` | 修改密码（验证原密码，新密码 ≥6 位且两次一致） | 登录 | body：`oldPassword` / `newPassword` / `confirmPassword` | `null` |
| 8 | GET | `/api/user/manage` | 用户分页列表（关键词：账号/姓名/学号；角色/状态筛选） | 管理员 | query：`page` / `size` / `keyword?` / `role?` / `status?` | `PageResult<UserVO>`（不含 password） |
| 9 | PUT | `/api/user/manage/{id}/status` | 启用/禁用用户（禁止操作当前登录管理员自己） | 管理员 | path：`id`；body：`status` | `null` |
| 10 | PUT | `/api/user/manage/{id}/password` | 重置密码为默认 `123456`（BCrypt 存储） | 管理员 | path：`id` | `null` |

## 四、教室模块

### 4.1 学生端 `/api/classroom`（`ClassroomStudentController`）

| # | 方法 | 路径 | 说明 | 权限 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- | --- |
| 11 | GET | `/api/classroom/list` | 教室分页列表（仅可用教室；含实时状态标签与指定日期占用） | 登录 | query：`page` / `size` / `keyword?` / `building?` / `type?` / `date?` | `PageResult<ClassroomVO>` |
| 12 | GET | `/api/classroom/{id}` | 教室详情 + 指定日期（默认当天）已通过预约占用时段 | 登录 | path：`id`；query：`date?` | `ClassroomVO` |

### 4.2 管理端 `/api/classroom/manage`（`ClassroomController`）

| # | 方法 | 路径 | 说明 | 权限 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- | --- |
| 13 | GET | `/api/classroom/manage` | 教室管理分页（关键词：名称/编号 + 楼栋/类型/状态） | 管理员 | query：`page` / `size` / `keyword?` / `building?` / `type?` / `status?` | `PageResult<Classroom>` |
| 14 | POST | `/api/classroom/manage` | 新增教室（名称/楼栋/编号/类型/容量必填，容量 > 0） | 管理员 | body：`name` / `building` / `roomNo` / `type` / `capacity` / `equipment` / `description` | `Long`（新教室 id） |
| 15 | PUT | `/api/classroom/manage` | 编辑教室（校验规则同新增） | 管理员 | body：`id` + 上述字段 | `null` |
| 16 | DELETE | `/api/classroom/manage/{id}` | 删除教室（存在任何预约记录时拒绝，返回 400） | 管理员 | path：`id` | `null` |
| 17 | PUT | `/api/classroom/manage/{id}/status` | 启用/停用教室 | 管理员 | path：`id`；body：`status` | `null` |
| 18 | POST | `/api/classroom/manage/batch-status` | 批量启用/停用教室 | 管理员 | body：`ids` / `status` | `Integer`（实际更新条数） |

## 五、预约模块 `/api/reservation`（`ReservationController`）

| # | 方法 | 路径 | 说明 | 权限 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- | --- |
| 19 | GET | `/api/reservation/conflict` | 冲突检测（仅与「已通过」预约按重叠公式判定） | 登录 | query：`classroomId` / `date` / `startTime` / `endTime` | `ConflictVO` |
| 20 | POST | `/api/reservation` | 提交预约（后端二次冲突检测兜底；初始状态待审核 0） | 登录 | body：`classroomId` / `reserveDate` / `startTime` / `endTime` / `purpose` | `Long`（新预约 id） |
| 21 | GET | `/api/reservation/mine` | 我的预约分页（支持状态筛选） | 登录 | query：`page` / `size` / `status?` | `PageResult<ReservationVO>` |
| 22 | PUT | `/api/reservation/{id}/cancel` | 取消预约（仅本人；待审核/已通过 → 已取消；开始前 1 小时内禁止） | 登录 | path：`id` | `null` |
| 23 | GET | `/api/reservation/manage` | 全量预约查询（状态/日期范围/教室/关键词） | 管理员 | query：`page` / `size` / `status?` / `startDate?` / `endDate?` / `keyword?` / `classroomId?` | `PageResult<ReservationManageVO>` |
| 24 | GET | `/api/reservation/export` | 预约记录导出 Excel | 管理员 | query：`status?` / `startDate?` / `endDate?` / `keyword?` / `classroomId?` | **xlsx 文件流（非 Result 结构）** |
| 25 | PUT | `/api/reservation/{id}/audit` | 审核（仅待审核可审；通过前复查冲突；驳回必填备注） | 管理员 | path：`id`；body：`status`(1 通过 / 2 驳回) / `auditRemark` | `null` |
| 26 | POST | `/api/reservation/batch-audit` | 批量审核（仅待审核参与；批量驳回必填备注） | 管理员 | body：`ids` / `status` / `auditRemark` | `Integer`（实际更新条数） |
| 27 | GET | `/api/reservation/calendar` | 日历总览区间查询（区间必填，跨度 ≤366 天） | 登录 | query：`classroomId?` / `startDate` / `endDate` | `List<CalendarVO>` |

## 六、收藏模块 `/api/favorite`（`FavoriteController`）

| # | 方法 | 路径 | 说明 | 权限 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- | --- |
| 28 | POST | `/api/favorite/{classroomId}` | 收藏/取消收藏（toggle；上限 10 间，超出拒绝） | 登录 | path：`classroomId` | `Boolean`（true=已收藏） |
| 29 | GET | `/api/favorite/list` | 我的收藏列表（含教室展示字段，按收藏时间倒序） | 登录 | - | `List<FavoriteVO>` |

## 七、统计模块 `/api/stats`（`StatsController`，全部管理员）

| # | 方法 | 路径 | 说明 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- |
| 30 | GET | `/api/stats/overview` | 管理端首页概览（今日预约 / 待审核 / 教室总数 / 用户总数） | - | `OverviewVO` |
| 31 | GET | `/api/stats/usage-rate` | 教室使用率排行（占用小时 /（天数×14h）） | query：`startDate?` / `endDate?` | `List<UsageRateVO>` |
| 32 | GET | `/api/stats/trend` | 月度预约趋势（按 reserve_date 归属自然月） | query：`startDate?` / `endDate?` | `List<TrendVO>` |
| 33 | GET | `/api/stats/time-distribution` | 热门时段分布（按开始时间分桶统计已通过预约） | query：`startDate?` / `endDate?` | `List<TimeDistributionVO>` |

## 八、系统配置模块 `/api/config`（`ConfigController`）

| # | 方法 | 路径 | 说明 | 权限 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- | --- |
| 34 | GET | `/api/config/booking-rules` | 下发预约规则（可预约时段窗口 `HH:mm` + 单次预约最长时长小时数），供前端实时校验与提示文案与后端保持同一口径 | 登录 | - | `BookingRulesVO` |

## 九、AI 能力模块 `/api/ai`（`AiController`，全部登录即可、全部只读）

| # | 方法 | 路径 | 说明 | 入参 | 返回 data |
| --- | --- | --- | --- | --- | --- |
| 35 | POST | `/api/ai/recommend` | 智能教室推荐 Top3（基于当前登录用户历史习惯 + 实时空闲；附推荐理由） | body 可选：`userId`（**服务端忽略**，以登录上下文用户为准，防越权读取他人偏好） | `AiRecommendVO`：`enabled` / `message` / `date` / `recommendations[]`（`classroomId` / `name` / `building` / `roomNo` / `type` / `capacity` / `reason`） |
| 36 | POST | `/api/ai/parse-reservation` | 自然语言预约解析 → 结构化参数 | body：`text` | `AiParseVO`：`enabled` / `message` / `date` / `startTime` / `endTime` / `capacity` / `roomType` / `purpose` / `error` |
| 37 | POST | `/api/ai/chat` | 场景限定智能问答（无关问题返回预设话术） | body：`question` | `AiChatVO`：`enabled` / `message` / `answer` |
| 38 | POST | `/api/ai/compliance-check` | 预约用途合规校验（只提示不改状态） | body：`purpose` | `AiComplianceVO`：`enabled` / `message` / `compliant` / `reason` |

AI 模块统一口径：

- **权限**：登录即可（`/api/ai` 不在 `ADMIN_API_PREFIXES` 内，仅校验 Token；未登录 401）。
- **双开关**：`ai.enable`（application.yml，交付默认 `false`）AND `ai_config.ai_enable`（数据库）同时为 true 才可用；任一为 false 时仍返回 HTTP 200 + `code=200`，但 `data.enabled=false` 且 `data.message` 为友好提示。
- **降级**：无密钥 / 限流（RPM 20，全站聚合 100）/ 超时（60s）/ 连接失败 / 服务异常时自动切换本地规则模拟，不返回 500、不阻断业务，降级原因透出到 `data.message`。
- **只读**：4 个接口均不写任何业务数据；所有业务操作由用户手动执行并走既有双重冲突校验。

## 十、与 R7 / R8 清单的差异对照（补齐与勘误）

| 项 | R7 / R8 清单口径 | 最终源码口径 | 处理 |
| --- | --- | --- | --- |
| 统计模块命名 | R7：`/api/statistics/overview`、`/classroom-rank`、`/monthly`（3 个） | `/api/stats/overview`、`/usage-rate`、`/trend`、`/time-distribution`（4 个） | 以源码为准；概览端点并入 `/api/stats` |
| 教室学生端 | R7：`/api/classroom/list-all`、`/api/classroom/occupancy?date=` | `/api/classroom/list`（含 `date` 筛选）、`/api/classroom/{id}`（含 `date` 时段占用） | 以源码为准；R7 两个路径在最终源码中无映射 |
| 冲突检测 | R7：`POST /api/reservation/check-conflict` | `GET /api/reservation/conflict` | 以源码为准（方法由 POST 改为 GET，参数走 query） |
| 收藏 | R7：`POST /api/favorite`、`DELETE /api/favorite/{classroomId}` | `POST /api/favorite/{classroomId}`（toggle）、`GET /api/favorite/list` | 以源码为准；改为单一 toggle 端点 |
| 用户管理写操作 | R7：`POST /api/user/manage`、`PUT /api/user/manage/{id}`、`DELETE /api/user/manage/{id}` | 最终源码无这 3 个映射，管理操作为分页查询 + 启停 + 重置密码 | 以源码为准 |
| 个人资料类接口 | R7 未收录 | `GET /api/user/stats`、`PUT /api/user/info`、`PUT /api/user/password` | **补齐**（R2/R4 已落地，R7 清单未列） |
| 退出登录 | R7 未收录；R8 引入 | `POST /api/user/logout`（Redis 会话登出） | **补齐**（R8 引入；R8 接口清单漏记此端点） |
| 首页概览 | R7 / R8 清单均未收录 | `GET /api/stats/overview` | **补齐**（管理端首页数据卡实际依赖） |
| 系统配置 | R7 / R8 清单均未收录 | `GET /api/config/booking-rules` | **补齐**（为杜绝前端手工镜像后端规则造成的口径漂移而新增） |
| AI 推荐入参 | R7：`userId`；R8：`userId 忽略，以登录用户为准` | `@RequestBody(required=false) AiRecommendRequest{userId}`，服务端取 `UserContext.getUserId()` | 与 R8 一致；**入参 userId 不再被信任**（越权加固） |
| AI 解析入参名 | R8 清单写作 `input` | 实际字段名为 `text` | **勘误**（以源码为准） |
| 教室类型字典 | R8 清单写作「0-普通 1-多媒体 2-机房」 | 1-普通教室 / 2-实验室 / 3-机房（`Constants` 与 `init_db.sql` 一致） | **勘误**（以源码与库为准） |
| 端点总数 | R7：35（31 + 4 AI）；R8：35（其清单口径） | **38** | 最终以本清单为准 |

> 说明：本节差异为「实地枚举 vs 历史清单」的对照结果，仅作契约勘误与补齐记录，未据此改动任何后端代码（本轮为收尾打包轮，后端处于 `v1.0-backend-freeze` 冻结状态）。