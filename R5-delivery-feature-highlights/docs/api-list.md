# R5 轮次接口清单（新增 4 个只读接口：日历区间 1 + 数据看板统计 3）

> 全部接口统一返回 `Result` 结构：`{ "code": 200, "message": "...", "data": ... }`；
> 业务失败 code=400 并携带友好 message；未登录访问受保护接口返回 HTTP 401（拦截器统一处理）。
> 本轮新增接口均为**只读**（查询/统计），不引入任何写接口。

## 一、日历区间查询（GET /api/reservation/calendar）

| 项 | 说明 |
| --- | --- |
| 权限 | **登录即可访问**（学生/管理员均可）；未登录 401 |
| 用途 | 日历总览页数据源（FullCalendar 月/周视图按区间渲染） |

### 请求参数（Query）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| startDate | 是 | 开始日期，格式 `yyyy-MM-dd` |
| endDate | 是 | 结束日期，格式 `yyyy-MM-dd`，不得早于 startDate |
| classroomId | 否 | 按教室筛选（不传 = 全部教室） |

校验规则：startDate/endDate 必填（缺失返回 400「缺少必要参数：startDate/endDate」）；
开始日期晚于结束日期返回 400「开始日期不能晚于结束日期」；
区间跨度超过 366 天返回 400「日期区间跨度不能超过 366 天」；
非法日期格式返回 400「日期格式不正确，应为 yyyy-MM-dd」。

### 返回 data：`List<CalendarVO>`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 预约 ID |
| classroomId | Long | 教室 ID |
| classroomName | String | 教室名称 |
| building | String | 楼栋 |
| roomNo | String | 教室编号 |
| reserveDate | String | 预约日期（yyyy-MM-dd） |
| startTime | String | 开始时间（HH:mm） |
| endTime | String | 结束时间（HH:mm） |
| purpose | String | 预约用途 |
| status | Integer | 0-待审核 1-已通过 2-已驳回 3-已取消 |
| auditRemark | String | 审核备注（驳回时） |

示例：
```json
{ "code": 200, "message": "操作成功", "data": [ { "id": 1, "classroomId": 1, "classroomName": "A101多媒体教室", "building": "信息楼", "roomNo": "A101", "reserveDate": "2026-09-11", "startTime": "14:00", "endTime": "16:00", "purpose": "课程设计小组讨论", "status": 0, "auditRemark": null } ] }
```

## 二、数据看板统计（/api/stats/*，管理员专用）

> 三个接口均位于拦截器管理员前缀（`Constants.ADMIN_API_PREFIXES` 含 `/api/stats`）：
> 管理员 200、学生 403、未登录 401。

### 1. GET /api/stats/usage-rate —— 教室使用率排行（柱状图数据源）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| startDate / endDate | 否 | 自定义区间；缺省近 30 天（[今天-29, 今天]）；跨度上限 366 天；非法格式 400 |

口径：使用率 = 区间内已通过预约占用小时 ÷（区间天数 × 14h）× 100，保留 1 位小数；
全部教室参与排行，按使用率倒序；无预约教室 usageRate=0.0。

返回 data：`List<UsageRateVO>`（classroomId/name/building/roomNo/type/capacity/approvedHours/usageRate）
```json
{ "code": 200, "message": "操作成功", "data": [ { "classroomId": 3, "name": "A201物理实验室", "building": "信息楼", "roomNo": "A201", "type": 2, "capacity": 60, "approvedHours": 2.0, "usageRate": 0.5 } ] }
```

### 2. GET /api/stats/trend —— 月度预约趋势（折线图数据源）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| startDate / endDate | 否 | 自定义区间；缺省近 30 天；跨度上限 366 天 |

口径：按 `reserve_date` 归属自然月统计区间内**全部状态**预约条数，月份升序。
返回 data：`List<TrendVO>`（month=yyyy-MM / count）
```json
{ "code": 200, "message": "操作成功", "data": [ { "month": "2026-09", "count": 11 } ] }
```

### 3. GET /api/stats/time-distribution —— 热门时段分布（环形图数据源）

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| startDate / endDate | 否 | 自定义区间；缺省近 30 天；跨度上限 366 天 |

口径：按已通过预约开始时间分六桶，统计各桶条数与占比 %（保留 1 位小数）。
返回 data：`List<TimeDistributionVO>`（slot/count/percentage）
```json
{ "code": 200, "message": "操作成功", "data": [ { "slot": "08:00-10:00", "count": 3, "percentage": 50.0 }, { "slot": "10:00-12:00", "count": 1, "percentage": 16.7 }, { "slot": "14:00-16:00", "count": 2, "percentage": 33.3 }, { "slot": "16:00-18:00", "count": 0, "percentage": 0.0 }, { "slot": "19:00-21:00", "count": 0, "percentage": 0.0 }, { "slot": "其他", "count": 0, "percentage": 0.0 } ] }
```

## 三、权限矩阵（R5 新增接口）

| 接口 | 学生 | 管理员 | 未登录 |
| --- | --- | --- | --- |
| GET /api/reservation/calendar | 200 | 200 | 401 |
| GET /api/stats/usage-rate | 403 | 200 | 401 |
| GET /api/stats/trend | 403 | 200 | 401 |
| GET /api/stats/time-distribution | 403 | 200 | 401 |

> 统计接口放行规则由拦截器管理员前缀统一处理（`/api/stats` 前缀），未新增任何权限代码；
> 日历接口按普通登录接口处理（非管理员前缀），未登录一律 401。
> R1-R4 全部既有接口权限规则未改动，153 条基线权限用例回归通过（T17-T21/T37-T40/T65-T71/T82/T90/T102/T122-T127/T131-T132/T137/T143/T151-T153）。

## 四、安全与数据一致性

- 所有新增接口返回 VO 为白名单字段，**不包含 password**（R5 用例 T156/T182 显式断言）；
- 统计口径与库中数据一致性：R5 用例 T168/T171/T173 通过 SQL 对照断言（B101 使用率 1.8 / 月度趋势 13 / 时段桶 3 条 50% 均与库一致）；
- 接口均为只读，不修改任何业务数据；数据库基线（预约 13 条四状态/收藏 6 条/用户 5 人/教室 12 间）保持不动。