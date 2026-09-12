# R6 轮次任务说明 —— 联调优化（13 页闭环 / 边界修复 / 注释补全 / 回归保障）

> 基线：R5-delivery-feature-highlights（git commit 7b5fa31，已推送），本轮从 R5 目录复制工程到本目录 `code/` 内继续开发。
> R1/R2/R3/R4/R5 交付目录只读，未做任何修改；数据库表结构未变更（五表沿用：sys_user / classroom / reservation / user_favorite / ai_config）。

## 一、本轮范围（spec.md 第 7 节 R6 行）

| 模块 | 内容 |
| --- | --- |
| 13 页闭环 | 登录 / 注册 / 学生端 5 页 / 管理端 6 页全部可达、可操作；补实现需求设计文档第 12 页「预约记录页」（管理端第 6 页，路由 `/admin/records`） |
| 全流程联调 | 跨角色闭环实测：学生提交预约 → 管理员审核 → 学生查看状态 → 取消 / 收藏 / 看板统计联动 |
| 边界与异常补全 | 逐页实测边界操作（超大分页 / 空数据 / 非法参数 / 重复提交 / 快速连续点击 / 时段边界 / 权限）不报错，修复联调发现的问题 |
| 注释完善 | 按 AGENTS.md 4.3 补齐后端关键类/方法 Javadoc 与前端关键逻辑注释，行为逻辑零变更 |
| 回归保障 | R1 21 + R2 50 + R3 54 + R4 28 + R5 27 = 180 条基线用例零回归失败 + 本轮新增 30 条 = **210 条全部通过** |
| 交付物 | code/ + database/ + docs/（任务说明/接口清单/测试报告/测试脚本）+ startup-guide.md，目录与文件名不使用中文 |

## 二、方案确认（负责人已拍板）

### 决策 1：预约记录页放置位置 = 管理端（第 6 页）
- 需求设计文档 2.4 第 12 页「预约记录页」为管理端页面（管理员查看全部预约记录 + 多条件筛选 + 导出），
  13 页口径 = 登录 + 注册 + 学生端 5 页 + 管理端 6 页。
- 提示词中「学生端第 6 页」经与需求文档核对判定为笔误，已按需求文档口径实现于管理端（项目负责人确认）。

### 决策 2：新增/变更接口（均为只读，无写接口）
- `GET /api/reservation/manage` 增加**可选参数 `classroomId`**（向后兼容，缺省行为与 R5 完全一致）。
- 新增 `GET /api/reservation/export`（管理员专属，spec.md 5.2 蓝图既定接口）：
  返回 xlsx 文件流（EasyExcel 3.3.4，pom 中 R3 已预留依赖；`Constants.ADMIN_API_PREFIXES` 已预登记管理员前缀）。
- 不引入任何写接口；其余 30 个既有接口契约不变（复用清单见 api-list.md）。

### 决策 3：前端依赖
- **本轮新增前端依赖：无**（spec.md 第 7 节 R6 行未点名新依赖）。
- 既有依赖（Vue 3.4.38 / Element Plus 2.7.8 / Vue Router 4.4.3 / Pinia 2.1.7 / Vite 5.4.21 / ECharts 5.5.1 / FullCalendar 6.1.15）版本未动。

### 决策 4：导出文件形态
- xlsx 二进制文件流（非 Result JSON），`Content-Disposition` 携带 RFC 5987 `filename*` 中文文件名（含时间戳）。
- 鉴权走拦截器管理员前缀：管理员 200（文件流）、学生 403、未登录 401。

## 三、主要实现说明

### 后端（`code/reservation-server`，基于 R5 增量）

新增/变更文件：
- `common/TimeUtil.java` —— 新增 `DATE_TIME_PATTERN("yyyy-MM-dd HH:mm:ss")` 与 `formatDateTime(LocalDateTime)`。
- `vo/ReservationExportVO.java` —— 导出 VO（13 个展示字段白名单：预约 ID/教室名称/楼栋/编号/账号/姓名/日期/起止时间/用途/状态文案/审核备注/审核时间/创建时间），不含 password，时间统一字符串输出。
- `service/ReservationService.java` + `impl/ReservationServiceImpl.java`：
  - `pageManage` 增加 `classroomId` 可选参数（缺省 = 全部，行为与 R5 一致）；
  - 新增 `listExport`（复用 `validateFilters` 状态合法/日期倒序 400/教室存在校验 + `buildManageWrapper` 公共条件构造）；
  - 抽取公共 `buildManageWrapper`（关键词命中为空时使用 `NO_MATCH_ID = -1L` 恒假条件，避免空 `IN()` 查询）；
  - `statusText` 四状态文案与前端标签口径一致（待审核/已通过/已驳回/已取消）；
  - `toExportVO` 字段映射。
- `controller/ReservationController.java` —— `/manage` 增加 `classroomId`（`@RequestParam(required = false)`）；新增 `GET /export`（EasyExcel 写 xlsx 文件流，RFC 5987 中文文件名，时间戳后缀）。
- `common/GlobalExceptionHandler.java` —— 新增 `MethodArgumentTypeMismatchException` 处理器：
  实测 `classroomId=abc` 原返回 500，修复后返回 400 友好提示「参数类型不正确：classroomId」，属本轮边界修复点。

注释自检：全工程关键类/方法均已有 Javadoc（AuthInterceptor / Result / PageResult / TimeUtil / 各 Controller / Service 等），本轮补 `main.js` 前端入口注释；未改变任何行为逻辑。

### 前端（`code/reservation-web`，基于 R5 增量）

新增/变更文件：
- `views/admin/ReservationRecord.vue` —— **新增预约记录页**：日期范围 / 教室下拉（复用 `pageClassrooms(500)`）/ 用户关键词 / 状态下拉多条件筛选 + 表格 9 列 + 分页（10/20/50/100）+ `el-empty` 空状态 + 导出按钮（`createObjectURL` + 临时 `<a>` 下载，文件名含时间戳）。
- `router/index.js` —— 新增路由 `/admin/records → ReservationRecord`（管理端嵌套布局内）。
- `views/admin/AdminHome.vue` —— 侧边菜单新增「预约记录」（Tickets 图标）。
- `api/reservation.js` —— 新增 `exportReservations(params, responseType: 'blob')`。
- `utils/request.js` —— 新增 blob 响应分支（`responseType === 'blob'` 直接返回 data）+ `readBlobMessage`（403 时解析 blob 内 JSON 错误信息友好提示）。
- `views/admin/Dashboard.vue` —— `import * as echarts from 'echarts'` 全量引入改为 `echarts/core` + Bar/Line/Pie + Grid/Legend/Tooltip + CanvasRenderer 按需注册，行为零变更。
- `vite.config.js` —— `manualChunks` 手动分包（element-plus / vue-vendor / echarts / fullcalendar / util-vendor）+ `chunkSizeWarningLimit: 800`（注释说明 element-plus 全量引入为 R1 设计）。
  build 结果：index chunk 913kB → 6.77kB；Dashboard chunk 522kB → 4.86kB（echarts 独立 517kB，gzip 174.57kB）；**build 无警告**。
- `views/student/ClassroomDetail.vue` —— 收藏按钮 `favoriteLoading` 防抖；停用教室（status===0）禁用「预约申请」按钮并提示文案。
- `views/student/MyReservation.vue` —— 取消预约 confirm 后 `cancelingId` 防重复点击。
- `main.js` —— 入口注释。

### 联调发现的既有缺陷修复（R6 修复范围，行为不变）

| 问题 | 修复方式 |
| --- | --- |
| `GET /api/reservation/manage?classroomId=abc` 返回 500 | `GlobalExceptionHandler` 新增类型不匹配处理器 → 400 友好提示 |
| 数据看板 Dashboard chunk 过大（ECharts 全量引入约 1MB，build 警告） | 按需引入 + manualChunks 分包，build 无警告 |
| 管理端首页欢迎文案仍为 R1「预约审核、预约记录、数据看板等模块将在后续迭代上线」 | 更新为「全部模块可用」文案（纯文案，无行为变更） |
| 收藏按钮 / 取消按钮快速连续点击可重复提交 | 前端 loading 防抖 + 二次确认期间禁用 |

## 四、浏览器实测（13 页闭环 + 跨角色流程）

浏览器实测记录（http://localhost:5173，前端 dev server + 后端 8080 + MySQL 基线）：

1. **登录页**：角色单选（学生默认）/ 账号 / 密码 / 登录 / 立即注册，退出登录二次确认正常。
2. **注册页**：学号 / 姓名 / 手机号（选填）/ 邮箱（选填）/ 账号 / 密码 / 确认密码 7 字段 + 返回登录。
3. **学生端 5 页**：教室列表（12 间卡片 + 筛选）→ 教室详情（返回 / 收藏 / 日期选择 / 时段占用时间轴 / 预约申请）→ 我的预约（状态 Tab + 今日置顶 + 三态标签）→ 日历总览（FullCalendar 月/周视图 + 四状态色块 + 图例）→ 个人中心（累计/本月/通过率统计 + 常用教室 + 消息通知 + 信息修改 + 修改密码）。
4. **管理端 6 页**：首页（欢迎 + 账号信息）→ 用户管理 → 教室资源管理 → 预约审核（通过/驳回 + 批量）→ **预约记录（R6 新增：筛选/分页/导出实测下载成功，文件名含中文+时间戳）** → 数据看板（ECharts 三图 + 时间筛选）。
5. **跨角色闭环实测**：学生提交 A101 9/11 10:00-12:00 预约（前端实时冲突校验「该时段可预约」→ 提交成功）→ 管理员审核通过（二次确认）→ 学生端我的预约显示「已通过」（通知联动）→ 取消（未来预约成功取消「已取消」；已过时段预约被业务规则拦截「预约开始前 1 小时内禁止取消，如需调整请联系管理员」，提示友好无报错）。
6. **边界验证**：登录提醒弹窗、收藏/取消二次确认、禁删有预约教室、空状态覆盖均正常；全流程控制台无 500/404（仅 favicon 噪音），无前端报错。

实测结束后已清理测试数据，数据库恢复初始基线（预约 13 条四状态 / 收藏 6 条 / 其余表数据不变）。

## 五、回归保障

- R1 21 + R2 50 + R3 54 + R4 28 + R5 27 = 180 条基线用例 **零回归失败**。
- R6 新增 30 条（T183-T212）覆盖：预约记录页 manage 接口（教室/状态/日期/关键词组合筛选、空数据、教室不存在、非法参数、日期倒序、超大分页、页码 0、size 上限）、导出接口（全量/筛选/库一致性/内容/空结果/非法参数/权限/不含 password/xlsx 文件头）、13 页闭环数据锚点、我的预约回归。
- 测试脚本可复跑：`docs/test-script.ps1`（T01-T212，1282 行），运行结束后恢复数据库初始基线。
- 首轮运行 9 条导出用例失败（PS 5.1 `Invoke-WebRequest -OutFile` 返回对象无 Headers 取 Content-Type 抛错；`$sql200 + 1` 字符串拼接），修复辅助函数（`RawContentStream.CopyTo(MemoryStream)` 取字节 + `[int]` 强转）后**第二轮全量重跑 210/210 通过**，测试报告数据以第二轮为准。

## 六、约束遵守说明

- 未新增业务规则、未修改表结构、未改 R5 已验收功能行为与统计口径（使用率/趋势/时段分布口径沿用 R5）。
- 核心逻辑零回退：冲突检测（前后端双重校验）、状态流转（0/1/2/3）、管理员前缀角色校验、日历登录即可、stats 管理员权限矩阵均与 R1-R5 一致。
- AI 配置项 `ai.enable=false` 保持关闭，本轮未实现任何 AI 相关功能（R7 范畴）。
- 密钥未硬编码；任何列表/详情接口不返回 password（T182/T208/T211 断言验证）。
