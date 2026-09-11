# R2 接口清单 —— 管理端基础（用户管理 + 教室资源管理）

> 通用约定：全部接口返回统一结构 `Result{code, message, data}`；除标注外均需鉴权。
> 鉴权方式：请求头 `Authorization: Bearer {token}`；管理员专属接口由拦截器按路径前缀校验角色（学生 Token 访问返回 HTTP 403，未登录/非法 Token 返回 HTTP 401）。
> 错误码：200 成功 / 400 参数或业务错误 / 401 未登录或登录已过期 / 403 无权限 / 500 系统异常。
> 分页通用参数：`page`（页码，默认 1，≥1）、`size`（每页条数，默认 10，1~500）；分页返回统一结构 `data: { total, records }`；排序统一按 `create_time DESC`。

---

## 一、用户管理（管理员专属，前缀 `/api/user/manage`）

### 1. 用户分页列表 `GET /api/user/manage`

| 项 | 内容 |
|----|------|
| 路径 | `/api/user/manage` |
| 参数（Query） | `page`（默认 1）、`size`（默认 10）、`keyword`（可选，账号/姓名/学号模糊匹配）、`role`（可选，0-学生/1-管理员）、`status`（可选，0-禁用/1-正常） |
| 成功返回 | `code=200`，`data: { "total": N, "records": [ {id, username, name, studentNo, phone, email, role, status, createTime} ] }`（**不含 password**） |
| 失败场景 | 400 页码必须大于等于 1 / 400 每页条数必须在 1-500 之间；HTTP 401 未登录；HTTP 403 学生 Token 访问 |

### 2. 启用/禁用用户 `PUT /api/user/manage/{id}/status`

| 项 | 内容 |
|----|------|
| 路径 | `/api/user/manage/{id}/status` |
| 请求体 | `{ "status": 0 }`（0-禁用，1-正常） |
| 成功返回 | `code=200, message="操作成功"`，`data=null` |
| 失败场景 | 400 用户不存在 / 400 状态参数不合法（0-禁用，1-正常）/ **400 不允许操作当前登录的管理员账号**（自护规则）；HTTP 401/403 |
| 规则 | 禁用后该用户无法登录（登录返回 code=403 账号已被禁用）；目标状态与当前一致时幂等返回 |

### 3. 重置用户密码 `PUT /api/user/manage/{id}/password`

| 项 | 内容 |
|----|------|
| 路径 | `/api/user/manage/{id}/password` |
| 请求体 | 无 |
| 成功返回 | `code=200, message="密码已重置为默认密码"`，`data=null` |
| 失败场景 | 400 用户不存在 / **400 不允许操作当前登录的管理员账号**；HTTP 401/403 |
| 规则 | 重置为默认密码 **123456**，BCrypt 加密存储（库中无明文）；旧密码立即失效 |

---

## 二、教室资源管理（管理员专属，前缀 `/api/classroom/manage`）

### 1. 教室分页列表 `GET /api/classroom/manage`

| 项 | 内容 |
|----|------|
| 路径 | `/api/classroom/manage` |
| 参数（Query） | `page`（默认 1）、`size`（默认 10）、`keyword`（可选，名称/编号模糊匹配）、`building`（可选，楼栋精确）、`type`（可选，1-普通教室/2-实验室/3-机房）、`status`（可选，0-停用/1-可用） |
| 成功返回 | `code=200`，`data: { "total": N, "records": [ {id, name, building, roomNo, type, capacity, equipment, description, status, createTime, updateTime} ] }` |
| 失败场景 | 400 分页参数非法；HTTP 401/403 |

### 2. 新增教室 `POST /api/classroom/manage`

| 项 | 内容 |
|----|------|
| 路径 | `/api/classroom/manage` |
| 请求体 | `{ "name": "A101多媒体教室", "building": "信息楼", "roomNo": "A101", "type": 1, "capacity": 60, "equipment": "投影仪（选填）", "description": "备注（选填）" }` |
| 校验规则 | 名称/楼栋/编号/类型/容量**必填**；容量 **> 0**；类型 ∈ {1,2,3}；新增默认状态可用（status=1） |
| 成功返回 | `code=200, message="新增成功"`，`data: 新教室ID` |
| 失败场景 | 400 教室名称、楼栋、编号不能为空 / 400 教室类型不能为空或类型不合法 / 400 教室容量不能为空或容量必须大于 0；HTTP 401/403 |

### 3. 编辑教室 `PUT /api/classroom/manage`

| 项 | 内容 |
|----|------|
| 路径 | `/api/classroom/manage` |
| 请求体 | 同新增，另需 `"id": 教室ID` |
| 成功返回 | `code=200, message="修改成功"`，`data=null` |
| 失败场景 | 400 教室 ID 不能为空 / 400 教室不存在 / 400 校验规则同新增；HTTP 401/403 |

### 4. 删除教室 `DELETE /api/classroom/manage/{id}`

| 项 | 内容 |
|----|------|
| 路径 | `/api/classroom/manage/{id}` |
| 成功返回 | `code=200, message="删除成功"`，`data=null` |
| 失败场景 | 400 教室 ID 不能为空 / 400 教室不存在 / **400 该教室存在预约记录，禁止删除**（删除保护：任何状态预约记录均禁止）；HTTP 401/403 |

### 5. 启用/停用教室 `PUT /api/classroom/manage/{id}/status`

| 项 | 内容 |
|----|------|
| 路径 | `/api/classroom/manage/{id}/status` |
| 请求体 | `{ "status": 0 }`（0-停用，1-可用） |
| 成功返回 | `code=200, message="操作成功"`，`data=null` |
| 失败场景 | 400 教室不存在 / 400 状态参数不合法（0-停用，1-可用）；HTTP 401/403 |
| 规则 | 目标状态与当前一致时幂等返回 |

### 6. 批量启用/停用教室 `POST /api/classroom/manage/batch-status`

| 项 | 内容 |
|----|------|
| 路径 | `/api/classroom/manage/batch-status` |
| 请求体 | `{ "ids": [1, 2, 3], "status": 0 }`（0-停用，1-可用） |
| 成功返回 | `code=200, message="批量操作成功"`，`data: 实际更新条数` |
| 失败场景 | 400 教室 ID 列表不能为空 / 400 状态参数不合法；HTTP 401/403 |

---

## 三、权限矩阵（R2 覆盖范围）

| 接口 | 未登录 | 学生 Token | 管理员 Token |
|------|--------|-----------|-------------|
| GET /api/user/manage | 401 | 403 | 200 |
| PUT /api/user/manage/{id}/status | 401 | 403 | 200 |
| PUT /api/user/manage/{id}/password | 401 | 403 | 200 |
| GET /api/classroom/manage | 401 | 403 | 200 |
| POST /api/classroom/manage | 401 | 403 | 200 |
| PUT /api/classroom/manage | 401 | 403 | 200 |
| DELETE /api/classroom/manage/{id} | 401 | 403 | 200 |
| PUT /api/classroom/manage/{id}/status | 401 | 403 | 200 |
| POST /api/classroom/manage/batch-status | 401 | 403 | 200 |

> 说明：拦截器（AuthInterceptor）按 `Constants.ADMIN_API_PREFIXES` 前缀校验角色；本轮 9 个新接口全部位于 `/api/user/manage`、`/api/classroom/manage` 前缀下，权限矩阵已由测试用例 T37~T40、T65~T71 全覆盖验证。

## 四、本轮接口与 R1 回归关系

- R1 已交付接口（登录/注册/当前用户信息）**未做任何修改**，仅在同一控制器内追加管理接口，回归用例 T01~T21 全部通过。
- 新增接口共 **9 个**（用户管理 3 + 教室管理 6），全部返回统一 `Result` 结构，遵循 spec.md 5.1 通用规范。
