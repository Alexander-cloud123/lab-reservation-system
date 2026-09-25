# -*- coding: utf-8 -*-
"""填充 QA canonical：input(输入基线) + requirements(需求) + risk_mechanisms(风险机制)"""
import json, io

PATH = r"C:\Users\72797\Course\reservation-system\qa-results\reservation-e2e\qa-run.json"
with io.open(PATH, "r", encoding="utf-8") as f:
    run = json.load(f)

run["input"]["summary"] = (
    "实验室/教室预约管理系统（前后端分离：Spring Boot 3.2.10 + Vue 3.4 + MySQL 8 + Redis 7，AI 模块 Agnes 接入可降级）。"
    "唯一需求依据《需求设计文档.md》V3.1：13 页面（2 公共 + 5 学生端 + 6 管理端），核心规则为预约冲突检测公式"
    "（新开始<旧结束 AND 新结束>旧开始，仅与已通过预约比较，前后端双重校验）与状态流转（0-待审核→1-已通过/2-已驳回；0/1→3-已取消），"
    "取消时限为开始前 1 小时；收藏上限 10；批量审核仅待审核；AI 双开关启停、只读不写、超时 60s 降级。"
    "测试账号：管理员 admin/admin123；学生 zhangsan/lisi/wangwu/zhaoliu 均 123456。"
    "当前代码源为 server/ + web/（R8 最终版 + R9 加固），数据库容器 reservation-mysql（库 reservation，5 用户/12 教室/15 预约）。"
)

run["input"]["sources"] = [
    {"id": "SRC-001", "type": "requirement_doc", "locator": "需求设计文档.md（V3.1）",
     "access_status": "read", "completeness_checked": True,
     "item_count": 5, "reviewed_item_count": 5},
    {"id": "SRC-002", "type": "sql", "locator": "database/init_db.sql",
     "access_status": "read", "completeness_checked": True,
     "item_count": 5, "reviewed_item_count": 5},
    {"id": "SRC-003", "type": "config", "locator": "server/src/main/resources/application.yml",
     "access_status": "read", "completeness_checked": True,
     "item_count": 1, "reviewed_item_count": 1},
    {"id": "SRC-004", "type": "readme", "locator": "README.md",
     "access_status": "read", "completeness_checked": True,
     "item_count": 1, "reviewed_item_count": 1},
    {"id": "SRC-005", "type": "test_report", "locator": "docs/iterations/R9-code-quality-refactor/test-report.md",
     "access_status": "read", "completeness_checked": True,
     "item_count": 1, "reviewed_item_count": 1},
    {"id": "SRC-006", "type": "api_list", "locator": "docs/iterations/R8-delivery-defense-prep/接口清单.md",
     "access_status": "read", "completeness_checked": True,
     "item_count": 1, "reviewed_item_count": 1},
    {"id": "SRC-007", "type": "backend_code", "locator": "server/src/main/java/com/example/reservation（controller/service/ai）",
     "access_status": "read", "completeness_checked": True,
     "item_count": 7, "reviewed_item_count": 7},
    {"id": "SRC-008", "type": "frontend_code", "locator": "web/src（router/views/api）",
     "access_status": "read", "completeness_checked": True,
     "item_count": 13, "reviewed_item_count": 13},
    {"id": "SRC-009", "type": "test_report", "locator": "docs/iterations/R8-delivery-defense-prep/test-report.md（228 条回归）",
     "access_status": "read", "completeness_checked": True,
     "item_count": 1, "reviewed_item_count": 1},
]

run["input"]["assumptions"] = [
    "以 server/ + web/ 当前工作区代码为唯一被测版本（R8 最终版 + R9 加固，git 历史可见）。",
    "测试账号与数据以 database/init_db.sql 种子数据为准；数据库当前 15 条预约（含历史测试新增 2 条，不影响规则验证，冲突检测以已通过预约为准）。",
    "AI 模块默认 ai.enable=false（双开关均 false 时本地规则模拟/降级路径即为线上默认行为）；如需验证真实模型链路由 AGNES_API_KEY 环境变量控制。",
    "Redis 为可选加分项：redis.enable=true 时 Token 缓存走 Redis；不可用时业务层降级直查库，不作为阻塞条件。",
    "系统运行环境：Windows 宿主 + Docker MySQL/Redis + JDK 21 + Node 22；后端端口 8080、前端 5173。",
]

run["input"]["conflicts"] = [
    "R8 接口清单状态口径写『类型：0-普通 1-多媒体 2-机房』，与需求设计文档 2.3 教室类型（1-普通教室 2-实验室 3-机房）不一致；"
    "经与当前代码 ClassroomController/ClassroomStudentController 核对，实际枚举为 1-普通教室/2-实验室/3-机房，以需求文档与代码实况为准（标注为文档口径差异，非产品缺陷）。",
]

run["input"]["artifacts"] = [
    {"id": "SRC-001", "type": "requirement_doc", "locator": "需求设计文档.md（V3.1）",
     "access_status": "read", "completeness_checked": True},
]

run["requirements"] = [
    # 公共功能
    {"id": "REQ-001", "summary": "双角色登录：学生/管理员角色切换登录，表单校验，错误提示", "source": "SRC-001", "risk": "登录失败信息不明确或角色判定错误导致越权", "behavior": {"given": "未登录用户", "when": "以正确/错误账号密码 + 角色登录", "then": "正确凭证返回 token 并跳转角色首页；错误凭证返回明确提示；角色不符被拒绝"}},
    {"id": "REQ-002", "summary": "学生自主注册：信息填写、唯一性校验、密码加密存储", "source": "SRC-001", "risk": "账号重复、密码明文存储、学号缺失", "behavior": {"given": "未注册用户", "when": "提交注册表单", "then": "用户名唯一校验生效；密码 BCrypt 存储；学生角色学号必填"}},
    {"id": "REQ-003", "summary": "登录状态提醒：当日有即将开始的预约时登录后顶部温和提示", "source": "SRC-001", "risk": "提醒缺失或时间口径错误", "behavior": {"given": "学生当日有已通过且即将开始的预约", "when": "登录进入首页", "then": "顶部出现温和提醒"}},
    # 学生端
    {"id": "REQ-004", "summary": "教室资源列表浏览：多条件筛选、关键词搜索、卡片式展示、分页", "source": "SRC-001", "risk": "筛选组合失效、分页越界", "behavior": {"given": "学生登录", "when": "按关键词/楼栋/类型/日期筛选并翻页", "then": "结果正确、分页总数正确"}},
    {"id": "REQ-005", "summary": "教室实时状态标签：空闲/使用中/今日剩余时段", "source": "SRC-001", "risk": "状态口径错误（与已通过预约时段比较）", "behavior": {"given": "教室当天存在已通过预约", "when": "查看教室卡片", "then": "当前时刻在预约时段内显示使用中，否则空闲"}},
    {"id": "REQ-006", "summary": "筛选条件记忆：自动保留上次楼栋/类型/日期筛选", "source": "SRC-001", "risk": "条件丢失", "behavior": {"given": "学生设置过筛选条件", "when": "重新进入列表页", "then": "筛选条件自动恢复"}},
    {"id": "REQ-007", "summary": "AI 智能教室推荐：基于历史预约习惯+实时空闲状态匹配 Top3", "source": "SRC-001", "risk": "推荐为空/不可用时不阻断页面", "behavior": {"given": "学生登录且有预约历史", "when": "进入教室列表页", "then": "出现 Top3 推荐卡片，标注 AI 生成仅供参考"}},
    {"id": "REQ-008", "summary": "AI 自然语言快速预约：解析日期/时段/人数/类型/用途，匹配可用教室发起预约", "source": "SRC-001", "risk": "解析字段缺漏、结果不可编辑", "behavior": {"given": "学生输入口语化预约需求", "when": "调用解析并匹配", "then": "结构化参数正确回填表单，用户可修改并手动确认提交"}},
    {"id": "REQ-009", "summary": "教室详情：基础信息 + 当日时段占用可视化", "source": "SRC-001", "risk": "占用时段展示与已通过预约不一致", "behavior": {"given": "学生进入教室详情", "when": "查看当日占用", "then": "占用时段与已通过预约一致"}},
    {"id": "REQ-010", "summary": "教室收藏：一键收藏/取消，常用教室快速访问，上限 10 间", "source": "SRC-001", "risk": "重复收藏、超上限未拦截", "behavior": {"given": "学生收藏教室", "when": "收藏/取消/超上限收藏", "then": "收藏成功、取消成功、第 11 间被拒绝或提示上限"}},
    {"id": "REQ-011", "summary": "预约申请：选择日期时段、填写用途、提交前冲突检测", "source": "SRC-001", "risk": "冲突预约被放行（核心）", "behavior": {"given": "学生选择教室/日期/时段", "when": "提交预约", "then": "前端实时校验冲突并禁用提交；无冲突提交成功为待审核"}},
    {"id": "REQ-012", "summary": "预约草稿自动保存：表单未提交关闭后下次自动填充（localStorage）", "source": "SRC-001", "risk": "草稿污染下次预约", "behavior": {"given": "学生填写预约表单未提交", "when": "关闭后再次打开", "then": "表单自动回填上次内容"}},
    {"id": "REQ-013", "summary": "我的预约管理：按状态分类查看、取消预约", "source": "SRC-001", "risk": "状态筛选错误、取消权限越界", "behavior": {"given": "学生查看我的预约", "when": "按状态筛选/取消", "then": "列表正确、仅本人可取消、取消二次确认"}},
    {"id": "REQ-014", "summary": "今日预约置顶：当日预约在列表顶部突出显示", "source": "SRC-001", "risk": "置顶失效", "behavior": {"given": "学生有当日预约", "when": "查看我的预约", "then": "当日预约置顶"}},
    {"id": "REQ-015", "summary": "友好空状态：无数据时引导文案+快捷操作按钮", "source": "SRC-001", "risk": "空状态缺失", "behavior": {"given": "学生无对应状态预约", "when": "查看空列表", "then": "展示引导文案与操作入口"}},
    {"id": "REQ-016", "summary": "个人中心：个人信息修改、密码修改（验证原密码）", "source": "SRC-001", "risk": "旧密码校验缺失、越权修改", "behavior": {"given": "学生进入个人中心", "when": "修改资料/密码", "then": "资料保存成功；旧密码错误被拒"}},
    {"id": "REQ-017", "summary": "个人预约数据概览：累计预约、本月预约、通过率等卡片", "source": "SRC-001", "risk": "统计数据口径错误", "behavior": {"given": "学生查看个人中心顶部", "when": "查看数据卡片", "then": "与预约记录口径一致"}},
    {"id": "REQ-018", "summary": "常用教室快捷入口：展示收藏教室，点击直达预约", "source": "SRC-001", "risk": "入口缺失", "behavior": {"given": "学生有收藏教室", "when": "查看个人中心", "then": "收藏教室展示且可跳转"}},
    {"id": "REQ-019", "summary": "消息通知中心：审核结果、预约提醒（由预约表状态动态生成）", "source": "SRC-001", "risk": "通知与状态不一致", "behavior": {"given": "学生预约状态变化", "when": "查看通知", "then": "通知与最新状态一致"}},
    {"id": "REQ-020", "summary": "AI 预约智能助手：仅解答预约/教室/个人记录，支持自然语言查询", "source": "SRC-001", "risk": "无关话题被回答（场景越界）", "behavior": {"given": "学生打开 AI 助手", "when": "询问预约相关问题/无关话题", "then": "预约相关问题正常回答；无关话题返回预设话术"}},
    {"id": "REQ-021", "summary": "预约日历总览：FullCalendar 月/周视图、色块占用、点击快速预约", "source": "SRC-001", "risk": "事件渲染错误（日期格式）", "behavior": {"given": "学生打开日历页", "when": "切换月/周视图", "then": "已通过预约以事件色块展示，日期正确"}},
    # 管理员端
    {"id": "REQ-022", "summary": "首页数据概览：今日预约、待审核、教室总数、用户总数", "source": "SRC-001", "risk": "统计口径错误", "behavior": {"given": "管理员进入后台首页", "when": "查看统计卡片", "then": "数据与库内口径一致"}},
    {"id": "REQ-023", "summary": "教室资源管理：增删改查、状态管理、批量操作、多条件搜索", "source": "SRC-001", "risk": "删除有预约教室未拦截", "behavior": {"given": "管理员操作教室", "when": "新增/编辑/删除/启停/批量", "then": "CRUD 完整；有预约记录的教室删除被拒"}},
    {"id": "REQ-024", "summary": "预约审核：全量查询、通过/驳回、驳回备注必填", "source": "SRC-001", "risk": "非待审核被审核、驳回缺备注", "behavior": {"given": "管理员审核预约", "when": "通过/驳回", "then": "仅待审核可审核；驳回备注必填；状态持久化"}},
    {"id": "REQ-025", "summary": "批量审核：勾选多条待审核一键批量通过/驳回，仅待审核参与", "source": "SRC-001", "risk": "已审核记录被重复批量操作", "behavior": {"given": "管理员勾选记录批量审核", "when": "批量通过/驳回", "then": "仅待审核被处理，已审核记录被跳过或拒绝"}},
    {"id": "REQ-026", "summary": "快捷驳回原因：预置常用原因一键选择", "source": "SRC-001", "risk": "快捷项缺失", "behavior": {"given": "管理员驳回预约", "when": "打开驳回弹窗", "then": "快捷原因可选并回填备注"}},
    {"id": "REQ-027", "summary": "AI 预约合规校验：命中违规内容自动标记并提示原因", "source": "SRC-001", "risk": "违规内容未被标记", "behavior": {"given": "管理员查看待审核预约", "when": "AI 校验用途", "then": "违规预约红色高亮并显示原因"}},
    {"id": "REQ-028", "summary": "用户管理：账号查询、禁用/启用、重置密码", "source": "SRC-001", "risk": "误禁自己、重置后旧密码失效", "behavior": {"given": "管理员操作用户", "when": "查询/禁用/启用/重置密码", "then": "操作生效；禁止操作自己；禁用用户登录被拒"}},
    {"id": "REQ-029", "summary": "预约记录查询：多条件筛选、历史全量、Excel 导出", "source": "SRC-001", "risk": "导出缺行/乱码", "behavior": {"given": "管理员查询预约记录", "when": "筛选并导出 Excel", "then": "筛选正确；导出行数与查询一致"}},
    {"id": "REQ-030", "summary": "数据可视化看板：教室使用率排行、月度趋势、热门时段分布（ECharts）", "source": "SRC-001", "risk": "图表数据口径错误", "behavior": {"given": "管理员打开看板", "when": "查看三图表", "then": "图表数据与统计接口一致"}},
    # 核心规则
    {"id": "REQ-031", "summary": "预约冲突检测：新开始<旧结束 AND 新结束>旧开始，仅与已通过比较，前后端双重校验", "source": "SRC-001", "risk": "冲突放行（P0 核心）", "behavior": {"given": "同一教室同一日期存在已通过预约", "when": "提交重叠时段预约", "then": "前端禁用+后端拒绝；首尾相接不冲突"}},
    {"id": "REQ-032", "summary": "预约状态流转：待审核(0)→已通过(1)/已驳回(2)；待审核/已通过→已取消(3)", "source": "SRC-001", "risk": "非法迁移（P0 核心）", "behavior": {"given": "预约处于各状态", "when": "执行审核/取消", "then": "仅合法迁移被允许；已驳回/已取消不可再操作"}},
    {"id": "REQ-033", "summary": "取消时限：预约开始前 1 小时可自由取消，不足 1 小时不可取消", "source": "SRC-001", "risk": "时限边界错误", "behavior": {"given": "预约开始时间距当前不足/超过 1 小时", "when": "取消", "then": "超过可取消；不足返回 400 提示"}},
    {"id": "REQ-034", "summary": "常用教室上限：每人最多收藏 10 间", "source": "SRC-001", "risk": "超限未拦截", "behavior": {"given": "学生已收藏 10 间", "when": "再收藏第 11 间", "then": "被拒绝并提示上限"}},
    {"id": "REQ-035", "summary": "批量审核约束：仅待审核状态可参与批量操作", "source": "SRC-001", "risk": "非待审核被批量处理", "behavior": {"given": "批量列表含已审核记录", "when": "批量审核", "then": "已审核记录被排除"}},
    # AI 规则
    {"id": "REQ-036", "summary": "AI 场景绝对限定：仅服务预约业务，禁止无关话题", "source": "SRC-001", "risk": "越界回答", "behavior": {"given": "用户问无关话题", "when": "调用 AI 助手", "then": "返回预设话术"}},
    {"id": "REQ-037", "summary": "AI 只读不写：仅查询/解析/建议，业务操作必须用户手动确认", "source": "SRC-001", "risk": "AI 直接写数据（P0）", "behavior": {"given": "AI 给出建议后", "when": "检查数据", "then": "AI 调用不产生任何业务写入"}},
    {"id": "REQ-038", "summary": "AI 降级容错：超时 60s/报错/限流自动降级本地规则模拟，不阻断业务", "source": "SRC-001", "risk": "AI 不可用导致页面不可用", "behavior": {"given": "模型超时/报错/限流", "when": "调用 AI 接口", "then": "返回本地规则结果或友好提示，业务不受阻"}},
    {"id": "REQ-039", "summary": "AI 双开关：application.yml ai.enable 与 ai_config 表 ai_enable 均 true 才启用", "source": "SRC-001", "risk": "开关失效", "behavior": {"given": "任一开关为 false", "when": "访问 AI 功能", "then": "AI 入口隐藏/降级"}},
    {"id": "REQ-040", "summary": "AI 内容标注：所有 AI 生成内容前端标注「AI 生成，仅供参考」", "source": "SRC-001", "risk": "标注缺失", "behavior": {"given": "前端展示 AI 结果", "when": "检查标注", "then": "结果旁有标注文案"}},
    # 非功能
    {"id": "REQ-041", "summary": "权限拦截：未登录 401、无权限 403、禁用账号拦截", "source": "SRC-001", "risk": "越权访问", "behavior": {"given": "无 token/学生 token/禁用账号", "when": "访问受保护接口", "then": "401/403 明确返回"}},
    {"id": "REQ-042", "summary": "密码安全：BCrypt 加密存储，禁止明文", "source": "SRC-001", "risk": "明文泄露", "behavior": {"given": "检查数据库", "when": "查看用户表密码字段", "then": "均为 BCrypt 哈希非明文"}},
    {"id": "REQ-043", "summary": "统一返回 Result：{code,message,data}，错误码语义化，全局异常不暴露堆栈", "source": "SRC-001", "risk": "异常堆栈外泄", "behavior": {"given": "触发业务异常", "when": "检查响应", "then": "code 语义化，无堆栈泄露"}},
    {"id": "REQ-044", "summary": "参数合法性校验：分页范围（size≤500）、必填、枚举、时间先后", "source": "SRC-001", "risk": "非法参数未拦截", "behavior": {"given": "传入非法参数", "when": "调用接口", "then": "返回 400 明确提示"}},
]

run["risk_mechanisms"] = [
    {"id": "RM-CORE-001", "title": "冲突检测公式与边界正确性", "failure_mode": "重叠时段被放行或首尾相接被误判冲突", "business_impact": "核心业务错误，直接决定答辩评分与可用性（P0）", "oracle": "同一教室同日：部分重叠/完全包含/包含被包含均返回 conflict=true；首尾相接（新结束=旧开始/新开始=旧结束）返回 conflict=false；无重叠 false；仅与已通过预约比较", "requirement_ids": ["REQ-011", "REQ-031"], "case_ids": [], "priority": "P0", "status": "identified"},
    {"id": "RM-CORE-002", "title": "预约状态流转合法性", "failure_mode": "非法迁移（如已驳回再审核、已取消再取消）被放行", "business_impact": "状态机错误导致数据不可信（P0）", "oracle": "待审核可审核/可取消；已通过可取消；已驳回/已取消不可再审核或取消；取消后状态=3", "requirement_ids": ["REQ-032"], "case_ids": [], "priority": "P0", "status": "identified"},
    {"id": "RM-CORE-003", "title": "后端二次冲突检测兜底", "failure_mode": "绕过前端直接调用提交重叠预约成功", "business_impact": "双重校验失效=核心亮点失守（P0）", "oracle": "直接 POST /api/reservation 提交与已通过重叠时段 → 400 冲突拒绝，数据不落库", "requirement_ids": ["REQ-011", "REQ-031"], "case_ids": [], "priority": "P0", "status": "identified"},
    {"id": "RM-CORE-004", "title": "取消时限边界（开始前 1 小时）", "failure_mode": "不足 1 小时仍可取消", "business_impact": "资源调度被临时占坑（P1）", "oracle": "开始时间-当前<1h → 400「预约开始前 1 小时内禁止取消」；>1h → 取消成功", "requirement_ids": ["REQ-033"], "case_ids": [], "priority": "P1", "status": "identified"},
    {"id": "RM-AUTH-001", "title": "角色权限隔离", "failure_mode": "学生 token 访问管理接口成功", "business_impact": "越权操作（P0）", "oracle": "学生 token GET /api/classroom/manage、/api/reservation/manage、/api/user/manage → 403「无权限访问」", "requirement_ids": ["REQ-041"], "case_ids": [], "priority": "P0", "status": "identified"},
    {"id": "RM-AUTH-002", "title": "未登录/Token 失效拦截", "failure_mode": "无 token 访问受保护接口返回业务数据", "business_impact": "未授权访问（P0）", "oracle": "无 token/伪造 token → 401「未登录或登录已过期」；HTTP 401", "requirement_ids": ["REQ-041"], "case_ids": [], "priority": "P0", "status": "identified"},
    {"id": "RM-AUTH-003", "title": "禁用账号拦截与自我操作保护", "failure_mode": "禁用账号仍可登录；管理员可禁自己", "business_impact": "账号安全与系统锁死（P1）", "oracle": "禁用用户登录 → 403；管理员 PUT /api/user/manage/{自己id}/status → 400 拒绝", "requirement_ids": ["REQ-028", "REQ-041"], "case_ids": [], "priority": "P1", "status": "identified"},
    {"id": "RM-BATCH-001", "title": "批量审核仅限待审核", "failure_mode": "已审核/已取消记录被批量重复操作", "business_impact": "状态覆盖/重复审核（P1）", "oracle": "批量列表中混入非待审核 → 仅待审核被处理；对已审核记录单独审核 → 400", "requirement_ids": ["REQ-025", "REQ-035"], "case_ids": [], "priority": "P1", "status": "identified"},
    {"id": "RM-FAV-001", "title": "收藏上限 10 与去重", "failure_mode": "第 11 间收藏成功或重复收藏", "business_impact": "违反明确业务规则（P1）", "oracle": "收藏 11 间 → 第 11 间被拒；重复收藏同一教室 → 幂等或提示已收藏", "requirement_ids": ["REQ-010", "REQ-034"], "case_ids": [], "priority": "P1", "status": "identified"},
    {"id": "RM-DATA-001", "title": "审核后状态与数据一致性", "failure_mode": "审核成功但列表/通知/统计未反映新状态", "business_impact": "跨层数据不一致（P1）", "oracle": "审核通过后：manage 列表状态=1、auditorId/auditTime 落库、学生 mine 列表同步、统计更新", "requirement_ids": ["REQ-024", "REQ-019", "REQ-029"], "case_ids": [], "priority": "P1", "status": "identified"},
    {"id": "RM-EXPORT-001", "title": "预约记录 Excel 导出完整性", "failure_mode": "导出行数与查询不一致、中文乱码、无法打开", "business_impact": "管理端交付能力受损（P2）", "oracle": "导出文件可打开、行数=查询 total、中文正常", "requirement_ids": ["REQ-029"], "case_ids": [], "priority": "P2", "status": "identified"},
    {"id": "RM-AI-001", "title": "AI 降级容错与可开关", "failure_mode": "AI 不可用时页面阻塞或报错；开关失效", "business_impact": "演示翻车/核心功能受阻（P1）", "oracle": "ai.enable=false 或模型超时/限流 → 返回本地规则结果或友好降级，页面可用；入口隐藏", "requirement_ids": ["REQ-038", "REQ-039"], "case_ids": [], "priority": "P1", "status": "identified"},
    {"id": "RM-AI-002", "title": "AI 只读不写", "failure_mode": "AI 调用产生业务写入", "business_impact": "违反只读不写红线（P0）", "oracle": "完整走 AI 推荐→解析→提交链路，检查数据库无 AI 直接产生的预约/取消等业务变更（用户手动确认除外）", "requirement_ids": ["REQ-037"], "case_ids": [], "priority": "P0", "status": "identified"},
    {"id": "RM-AI-003", "title": "AI 场景限定与内容标注", "failure_mode": "无关话题被回答；AI 内容无标注", "business_impact": "场景越界/合规标注缺失（P2）", "oracle": "无关问题返回预设话术；AI 生成内容处有「AI 生成，仅供参考」", "requirement_ids": ["REQ-020", "REQ-036", "REQ-040"], "case_ids": [], "priority": "P2", "status": "identified"},
    {"id": "RM-VALID-001", "title": "参数合法性校验", "failure_mode": "非法分页/枚举/时间参数被接受或产生 500", "business_impact": "接口健壮性（P1）", "oracle": "page<1、size>500、status 非法、startTime>=endTime、缺必填 → 400 明确文案；无堆栈", "requirement_ids": ["REQ-044"], "case_ids": [], "priority": "P1", "status": "identified"},
]

with io.open(PATH, "w", encoding="utf-8") as f:
    json.dump(run, f, ensure_ascii=False, indent=2)

print("filled: input.sources=%d, requirements=%d, risk_mechanisms=%d" % (
    len(run["input"]["sources"]), len(run["requirements"]), len(run["risk_mechanisms"])))
