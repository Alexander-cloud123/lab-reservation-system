# 前端 AI 痕迹体检报告

- 项目：高校实验室预约管理系统（reservation-system）
- 审计范围：`web/src` 下全部页面与公共组件（15 个视图 + 3 个 AI 组件 + 全局样式，共 19 个文件）
- 审计依据：`.agents/skills/avoid-ai-design/SKILL.md` + `references/ai-tells-catalog.md`（detect 模式）
- 审计日期：2026-09-12
- 标记说明：`[code]` = 源码可确认；`[infer]` = 需渲染像素确认（本次未渲染，标注为推断、低置信）

---

## 一、审计范围清单

| 分组 | 文件 |
|------|------|
| 全局样式 | `web/src/assets/main.css` |
| 布局 | `StudentLayout.vue`、`AdminHome.vue` |
| 学生端 | `ClassroomList.vue`、`ClassroomDetail.vue`、`CalendarOverview.vue`、`MyReservation.vue`、`Profile.vue` |
| 管理端 | `AdminWelcome.vue`、`Dashboard.vue`、`AuditManage.vue`、`ClassroomManage.vue`、`ReservationRecord.vue`、`UserManage.vue` |
| 公共 | `Login.vue`、`Register.vue` |
| AI 组件 | `AiAssistant.vue`、`AiQuickReserve.vue`、`AiRecommendCard.vue` |

---

## 二、P0 级问题（外行一眼识别为 AI 生成）

### P0-1 蓝色对角渐变作为全局主视觉 `[code]`（C1）
135° 蓝-浅蓝渐变 `linear-gradient(135deg, #2563eb 0%, #4f8dff 100%)` 被定义为全局 token（`--brand-gradient`），铺满所有主视觉：
- `main.css:17`（token 定义）、`main.css:176`（主按钮背景）
- `StudentLayout.vue:156`（logo 徽标）、`:239`（用户头像）
- `AdminHome.vue:162`（logo 徽标）、`:205`（头像橙色渐变）
- `AiAssistant.vue:107`（悬浮球）、`:160`（用户聊天气泡）
- `AiRecommendCard.vue:95`（AI 图标块）、`:137`（排名圆标）
- `ClassroomDetail.vue:601`（预约时段色块）
- `CalendarOverview.vue:468-489`（日历事件四态渐变）
- `Dashboard.vue:162`（柱状图渐变）、`:211`（面积渐变）

**为什么像 AI**：这是 AI 生成界面的"名片级"默认：135° 对角渐变 + 蓝系未绑定品牌的选择。

### P0-2 登录/注册页超大渐变品牌面板 `[code]`（C1）
`Login.vue:138-141` 与 `Register.vue:147-150`：150° 三色蓝渐变（`#1e40af → #2563eb → #3b82f6`）+ 双 radial 光晕 + 装饰圆环。默认 SaaS 登录页模板，未做任何场景决策。

### P0-3 渐变文字 `[code]`（C6）
`main.css:310-315` `.grad-text` 使用 `background-clip: text` 渐变填充标题文字。削弱对比度且为 2024 时代默认装饰。

### P0-4 Inter 单字体、无字体配对 `[code]`（T1）
`main.css:111-112`、`:126-128` 全局唯一字体 `'Inter', ...`。中文场景下 Inter 不覆盖汉字、整体回退系统字体，等于"没有做过排版决策"。无显示字族、无字号层级体系。

---

## 三、P1 级问题（设计师/开发者可识别）

### P1-1 彩色装饰条 `[code]`（K4）
"彩色/渐变左侧条"是 AI 生成设计的最可靠信号之一：
- `.page-title::before` 渐变竖条：`main.css:298-307`、`CalendarOverview.vue:360-369`、`Dashboard.vue:302-311`
- `.slots-title::before`：`ClassroomDetail.vue:560-569`
- `.section-title::before`：`Profile.vue:506-515`、`AdminWelcome.vue:243-252`
- `.room-card::before` 顶部渐变条（hover 显现）：`ClassroomList.vue:346-355`
- 菜单激活渐变左条：`AdminHome.vue:266-276`

### P1-2 统一大圆角 + 统一轻阴影，无层级 `[code]`（K2/K8）
所有卡片一律 `border-radius: 16px` + 同一套 `shadow-card`（`main.css:41-44,152-166`），圆角/投影没有表达层级差异，读作模板。

### P1-3 调色板失控：散点色未进 token `[code]`（C4）
- 紫色 `#7c3aed`：`Profile.vue:460-461`、`AdminWelcome.vue:198-201`、`Dashboard.vue:226`（饼图色板含紫）
- 青色 `#0891b2`：`AdminWelcome.vue:202-205`
- 橙色 `#ea580c`：`AdminHome.vue:181-182`
多个"同权重"色彩无主次、无主导色，属典型 AI 均匀铺色。

### P1-4 玻璃拟态滥用 `[code]`（K3）
- 顶栏 `rgba(255,255,255,0.92) + backdrop-filter: blur(8px)`：`StudentLayout.vue:137-138`、`AdminHome.vue:143-144`
- 登录品牌 logo `backdrop-filter: blur(6px)`：`Login.vue:177`、`Register.vue:184`
玻璃叠加玻璃，且与扁平卡片体系矛盾。

### P1-5 交互状态：浮起 + 彩色描边 + 辉光阴影 `[code]`（K7/C5）
- hover 一律 `translateY(-3px/-4px)` 浮起：`.stat-card`（`Profile.vue:434`）、`.quick-card`（`AdminWelcome.vue:171`）、`.fav-item`（`Profile.vue:539`）、`.room-pick-item`（`AiQuickReserve.vue:390`）
- hover 彩色描边 `#dbe6fb` / `#bcd2f9`
- 彩色辉光阴影：`--shadow-hover` 蓝色调（`main.css:44`）、logo/头像/AI 标 `box-shadow: rgba(37,99,235,0.3~0.5)`（`StudentLayout.vue:161`、`AdminHome.vue:167`、`AiAssistant.vue:109`、`AiRecommendCard.vue:100,144`）、状态圆点辉光（`ClassroomList.vue:425-433`、`Profile.vue:637`）

### P1-6 箭头贴 CTA `[code]`（CP3）
- `ClassroomList.vue:104`「查看详情并预约」按钮内 ArrowRight 图标
- `AdminWelcome.vue:27,35,43,51,59` 快捷卡片统一 ArrowRight
- `ClassroomList.vue:497-500` `.btn-arrow`

### P1-7 表情符号入 UI `[code]`（I2）
`AdminWelcome.vue:6` 问候语 `你好，xxx 👋`。AI 用 emoji 充当装饰的懒替代。

### P1-8 AI 图标统一"魔法棒" `[code]`（I3）
`AiQuickReserve.vue:5`、`AiRecommendCard.vue:6` 均用 `MagicStick`。"魔法棒/AI" 组合为 2024-26 签名式样（Sparkles 的 EP 版本）。受"禁止新增依赖"约束保留该图标，但不再叠加渐变强调。

---

## 四、P2 级问题（工艺与打磨）

| 编号 | 问题 | 位置 |
|------|------|------|
| P2-1 | 全角微标签 `letter-spacing: 1px`（T5） | `StudentLayout.vue:178`、`AdminHome.vue:183` |
| P2-2 | 中文按钮字距 `letter-spacing: 4px` | `Login.vue:275`、`Register.vue:264` |
| P2-3 | `'A · B · C'` 中缀点元信息串 | `Profile.vue:43`（fav-meta）、`AiRecommendCard.vue:26`（room-meta）、`AiQuickReserve.vue:72` |
| P2-4 | 分散微交互无编排（多处 hover 位移/弹跳，M2） | 上述 P1-5 各 hover |
| P2-5 | `transition: all` 未列属性 | `ClassroomList.vue:342`、`Profile.vue:534`、`AdminWelcome.vue:169`、`AiQuickReserve.vue:383`、`AiRecommendCard.vue:123` |
| P2-6 | 时间/数字列未启用 `tabular-nums` | 各表格时间列、统计卡 |

---

## 五、合规隐患（web-design-guidelines 预检）

- 悬浮球为纯图标按钮，无 `aria-label`（`AiAssistant.vue:5`）
- 全局无 `:focus-visible` 兜底，键盘焦点不可见（`main.css`）
- 无 `prefers-reduced-motion` 降级（`main.css`）
- `transition: all` 违反"仅动画 transform/opacity"（见 P2-5）
- 时间/数字列建议 `font-variant-numeric: tabular-nums`

---

## 六、设计方向（单一方向，贯穿全部整改）

**方向名：「排课表 Timetable」** —— 设计语言直接取材于产品本体（教室 × 时段 × 状态）：

| 维度 | 决策 |
|------|------|
| 字体 | 中文系统字体栈 `HarmonyOS Sans SC / PingFang SC / Microsoft YaHei`；不引入 WebFont（无新依赖、国内可达性差）；层级靠字重+字号，数字/时间列启用 `tabular-nums` |
| 色彩 | 60/30/10：60% 冷纸白 `#f3f5f7` + 墨蓝黑 `#1c2733`；30% 深普鲁士蓝 `#1e6091`（唯一主操作色）；10% 语义状态色（通过 `#2e8b57` / 待审 `#b45309` / 驳回 `#b3261e` / 取消 `#64748b`）。消灭渐变、紫色、青色、橙色散点 |
| 布局 | 信息密度优先：扁平面板（1px 边框、无重阴影）、圆角分级（容器 10 / 控件 8 / 徽标 6）、左对齐、规则线分隔；hover 只加深背景/边框，删除浮起与彩色描边 |
| 动效 | 唯一动效 = 状态响应（focus/hover 120-160ms）；`prefers-reduced-motion` 时全部关闭；删除装饰性位移 |
| 签名细节 | 状态即"印章"：状态标签实心语义色块 + 白字；时间轴/日历事件扁平纯色块；页面标题纯墨色大字重，去渐变装饰条；统计数字大号 tabular 数字 |

**备选方向**：①「文献卡片」暖纸+衬线（偏内容展示，与管理系统气质不符）；②「仪表盘深色」暗色高密度（与现有浅色体系冲突过大，改动面超限）。选定方向以最小改动面达成最大去 AI 味效果。

---

> 本报告为整改前基线。整改完成后的复检结果与前后对照见文末「九、复检结果与整改前后对照」。

---

## 七、整改动作清单（设计方向与 token 落地）

**统一方向：「排课表 Timetable」**（单一承诺色 + 冷纸底 + 墨蓝黑文本，圆角分级，无渐变/辉光/玻璃，动效仅响应状态）。

| 文件 | 整改动作 |
|------|----------|
| `assets/main.css` | 重写全部 token：主色 #1e6091（替代 #2563eb 默认蓝与渐变）、语义色进 token（绿/琥珀/红/灰）、冷纸 #f3f5f7、圆角分级 10/8/6；删除 --brand-gradient、.grad-text、辉光阴影；Element Plus 主题变量全量重算（primary/success/warning/danger/info 的 light-3~9 与 dark-2）；按钮扁平化 + hover/active 状态；卡片 1px 边框轻投影；聚焦环全局兜底；tabular-nums；prefers-reduced-motion |
| `StudentLayout.vue` | 顶栏玻璃拟态改纯白实底 + 底边框；logo 徽标渐变改扁平主色；用户头像渐变改扁平主色；角色章间距 1px→0.5px；激活态下划线改扁平纯色 |
| `AdminHome.vue` | 同上；橙色渐变头像改扁平主色；橙色角色章改 token 色；侧边菜单激活渐变左条改扁平浅底 + 主色字 |
| `ClassroomList.vue` | 删除卡片顶部渐变条（::before）；hover 浮起/彩色描边改边框加深；按钮去 ArrowRight 图标；状态点去辉光；transition 显式化 |
| `ClassroomDetail.vue` | 删除时段标题渐变条；预约时段红渐变改扁平纯色（去辉光）；两个 round 按钮改 8px 圆角 |
| `CalendarOverview.vue` | 删除页面标题渐变条；日历事件四态渐变改扁平语义纯色 |
| `Profile.vue` | 统计卡 4 色图标（紫/青/橙/蓝）统一为中性主色底；hover 浮起/辉光删除；章节标题渐变条删除；收藏项 hover 改背景加深；未读消息底色硬编码 #dbeafe 改 token；消息点去辉光；transition 显式化 |
| `AdminWelcome.vue` | 欢迎横幅渐变+径向光晕改扁平深蓝面板（去 👋 emoji）；快捷卡 5 色图标统一中性；去 ArrowRight 图标；hover 浮起改边框加深；章节渐变条删除 |
| `Dashboard.vue` | 删除页面标题渐变条；柱状图渐变改扁平主色；面积渐变改同色 10% 透明；饼图色板去紫/青/橙，换承诺色板（主色/绿/琥珀/红/墨青/灰/棕） |
| `Login.vue` / `Register.vue` | 品牌面板 150° 三色渐变 + 双光晕改扁平深蓝面板（保留规则圆环母题）；玻璃 logo 改半透明白底；登录按钮字距 4px→2px；登录输入框补 aria-label |
| `AiAssistant.vue` | 悬浮球渐变+辉光改扁平主色（hover 加深）；用户气泡渐变改扁平主色；补 aria-label（纯图标按钮可访问性） |
| `AiQuickReserve.vue` | hover 浮起/彩色描边改边框+背景加深；transition: all 显式化 |
| `AiRecommendCard.vue` | 卡片径向渐变底改纯白；AI 图标渐变+辉光改扁平主色；排名圆渐变+辉光改浅底主色字；hover 位移改背景加深；transition 显式化 |
| 其余页面（AuditManage/ClassroomManage/ReservationRecord/UserManage/MyReservation） | 无独立 AI 痕迹，仅受全局主题统一影响，未改动 |

## 八、合规整改（web-design-guidelines 抽查）

- 纯图标按钮补 `aria-label`（AI 悬浮球）
- 全局 `:focus-visible` 兜底（链接/按钮/可聚焦元素 2px 主色描边）
- 新增 `prefers-reduced-motion` 降级（全局关闭动画与过渡）
- `transition: all` 全部显式化为具体属性（grep 验证清零）
- 表格/分页/统计值启用 `font-variant-numeric: tabular-nums`
- 登录表单无 label 输入框补 `aria-label`
- 表单加载态：登录/注册/提交按钮均有 loading 态；错误信息由统一拦截器提示

## 九、复检结果与整改前后对照

复检方式：对 catalog 全部模式做全量正则扫描 + `npm run build` 编译验证 + 浏览器渲染验证（登录页、注册页实拍截图确认扁平主色面板与表单布局正常渲染）。

| 严重级 | 整改前 | 整改后 |
|--------|--------|--------|
| P0 | 4 类（135° 渐变主视觉、登录大渐变面板、渐变文字、Inter 单字体） | **0** |
| P1 | 8 类（渐变装饰条、统一大圆角+轻阴影、调色板失控、玻璃拟态、浮起+辉光 hover、箭头贴 CTA、emoji、魔法棒强调） | **0** |
| P2 | 6 类（全角微标签字距、按钮 4px 字距、'·' 元信息串、分散微交互、transition: all、无 tabular-nums） | **0**（'·' 元信息串为真实业务数据保留，判定为合理） |
| 合规 | 4 项（缺 aria-label、无 focus-visible、无 reduced-motion、transition: all） | 全部修复 |

> 说明：catalog 的「'A · B · C' 中缀点元信息串」在 Profile 收藏卡 / AI 推荐卡中为真实业务字段分隔（楼栋-房号 · 类型 · 容量），非模板装饰，按语境判定保留；P2-6 tabular-nums 已在全局统一启用。

## 十、验证方式、覆盖范围与缺口

- **编译验证**：`npm run build` 通过（6.3s，无错误、无警告），覆盖全部页面与组件。
- **渲染验证**：vite preview + 浏览器实拍登录页、注册页（公开页面），确认扁平深蓝品牌面板、白色表单卡、字体与间距正常。
- **代码级复检**：对 catalog 全部可检索模式做正则扫描，P0/P1/P2 清零（唯一残留为 Dashboard 中 `minInterval` 字段的 "Inter" 子串误匹配，非字体引用）。
- **缺口**：学生端/管理端内部页面需登录态与后端数据，未做像素级实拍；其改动均为同一主题 + 同类 scoped 规则的等价变换，风险集中在编译与选择器命中，已由构建与全局扫描覆盖。建议项目负责人启动前后端后人工过一遍核心页面。
