# 更新日志

本项目采用 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录变更，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

当前交付版本为 `1.2.0`，覆盖 R1–R17 共 17 轮迭代。逐轮的完整交付文档（任务说明 / 接口清单 / 测试报告 / 启动说明）见 `docs/iterations/`。

## [未发布]

## [1.2.0] - 2026-11-26

### 新增
- **URL 深链、语义结构与图表文本替代**：完成前端设计原则第二批建议。六个列表页把筛选、分页与高级筛选展开状态写入 URL，看板同步快捷/自定义时间区间，预约日历同步月/周视图、日期与教室；默认参数省略、非法参数规范化，用户操作写历史、初始化/纠错用 replace，仅展开变化不请求，快速前进/后退丢弃旧响应。学生教室列表继续兼容按用户保存的旧筛选记忆。学生与管理端楼栋筛选改用独立全量字典（每页 500，按 total 续取），不再只列当前页；学生页补齐稳定 h1，学生/管理布局增加「跳至主内容」及 path 切换后的主区焦点管理，AI 页移除嵌套 main，欢迎标识改为强制颜色模式可读的实色。看板三个 canvas 增加中文标签与默认折叠的原生数据表，和图表共享最近成功数据。新增 `44-url-state.spec.js`、`45-semantic-structure.spec.js`，并扩展看板数据表与楼栋字典回归。
- **前端设计原则审计与交互修正**：依据尼尔森启发式、WCAG 2.2 与 Vercel Web Interface Guidelines，对全站做代码级审计并保持晨光玻璃视觉不变。窄屏不再丢失核心导航：学生菜单保留文字并横向滚动，管理端与 AI 历史改用可关闭抽屉（Esc / 焦点循环 / 焦点返回）；普通弹窗限制视口宽高并隔离滚动，教室详情窄屏描述改一列。六个列表页区分加载失败与成功空态，支持首载重试、刷新失败保留旧数据；管理首页未知指标显示 `—`，看板保留旧图并标注未更新。高级筛选收起后显示生效条件且可单项清除；教室列表、我的预约、用户、教室资源、审核列表切换每页条数都先回第 1 页，避免旧请求覆盖新页长结果；移动抽屉跨断点收起时焦点回到桌面可见控件；审核仅允许勾选待审核记录；个人中心取消编辑不留旧值，密码弹窗关闭即清空；日历增加键盘可达的「快速预约」按钮，管理空态重置改真实按钮。新增 `43-design-principles.spec.js` 并扩展相关 E2E，规范同步至 `docs/界面设计规范.md` 第 8 节。
- **留白处补纸面纹理（颗粒 + 点阵）**：此前页面留白只有一层浅蓝极光，大片负空间偏空。第一版试过「排课表行线」（`repeating-linear-gradient` 1px / 96px），实测不精致——横线有方向，遇到卡片矩形边缘必然被硬切断，接缝处读作「表格线被卡片盖住」，是表格感而非纸感。改为两层**各向同性**的纸面纹理：① 180px 平铺的低透明度 SVG 噪声（`feTurbulence` fractalNoise + `stitchTiles` 保证无缝，`opacity .05`）提供纸面细颗粒；② 32px 极淡点阵（`--paper-dot: rgba(11,42,91,.09)`，点半径 1.2px）提供可读的「图纸」秩序。两层只画在 `#app` 这一层，内容面（卡片/表格/弹窗）自带不透明底，纹理**只在留白负空间可见**，不进入任何内容区。为让底纹透出，学生端滚动容器 `.student-main` 由 `--page` 实色改 `transparent`（管理端 `.admin-main` 本就是透明）。`docs/界面设计规范.md` 材质表「留白底纹」行同步改写，并写明「不使用等距横线/竖线」「禁止给内容面加纹理」。
- **交互设计优化（渐进式披露与可负担性）**：按交互设计原则补齐四处——① 教室列表卡片由「整卡可点但键盘不可达」改为整卡 v-clickable（role=button + tabindex=0 + Enter/空格激活 + 简洁 aria-label），移除内部冗余的「查看详情并预约」按钮（改为非交互视觉占位 .card-cta），消除嵌套交互与双 Tab 停靠，与个人中心既有模式对齐；② 卡片渐进式披露：设备说明与备注描述（详情页已含）移出卡片默认态，列表只保留「选择信号」（名称/门牌/状态/类型/楼栋/容量/今日剩余/某日占用），降低扫读负担；③ 教室详情时段刻度尺首屏加常驻提示「点击空白时段，直接带入预约」，点空档回填预约不再只在 hover 时显形；④ 筛选条「日期」改 label「占用日期」，澄清它是「标注某日占用」而非「收窄结果」。E2E 同步（11-classroom-list 卡片字段断言、41-a11y 新增整卡键盘可达用例），并让此前因缺 tabindex 一直未生效的 .room-card:focus-visible 焦点样式真正生效。

- **渐进式披露第二轮：AI 对话页建议折叠 + 列表页「更多筛选」**：延续交互设计原则补齐两处渐进式披露——① AI 对话页欢迎区建议默认只显示 3 条（上下文建议 + 2 条基础建议），点「更多建议」展开全部、「收起建议」收回，避免一屏塞满全部建议；② 五个列表页（学生教室列表、管理端教室/用户/审核/预约记录）的次级筛选条件折叠进「更多筛选」，默认只露主关键词输入，点「更多筛选」展开楼栋/类型/状态/日期范围等次级条件、「收起筛选」收回，降低查询区首屏扫读负担。E2E 新增 `openMoreFilters` 幂等 helper，11/21/22/24 spec 先展开再操作次级筛选。

- **视觉重做为晨光玻璃体系（Aurora Glass，R17）**：前三版视觉的共同问题是不断做减法，最后落到「白底 + 同款圆角卡片 + 同一枚灰阴影」的 SaaS 模板——这正是 2026 年市场调研点名的 AI 默认外观。本轮按 Muzli《Web Design Trends 2026》的方向（「Goodbye Beige, Hello Bold」：大胆渐变、饱和色回潮、micro-delight）先出三套方向样张（晨光玻璃 / 大胆色块海报 / 深色沉浸）供选择，定为「晨光玻璃」后全站重做：主视觉为夜蓝 `#0b2a5b` → 天蓝 `#1d6fe0` → 薄荷青 `#22c3b3` 的渐变，`body` 用四层极光背景（左上 30% 天蓝、右上 26% 薄荷、底部 16% 晨光橙）把渐变透出来；内容面改半透明玻璃（白面内渐变 + 1px 白色半透明边 + 顶部内高光 + 近/中/远三段阴影），顶栏、侧栏、AI 输入 dock 与弹窗用 `backdrop-filter`；字号从 6 档扩到 7 档并新增 `--text-display 44px` 与 `--text-display-xl 56px`（登录门牌、时刻带主时钟、欢迎页首格数字），标题与正文对比 ≥3×，数字与门牌统一等宽 `--font-num`。落地：登录/注册改「渐变左栏 + 玻璃登录面板 + 56px 门牌」；学生首页顶部满铺渐变时刻带 + 玻璃教室卡（左 5px 类型渐变条，标题去掉与门牌重复的编号）；详情页玻璃信息面/时段面 + 底部 sticky 玻璃动作条；我的预约「今天」浅渐变条；个人中心全部玻璃面 + 头像渐变；管理端顶栏玻璃 + 浮起玻璃侧栏（选中态渐变底白字）；欢迎页 compact 时刻带 + 不等宽 KPI bento（1.6fr : 1 : 1 : 1）；看板三图玻璃卡，柱状图改垂直渐变 brand→aqua 且 X 轴只放门牌号（长名不再互相压住）、折线 3px + 浅面积、饼图分块留白；AI 推荐区/方案卡/思考面板/输入 dock 全部玻璃化。[`docs/界面设计规范.md`](docs/界面设计规范.md) 重写为「晨光玻璃」版：token 表、材质与背景层级、动效规范，以及**已消除的 AI 默认外观对照表**（SaaS 卡片套装 / 紫蓝渐变白底 / 全大写 eyebrow / 中间点 / 按钮粘箭头 / 字号平板化 / 处处圆角阴影 / 常驻动画）。


- **墨蓝时刻带与克制动效（R16）**：在墨蓝门牌体系上补一层「只属于教室预约」的签名与动效语法，解决「精致但静止、仍有模板感」的问题。签名是「时刻带」——墨蓝满铺 + 96px 极淡网格 + 52px 等宽主时钟 + 0–24h 刻度尺（08:00–22:00 为可约窗口，闭馆段压暗）+ 暖色指针挂载时从 08:00 扫到此刻一次；全站只满铺两处（学生首页顶部、登录页左栏），摘要句取真实数据（今天还有几间空着 / 今天几个预约在等审核）。学生端教室详情页把占用时间轴换成 `TimeRuler`（保留 `timeline-slot` 类名以兼容回归用例），空档可点，只回填预约弹窗、不代提交；我的预约页顶部加「今天」刻度条，取消成功行先淡出 180ms 再刷新。动效只两种用途：每页一次入场编排（`.reveal` / `.stagger` 按 22ms 错开、上限 12 项）与回应用户动作（路由 `.page-swap`、审核与取消 `.is-leaving`、状态 `.status-cell.is-updated`、按钮 `.press:active`、未读一次 `pill-flash`、AI 思考点仅在真实进行中状态脉冲）；`prefers-reduced-motion: reduce` 下时长与延迟全部压到 0.01ms。仪表盘三张图统一 320ms `cubicOut` 并按 0/80/160ms 依次入场，管理端欢迎页 KPI 用 `useCountUp` 从 0 数到目标值。新增 `web/e2e/specs/42-motion.spec.js` 2 条守住「默认有动效 / 减少动效时归零」两端；规范见 [docs/界面设计规范.md](docs/界面设计规范.md)（新增「时刻带与刻度尺」「动效规范」两节）。

- **对话携带页面上下文并在规划中优先当前教室（R15-3）**：在教室详情页点悬浮球进对话会带 `?roomId=<id>`，对话页顶栏下方显示「正在查看：A101 多媒体教室」标签（可点 × 移除；刷新按 URL 恢复，历史会话不存上下文）。请求新增结构化 `context: { classroomId }`（只收 id、不收自由文本，防 Prompt 注入）；后端 `AiChatContext` 查库校验教室存在且启用后，才在模型上下文注入「【用户当前查看】…（楼栋/编号/类型/容量）」并约定「这间/这个教室」指它；规划分支里这间教室只要满足容量/类型/时段就直接排第一，理由写「你正在看这间」，不满足则照常排序、不硬塞。新增单测 4 例（上下文注入、未知/停用教室静默忽略、偏好排前、不满足不硬塞）与 `web/e2e/specs/34-ai-context.spec.js` 2 条；[docs/AI对话界面.md](docs/AI对话界面.md) 增「页面上下文」，[docs/AI智能体设计.md](docs/AI智能体设计.md) 排序口径同步。
- **视觉重做为墨蓝门牌体系**：以「墨蓝门牌」为母题重做全站视觉——冷灰底（`--page #f5f6f8`）+ 白内容面（`--surface`）+ 墨蓝（`--brand #1b3f8f`）三级对比，加上墨蓝实底导视牌登录页、26px 描边门牌编号（`.code-block`）作为唯一记忆点。`web/src/assets/main.css` 整体重写：6 个命名色 + 语义色、字号收敛到 **6 档**（12/13/15/17/22/28）、圆角改为 12（容器）/ 8（控件）/ 6（小元素）/ 全圆（仅状态药丸）、8pt 间距网格、四档阴影按浮起高度递进；Element Plus 主色与色阶重定义、控件 36px、表格行高 52px 去斑马纹、控件内文字统一 13px（修掉 EP 默认 14px 破坏字阶的问题）；新增 `.page-container` / `.page-head` / `.surface` / `.section` / `.code-block` / `.status-pill` 等原语，旧的 `--brand-primary`、`--text-primary`、`--rule` 等 token 名保留为兼容别名。17 个页面 + 8 个 AI 组件全部按新体系重排：删掉装饰性图标（菜单图标、MagicStick、"设备："图标方块）、去掉 `·`/`→` 拼接元信息、AI 推荐区由「卡片 + 排名药丸 + 药丸按钮」改为「标题 + 排序行 + 文本按钮」，方案卡/推理块与全站同一套标点与圆角。规范见 [`docs/界面设计规范.md`](docs/界面设计规范.md)。
- **预约方案卡支持选择结束时间（R15-2）**：此前用户只说「明天下午两点」时，解析层拿不到 `endTime` 会把整句判失败、方案服务直接回澄清；现在结束时间由交互层兜底——`AiParseFallback` 先补上中文数字时段识别（「两点」「下午两点半」），方案服务按 1、2、3 小时三档估算，逐档独立过滤容量与已通过预约冲突，按「候选最多、并列优先 2 小时」选默认档并回填顶层字段，同时在 `plan` 帧新增 `timeOptions`（每档带 `availableCount / excludedCount / candidates`）、`endTimeGuessed`、`guessHours`，思考过程多一步「估算可选的结束时间」。前端 `AiPlanCard` 渲染时段切换按钮（`aria-pressed` + 空档不可点），切换只替换本地候选、不再发请求；「去预约」把所选档的结束时间写入确认页，用户仍可改、仍由人点提交。上限与预约表单一致（22:00），21:00 之后起无合法档位则澄清；估时说明单独一行，不假装用户说过。新增单测 5 例（三档 / 临近闭馆截断 / 无合法档位不查库 / 各档独立判冲突 / 编排含 `plan-time` 与估时文案）与 1 条 E2E（切换时段→候选与预填时段同步），设计说明同步 [docs/AI智能体设计.md](docs/AI智能体设计.md) 与 [docs/AI对话界面.md](docs/AI对话界面.md)。
- **对话内预约规划智能体（plan & confirm，R15-1）**：聊天助手从「只回答问题」扩展到「生成可执行方案」——说出「帮我约明天下午的机房，30 人」后，后端按真实数据解析需求、一次分页取出当日教室及其已通过时段、本地判重叠、按「类型匹配 → 常用楼栋 → 容量贴合 → 剩余时段」排序，取前 3 间候选，在 SSE 里新增 `plan` 帧（`meta` 之后、正文之前），前端渲染为 `AiPlanCard`：候选项带楼栋/编号/类型/容量与排序理由，点「去预约」打开既有 `AiQuickReserve` 确认弹窗并预填日期、时段、人数、用途，直接落到第 3 步，实时冲突校验后**仍由用户手动点「提交预约」**（AI 只读不写，符合《需求设计文档》§1.3）；无候选时只给澄清文案、不出按钮。意图判定由 `AiBookingIntent`（请求词 + 预约线索词 + 查询排除词）完成，「我有哪些预约」不会被误判成「帮我约」；规划分支不读结果缓存、不调 chat completion（省额度），解析失败自动降级规则解析。前端方案随对话历史持久化（`sanitizePlan` 只留展示字段，候选 ≤10），历史恢复后按钮仍可用但会重新判冲突。新增 `web/e2e/specs/33-ai-plan.spec.js` 3 条（方案卡渲染 / 去预约预填与实时判冲突 / 空方案无按钮），后端新增 `AiBookingIntentTest`、`AiBookingPlanServiceImplTest` 并扩展 `AiChatStreamServiceTest`（plan 帧顺序、不调上游、澄清与空候选），设计说明见 [docs/AI智能体设计.md](docs/AI智能体设计.md)。

- **AI 对话历史持久化与界面细化**：会话从「只在内存」改为浏览器本地持久化——`web/src/utils/aiChatStorage.js` 写入 `localStorage` 的 `reservation_ai_conversations:<userId>`，只存消息结构不存 HTML（恢复时重新渲染），上限 40 会话 / 每会话 60 条 / 单条 12000 字，序列化超 2MB 按活动时间淘汰，`QuotaExceededError` 再淘汰一次后静默跳过；`useAiChat` 增加 `init(userId)`、400ms 防抖落盘、发送结束立即落盘；刷新后会话、思考步骤与「已思考」计时恢复，`thinking` 态一律收敛为「已停止」、不补发请求；退出登录 `clearAuth()` 清空本地会话。界面同步细化：左栏增加搜索与「今天 / 昨天 / 更早」分组、hover 删除（二次确认），欢迎屏改竖排建议，正文 768px 居中，输入区 16px 圆角 + 34px 发送钮。新增 `web/e2e/specs/32-ai-history.spec.js` 4 条覆盖恢复 / 排序 / 删除 / 登出清理，说明见 [docs/AI对话界面.md](docs/AI对话界面.md)。

- **AI 对话交互补齐（重新生成 / 回到底部 / Esc 停止 / 焦点回位）**：`useAiChat` 新增 `regenerate()`——删掉最后一条回答、用同一提问重发，不重复追加用户气泡；`AiChatMessage` 增加「重新生成」按钮（仅最后一条回答、非生成中显示，失败态显示「重试」）与「回答已停止生成」提示。对话页新增「回到底部 ↓」浮层：上翻超过 160px 才出现，点击贴底并恢复跟随；程序化贴底临时切 `scroll-behavior: auto`，避免平滑动画首帧滚动事件被误判成「用户上翻」而中断跟随。Esc 与「停止」按钮等价，流式中按下立即中断；发送/重新生成后焦点回到输入框。新增 3 条 E2E（重新生成、回到底部、Esc 停止），设计说明补进 [docs/AI对话界面.md](docs/AI对话界面.md)。

- **AI 助手改为 DeepSeek 式全屏对话界面**：原来的 380px 抽屉换成 `/student/ai` 全屏页——左侧 248px 会话栏（新建 / 切换 / 删除，标题取首问前 18 字）、760px 居中正文、底部自增高输入框（Enter 发送 / Shift + Enter 换行 / 输入法组合态不拦截）、流式中发送键变「停止」。会话改存浏览器本地（见上一条）；`trace` 与气泡改为 `reactive()`，修复「面板永远停在正在思考」；页面只保留一个 `role="status"` 的 live region（播报最终回答纯文本），提示条与横幅不再抢读。既有 `30-ai` E2E 通过类名兼容层继续有效。设计说明见 [docs/AI对话界面.md](docs/AI对话界面.md)。

- **AI 助手思考过程面板（R14）**：`/api/ai/chat` 流式接口新增 `thinking` / `reasoning` 两个 SSE 事件。`thinking` 按真实执行轨迹下发（场景判断 → 检索预约记录 → 调用模型 → 校验回答，缓存命中 / 降级 / 超范围 / 关闭态各有独立步骤），每步带服务端真实耗时与 `running` / `done` / `failed` 状态；上游若返回 `reasoning_content`（含 `reasoning` 别名）则原样透传，**不伪造思维链、不并入正文**。前端新增 `AiThinkingPanel`：思考中三点脉冲 + 每 100ms 刷新计时，首个正文到达自动折叠为「已思考 X.X 秒」，可展开查看步骤时间线与推理原文（固定标注「模型推理 · AI 生成，仅供参考」）；降级 / 缓存命中 / 停止 / 失败各有明确文案，模型未返回推理时明确说明「以上为本次处理的实际步骤」，不编造内容。助手滚动改为仅在距底 ≤24px 时跟随。设计说明见 [docs/AI思考过程设计.md](docs/AI思考过程设计.md)。

### 修复 / 安全加固

- **v1.2.0 发布收口与依赖安全维护**：使用 npm 官方 registry 完成前端依赖审计，并在 dry-run 确认后仅应用四个无大版本变更的补丁：`markdown-it 14.3.3`、`follow-redirects 1.16.3`、`source-map-js 1.2.4`、`postcss-selector-parser 6.1.6`，未新增依赖或升级 Vue / Vite / ECharts / Element Plus / Spring Boot。剩余 advisory 按 SSR、`el-link`、开发服务器与实际图表输入逐项记录适用性，详见 [`docs/依赖安全审计.md`](docs/依赖安全审计.md)，不宣称 npm audit 已清零。同步补齐 Web CI 的 PR 触发、数据库迁移目录的 CI 触发路径、E2E 当前规模与报告保留期。

- **AI 默认口径调整为「容器演示默认开启」**：`docker-compose.yml` 的 api 环境默认值由 `AI_ENABLE=false` 改为 `true`，`.env.example` 同步为 `AI_ENABLE=true`，`database/init_db.sql` 种子 `ai_config.ai_enable` 改为 `true`——`docker compose up -d` 后登录即下发 `aiEnabled=true`，不再需要人工跑开关脚本。裸 `java -jar`、单元测试与 CI 仍由 `application.yml` 的 `${AI_ENABLE:false}` 与工作流显式 `AI_ENABLE=false` 保持关闭（零依赖、确定性不变）。未配置 `AGNES_API_KEY` 时两开关仍为开启，但所有 AI 回复自动降级为本地规则并在界面标注「AI 服务未配置密钥」，不阻断预约核心功能。存量数据卷（种子改动前创建、`ai_enable` 仍为 `false`）需跑一次 `pwsh server/scripts/ai-switch.ps1` 补齐；文档见 [docs/运行与配置.md](docs/运行与配置.md)。

- **会话撤销改为数据库版本号权威**：`sys_user` 新增 `token_version`（幂等迁移脚本 [`database/migrations/20261107_add_sys_user_token_version.sql`](database/migrations/20261107_add_sys_user_token_version.sql)）。JWT 签发时携带版本号，登出 / 改密 / 管理员重置 / 启用禁用都会原子自增，正常路径下旧 Token 下一次请求即 401——Redis 关闭、后端重启、多实例部署下同样成立（删除鉴权快照失败的异常路径见下方「文档」修正条目）。
- 移除 Redis 侧「本地撤销标记」兜底（`RedisCache.removeToken` 的「撤销未完全确认」异常语义），改为 `invalidateSession` 只做清理；鉴权状态缓存升级为 `status|tokenVersion`。
- Redis 启用但连接异常时鉴权 **fail closed（401）**，不再退化为仅 JWT 放行；`REDIS_ENABLE=false` 才是明确无状态模式（无单点会话，撤销仍由版本号保证）。
- 前端 401 处理尊重 `silent`：改密后调用登出（旧 Token 已被撤销）不再误报「登录已过期」或硬跳转。
- **无障碍缺陷修复（读屏器清单机器代理验收发现）**：AI 助手 live region 改为播报 Markdown 渲染后的纯文本（原先播报 `**`/列表序号，与可见气泡不一致）；登录页错误提示补 `aria-invalid` / `aria-describedby` 关联与 `role=alert`；补齐 Element Plus zh-cn 缺失的 `dialog.close` / `drawer.close` / `messagebox.close` 键，关闭按钮不再暴露 `el.dialog.close` 这类原始 i18n key；「我的预约」取消确认后触发按钮被移除会导致焦点悬空到 `body`，修复为确认成功后聚焦列表区域（`role=region` + `aria-label="我的预约列表"`，`13-my-reservation` 已加断言）。

- **上线前安全基线（Docker 部署）**：`docker-compose.yml` 的 api 固定 `SPRING_PROFILES_ACTIVE=prod`，`JWT_SECRET` 改为必填（缺失 / 为空 / 沿用开发默认值直接拒绝启动，已实测 `docker compose config` 退出 1）；容器关闭接口文档（`knife4j.enable=false` + `spring.web.resources.add-mappings=false`，`/doc.html` 不再返回文档页、`/v3/api-docs` 不再返回 schema，统一落到 `{"code":404}` 语义）与 MyBatis SQL 明细日志（`NoLoggingImpl`）；`web/nginx.conf` 增 `server_tokens off`、四条安全响应头（`/assets/` 与 `= /index.html` 各自补 `add_header`，避免继承被覆盖）、登录 / 注册 `limit_req`（10r/m，burst 9，第 11 次起 429）；CORS 白名单改由 `CORS_ALLOWED_ORIGINS` 注入，compose 默认收敛到本机 `8088` 与 Vite dev / E2E 的 `5173`。本机 `mvn spring-boot:run` 行为不变。
- 关闭静态资源映射后，未映射路径原先会被兜底异常处理器当成 500：GlobalExceptionHandler 新增 NoHandlerFoundException 处理，返回统一 {"code":404,"message":"接口不存在"}（/api/classroom 这类少写一段的路径不再报 500）。
- server/scripts/smoke.ps1 匿名访问用例从 /api/classroom 改到真实存在的 /api/classroom/list，避免依赖静态资源处理器兜出的 401 假阳性。

- **问答上下文补当前时间**：真实模型验收发现回答把 2026-11-16 说成「今天是 2026年11月14日」，相对日期整体偏移。`AiChatServiceImpl.buildContext()` 现于上下文首行下发 `【当前时间】yyyy-MM-dd HH:mm（Asia/Shanghai，星期X）`（固定东八区，格式常量 `CONTEXT_TIME_FORMATTER`），单测断言下发的用户消息包含该行与当天日期。

- **AI 开关变更后悬浮球不再"消失到底"**：前端此前只信任登录时落盘的 `reservation_ai_enabled`，开关后开时旧值 `false` 会让 `probeAiRecommend()` 直接短路（刷新无效，只能重新登录才恢复）。`web/src/utils/aiProbe.js` 改为始终向服务端探测（仅未登录时短路），`AiAssistant.vue` 探测成功后回写 store 与 localStorage，下次启动即为正确值。另新增 [`server/scripts/ai-switch.ps1`](server/scripts/ai-switch.ps1)（默认开、`-Off` 关：改 `ai_config.ai_enable`、清 `cache:ai:*`、等 5 秒开关缓存过期、校验登录接口下发的 `aiEnabled`），避免 `init_db.sql` 复位后忘记开开关导致演示时 AI 入口静默缺失。

- **修复结构化 SSE 空载荷终态（AI 入口在真实后端全隐藏）**：真实后端 `SseStructuredStreamSink.done()` 对空对象只写出 `event:done\ndata:`（没有 JSON 体），前端 `dispatchFrame` 把空 `data:` 当脏帧丢弃，`done` 永不落地 → `probeAiRecommend()` 的 Promise 一直悬空 → 推荐卡、悬浮球、AI 快速预约在**真机**上全部不出现（E2E 用 mock 的 `data:{}` 掩盖了该缺陷）。前端改为把「空载荷事件」按终态分发（`done` 回调收到 `null`），并同步 `web/e2e/helpers/ai.js` 的 mock 线格式为真实空 `data:`，新增回归用例「结构化流空载荷 done 帧仍是终态」。修复后实测推荐卡与悬浮球立即出现。

### 测试

- 后端 **347 项**（+28，2026-11-17 复核：surefire 汇总 43 类 / 347 例 / 0 失败、0 跳过；原记录的 347 含已删测试类残留虚增）：新增 `AiBookingIntentTest`（8 条意图正例 / 10 条查询类反例）、`AiBookingPlanServiceImplTest` 8 例（Top3 与 moreAvailable、容量/冲突排除含首尾相接、解析失败不检索、空候选澄清、AI 关闭、日期早于今天、常用楼栋加权、无历史仍可用）与 `AiChatStreamServiceTest` 3 例（`plan` 帧顺序、规划分支不调上游与不读缓存、澄清/空候选不出方案帧）；另新增 `AiThinkingTraceTest`（步骤计时 / closed 后静默丢弃 / 写失败抛 UncheckedIOException）、`AiChatStreamServiceTest` 5 例（成功轨迹顺序、缓存命中、降级 failed+fallback、关闭态无轨迹、reasoning 透传且不并入正文）、`AiStreamFramesTest` 2 例（`reasoning_content` 与别名 `reasoning`）、`AgnesClientStreamOrchestrationTest` 2 例（推理端口隔离）。
- 前端 E2E **192 例 / 24 spec**：覆盖 AI 页面上下文与预约方案卡、思考过程、入口自愈、历史持久化、重新生成 / 回到底部 / Esc 停止，并新增交互与设计原则、动效、URL 深链、语义结构等 R16–R17 回归；2026-11-26 本机实测 Chromium 与 Edge 均为 **192 passed / 0 skipped**。
- 后端 **309 项**（新增 `TokenRevocationIntegrationTest`：固定 `redis.enable=false`，验证无 Redis 也能持久撤销；新增拦截器版本号 / 状态缓存用例）。新增 4 个纯逻辑测试类补齐看板与降级链：`StatsServiceImplTest`（聚合口径 / 窗口裁剪与 14h 封顶 / 排序 / 区间校验）、`ReservationConverterTest`（批量预查短路 / 三种 VO / 状态文案）、`AiParseFallbackTest`（自然语言解析与非法输入不抛异常）、`AiRecommendFallbackTest`（规则打分与推荐理由）；另补 2 个业务分支覆盖类 `ReservationServiceImplCoverageTest`（参数校验/授权/导出上限/日历区间与可见性/关键词拼接）与 `ClassroomServiceImplCoverageTest`（CRUD 校验/删除保护/启停幂等/学生端缓存与占用时段/状态标签），以及 `UserServiceImplCoverageTest`（登录/注册/资料/改密全分支）、`RedisCacheObjectCacheTest`（对象缓存与代次失效降级）、`AiJsonSupportTest`（模型 JSON 容错解析）、`FavoriteServiceImplCoverageTest`（收藏上限与幽灵卡片过滤），行覆盖率 54% → **80.6%**（指令 81.9% / 分支 74.2%，ReservationServiceImpl 98.1%、ClassroomServiceImpl 99.1%、UserServiceImpl 97.5%、RedisCache 87.0%）。
- 前端 E2E **120 项 / 17 spec**，新增「会话撤销」spec；新增 Microsoft Edge 全量回归项目（`npm run test:e2e:edge`，默认脚本仍只跑 Chromium）。
- `server/scripts/smoke.ps1` 增至 **41 项**（含重置密码后旧 Token 失效断言），并修复脚本对 PowerShell 7 错误响应的兼容性。
- 10-auth.spec.js 的「即将开始提醒」用例在 22:00 之后会把窗口算到跨午夜（如 23:35-00:35），被后端判「开始时间必须早于结束时间」而误报失败；补充「窗口不跨日、落在 08:00-22:00」的跳过条件。
- `ReservationServiceImplCoverageTest` 的日期字面量（2026-11-08）随系统日期过期导致 2 例失败，改用 `LocalDate.now().plusDays(1)` 生成未来日期，用例不再随时间漂移。

### 文档

- **v1.2.0 重录当前晨光玻璃演示素材并更新社交预览**：GIF `docs/images/demo-walkthrough.gif` 为 880×550 / 8fps / 96 色、45.76 秒、7,948,675 字节（≤8MB）；完整版 MP4 为 1440×900 / H.264、85.16 秒、5,016,044 字节；AI 思考片段为 29.76 秒、1,298,883 字节，两个 MP4 作为 v1.2.0 Release 资产发布。录制脚本适配整卡点击与折叠筛选，结尾卡更新为 192 例 E2E，并修复 AI 模式自定义 `--out` 不生效的问题。社交预览图更新为当前界面及 347 后端测试 / 81.6% 行覆盖 / 192 E2E。

- **重录三段演示视频以匹配墨蓝门牌体系（v1.1.0，历史素材）**：视觉重构后旧素材（排课表式界面）已过期，用 `web/scripts/capture-demo-video.mjs` 重新录制并替换——GIF `docs/images/demo-walkthrough.gif`（880×550 / 10fps / 6.4MB / 实测 41.6 秒）、完整版 `demo-walkthrough.mp4`（1440×900 / H.264 / 2.9MB / 实测 73 秒）、AI 片段 `demo-ai-thinking.mp4`（1440×900 / H.264 / 0.8MB / 实测 30 秒），两个 MP4 已通过 `gh release upload v1.1.0 --clobber` 替换 Release 资产。脚本同步适配新界面：录制前清空 Redis `cache:ai:*` 保证等待动画与逐步轨迹真实出现、真实调用一次推荐接口预热 60 秒缓存、结尾指标卡更新为 347 项后端测试 / 行覆盖 81.6% / 41 项冒烟 / 141 例 E2E、字幕与光标改用墨蓝 token；AI 段落新增「思考中 → 自动折叠 → 展开回看」三态录制，面板头点击按「合成光标 → Playwright 点击 → DOM 点击」三级兜底（正文贴底滚动会拦截坐标点击）。README「演示」区块与 [`docs/演示视频分镜.md`](docs/演示视频分镜.md) 的分镜、实测时长、体积与验收阈值同步更新，并删除「录制于视觉重构前」的过期说明。
- 新增 [`server/scripts/ai-switch.ps1`](server/scripts/ai-switch.ps1)：AI 动态开关的一键切换与校验；README「启用 AI」与 [`docs/运行与配置.md`](docs/运行与配置.md) §2 同步改写，并补「悬浮球不出现」排查表（静态/动态双开关、`docker compose restart` 不重读环境变量、前端每次启动以服务端探测为准）。
- 新增可靠性证据：[`docs/可靠性验证.md`](docs/可靠性验证.md) 汇总故障演练与性能基准实测 —— 故障演练脚本 [`server/scripts/chaos-drill.ps1`](server/scripts/chaos-drill.ps1)（Redis fail closed 401 / MySQL 统一错误包 30s / 恢复自愈 / 数据复原，15 项；`-IncludeSlow` 补 60s 会话撤销窗口）、接口基准 [`web/scripts/bench-api.mjs`](web/scripts/bench-api.mjs)（7 个接口 p50/p95/p99，冲突检测 p95 ≈ 16.5 ms 对 500 ms 上限）、页面基准 [`web/scripts/bench-page.mjs`](web/scripts/bench-page.mjs)（7 个页面首屏就绪 p95 54–137 ms 对 2 s 上限）；README 质量表与文档导航同步。
- 新增 [`docs/安全基线.md`](docs/安全基线.md) 与 [`server/scripts/security-check.ps1`](server/scripts/security-check.ps1)（8 项自动检查：响应头 / Server 版本 / 接口文档端点 / 默认密钥伪造 Token / 未登录 401 / 学生越权 403 / 登录与注册限流）；README「快速开始」补 `.env` 复制与密钥生成步骤、质量表增一行，[`docs/运行与配置.md`](docs/运行与配置.md) 环境变量表补 `SPRING_PROFILES_ACTIVE` / `CORS_ALLOWED_ORIGINS` 并更新 `JWT_SECRET` / `KNIFE4J_ENABLE` / `MYBATIS_LOG_IMPL` 口径。
- 新增仓库「演示」物料：41 秒 GIF [`docs/images/demo-walkthrough.gif`](docs/images/demo-walkthrough.gif) 与 73 秒完整版 MP4（GitHub Release `v1.1.0` 资产），README 增 `## 演示` 区块与文档导航入口；录制脚本 [`web/scripts/capture-demo-video.mjs`](web/scripts/capture-demo-video.mjs) 以 Playwright 驱动真实浏览器（合成光标 + 页内字幕条），录制前自动复位种子库并按固定锚点造冲突 / 待审核数据，`web/package.json` 增 `demo:gif` / `demo:full`；分镜与验收见 [`docs/演示视频分镜.md`](docs/演示视频分镜.md)。
- 新增 AI 演示片段：`--mode=ai` 录制 30 秒「AI 助手思考过程」视频（`.devtmp/demo/demo-ai-thinking.mp4`，GitHub Release `v1.1.0` 资产），README「演示」区块增链接，`web/package.json` 增 `demo:ai`；[`docs/演示视频分镜.md`](docs/演示视频分镜.md) 增「AI 助手思考片段分镜」一节与预热缓存、真实模型耗时兜底说明。
- 同步 `需求设计文档.md` §2.3.2（`sys_user.token_version`）、`docs/Redis加分项接入说明.md`、`docs/演示脚本.md` 的撤销与 fail-closed 口径，并新增数据库设计图字段。
- 新增 [`docs/无障碍人工验收清单.md`](docs/无障碍人工验收清单.md)（读屏器人工验收步骤；人工听感按项目负责人 2026-11-07 决定**不执行**，非需求项，机器代理记录见下）。
- 新增无障碍机器代理验收：脚本 [`web/scripts/a11y-acceptance.mjs`](web/scripts/a11y-acceptance.mjs)、记录 [`docs/review/无障碍机器代理验收记录.md`](docs/review/无障碍机器代理验收记录.md) 与证据 [`docs/review/a11y-evidence/`](docs/review/a11y-evidence/)；当前为 **15 项通过 + 1 项信息检查**（C4 修复后由机器验证）；真实播报人工听感按负责人决定不纳入交付（本机未安装 NVDA、讲述人未运行，未执行）。
- 修正会话撤销「立即生效」的绝对表述：补充「删除鉴权状态快照失败时，Redis 恢复后 ≤60s 旧快照窗口内旧 Token 仍可能被接受」的如实口径（故障期间仍 fail-closed 401；窗口结束后回源数据库即 401），同步 [`docs/Redis加分项接入说明.md`](docs/Redis加分项接入说明.md) §3/§4/§5/§6.2、[`docs/演示脚本.md`](docs/演示脚本.md)、`AuthInterceptor` 与 `RedisCache` 注释。
- README 重构为作品页结构（250 行）：预览截图前置、迭代历程压缩为 4 个里程碑，环境变量 / AI / Redis / 端口细节迁移至 [`docs/运行与配置.md`](docs/运行与配置.md)；新增 `.gitattributes` 统一换行为 LF（13 个文本文件 renormalize，无内容改动）。
- README 增加「项目预览」真实运行截图（[`docs/images/`](docs/images/)，由 [`web/scripts/capture-screens.mjs`](web/scripts/capture-screens.mjs) 采集）与「架构设计」Mermaid 图、三点设计取舍表；「测试」章节补充规模统计表，方便快速了解项目与面试讲解。

### 持续集成

- 新增 [`stack-smoke.yml`](.github/workflows/stack-smoke.yml)：真实起 MySQL / Redis / 后端 / 前端，`smoke` job 跑 41 项接口冒烟、`e2e` job 跑当前 spec 全集（192 项 Playwright E2E）；两个 job 各自持有独立 service 容器，避免冒烟写入的数据污染 E2E 基线。
- `server-ci.yml` 增加 JaCoCo 覆盖率报告并作为 artifact 上传（`jacoco-maven-plugin 0.8.12`，指令覆盖率 81.9% / 行覆盖率 80.6% / 分支 74.2%，DTO / VO / entity 等纯数据载体不计入分母）。

## [1.0.0] - 2026-10

### 新增

- **R1 基础底座**：数据库建库建表与基线数据、前后端工程骨架、登录注册、JWT 鉴权与角色权限拦截。
- **R2 管理端**：用户管理（增删改查、启停用）与教室管理。
- **R3 预约核心**：教室浏览与详情、预约提交（前端实时校验 + 后端二次冲突检测）、管理员审核。
- **R4 体验升级**：个人中心、收藏全链路、预约记录与体验细节打磨。
- **R5 亮点功能**：日历总览（FullCalendar）与数据看板（ECharts）。
- **R7 AI 模块**：Agnes AI 接入（推荐 / 需求解析 / 智能问答 / 合规校验），限流与超时自动降级为本地规则。
- **R8 答辩准备**：实训报告、答辩演示材料与演示脚本、Redis 加分项接入（Token 与热点数据缓存，一键启停）。
- **R10 最终交付收口**：Playwright E2E 基建（16 个 spec / 118 个用例）、后端逻辑冻结与完整性复核、前端视觉改版、无障碍修复。
- **R11 AI 观测补全与结果缓存**：token 用量与结束原因观测、只读结果缓存。
- **R12 智能问答 SSE 流式输出**：`/api/ai/chat?stream=true` 打字机体验与收尾打磨。
- **R13 AI 体验增强**：停止生成、Markdown 渲染、会话内多轮上下文、WebClient 上游改造、推荐 / 解析 / 合规三个接口结构化 SSE 化。

### 变更

- **R6 联调优化**：全流程贯通与回归保障。
- **R9 代码质量重构与防御加固**：分层与命名规范化，补充边界与并发防护。

### 修复

- **R9 / R10**：预约状态流转口径与实现对齐、并发场景下的行锁与条件更新加固、前端 AI 痕迹与无障碍问题修复。

### 文档

- 每轮同步产出任务说明、接口清单、测试报告与启动说明四件套，归档于 `docs/iterations/`。
- QA 收口报告、软件审查报告、端到端测试报告分别归档于 `docs/qa/`、`docs/review/`、`docs/e2e/`。
