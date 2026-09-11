# R3 轮次接口清单（新增 9 个，含分页与筛选参数）

- 统一返回结构：`Result<T>` = `{ "code": 200, "message": "操作成功", "data": ... }`；业务错误 `code=400/401/403`，HTTP 状态恒为 200（登录/权限类 401/403 使用 HTTP 状态码）
- 鉴权：请求头 `Authorization: Bearer <token>`；登录接口 `POST /api/user/login`
- 分页返回：`PageResult` = `{ "total": N, "records": [...] }`，分页参数 `page`（默认 1）、`size`（默认 10，上限 500）
- 权限：学生 = 已登录学生 Token；管理员 = 已登录管理员 Token（拦截器角色校验）

---

## 一、教室浏览（学生端，学生/管理员登录均可访问）

### 1. GET /api/classroom/list —— 学生端教室分页列表
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page | long | 否 | 页码，默认 1 |
| size | long | 否 | 每页条数，默认 10 |
| keyword | String | 否 | 关键词，名称/编号模糊匹配 |
| building | String | 否 | 楼栋精确匹配 |
| type | Integer | 否 | 教室类型（1 普通 / 2 实验室 / 3 机房） |
| date | String | 否 | yyyy-MM-dd，返回该日已通过预约占用时段列表（不改变状态标签口径） |

返回 `data.records[]`（`ClassroomVO`）字段：`id, name, building, roomNo, type, capacity, equipment, description, status, statusLabel(当前空闲/使用中), occupiedSlots[{startTime, endTime}]`
- 仅返回 `status=1`（可用）教室，按 `create_time DESC`
- 状态标签口径：当天存在已通过预约且当前时刻 ∈ [开始, 结束) → 「使用中」，否则「当前空闲」

### 2. GET /api/classroom/{id} —— 教室详情 + 指定日期占用
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id（路径） | Long | 是 | 教室 ID |
| date | String | 否 | yyyy-MM-dd，缺省当天；返回该日期已通过预约占用时段列表 |

返回 `data`（`ClassroomVO`）：教室全部字段 + `statusLabel` + `occupiedSlots[{startTime, endTime}]`
- 教室不存在返回 400「教室不存在」；date 非法返回 400

---

## 二、预约提交 + 冲突检测（学生登录）

### 3. GET /api/reservation/conflict —— 冲突检测
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| classroomId | Long | 是 | 教室 ID |
| date | String | 是 | yyyy-MM-dd |
| startTime | String | 是 | HH:mm，开始时间 |
| endTime | String | 是 | HH:mm，结束时间（须晚于开始） |

返回 `data`（`ConflictVO`）：`{ "conflict": true/false }`
- 判定公式：新开始 < 旧结束 AND 新结束 > 旧开始，仅与**已通过(1)** 预约比较
- 边界：首尾相接（新结束=旧开始）不冲突；完全包含/部分重叠冲突；无重叠不冲突
- 缺 date 参数返回 400「缺少必要参数：date」；开始≥结束返回 400；教室不存在返回 400

### 4. POST /api/reservation —— 提交预约
请求体 `ReservationDTO`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| classroomId | Long | 是 | 教室 ID |
| reserveDate | String | 是 | yyyy-MM-dd |
| startTime | String | 是 | HH:mm |
| endTime | String | 是 | HH:mm，须晚于开始 |
| purpose | String | 是 | 预约用途 |

返回 `data`：新预约 ID（Long）；成功状态为**待审核(0)**，消息「预约提交成功，待管理员审核」
- 后端二次冲突检测兜底：与已通过预约重叠即拒绝 400（如「该时段与『08:00-10:00』的已通过预约冲突」），绕过前端直接调用仍被拒绝
- 教室不存在 / 已停用（「该教室已停用，无法预约」）/ 必填缺失 / 开始≥结束均返回 400

### 5. GET /api/reservation/mine —— 我的预约列表
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page | long | 否 | 默认 1 |
| size | long | 否 | 默认 10 |
| status | Integer | 否 | 0 待审核 / 1 已通过 / 2 已驳回 / 3 已取消 |

返回 `data.records[]`（`ReservationVO`）：`id, classroomId, classroomName, building, roomNo, reserveDate, startTime, endTime, purpose, status, auditRemark, auditTime, createTime`（含教室展示字段）
- 非法 status 返回 400「状态参数不合法（0-待审核，1-已通过，2-已驳回，3-已取消）」

### 6. PUT /api/reservation/{id}/cancel —— 取消预约
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id（路径） | Long | 是 | 预约 ID |

返回：成功消息「取消成功」（data=null）
- 仅本人可取消；待审核(0)/已通过(1) → 已取消(3)
- 开始前 1 小时内禁止取消 → 400「预约开始前 1 小时内禁止取消，如需调整请联系管理员」
- 非本人 → 400「只能取消自己的预约」；已驳回/已取消 → 400「当前状态不可取消」；不存在 → 400「预约不存在」

---

## 三、审核 + 批量审核（管理员专属，学生 Token 访问返回 403）

### 7. GET /api/reservation/manage —— 管理员全量查询
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| page | long | 否 | 默认 1 |
| size | long | 否 | 默认 10 |
| status | Integer | 否 | 状态筛选 |
| startDate | String | 否 | yyyy-MM-dd，起始日期（含） |
| endDate | String | 否 | yyyy-MM-dd，结束日期（含） |
| keyword | String | 否 | 关键词：用户账号/姓名、教室名称/楼栋/编号 |

返回 `data.records[]`（`ReservationManageVO`，继承 ReservationVO 追加）：`userName, userNo, studentNo, auditorId, auditorName, auditTime`；按 `create_time DESC`

### 8. PUT /api/reservation/{id}/audit —— 单条审核
| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id（路径） | Long | 是 | 预约 ID |

请求体 `AuditDTO`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| status | Integer | 是 | 1 通过 / 2 驳回 |
| auditRemark | String | 驳回时必填 | 审核备注（驳回必填） |

返回：成功消息「审核成功」（data=null）
- 仅**待审核(0)** 可审核；通过 → 已通过(1)，驳回 → 已驳回(2)；记录 `auditorId` / `auditTime`
- 驳回缺备注 → 400「驳回必须填写审核备注」；非待审核 → 400「仅待审核状态的预约可审核」；status 非法 → 400；不存在 → 400「预约不存在」

### 9. POST /api/reservation/batch-audit —— 批量审核
请求体 `BatchAuditDTO`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| ids | List\<Long\> | 是 | 预约 ID 列表 |
| status | Integer | 是 | 1 批量通过 / 2 批量驳回 |
| auditRemark | String | 批量驳回时必填 | 审核备注（驳回必填） |

返回 `data`：**实际更新条数**（Integer）——仅待审核(0) 记录参与；混合列表只更新待审核部分
- ids 为空 → 400「预约 ID 列表不能为空」；批量驳回缺备注 → 400「驳回必须填写审核备注」

---

## 四、权限矩阵（R3 新接口）

| 接口 | 未登录 | 学生 Token | 管理员 Token |
|---|---|---|---|
| GET /api/classroom/list、/{id} | 401 | 200 | 200 |
| GET /api/reservation/conflict、POST /api/reservation、GET /api/reservation/mine、PUT /api/reservation/{id}/cancel | 401 | 200 | 200 |
| GET /api/reservation/manage、PUT /api/reservation/{id}/audit、POST /api/reservation/batch-audit | 401 | **403** | 200 |

> 与管理员教室接口 `/api/classroom/manage`（仅管理员）区分：学生端教室接口路径为 `/api/classroom/list` 与 `/api/classroom/{id}`，拦截器仅对 manage 前缀校验角色。
