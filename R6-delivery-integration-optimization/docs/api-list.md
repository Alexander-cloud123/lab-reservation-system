# R6 轮次接口清单（新增导出 1 个只读接口 + manage 增加可选教室筛选参数）

> 全部接口统一返回 `Result` 结构：`{ "code": 200, "message": "...", "data": ... }`；
> 业务失败 code=400 并携带友好 message；未登录访问受保护接口返回 HTTP 401（拦截器统一处理）。
> 本轮新增接口为**只读**（导出查询），不引入任何写接口。

## 一、本轮变更总览

| 接口 | 变更 | 说明 |
| --- | --- | --- |
| GET /api/reservation/manage | **参数变更（向后兼容）** | 新增可选参数 `classroomId`（按教室筛选，缺省 = 全部，行为与 R5 完全一致） |
| GET /api/reservation/export | **新增（管理员专属）** | 导出预约记录 xlsx 文件流（非 Result JSON） |
| 其余 30 个接口 | 无变更 | 契约与 R1-R5 完全一致（复用清单见第三节） |

## 二、R6 接口详情

### 1. 预约记录分页查询（GET /api/reservation/manage，管理员）

| 项 | 说明 |
| --- | --- |
| 权限 | 管理员（拦截器 `/api/reservation/manage` 前缀角色校验）；学生 403 / 未登录 401 |
| 用途 | 管理端预约记录页 / 预约审核页数据源（R3 既有接口，R6 增加教室筛选） |

请求参数（Query）：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| page | 否 | 页码，默认 1，必须 ≥ 1（否则 400「页码必须大于等于 1」） |
| size | 否 | 每页条数，默认 10，1-500（否则 400「每页条数必须在 1-500 之间」） |
| keyword | 否 | 关键词（账号 / 姓名 / 教室名称），命中为空时按恒假条件返回空页 |
| status | 否 | 状态（0-待审核 1-已通过 2-已驳回 3-已取消），非法状态 400 |
| startDate | 否 | 开始日期 yyyy-MM-dd，晚于 endDate 返回 400「开始日期不能晚于结束日期」 |
| endDate | 否 | 结束日期 yyyy-MM-dd |
| **classroomId** | **否（R6 新增）** | 按教室筛选；教室不存在返回 400「教室不存在」；非数字返回 400「参数类型不正确：classroomId」 |

返回 data：`PageResult<ReservationVO>`（含预约人账号/姓名、教室名称/楼栋/编号、日期、起止时间、用途、状态、审核备注、创建时间；不含 password）。

### 2. 预约记录导出（GET /api/reservation/export，管理员）—— R6 新增

| 项 | 说明 |
| --- | --- |
| 权限 | 管理员（拦截器 `/api/reservation/export` 前缀角色校验）；学生 403 / 未登录 401 |
| 返回 | xlsx 二进制文件流（application/vnd.openxmlformats-officedocument.spreadsheetml.sheet），非 Result 结构 |
| 用途 | 预约记录页「导出 Excel」按钮数据源（spec.md 5.2 蓝图既定接口，EasyExcel 3.3.4） |

请求参数（Query，与 manage 完全一致的筛选条件）：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| keyword | 否 | 关键词（账号 / 姓名 / 教室名称） |
| status | 否 | 状态（0/1/2/3），非法状态 400 |
| startDate | 否 | 开始日期 yyyy-MM-dd，晚于 endDate 返回 400 |
| endDate | 否 | 结束日期 yyyy-MM-dd |
| classroomId | 否 | 教室 ID；教室不存在 400「教室不存在」；非数字 400「参数类型不正确：classroomId」 |

返回：xlsx 文件流，`Content-Disposition: attachment; filename*=UTF-8''预约记录_yyyyMMddHHmmss.xlsx`（RFC 5987 中文文件名 + 时间戳）。

Excel 列（13 列白名单，无 password）：预约 ID / 教室名称 / 楼栋 / 教室编号 / 账号 / 姓名 / 预约日期 / 开始时间 / 结束时间 / 预约用途 / 状态文案 / 审核备注 / 审核时间 / 创建时间（其中预约 ID、教室名称、楼栋、编号、账号、姓名、日期、起止时间、用途、状态文案、审核备注、审核时间、创建时间按展示字段白名单输出）。

空结果仅输出表头（1 行）。

## 三、既有接口复用清单（30 个，契约未变）

### 1. 认证与用户（/api/user，9 个）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | /api/user/login | 公开 | 登录签发 JWT |
| POST | /api/user/register | 公开 | 学生注册 |
| GET | /api/user/info | 登录即可 | 当前用户信息（不含 password） |
| GET | /api/user/stats | 登录即可 | 个人预约统计（累计/本月/通过率） |
| PUT | /api/user/info | 登录即可 | 修改个人信息 |
| PUT | /api/user/password | 登录即可 | 修改密码 |
| GET | /api/user/manage | 管理员 | 用户分页管理 |
| PUT | /api/user/manage/{id}/status | 管理员 | 启用/禁用用户 |
| PUT | /api/user/manage/{id}/password | 管理员 | 重置密码 |

### 2. 教室（学生端 /api/classroom，2 个 + 管理端 /api/classroom/manage，6 个）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | /api/classroom/list | 登录即可 | 教室列表（学生端） |
| GET | /api/classroom/{id} | 登录即可 | 教室详情（学生端） |
| GET | /api/classroom/manage | 管理员 | 教室分页 + 多条件搜索 |
| POST | /api/classroom/manage | 管理员 | 新增教室 |
| PUT | /api/classroom/manage | 管理员 | 编辑教室 |
| DELETE | /api/classroom/manage/{id} | 管理员 | 删除教室（有预约记录禁止删除） |
| PUT | /api/classroom/manage/{id}/status | 管理员 | 启用/停用教室 |
| POST | /api/classroom/manage/batch-status | 管理员 | 批量启用/停用 |

### 3. 预约核心（/api/reservation，除 manage/export 外 8 个）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | /api/reservation | 登录即可 | 提交预约（后端二次冲突校验） |
| GET | /api/reservation/conflict | 登录即可 | 实时冲突检测 |
| GET | /api/reservation/mine | 登录即可 | 我的预约 |
| PUT | /api/reservation/{id}/cancel | 登录即可 | 取消预约（开始前 1 小时规则） |
| PUT | /api/reservation/{id}/audit | 管理员 | 审核通过/驳回 |
| POST | /api/reservation/batch-audit | 管理员 | 批量审核 |
| GET | /api/reservation/calendar | 登录即可 | 日历区间查询（R5） |
| GET | /api/reservation/manage | 管理员 | 预约记录分页（R3；R6 增加 classroomId 可选参数） |
| GET | /api/reservation/export | 管理员 | 预约记录导出 xlsx（R6 新增） |

### 4. 收藏（/api/favorite，2 个）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | /api/favorite/{classroomId} | 登录即可 | 收藏/取消收藏（toggle） |
| GET | /api/favorite/list | 登录即可 | 我的收藏列表 |

### 5. 数据看板统计（/api/stats，3 个，管理员）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| GET | /api/stats/usage-rate | 管理员 | 教室使用率排行 |
| GET | /api/stats/trend | 管理员 | 月度预约趋势 |
| GET | /api/stats/time-distribution | 管理员 | 热门时段分布 |

## 四、权限矩阵（回归不变）

| 接口 | 学生 | 管理员 | 未登录 |
| --- | --- | --- | --- |
| GET /api/reservation/manage | 403 | 200 | 401 |
| GET /api/reservation/export | 403 | 200 | 401 |
| GET /api/reservation/calendar | 200 | 200 | 401 |
| GET /api/stats/* | 403 | 200 | 401 |
| /api/user/manage*、/api/classroom/manage* | 403 | 200 | 401 |
| 其余登录即可接口 | 200 | 200 | 401 |
