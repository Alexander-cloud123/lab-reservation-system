# R1 接口清单

> 通用约定：全部接口返回统一结构 `Result{code, message, data}`；除标注外均需鉴权。
> 鉴权方式：请求头 `Authorization: Bearer {token}`（登录接口签发）。
> 错误码：200 成功 / 400 参数或业务错误 / 401 未登录或登录已过期 / 403 无权限 / 500 系统异常。

---

## 一、本轮新增接口（3 个）

### 1. 登录 `POST /api/user/login`（免鉴权）

| 项 | 内容 |
|----|------|
| 请求体 | `{ "username": "zhangsan", "password": "123456", "role": 0 }` |
| 参数说明 | `username` 登录账号；`password` 明文密码（后端 BCrypt 校验）；`role` 登录角色：0-学生，1-管理员 |
| 成功返回 | `code=200, message="登录成功"`，`data: { "token": "<JWT>", "user": {id, username, name, studentNo, phone, email, role, status, createTime} }` |
| 失败场景 | 400 账号或密码错误 / 400 角色选择与账号类型不匹配 / 403 账号已被禁用 / 400 账号、密码、角色不能为空 |

### 2. 学生注册 `POST /api/user/register`（免鉴权）

| 项 | 内容 |
|----|------|
| 请求体 | `{ "username", "password", "confirmPassword", "name", "studentNo", "phone"(选填), "email"(选填) }` |
| 校验规则 | 必填：账号/密码/确认密码/姓名/学号；账号唯一；两次密码一致；密码 ≥ 6 位；手机号选填但须为 11 位 |
| 成功返回 | `code=200, message="注册成功，请登录"`，`data=null` |
| 失败场景 | 400 该账号已被注册 / 400 两次输入的密码不一致 / 400 密码长度不能少于 6 位 / 400 手机号格式不正确 / 400 请完整填写必填信息 |

### 3. 当前用户信息 `GET /api/user/info`（需鉴权）

| 项 | 内容 |
|----|------|
| 参数 | 无（从 Token 取当前用户） |
| 成功返回 | `code=200`，`data: {id, username, name, studentNo, phone, email, role, status, createTime}`（**不含 password**） |
| 失败场景 | HTTP 401 + `code=401` 未登录或登录已过期（无/非法/过期 Token） |

---

## 二、鉴权与权限机制（已实现，后续轮次接口复用）

| 机制 | 说明 |
|------|------|
| 拦截范围 | `/api/**`，放行 `/api/user/login`、`/api/user/register`、CORS 预检 OPTIONS |
| 未登录/非法 Token | HTTP 401 + `Result{code:401}`（前端拦截器清登录态并跳转 `/login?redirect=…`） |
| 管理员专属前缀 | `/api/user/manage`、`/api/classroom/manage`、`/api/reservation/manage`、`/api/reservation/export`、`/api/stats`、`/api/ai`——学生 Token 访问返回 HTTP 403（R2 起生效于对应控制器） |
| Token 载荷 | `userId` / `username` / `role`，有效期 24h，签名密钥 `app.jwt.secret`（环境变量 `JWT_SECRET` 可覆盖） |

---

## 三、规划接口蓝图（spec.md 5.2，后续轮次按此实现）

用户：`/api/user/manage`、`/api/user/password`、`/api/user/stats`；教室：`/api/classroom/list`、`/api/classroom/{id}`、`/api/classroom/manage`、`/api/favorite/*`；预约：`/api/reservation`、`/api/reservation/{id}/cancel`、`/api/reservation/mine`、`/api/reservation/manage`、`/api/reservation/{id}/audit`、`/api/reservation/batch-audit`、`/api/reservation/conflict`、`/api/reservation/export`；统计：`/api/stats/*`；AI：`/api/ai/*`（第 7 轮）。完整定义见 spec.md 5.2。
