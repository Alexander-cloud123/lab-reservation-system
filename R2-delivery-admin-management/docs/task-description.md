# R2 本轮任务说明 —— 管理端基础（用户管理 + 教室资源管理）

> 项目：高校实验室预约管理系统（基于双重校验机制）
> 轮次：R2 管理端基础　|　迭代依据：spec.md 第 7 节 R2 行；需求设计文档 1.3 管理员端（用户管理、教室资源管理）
> 基线：R1-delivery-base-foundation（只读，未改动）；本目录在基线上增量开发
> 交付日期：2026-09-11

---

## 一、任务范围（与需求依据）

| 任务单元 | 需求/规格依据 | 完成情况 |
|----------|---------------|----------|
| ① 用户管理（后端 3 接口 + 前端页面 + 导航/路由） | 需求文档 1.3 管理员端「用户管理」；spec.md 7 节 R2 ①；spec.md 5.2 用户管理接口 | ✅ |
| ② 教室资源管理（后端 6 接口 + 前端页面 + 导航/路由） | 需求文档 1.3 管理员端「教室资源管理」；spec.md 7 节 R2 ②；spec.md 5.2 教室管理接口 | ✅ |
| ③ R1 回归保障（21 条用例 + 新接口权限全覆盖） | spec.md 7 节 R2 ③；AGENTS.md 第 3 节测试验证 | ✅ 71/71 |

## 二、交付内容概览

```
R2-delivery-admin-management/
├── code/
│   ├── reservation-server/      # 后端（R1 复制 + 本轮新增 8 文件、修改 5 文件）
│   └── reservation-web/         # 前端（R1 复制 + 本轮新增 3 文件、修改 4 文件）
├── database/
│   └── init_db.sql              # 复制自 R1（表结构/测试数据未变，自包含可导入）
├── docs/
│   ├── task-description.md      # 本文件
│   ├── api-list.md              # 本轮新增接口：路径/参数/返回值
│   ├── test-report.md           # 71 条用例测试报告（含 R1 回归关系）
│   └── test-script.ps1          # 可复跑接口测试脚本（R1 21 条 + R2 50 条）
└── startup-guide.md             # 环境要求/启动步骤/验证方法
```

## 三、后端实现要点（R2）

1. **分层结构**（AGENTS.md 4.3）：`controller → service → mapper → entity` 四层，业务逻辑全部在 service 层。
2. **用户管理**（`UserController` 追加，路径 `/api/user/manage`，拦截器已按前缀校验管理员角色）：
   - `GET /api/user/manage`：分页 + 多条件查询（关键词：账号/姓名/学号模糊；角色、状态筛选），排序 `create_time DESC`；实体 → `UserVO` 转换**剔除 password 字段**。
   - `PUT /api/user/manage/{id}/status`：启用/禁用（status=0/1 参数校验；目标状态相同幂等直接返回）。
   - `PUT /api/user/manage/{id}/password`：重置为默认密码 `123456`，BCrypt 加密存储（库中无明文）。
3. **教室管理**（新增 `ClassroomController`，路径 `/api/classroom/manage`）：
   - `GET`：分页 + 多条件搜索（关键词：名称/编号模糊；楼栋、类型、状态筛选），排序 `create_time DESC`。
   - `POST` / `PUT`：新增/编辑，共用 `validateClassroomDTO` 校验（名称/楼栋/编号/类型/容量必填，容量 > 0，类型 ∈ {1,2,3}）；新增默认状态可用。
   - `DELETE /{id}`：**删除保护**——该教室存在**任何预约记录（不限状态）**时返回 400「该教室存在预约记录，禁止删除」；无预约记录可删除。
   - `PUT /{id}/status`：启用/停用（幂等）。
   - `POST /batch-status`：批量启用/停用（入参 `ids` + `status`，返回实际更新条数）。
4. **新增通用分页结构** `PageResult{total, records}`（spec.md 2.5 分页约定）；分页参数校验：page ≥ 1，1 ≤ size ≤ 500（与 MyBatis-Plus 分页插件 maxLimit 一致）。
5. **常量收敛**：默认密码、教室类型、用户/教室状态常量统一收敛至 `Constants`（AGENTS.md 4.3 禁止魔法值散落）。
6. 全部接口统一返回 `Result{code, message, data}`；错误码语义化（400/401/403/500）。

## 四、前端实现要点（R2）

1. **管理端布局**：`AdminHome.vue` 由欢迎页升级为管理端布局（顶部栏 + 左侧导航菜单 + 主内容区 `router-view` + 退出登录），原欢迎内容迁移至新增 `AdminWelcome.vue`；菜单含「首页 / 用户管理 / 教室资源管理」入口。
2. **路由**：管理端改为嵌套路由 `/admin`（布局，管理员角色守卫）→ `home`（欢迎）/ `users`（用户管理）/ `classrooms`（教室资源管理）；学生 Token 访问被路由守卫跳回学生首页，后端拦截器二次兜底 403。
3. **用户管理页** `UserManage.vue`：关键词/角色/状态搜索栏、用户表格（不含密码字段）、启用/禁用（二次确认）、重置密码（二次确认）、分页；当前登录管理员所在行操作按钮禁用并提示（前端体验 + 后端防护双保险）。
4. **教室管理页** `ClassroomManage.vue`：关键词/楼栋/类型/状态搜索栏、教室表格、新增/编辑弹窗（Element Plus 表单校验：必填 + 容量 ≥ 1，与后端一致）、删除（二次确认）、状态开关（二次确认）、勾选批量启用/禁用（二次确认）、分页；楼栋筛选下拉由已加载数据动态去重生成。
5. **接口封装**：`src/api/user.js` 追加 3 个管理接口；新增 `src/api/classroom.js` 封装 6 个教室管理接口。Axios 统一携带 Token、统一错误提示、401 跳转沿用 R1 `request.js`。

## 五、规则与决策标注（负责人知悉）

| 事项 | 规则/决策 | 依据与说明 |
|------|-----------|-----------|
| **管理员自护** | 管理员不允许禁用/重置**自己**，后端返回 400「不允许操作当前登录的管理员账号」；前端同步禁用当前管理员行的操作按钮 | 本轮任务要求「合理防护」；后端为强制兜底 |
| **重置密码默认值** | 重置为 `123456`（BCrypt 存储，库中无明文），旧密码立即失效 | 本轮任务给定默认值 |
| **删除保护** | 存在**任何预约记录（不限状态：待审核/已通过/已驳回/已取消）**时禁止删除教室，返回 400；无预约记录可删除 | 本轮任务要求「该教室存在任何预约记录时禁止删除」 |
| **分页默认值与上限** | page 默认 1、size 默认 10；size 上限 500；排序统一 `create_time DESC` | spec.md 5.1 |
| **EasyExcel 版本** | 沿用 **3.3.4**（R1 已核实 3.4.0 不存在于 Maven Central，负责人已确认） | R1 交付说明「六」 |
| **前端依赖锁定** | 沿用 R1 package.json 精确锁定（Vue 3.4.38 / Element Plus 2.7.8 / Vue Router 4.4.3 / Pinia 2.1.7），npm install 不升级大版本 | R1 交付说明「六」 |
| **测试脚本可复跑** | R2 测试脚本中注册用例用户名带时间戳后缀（`test_stu_/test_stu2_/pwt_` 等），测试新增教室结束时删除，临时用户由 SQL 清理；R1 脚本 T03/T07 静态用户名改为时间戳后缀，保证回归可重复执行 | 本报告「测试说明」 |
| **管理端布局调整** | R1 的 `AdminHome.vue` 欢迎页升级为管理端布局容器，欢迎内容独立为 `AdminWelcome.vue`（路由 `/admin/home` 保持不变）；为 R3~R5 后续管理页面（审核/记录/看板）复用布局 | 迭代演进需要 |

## 六、验收标准对照

| 验收标准 | 结果 |
|----------|------|
| 用户管理、教室管理后端接口 + 前端页面完整可用，管理端导航可进入两页面 | ✅ 见测试报告与前端验证 |
| 学生 Token 访问管理接口返回 403；禁用用户无法登录；重置后旧密码失效、123456 可登录 | ✅ T27~T29、T37~T39、T65~T70 |
| 关键操作（删除/禁用/重置密码/批量启用禁用）均有二次确认 | ✅ 前端 ElMessageBox.confirm 全覆盖 |
| 列表/详情接口不返回 password；重置密码 BCrypt 存储、库中无明文 | ✅ T22 hasPassword=False；SQL 核验 0 明文 |
| 教室 CRUD 全链路正确（含有关联预约禁止删除） | ✅ T41~T64（含 T60 删除保护） |
| 代码规范符合 AGENTS 4.3 | ✅ 见 spec 第 8 节 Checklist 自查 |
| R1 21 条用例回归通过 | ✅ T01~T21 全部通过 |

## 七、R1 基线新增/修改文件清单

**后端（reservation-server）**
- 新增：`common/PageResult.java`、`dto/UserStatusDTO.java`、`dto/ClassroomDTO.java`、`dto/ClassroomStatusDTO.java`、`dto/BatchStatusDTO.java`、`service/ClassroomService.java`、`service/impl/ClassroomServiceImpl.java`、`controller/ClassroomController.java`
- 修改：`common/Constants.java`（默认密码/教室类型常量）、`controller/UserController.java`（+3 管理接口）、`service/UserService.java`（+3 方法）、`service/impl/UserServiceImpl.java`（+3 实现）

**前端（reservation-web）**
- 新增：`src/api/classroom.js`、`src/views/admin/UserManage.vue`、`src/views/admin/ClassroomManage.vue`、`src/views/admin/AdminWelcome.vue`
- 修改：`src/api/user.js`（+3 方法）、`src/router/index.js`（管理端嵌套路由）、`src/views/admin/AdminHome.vue`（布局化）
