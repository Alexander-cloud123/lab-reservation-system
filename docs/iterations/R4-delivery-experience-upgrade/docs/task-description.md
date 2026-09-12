# R4 轮次任务说明 —— 体验升级（个人中心 / 收藏全链路 / 体验细节）

> 基线：R3-delivery-reservation-core（git commit 9aa8ecc），本轮从 R3 复制工程到本目录 `code/` 内继续开发。
> R1/R2/R3 交付目录只读，未做任何修改；数据库表结构未变更（五表沿用：sys_user / classroom / reservation / user_favorite / ai_config）。

## 一、本轮范围

| 模块 | 内容 |
| --- | --- |
| 个人中心 | 学生端第 4 页（路由 `/student/profile`）：数据概览 / 常用教室 / 消息通知 / 个人信息 / 修改密码 |
| 收藏全链路 | 收藏 toggle + 列表（上限 10 间），详情页收藏按钮、个人中心常用教室快捷入口 |
| 体验细节 | 预约草稿自动保存、筛选条件记忆、今日预约置顶、友好空状态、登录提醒、状态标签三态细化 |
| 回归保障 | R1 21 + R2 50 + R3 54 = 125 条基线用例零回归失败 + 本轮新增 28 条 = 153 条全部通过 |

## 二、规则与决策标注（依据三份文档，无自行新增业务规则）

### 决策 1：消息通知由预约表状态动态生成（不新增通知表）
- 个人中心「消息通知」不落库：前端读取 `GET /api/reservation/mine`（本人全部状态）动态生成三类消息：
  待审核提醒（status=0）、审核结果通知（status=1 已通过 / status=2 已驳回，含审核备注）、即将开始提醒（status=1 且开始时间在未来 24 小时内）。
- 已读状态存 `localStorage`（key 带用户维度：`reservation_msg_read_<userId>`），单条点击即读 + 全部标为已读。

### 决策 2：审核通过率口径（写进交付说明的标注）
- 通过率 = 已通过 /（已通过 + 已驳回）× 100，保留一位小数；无审核记录（通过+驳回=0）返回 0。
- 待审核与已取消不计入分母（它们尚未产生审核结论）。

### 决策 3：收藏 toggle 语义与上限（硬约束）
- `POST /api/favorite/{classroomId}` 为 toggle：已收藏 → 取消收藏；未收藏 → 新增收藏。
- 新增前校验上限：每人最多收藏 **10 间**（需求文档 1.4），超出拒绝并提示「收藏数量已达上限（10 间），请先取消部分收藏」。
- 收藏目标必须存在（教室不存在返回 400）；user_favorite 表联合唯一索引 `idx_user_class` 兜底防重复。
- 重复收藏/取消/超上限均由接口返回友好 message，前端 ElMessage 提示。

### 决策 4：实时状态标签三态口径（R3 口径 + 补全已结束态）
- 以「当天」为基准，对每间教室：
  1. 存在已通过预约且当前时刻 ∈ [开始, 结束) → **使用中**（红）；
  2. 当天存在已通过预约且当前时刻 ≥ 全部时段结束时间 → **已结束**（灰，R4 补全）；
  3. 其余（当天无已通过预约，或尚未开始）→ **当前空闲**（绿）。
- 该口径与 R3 完全兼容（R3 的「使用中」判定未变，仅将原「当前空闲」拆分为「空闲 / 已结束」），
  R3 基线用例未断言 statusLabel 的具体取值，无回归风险。

### 决策 5：新接口权限（拦截器既有规则，无新规则）
- `GET /api/user/stats`、`PUT /api/user/info`、`PUT /api/user/password` 位于 `/api/user` 但**不在** `/api/user/manage`
  管理员前缀内；`/api/favorite/*` 为全新路径——两者均只需登录即可访问（学生/管理员均可），未登录一律 401。

### 决策 6：修改密码 / 收藏的二次确认与友好提示（硬约束）
- 修改密码：前端 ElMessageBox 二次确认后才提交；成功后清除登录态并跳转登录页（提示「密码修改成功，请重新登录」）。
- 收藏/取消收藏：接口返回友好 message（收藏成功 / 已取消收藏 / 超上限提示），前端 ElMessage 展示。

### 决策 7：个人信息修改范围（硬约束）
- `PUT /api/user/info` 仅接受 姓名/学号/邮箱/手机号；DTO 不含密码/账号/角色/状态字段，
  配合 MyBatis-Plus 默认 NOT_NULL 更新策略，未传字段不会被置空。
- 前端个人信息表单按任务说明仅修改 姓名/邮箱/手机号，学号/登录账号只读展示。

### 决策 8：草稿与筛选记忆的存储维度（需求文档 2.4 标注）
- 预约草稿：`localStorage` key = `reservation_draft_<userId>_<classroomId>`，存储 日期/开始/结束/用途；
  弹窗未提交关闭自动保存，提交成功后清除。
- 筛选条件记忆：`localStorage` key = `reservation_filter_<userId>`，存储 关键词/楼栋/类型/日期；
  搜索、重置及条件变化时保存，页面挂载时还原。

## 三、主要实现说明

### 后端（`code/reservation-server`，基于 R3 增量 5 个接口）

新增类：
- `dto/UserInfoDTO` —— 个人信息请求体（name/studentNo/phone/email）
- `dto/PasswordDTO` —— 修改密码请求体（oldPassword/newPassword/confirmPassword）
- `vo/UserStatsVO` —— 数据统计（totalReservations/monthReservations/approvalRate/lastReservationTime）
- `vo/FavoriteVO` —— 收藏视图（含教室展示字段）
- `controller/FavoriteController` —— 收藏模块（POST /api/favorite/{classroomId}、GET /api/favorite/list）
- `service/FavoriteService` / `impl/FavoriteServiceImpl` —— toggle + 上限校验 + 批量补全教室字段

改造类：
- `service/UserService` / `impl/UserServiceImpl` —— 新增 `getStats()`（累计/本月/通过率/最近一次）、
  `updateInfo()`（字段校验 + 仅更新白名单字段）、`updatePassword()`（必填/长度/一致性校验 → BCrypt 验证原密码 → BCrypt 存储新密码）
- `controller/UserController` —— 新增 GET /stats、PUT /info、PUT /password
- `common/Constants` —— 新增 `FAVORITE_MAX_COUNT = 10`、`STATUS_LABEL_ENDED = "已结束"`
- `service/impl/ClassroomServiceImpl#calcStatusLabel` —— 两态改为三态（补全已结束态）

安全要点：
- 任何用户相关接口不返回 `password` 字段（UserVO 白名单映射，基线既有，R4 新增用例复核）；
- 新密码一律 BCrypt 加密存储，数据库无明文；原密码错误不泄露用户存在性信息。

### 前端（`code/reservation-web`）

新增页面/文件：
- `views/student/Profile.vue` —— 个人中心四区块：数据概览卡片、常用教室（收藏卡片 + 空状态引导）、
  消息通知（动态生成 + localStorage 已读）、个人信息表单、修改密码表单（二次确认）
- `api/favorite.js` —— 收藏接口封装

改造文件：
- `router/index.js` —— 学生端新增 `profile` 子路由（第 4 页）
- `views/student/StudentLayout.vue` —— 导航新增「个人中心」
- `views/student/ClassroomDetail.vue` —— 收藏/取消收藏星标按钮（挂载时同步收藏状态）、
  预约弹窗草稿自动保存（未提交关闭保存 / 提交成功清除）、状态标签三态渲染
- `views/student/ClassroomList.vue` —— 筛选条件记忆（localStorage + 用户维度 key）、状态标签三态渲染
- `views/student/MyReservation.vue` —— 今日预约置顶（当日行置顶 + 「今日」标签 + 行高亮）+ 空状态引导
- `views/common/Login.vue` —— 登录成功后（学生角色）检查「已通过且即将开始（24h 内）」预约并弹出温和提醒，失败不阻断登录
- `api/user.js` —— 新增 getUserStats / updateUserInfo / changePassword

体验细节清单（验收逐项核对）：
1. 预约草稿自动保存：弹窗未提交关闭 → 下次打开自动填充（按教室+日期+时段+用途）；
2. 筛选条件记忆：关键词/楼栋/类型/日期切换页面返回后还原；
3. 今日预约置顶：我的预约页当日预约排最前 + 「今日」标签 + 行高亮；
4. 友好空状态：教室列表无结果（重置筛选按钮）、我的预约无数据（去预约按钮）、收藏无数据（去收藏按钮）三处齐备；
5. 登录提醒：存在已通过且 24h 内开始的预约时弹出提醒；
6. 状态标签细化：空闲绿 / 使用中红 / 已结束灰（前后端口径一致）。

## 四、测试与回归

- 复用 R3 `docs/test-script.ps1`（T01-T127 共 125 条）并追加 R4 段（T128-T155 共 28 条），总计 **153 条**。
- R4 用例覆盖：正常流程 13 条 / 边界场景 7 条 / 异常操作 3 条 / 权限校验 5 条。
- 测试数据策略：使用独立测试用户（`r4stu_*` / `r4pw_*`）避免污染基线学生数据；
  日期用动态计算（+3 天、+2 小时），保证任意日期可复跑。
- 清理策略：结束后恢复数据库初始基线——预约 13 条四状态（id 1-13）、收藏 6 条（id 1-6）、
  用户 5 人、教室 12 间；测试用户与测试收藏/预约按 id/前缀清理。
- 运行方式见 `startup-guide.md`。

## 五、数据库

- 无表结构变更、无新增表、无数据脚本变更（`database/init_db.sql` 与 R3 一致，自包含）。
- user_favorite 沿用 R1 已建表（含联合唯一索引 idx_user_class）。
