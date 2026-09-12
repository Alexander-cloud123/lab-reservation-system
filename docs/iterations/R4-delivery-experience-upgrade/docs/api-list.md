# R4 轮次接口清单（新增 5 个：个人中心 3 + 收藏 2）

> 全部接口统一返回 `Result` 结构：`{ "code": 200, "message": "...", "data": ... }`；
> 业务失败 code=400 并携带友好 message；未登录访问受保护接口返回 HTTP 401（拦截器统一处理）。
> 权限说明：以下接口均位于 `/api/user`（非 manage 前缀）或 `/api/favorite`，只需登录即可访问（学生/管理员均可），未登录 401。

## 一、个人中心（学生端 /api/user/*）

### 1. GET /api/user/stats —— 个人预约数据统计

| 项 | 说明 |
| --- | --- |
| 权限 | 登录即可访问；未登录 401 |
| 返回 data | `UserStatsVO` |

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| totalReservations | Long | 累计预约次数（本人全部状态记录数） |
| monthReservations | Long | 本月预约数（预约日期属于当前自然月） |
| approvalRate | Double | 审核通过率 = 已通过/(已通过+已驳回)×100，一位小数；无审核记录为 0 |
| lastReservationTime | LocalDateTime | 最近一次预约时间（按创建时间最新一条），无记录为 null |

示例：
```json
{ "code": 200, "message": "操作成功", "data": { "totalReservations": 4, "monthReservations": 4, "approvalRate": 66.7, "lastReservationTime": "2026-09-11T10:43:21" } }
```

### 2. PUT /api/user/info —— 修改个人信息

| 项 | 说明 |
| --- | --- |
| 权限 | 登录即可访问；未登录 401 |
| 请求体 | `UserInfoDTO`（仅白名单字段，禁止修改密码字段） |

| 字段 | 必填 | 校验 |
| --- | --- | --- |
| name | 是 | 非空、≤20 字符 |
| studentNo | 否 | ≤20 字符（选填） |
| phone | 否 | 填写则必须为 11 位手机号 |
| email | 否 | ≤50 字符（选填） |

返回：`{ "code": 200, "message": "个人信息修改成功", "data": null }`
失败示例：`{ "code": 400, "message": "手机号格式不正确", "data": null }`
安全：不返回/不接受密码字段；未传字段不更新（MyBatis-Plus NOT_NULL 策略）。

### 3. PUT /api/user/password —— 修改密码

| 项 | 说明 |
| --- | --- |
| 权限 | 登录即可访问；未登录 401 |
| 请求体 | `PasswordDTO` |

| 字段 | 必填 | 校验 |
| --- | --- | --- |
| oldPassword | 是 | 与库中 BCrypt 密文比对，不匹配返回「原密码错误」 |
| newPassword | 是 | 长度 ≥ 6 位 |
| confirmPassword | 是 | 须与 newPassword 一致 |

返回：`{ "code": 200, "message": "密码修改成功，请重新登录", "data": null }`（新密码 BCrypt 加密存储，数据库无明文）

## 二、收藏全链路（/api/favorite/*）

### 4. POST /api/favorite/{classroomId} —— 收藏 / 取消收藏（toggle）

| 项 | 说明 |
| --- | --- |
| 权限 | 登录即可访问；未登录 401 |
| 语义 | 已收藏 → 取消收藏；未收藏 → 新增收藏（先校验上限 10 间） |
| 返回 data | Boolean：true=当前已收藏（本次新增），false=当前未收藏（本次取消） |

返回示例（新增）：`{ "code": 200, "message": "收藏成功", "data": true }`
返回示例（取消）：`{ "code": 200, "message": "已取消收藏", "data": false }`
失败示例：
- `{ "code": 400, "message": "教室不存在", "data": null }`
- `{ "code": 400, "message": "收藏数量已达上限（10 间），请先取消部分收藏", "data": null }`

### 5. GET /api/favorite/list —— 我的收藏列表

| 项 | 说明 |
| --- | --- |
| 权限 | 登录即可访问；未登录 401 |
| 返回 data | `List<FavoriteVO>`，按收藏时间倒序 |

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | Long | 收藏记录 ID |
| classroomId | Long | 教室 ID |
| name / building / roomNo | String | 教室名称 / 楼栋 / 编号 |
| type | Integer | 1-普通教室 2-实验室 3-机房 |
| capacity | Integer | 容纳人数 |
| createTime | LocalDateTime | 收藏时间 |

## 三、权限矩阵（R4 新接口）

| 接口 | 学生 | 管理员 | 未登录 |
| --- | --- | --- | --- |
| GET /api/user/stats | 200 | 200 | 401 |
| PUT /api/user/info | 200 | 200 | 401 |
| PUT /api/user/password | 200 | 200 | 401 |
| POST /api/favorite/{classroomId} | 200 | 200 | 401 |
| GET /api/favorite/list | 200 | 200 | 401 |

> 管理员前缀 `/api/user/manage`、`/api/classroom/manage`、`/api/reservation/manage` 等角色校验规则沿用 R2/R3 未改动；
> 学生 Token 访问管理前缀返回 403 的既有用例（T121-T124）回归通过。
