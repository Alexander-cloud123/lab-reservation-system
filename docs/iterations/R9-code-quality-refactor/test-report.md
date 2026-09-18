# 代码质量重构轮 · 回归结果

- 被测版本：`60b8bff`（工作区 clean，无 `+dirty`；回归结果 md 记录的干净版本为 `1ebd39b`）
- 本轮基线：`890f729`（重构前 HEAD）
- 执行时间：2026-09-18
- 环境：JDK 21 · Maven 3.9.x（IDEA 内置）· Node 22 · Docker（reservation-mysql / reservation-redis 在线）

## 一、提交明细（6 笔独立 commit）

| # | Commit | 内容 | 变更统计 |
|---|--------|------|----------|
| 1 | `a65c3f2` | 前端 lint 工具链（ESLint 9 flat config + Prettier + EditorConfig + npm scripts） | web/eslint.config.js、web/.prettierrc.json、根 .editorconfig、web/package.json、package-lock.json |
| 2 | `6ebd40a` | 前端教室类型/预约状态字典收敛为 `utils/dict.js`（10 个 .vue 移除本地函数） | 新增 web/src/utils/dict.js；改 10 个 .vue |
| 3 | `9f4b07d` | 后端分页/教室存在性校验收敛（PageValidator/ClassroomValidator）+ 删死代码 JwtUtil.parseToken + 修 2 处失实注释 | 新增 common/PageValidator.java、ClassroomValidator.java；改 4 个 ServiceImpl + JwtUtil/AuthInterceptor/AgnesClient |
| 4 | `7edef32` | 6 处裸 `static ObjectMapper` 统一为 Spring `@Resource` 注入；AI 类型文案收敛为 AiConstants | 改 AuthInterceptor/AgnesClient + 4 个 AI ServiceImpl |
| 5 | `7d0fecf` | ReservationConverter 外移（转换逻辑从 ServiceImpl 迁出）+ EasyExcel 版本号收敛 `${easyexcel.version}` | 新增 service/converter/ReservationConverter.java；改 ReservationServiceImpl、ReservationServiceImplTest、pom.xml |
| 6 | `c7fddf8` | 鉴权与参数校验 MockMvc 冒烟测试（401/403/分页 400） | 新增 controller/AuthAndValidationSmokeTest.java |

## 二、验证结果

### 1. 后端编译
- `mvn compile`：通过（6 笔期间多次编译全绿）

### 2. 后端单元/冒烟测试（`mvn test`，全量 29 条）
| 测试类 | 结果 |
|--------|------|
| RateLimiterTest | 4/4 通过 |
| ConstantsWindowConsistencyTest | 3/3 通过 |
| JacksonDateSerializationTest | 3/3 通过 |
| **AuthAndValidationSmokeTest（本轮新增）** | **6/6 通过** |
| ClassroomServiceImplTest | 2/2 通过 |
| FavoriteServiceImplTest | 2/2 通过 |
| ReservationServiceImplTest | 6/6 通过 |
| UserServiceImplLoginLockTest | 5/5 通过 |
| **合计** | **31/31，Failures 0，Errors 0，BUILD SUCCESS** |

### 3. 前端构建与 Lint
- `npm run build`：通过（vite build，6.70s）
- `npm run lint`：68 errors / 0 warnings —— 与第 1 笔建立基线一致（本轮仅记录基线，不 auto-fix、不加 --max-warnings，符合方案口径）

### 4. 端到端回归（真实启动后端 :8080 + Docker MySQL/Redis，HTTP 断言）
| # | 场景 | 期望 | 实际 | 结果 |
|---|------|------|------|------|
| T1 | 无 token 访问 /api/user/info | 401 + code=401 | 401 + code=401 "未登录或登录已过期" | ✅ |
| T2 | admin/admin123 登录 | 200 + token | 200 + token（163 字符） | ✅ |
| T3 | admin GET /api/classroom/manage?page=0 | 400 "页码必须大于等于 1" | 200 + code=400 + 文案逐字匹配 | ✅ |
| T4 | admin page=1&size=501 | 400 "每页条数必须在 1-500 之间" | 200 + code=400 + 文案逐字匹配 | ✅ |
| T5 | admin 正常分页 | 200 + records | 200 + records=10 | ✅ |
| T6 | 学生 token 访问管理接口 | 403 + code=403 | 403 + code=403 "无权限访问" | ✅ |
| T7 | 学生 GET /api/reservation/mine | 200 + records | 200 + records（ReservationConverter 链路） | ✅ |

## 三、偏差与说明

1. **无偏差项**：6 笔全部按方案落地，无"不适合抽所以没抽"的区块。
2. 首轮 lint 基线 68 errors 未清零属方案既定口径（工具链引入轮只建基线），后续轮收敛建议见回报第 5 段。
3. 分页/教室校验消重未改变任何用户可见文案（4 处分页、9 处"教室不存在"原文案逐字保留）。
4. 第 6 笔冒烟测试账号：种子学生为 zhangsan/lisi/wangwu/zhaoliu（无 student01），已按 init_db.sql 实况取 zhangsan。

## 四、独立核验响应（2026-09-18 第三方核验报告 4 项发现 → 4 笔修复 + 1 项环境记录）

### 4.1 前端字典 15 处行为差异（应修项，已修复）
- **发现**：`dict.js` 用 `Number()` 归一比旧"对象键索引"更宽松 —— `Number(null)===0`、`Number('')===0`、`Number(true)===1`，`null`/`''`/布尔/带空白数字串静默命中合法值（statusText(null) 由"未知"变"待审核"等）。
- **独立复算**：Node 加载新旧实现做 24 样本 × 3 函数 = 72 次比对，实测 **17 处差异**（核验方 15 处，样本集差异，方向一致；额外捕获 `'1.0'`、`[]` 两组）。
- **修复**：三处归一改为双端 String 比较 `String(x.value) === String(v)`，复算 **0 差异**，与旧实现完全等价。
- **提交**：`d8e73d4` fix(web)。

### 4.2 MockMvc 4 条 ≠ 提示词 5 条（覆盖缺口，已补齐）
- **发现**：缺"无效 Token → 401"与"正常路径 200 + records"（后者是提示词点名三类覆盖之一）。
- **修复**：补 2 条（无效 Token 401、学生 /api/classroom/list 200 + records 存在），现 6 条覆盖提示词全部 5 类。
- **提交**：`49eef29` test(server)；单测 25 + 6 = **31 条全绿**。

### 4.3 parseDateOrNull 未外移 + converter 路径偏差（已修复）
- **发现**：提示词外移清单含 `parseDateOrNull:504`，实际未外移；新类落在 `service/converter/`（提示词写 `service/impl/`）。
- **修复**：`parseDateOrNull` 外移至 ReservationConverter（方法体逐字，仅 private→public）；类按提示词路径 `git mv` 归位 `service/impl/`，ServiceImpl 同包移除 import，4 处调用改 `reservationConverter.parseDateOrNull(...)`。
- **提交**：`cc42edb` refactor(server)。

### 4.4 回归脚本未运行（强制项，已补跑 + 修复脚本时刻依赖）
- **发现**：`docs/e2e/regression-r1r5.py` 本轮未运行；核验方补跑 15/22，7 项失败全部源于脚本硬编码"今天 10:00-12:00"的隐性时刻前提（现在 19:17，被"预约开始时间必须晚于当前时间"拒绝），**非产品回归**。
- **修复**：日期全部运行期动态化（新预约用明日、R4/R2 用一周后、本人种子预约日期从库动态取），并修正脏标记口径（排除结果 md 自身，规避 Windows 中文 pathspec 编码问题）。
- **补跑**：`1f34c35` + `d77e527` + `1ebd39b` 后，clean HEAD 上复跑 **22/22 通过**，结果 md 被测版本 `1ebd39b` **无 +dirty**（`60b8bff` 归档）。
- **提交**：`1f34c35`（日期动态化）、`d77e527`（脏标记口径）、`1ebd39b`（字符串过滤）、`60b8bff`（结果归档）。

### 4.5 人工/UI 回归（已由真实浏览器代做完成：A 8/8 + B PASS）
- **方式**：headless Chromium + Playwright（复用本机 ms-playwright chromium，未下载浏览器）实跑，非模拟。
- **A 部分 8 页面全部通过**：教室列表/详情/我的预约/日历总览/个人中心/教室管理/预约审核/预约记录；教室类型覆盖 `["普通教室","实验室","机房"]`、预约状态覆盖 `["已通过","待审核","已驳回","已取消"]`、出现"未知"= 0、浏览器控制台错误 = 0。管理端类型筛选下拉与新增弹窗下拉均 3 类、选中回显正常（普通教室/实验室/机房逐一切换）。
- **B 部分 AI 回填 PASS（关键项）**：输入"我想要一个机房，明天下午两小时"→ 解析日期 = 2026-09-19（"明天"推算正确）、**教室类型下拉值 = "机房"**（中文字符串 value 与选项匹配）、下一步候选 3 间 A301/B201/C301 全部为机房（typeValue 中文→数字筛选生效）；已取消未提交、数据零写入。
- **顺带验证**：AI 关闭时「AI 快速预约」入口按钮数=0、推荐卡数=0（符合"关闭即隐藏"设计）。
- **证据**：17 张截图 `docs/e2e/ui-*.png`（.png 已 gitignore，不入库）；报告 `outputs/reservation-system-人工回归代做结果.html`。
- **遗留**：浏览器级 UI 已闭环；剩余"视觉呈现"层面的一个既有缺陷见 4.7。

### 4.6 环境记录：SERVER__PORT 覆盖 server.port（本项目首次记录）
- 宿主注入 `SERVER__PORT=65459`，经工具环境启动后端必失败（`Port 65459 was already in use`），与业务代码无关。
- 规避：启动时显式 `--server.port=8080`（或 `env -u SERVER__PORT`）。本轮所有后端启动均按此规避。

### 4.7 新发现：AI 解析表单「教室类型」下拉塌成 44px（既有缺陷，非本轮引入，建议下一轮修复）
- **症状**：AI 快速预约解析完成后，「教室类型」下拉只有 44px 宽——解析出的「机房」在 DOM 中值正确（4.5 已证），但**视觉上看不见**。
- **根因**（浏览器实测）：该表单 `.el-form-item` 全部渲染为 `inline-flex` 按内容收缩（教室类型项父级 134px），而 `el-select` 是唯一完全依赖 `width:100%` 的控件 → 塌成 44px。对照：管理端新增弹窗同类下拉 398px（其表单项为 `flex`），正常。
- **非本轮引入的依据**：本轮对该组件改动仅 `<el-option>` 两行与 `TYPE_MAP→typeValue`，**未触碰 `<style>` 块**；且 el-option 渲染在 teleport 的 popper 中，不参与 select 自身布局。
- **修法（任选其一，不影响功能）**：① 该下拉 `style="width:220px"` 固定宽度（最小改动）；② `.parse-form :deep(.el-form-item){display:flex}` 恢复块级布局（治本）。
- **待确认**：是否本轮修复（涉及 style 改动，超出重构轮范围，建议下一轮 UI 修复带上或经负责人确认后即改）。
