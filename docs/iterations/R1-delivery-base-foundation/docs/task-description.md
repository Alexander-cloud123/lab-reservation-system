# R1 本轮任务说明 —— 基础底座

> 项目：高校实验室预约管理系统（基于双重校验机制）
> 轮次：R1 基础底座　|　迭代依据：spec.md 第 7 节 R1 行
> 交付日期：2026-09-11

---

## 一、任务范围（与需求依据）

| 任务单元 | 需求/规格依据 | 完成情况 |
|----------|---------------|----------|
| ① 建库建表 + 测试数据 | 需求文档 2.3 表结构；spec.md 第 4 节；spec.md 4.3 测试数据要求 | ✅ |
| ② 后端骨架 | spec.md 第 2 节（依赖/配置/Result/全局异常/Knife4j/分层） | ✅ |
| ③ 前端骨架 | spec.md 第 3 节（依赖/目录/request 封装/路由守卫） | ✅ |
| ④ 登录注册 + 权限拦截 | 需求文档 1.2 角色定义、1.3 公共功能；spec.md 2.5/5.2 接口规范 | ✅ |

## 二、交付内容概览

```
R1-delivery-base-foundation/
├── code/
│   ├── reservation-server/      # 后端完整可运行工程（Spring Boot 3.2.10）
│   └── reservation-web/         # 前端完整可运行工程（Vue 3.4 + Vite 5）
├── database/
│   └── init_db.sql              # 建库建表 + 测试数据脚本（可重复执行）
├── docs/
│   ├── task-description.md      # 本文件
│   ├── api-list.md              # 新增接口：路径/参数/返回值
│   ├── test-report.md           # 21 条用例：正常/边界/异常/权限
│   └── test-script.ps1          # 可复跑的接口回归脚本
└── startup-guide.md             # 环境要求/启动步骤/验证方法
```

## 三、后端实现要点

1. **工程**：`com.example.reservation`，Java 21 + Spring Boot 3.2.10，依赖严格按 spec.md 2.2（MyBatis-Plus 3.5.7 / Knife4j 4.4.0 / EasyExcel / Hutool 5.8.32 / Lombok）。
2. **分层**：`controller → service → mapper → entity` 四层，业务逻辑全部在 service 层，controller 仅收参返参。
3. **统一返回**：`Result{code,message,data}`；错误码语义化（200 成功 / 400 参数 / 401 未登录 / 403 无权限 / 500 系统异常）。
4. **全局异常**：`GlobalExceptionHandler` 拦截业务异常与非法 JSON（HttpMessageNotReadable → 400），兜底 500 不泄露堆栈。
5. **鉴权**：`AuthInterceptor` 拦截 `/api/**`（放行 login/register）——无 Token/非法 Token 返回 HTTP 401 + Result；管理员专属前缀（`/manage`、`/stats`、`/ai`、`/export`）校验角色，学生访问返回 403；请求期用户信息存入 `UserContext`（ThreadLocal）。
6. **Token**：Hutool JWT 签发，载荷含 userId/username/role，有效期 24h，签名密钥 `app.jwt.secret` 支持环境变量 `JWT_SECRET` 注入（**未硬编码**）。
7. **密码**：Hutool BCrypt 加密/校验（`$2a$/$2b$` 兼容），数据库中无明文。
8. **实体自动填充**：`MyMetaObjectHandler` 统一填充 create_time/update_time；分页插件就绪（后续轮次使用）。
9. **登录校验顺序**：账号存在性+密码（统一提示"账号或密码错误"，防枚举）→ 状态（禁用 → 403）→ 角色匹配（学生账号不能以管理员身份登录）。

## 四、前端实现要点

1. **工程**：Vue 3.4.38 + Vite 5.4.21（锁定精确版本），Element Plus 2.7.8、Vue Router 4.4.3、Pinia 2.1.7、Axios、ECharts 5.5+、FullCalendar 6.1、dayjs、sass（非 node-sass）。
2. **目录结构**：按 spec.md 3.2 拆分为 api/ components(预留) / router / stores / utils / views(common·student·admin)。
3. **request.js**：baseURL `/api`；请求拦截注入 `Authorization: Bearer {token}`；响应拦截统一错误提示（ElMessage），**401 清除登录态并跳转 `/login?redirect=…`**，403 提示无权限。
4. **路由守卫**：`beforeEach` 全局前置守卫——未登录访问受保护页 → 登录页带回跳；已登录访问登录/注册页 → 跳角色首页；**学生访问管理端路由 / 管理员访问学生端路由 → 跳回本角色首页**；兜底 404 重定向。
5. **页面**：登录页（角色单选学生/管理员 + 表单校验）、注册页（学号/姓名/手机号/邮箱/账号/密码/确认密码 + 前端两次密码一致性 + 手机号格式校验）、学生首页与管理端首页（展示当前用户信息 + 二次确认退出，验证角色分流与 Token 有效性）。
6. **Vite 代理**：`/api` → `http://localhost:8080`，开发期无跨域问题；后端同时配置 CORS 兜底。

## 五、数据库要点

1. 五表结构与需求文档 2.3 逐字段一致；两处索引：`reservation.idx_class_date(classroom_id,reserve_date)`（冲突检测优化）、`user_favorite.idx_user_class(user_id,classroom_id)` 唯一（防重复收藏）。
2. 测试数据：管理员 1（admin/admin123）+ 学生 4（123456）；教室 12（3 类 × 3 楼栋）；预约 13 条覆盖**四状态**（待审核 3 / 已通过 6 / 已驳回 2 / 已取消 2），含**当日 5 条**、本周、本月数据，含一条"已通过且今日 15:00 即将开始"（支撑登录提醒演示）；收藏 6 条；AI 配置 5 条（合规关键词库/Prompt 模板，避免硬编码）。
3. "当日/本周/本月"用 `CURDATE()` 动态生成，任意日期导入均可支撑日历、看板、提醒演示。

## 六、本轮需要负责人知晓/裁决的事项

| 事项 | 说明 | 处理 |
|------|------|------|
| **EasyExcel 版本** | spec.md 2.2 标注 3.4.0，经核实 **Maven Central 与阿里云镜像均不存在该版本**（3.x 最新真实版本为 3.3.4，其后直接为 4.x） | 暂用 **3.3.4**（3.x 最新），**请负责人确认**是否接受；若要求 4.x 需评估 API 变更影响 |
| **Element Plus 图标包** | 页面使用 `@element-plus/icons-vue`（Element Plus 官方配套图标库，非新增框架/中间件） | 已引入，请知悉 |
| **Docker 容器自愈** | 开发中 Docker Desktop 中途退出导致 MySQL 停止、后端 500；已为容器设置 `--restart unless-stopped` | 已解决，启动说明中注明 |
| **前端依赖锁定** | npm 解析到 Vue 3.5/Element Plus 2.14，已按 spec 锁定为 **Vue 3.4.38 / Element Plus 2.7.8 / Vue Router 4.4.3 / Pinia 2.1.7**（peer 兼容 3.4） | 已锁定，包版本与 spec 一致 |

## 七、R1 完成标准对照

| 验收标准（spec 7 节 R1） | 结果 |
|--------------------------|------|
| 五表 + 测试数据导入成功（含四状态、当日数据） | ✅ 见测试报告/数据库核验 |
| 后端启动无错，doc.html 可访问 | ✅ HTTP 200，3 接口入文档 |
| 前端 dev 可运行，登录/注册页可用 | ✅ dev 5173 起，页面可达，构建通过 |
| 未登录 401 跳转登录页；角色区分正确 | ✅ T17~T21 通过；前端 401 跳转/角色守卫已实现 |
| 密码 BCrypt 存储无明文 | ✅ 库中 0 条明文，全部 `$2a$/$2b$` |
