# -*- coding: utf-8 -*-
"""填充 QA canonical：cases（测试用例设计）"""
import json, io

PATH = r"C:\Users\72797\Course\reservation-system\qa-results\reservation-e2e\qa-run.json"
with io.open(PATH, "r", encoding="utf-8") as f:
    run = json.load(f)

C = []
def add(cid, module, title, prio, ctype, steps, tdata, expect, reqs, rms, mode="automated",
        so=None, ev=None, rbr=None):
    item = {
        "id": cid, "module": module, "title": title, "priority": prio, "type": ctype,
        "steps": steps, "test_data": tdata, "expected_result": expect,
        "requirement_ids": reqs, "risk_mechanism_ids": rms, "execution_mode": mode,
    }
    if so: item["state_oracle"] = so
    if ev: item["evidence_expected"] = ev
    if rbr: item["release_blocking_reason"] = rbr
    C.append(item)

AUTH = "登录注册"; CLASS = "教室浏览"; FAV = "教室收藏"; RES = "预约核心"; AUD = "预约审核"
ADMC = "教室管理"; ADMU = "用户管理"; STAT = "统计看板"; REC = "预约记录"; PROF = "个人中心"
CAL = "预约日历"; AI = "AI模块"; PERM = "权限安全"; VAL = "参数校验"

# ===== 公共：登录注册 =====
add("TC-AUTH-001", AUTH, "学生正确登录", "P0", "api",
    ["POST /api/user/login body{username:zhangsan,password:123456,role:0}", "校验响应 code/token/role"],
    "zhangsan/123456/role=0", "code=200，data.token 非空，data.role=0，message=登录成功", ["REQ-001"], ["RM-AUTH-002"])
add("TC-AUTH-002", AUTH, "管理员正确登录", "P0", "api",
    ["POST /api/user/login body{username:admin,password:admin123,role:1}", "校验响应"],
    "admin/admin123/role=1", "code=200，token 非空，role=1", ["REQ-001"], ["RM-AUTH-002"])
add("TC-AUTH-003", AUTH, "错误密码登录提示", "P1", "api",
    ["POST /api/user/login body{username:zhangsan,password:wrong,role:0}", "校验错误响应"],
    "zhangsan/wrong", "code!=200（400/401），message 明确提示密码错误，HTTP 非 500", ["REQ-001"], [], "automated", ev=["error_response"])
add("TC-AUTH-004", AUTH, "角色与账号不匹配登录拒绝", "P1", "api",
    ["POST /api/user/login body{username:zhangsan,password:123456,role:1}", "校验响应"],
    "学生账号+管理员角色", "code!=200，提示角色与账号不匹配或登录失败", ["REQ-001"], [])
add("TC-AUTH-005", AUTH, "学生自主注册成功", "P0", "api",
    ["POST /api/user/register body{username:qa_stu1,password:123456,confirmPassword:123456,name:QA测试,studentNo:2024099}",
     "再用新账号登录"], "新唯一用户名", "code=200；新账号可登录；数据库 password 为 BCrypt 哈希（$2b$开头）", ["REQ-002", "REQ-042"], [],
    "automated", so={"terminal": "注册成功，可登录"}, ev=["db_bcrypt_hash"])
add("TC-AUTH-006", AUTH, "重复用户名注册拒绝", "P1", "api",
    ["POST /api/user/register body{username:zhangsan,...}", "校验响应"],
    "已存在的 zhangsan", "code=400，提示用户名已存在", ["REQ-002"], [])
add("TC-AUTH-007", AUTH, "两次密码不一致拒绝", "P1", "api",
    ["POST /api/user/register body{password:123456,confirmPassword:654321,...}", "校验响应"],
    "不一致密码", "code=400，提示两次密码不一致", ["REQ-002"], [])
add("TC-AUTH-008", AUTH, "禁用账号登录拒绝", "P1", "api",
    ["前置：管理员将某学生禁用", "POST /api/user/login 该学生账号", "校验响应"],
    "被禁用账号", "code=403/400，提示账号已禁用，无法登录", ["REQ-041"], ["RM-AUTH-003"], "hybrid",
    so={"terminal": "禁用后登录被拒"})
add("TC-AUTH-009", AUTH, "登录后获取当前用户信息", "P0", "api",
    ["登录获取 token", "GET /api/user/info with Authorization", "校验响应"],
    "zhangsan token", "code=200，data.username=zhangsan，role=0", ["REQ-001"], ["RM-AUTH-002"])

# ===== 权限 =====
add("TC-PERM-001", PERM, "无 token 访问受保护接口返回 401", "P0", "api",
    ["GET /api/user/info（无 Authorization）", "校验响应"],
    "无 token", "HTTP 401 + code=401「未登录或登录已过期」", ["REQ-041"], ["RM-AUTH-002"], "automated",
    so={"terminal": "未授权访问被拒"})
add("TC-PERM-002", PERM, "伪造/失效 token 返回 401", "P0", "api",
    ["GET /api/user/info with Authorization: Bearer invalid.token.xxx", "校验响应"],
    "伪造 token", "HTTP 401 + code=401", ["REQ-041"], ["RM-AUTH-002"])
add("TC-PERM-003", PERM, "学生访问教室管理接口 403", "P0", "api",
    ["zhangsan 登录", "GET /api/classroom/manage?page=1&size=10", "校验响应"],
    "学生 token", "HTTP 403 + code=403「无权限访问」", ["REQ-041"], ["RM-AUTH-001"], "automated",
    so={"terminal": "越权被拒"})
add("TC-PERM-004", PERM, "学生访问预约管理接口 403", "P0", "api",
    ["zhangsan 登录", "GET /api/reservation/manage?page=1&size=10", "校验响应"],
    "学生 token", "HTTP 403 + code=403", ["REQ-041"], ["RM-AUTH-001"])
add("TC-PERM-005", PERM, "学生访问用户管理接口 403", "P0", "api",
    ["zhangsan 登录", "GET /api/user/manage?page=1&size=10", "校验响应"],
    "学生 token", "HTTP 403 + code=403", ["REQ-041"], ["RM-AUTH-001"])

# ===== 教室浏览 =====
add("TC-CLASS-001", CLASS, "教室列表分页与字段完整性", "P1", "api",
    ["zhangsan 登录", "GET /api/classroom/list?page=1&size=5", "校验 records/total/分页字段"],
    "page=1,size=5", "code=200，records 长度≤5，total>0，每条含 id/name/building/roomNo/type/capacity/statusLabel", ["REQ-004"], [])
add("TC-CLASS-002", CLASS, "关键词搜索教室", "P1", "api",
    ["GET /api/classroom/list?keyword=A301", "校验结果"],
    "keyword=A301", "code=200，结果中名称或编号含 A301", ["REQ-004"], [])
add("TC-CLASS-003", CLASS, "楼栋+类型+日期组合筛选", "P1", "api",
    ["GET /api/classroom/list?building=信息楼&type=3&date=<明日>", "校验结果与 occupiedSlots"],
    "信息楼/机房/明日", "code=200，全部为信息楼机房；date 参数返回该日已通过占用时段", ["REQ-004", "REQ-005"], [])
add("TC-CLASS-004", CLASS, "教室详情与当日占用", "P1", "api",
    ["GET /api/classroom/1?date=<今日>", "校验详情字段与 occupiedSlots"],
    "教室 id=1", "code=200，字段完整，occupiedSlots 与已通过预约一致", ["REQ-009"], [])
add("TC-CLASS-005", CLASS, "教室不存在返回 400", "P2", "api",
    ["GET /api/classroom/999999", "校验响应"],
    "id=999999", "code=400「教室不存在」", ["REQ-009"], [])
add("TC-CLASS-006", CLASS, "教室实时状态标签口径", "P1", "api",
    ["GET /api/classroom/list?date=<今日>", "比对当前时刻与 occupiedSlots 判定 statusLabel"],
    "今日含已通过预约的教室", "当前时刻∈[start,end) → 使用中；否则当前空闲（与需求口径一致）", ["REQ-005"], [])

# ===== 收藏 =====
add("TC-FAV-001", FAV, "收藏教室成功", "P1", "api",
    ["zhangsan 登录", "POST /api/favorite/2", "校验响应；GET /api/favorite/list 确认"],
    "教室 id=2", "code=200；收藏列表含 id=2", ["REQ-010"], ["RM-FAV-001"])
add("TC-FAV-002", FAV, "我的收藏列表", "P2", "api",
    ["GET /api/favorite/list", "校验返回"],
    "zhangsan（已有收藏）", "code=200，返回收藏教室列表（含教室信息）", ["REQ-010", "REQ-018"], [])
add("TC-FAV-003", FAV, "取消收藏", "P2", "api",
    ["POST /api/favorite/2（收藏）", "再次 POST /api/favorite/2（取消）", "GET 列表确认"],
    "教室 id=2", "第二次调用后列表不再含 id=2（切换收藏状态）", ["REQ-010"], [])
add("TC-FAV-004", FAV, "收藏上限 10 间", "P1", "api",
    ["前置：zhangsan 收藏满 10 间不同教室（含种子数据）", "再收藏第 11 间", "校验响应"],
    "已收藏 10 间", "第 11 间被拒：code=400「收藏数量已达上限」或等义提示；DB 无新增", ["REQ-010", "REQ-034"], ["RM-FAV-001"], "hybrid",
    so={"side_effect": "收藏数保持 10"})

# ===== 预约核心 =====
add("TC-RES-001", RES, "冲突检测：无重叠时段", "P0", "api",
    ["zhangsan 登录", "GET /api/reservation/conflict?classroomId=1&date=<未来日>&startTime=08:00&endTime=09:00", "校验"],
    "教室 1 未来日 08:00-09:00（无已通过预约）", "code=200，data.conflict=false", ["REQ-011", "REQ-031"], ["RM-CORE-001"], "automated",
    so={"technical_outcome": "conflict=false"})
add("TC-RES-002", RES, "冲突检测：部分重叠", "P0", "api",
    ["zhangsan 登录", "取一条已通过预约（教室 3 今日 15:00-17:00）", "GET /api/reservation/conflict?classroomId=3&date=<同日>&startTime=16:00&endTime=18:00", "校验"],
    "教室 3 同日 16:00-18:00 与 15:00-17:00 重叠", "code=200，data.conflict=true", ["REQ-011", "REQ-031"], ["RM-CORE-001"], "automated",
    so={"technical_outcome": "conflict=true"})
add("TC-RES-003", RES, "冲突检测：首尾相接不冲突（边界）", "P0", "api",
    ["zhangsan 登录", "取已通过预约 15:00-17:00", "GET /api/reservation/conflict?classroomId=3&date=<同日>&startTime=17:00&endTime=19:00", "校验"],
    "教室 3 同日 17:00-19:00（新开始=旧结束）", "code=200，data.conflict=false（首尾相接不冲突）", ["REQ-011", "REQ-031"], ["RM-CORE-001"], "automated",
    so={"technical_outcome": "conflict=false（边界）"})
add("TC-RES-004", RES, "冲突检测：完全包含", "P0", "api",
    ["zhangsan 登录", "GET /api/reservation/conflict?classroomId=3&date=<同日>&startTime=14:00&endTime=18:00", "校验"],
    "教室 3 同日 14:00-18:00 完全包含 15:00-17:00", "code=200，data.conflict=true", ["REQ-011", "REQ-031"], ["RM-CORE-001"])
add("TC-RES-005", RES, "提交预约成功（无冲突）", "P0", "api",
    ["zhangsan 登录", "POST /api/reservation body{classroomId:1,reserveDate:<未来日>,startTime:10:00,endTime:11:00,purpose:QA测试预约}", "校验返回 id", "GET /api/reservation/mine 确认"],
    "教室 1 未来日 10:00-11:00", "code=200，返回预约 id；状态=0 待审核", ["REQ-011"], ["RM-CORE-002"], "automated",
    so={"terminal": "待审核(0)"}, ev=["reservation_id"])
add("TC-RES-006", RES, "后端二次校验：直接提交重叠时段被拒（绕过前端）", "P0", "api",
    ["zhangsan 登录", "POST /api/reservation body{classroomId:3,reserveDate:<已通过同日>,startTime:16:00,endTime:18:00,purpose:冲突测试}", "校验响应", "确认未落库"],
    "与已通过 15:00-17:00 重叠", "code=400，提示与已通过预约冲突；数据库无新增待审核记录", ["REQ-011", "REQ-031"], ["RM-CORE-003"], "automated",
    so={"terminal": "提交被拒，无写入"}, ev=["no_db_insert"])
add("TC-RES-007", RES, "提交过去日期被拒", "P1", "api",
    ["POST /api/reservation body{reserveDate:<昨日>}", "校验"],
    "过去日期", "code=400，提示不可预约过去日期", ["REQ-011", "REQ-044"], [])
add("TC-RES-008", RES, "开始时间不早于结束被拒", "P1", "api",
    ["POST /api/reservation body{startTime:11:00,endTime:10:00}", "校验"],
    "start>=end", "code=400，提示开始时间必须早于结束时间", ["REQ-011", "REQ-044"], [])
add("TC-RES-009", RES, "停用教室预约被拒", "P1", "api",
    ["前置：管理员停用某教室", "POST /api/reservation 该教室", "校验"],
    "停用教室", "code=400「该教室已停用，无法预约」", ["REQ-011"], [], "hybrid")
add("TC-RES-010", RES, "我的预约列表与状态筛选", "P1", "api",
    ["zhangsan 登录", "GET /api/reservation/mine?page=1&size=10", "GET ?status=1", "校验"],
    "zhangsan 预约记录", "code=200；records 字段含 classroomName/building/roomNo/status；status=1 过滤正确", ["REQ-013"], [])
add("TC-RES-011", RES, "取消待审核预约成功", "P0", "api",
    ["zhangsan 新建待审核预约", "PUT /api/reservation/{id}/cancel", "GET mine 确认状态"],
    "新建待审核预约（开始>1h）", "code=200「取消成功」；状态变 3-已取消", ["REQ-013", "REQ-032"], ["RM-CORE-002"], "automated",
    so={"intermediate": "待审核(0)", "terminal": "已取消(3)"})
add("TC-RES-012", RES, "取消时限：开始前 1 小时内禁止取消", "P1", "api",
    ["前置：插入一条开始时间距当前<1h 的待审核/已通过预约（管理员造数或取库内今日即将开始记录）", "PUT /api/reservation/{id}/cancel", "校验"],
    "开始前 1 小时内", "code=400「预约开始前 1 小时内禁止取消，如需调整请联系管理员」；状态不变", ["REQ-033"], ["RM-CORE-004"], "hybrid",
    so={"terminal": "状态不变"})
add("TC-RES-013", RES, "取消他人预约被拒", "P1", "api",
    ["zhangsan 登录", "取 lisi 的待审核预约 id", "PUT /api/reservation/{lisiId}/cancel", "校验"],
    "他人预约", "code=400「只能取消自己的预约」", ["REQ-013", "REQ-032"], ["RM-CORE-002"])
add("TC-RES-014", RES, "已驳回/已取消预约不可再取消", "P1", "api",
    ["zhangsan 登录", "取一条已驳回/已取消预约 id", "PUT /api/reservation/{id}/cancel", "校验"],
    "状态=2 或 3 的预约", "code=400「当前状态不可取消」", ["REQ-032"], ["RM-CORE-002"])
add("TC-RES-015", RES, "非法状态参数返回 400", "P2", "api",
    ["GET /api/reservation/mine?status=9", "校验"],
    "status=9", "code=400「状态参数不合法」", ["REQ-044"], ["RM-VALID-001"])

# ===== 审核 =====
add("TC-AUD-001", AUD, "管理员全量查询预约（多条件筛选）", "P1", "api",
    ["admin 登录", "GET /api/reservation/manage?page=1&size=10&status=0", "校验"],
    "status=0 筛选", "code=200；records 全为待审核；含 userName/auditorName 等管理字段", ["REQ-024", "REQ-029"], [])
add("TC-AUD-002", AUD, "审核通过并落库审核人/时间", "P0", "api",
    ["admin 登录", "新建待审核预约", "PUT /api/reservation/{id}/audit body{status:1}", "GET manage 校验"],
    "待审核预约 id", "code=200「审核成功」；记录状态=1、auditorId=1、auditTime 非空", ["REQ-024", "REQ-032"], ["RM-CORE-002", "RM-DATA-001"], "automated",
    so={"intermediate": "待审核(0)", "terminal": "已通过(1)", "side_effect": "auditorId/auditTime 落库"})
add("TC-AUD-003", AUD, "审核驳回（备注必填）", "P0", "api",
    ["admin 登录", "新建待审核预约", "PUT /api/reservation/{id}/audit body{status:2,auditRemark:用途不明确}", "校验"],
    "待审核预约", "code=200；状态=2-已驳回，auditRemark 落库", ["REQ-024", "REQ-026"], ["RM-CORE-002"], "automated",
    so={"terminal": "已驳回(2)"})
add("TC-AUD-004", AUD, "驳回缺备注返回 400", "P1", "api",
    ["admin 登录", "PUT /api/reservation/{id}/audit body{status:2}", "校验"],
    "驳回无备注", "code=400「驳回必须填写审核备注」", ["REQ-024"], [])
add("TC-AUD-005", AUD, "非待审核状态不可再审核", "P1", "api",
    ["admin 登录", "取已通过/已驳回/已取消预约", "PUT /api/reservation/{id}/audit body{status:1}", "校验"],
    "状态≠0 的预约", "code=400「仅待审核状态的预约可审核」", ["REQ-024", "REQ-032"], ["RM-CORE-002"])
add("TC-AUD-006", AUD, "批量审核全部通过", "P1", "api",
    ["admin 登录", "新建 2 条待审核预约", "POST /api/reservation/batch-audit body{ids:[id1,id2],status:1}", "校验"],
    "2 条待审核", "code=200；两条均变已通过", ["REQ-025", "REQ-035"], ["RM-BATCH-001"], "automated",
    so={"terminal": "均已通过(1)"})
add("TC-AUD-007", AUD, "批量审核混入已审核记录被排除", "P1", "api",
    ["admin 登录", "取待审核 id1 + 已通过 id2", "POST /api/reservation/batch-audit body{ids:[id1,id2],status:1}", "校验两条最终状态"],
    "混入已通过记录", "待审核被通过；已通过记录不被重复处理（保持 1 或提示跳过）", ["REQ-025", "REQ-035"], ["RM-BATCH-001"], "automated",
    so={"side_effect": "已审核记录状态不被覆盖"})
add("TC-AUD-008", AUD, "学生访问审核接口 403", "P0", "api",
    ["zhangsan 登录", "PUT /api/reservation/1/audit", "校验"],
    "学生 token", "HTTP 403 + code=403", ["REQ-041"], ["RM-AUTH-001"])

# ===== 教室管理 =====
add("TC-ADMC-001", ADMC, "新增教室", "P1", "api",
    ["admin 登录", "POST /api/classroom/manage body{name:QA测试教室,building:信息楼,roomNo:QA101,type:1,capacity:30,equipment:无}", "校验返回 id"],
    "合法教室数据", "code=200 返回新 id；列表可见", ["REQ-023"], [], "automated", ev=["new_id"])
add("TC-ADMC-002", ADMC, "编辑教室", "P2", "api",
    ["admin 登录", "PUT /api/classroom/manage body{id:<新建id>,name:QA测试教室改,...}", "GET 校验"],
    "编辑新教室", "code=200「修改成功」，字段已更新", ["REQ-023"], [])
add("TC-ADMC-003", ADMC, "删除有预约记录的教室被拒", "P1", "api",
    ["admin 登录", "DELETE /api/classroom/manage/1（存在预约）", "校验"],
    "教室 id=1 有预约", "code=400，提示存在预约记录不可删除", ["REQ-023"], [])
add("TC-ADMC-004", ADMC, "启停教室", "P2", "api",
    ["admin 登录", "PUT /api/classroom/manage/{id}/status body{status:0}", "校验；再用学生端确认不可见/不可约"],
    "教室启停", "code=200；停用后学生端列表不返回该教室、预约被拒", ["REQ-023", "REQ-009"], [], "hybrid")
add("TC-ADMC-005", ADMC, "批量启停教室", "P2", "api",
    ["admin 登录", "POST /api/classroom/manage/batch-status body{ids:[...],status:1}", "校验"],
    "批量启用", "code=200，返回更新数量", ["REQ-023"], [])
add("TC-ADMC-006", ADMC, "新增教室参数校验（缺必填/容量≤0）", "P1", "api",
    ["admin 登录", "POST body 缺 name；POST body 容量=0", "校验"],
    "非法教室数据", "code=400 明确提示；容量>0 校验生效", ["REQ-044"], ["RM-VALID-001"])

# ===== 用户管理 =====
add("TC-ADMU-001", ADMU, "用户分页查询", "P1", "api",
    ["admin 登录", "GET /api/user/manage?page=1&size=10", "校验"],
    "分页查询", "code=200，records 含用户信息与 role/status", ["REQ-028"], [])
add("TC-ADMU-002", ADMU, "禁用用户后其登录被拒", "P1", "api",
    ["admin 登录", "PUT /api/user/manage/{lisiId}/status body{status:0}", "lisi 登录", "校验"],
    "禁用 lisi", "禁用成功；lisi 登录 → 403/400 提示账号禁用", ["REQ-028", "REQ-041"], ["RM-AUTH-003"], "hybrid",
    so={"side_effect": "禁用后登录被拒"})
add("TC-ADMU-003", ADMU, "重置密码为默认 123456", "P2", "api",
    ["admin 登录", "PUT /api/user/manage/{id}/password", "用新密码登录"],
    "重置某用户", "code=200；该用户可用 123456 登录", ["REQ-028"], [])
add("TC-ADMU-004", ADMU, "禁止管理员操作自己", "P1", "api",
    ["admin 登录", "PUT /api/user/manage/1/status body{status:0}", "校验"],
    "操作 admin 自己(id=1)", "code=400，提示不能操作自己", ["REQ-028"], ["RM-AUTH-003"])

# ===== 统计看板 =====
add("TC-STAT-001", STAT, "首页数据概览", "P1", "api",
    ["admin 登录", "GET /api/stats/overview", "校验四类数据"],
    "概览", "code=200，含今日预约/待审核/教室总数/用户总数，口径与库一致", ["REQ-022"], [])
add("TC-STAT-002", STAT, "教室使用率排行", "P1", "api",
    ["GET /api/stats/usage-rate", "校验"],
    "使用率", "code=200，返回按使用率排序的教室列表（含使用率数值）", ["REQ-030"], [])
add("TC-STAT-003", STAT, "月度预约趋势", "P1", "api",
    ["GET /api/stats/trend?days=30", "校验"],
    "趋势", "code=200，返回每日/每周预约数量序列", ["REQ-030"], [])
add("TC-STAT-004", STAT, "热门时段分布", "P1", "api",
    ["GET /api/stats/time-distribution", "校验"],
    "时段分布", "code=200，返回各时段预约占比数据", ["REQ-030"], [])

# ===== 预约记录/导出 =====
add("TC-REC-001", REC, "预约记录多条件筛选", "P1", "api",
    ["admin 登录", "GET /api/reservation/manage?status=1&startDate=<7日前>&endDate=<今日>&keyword=信息楼", "校验"],
    "多条件组合", "code=200，结果满足全部条件（状态/日期区间/关键词）", ["REQ-029"], [])
add("TC-REC-002", REC, "Excel 导出完整性", "P1", "api",
    ["admin 登录", "GET /api/reservation/export（带筛选）", "下载文件", "打开校验行数与中文"],
    "导出预约记录", "返回 xlsx 文件可打开；行数=筛选 total；中文正常无乱码；首行为表头", ["REQ-029"], ["RM-EXPORT-001"], "hybrid",
    so={"technical_outcome": "xlsx 可解析"}, ev=["xlsx_file"])

# ===== 个人中心 =====
add("TC-PROF-001", PROF, "修改个人信息", "P2", "api",
    ["zhangsan 登录", "PUT /api/user/info body{name:张三改,email:new@stu.edu.cn,phone:13800000009}", "GET info 校验"],
    "修改资料", "code=200；info 返回更新后字段", ["REQ-016"], [])
add("TC-PROF-002", PROF, "修改密码（旧密码校验）", "P1", "api",
    ["zhangsan 登录", "PUT /api/user/password body{oldPassword:wrong,newPassword:new123}", "校验", "PUT body{oldPassword:123456,newPassword:new123}", "新密码登录"],
    "错误旧密码→正确旧密码", "旧密码错误 → code=400；旧密码正确 → 成功且新密码可登录", ["REQ-016"], [])
add("TC-PROF-003", PROF, "个人预约数据概览", "P2", "api",
    ["zhangsan 登录", "GET /api/user/stats", "校验"],
    "数据概览", "code=200，返回预约总数/本月/通过率等口径数据", ["REQ-017"], [])

# ===== 日历 =====
add("TC-CAL-001", CAL, "日历事件数据（当月已通过预约）", "P1", "api",
    ["zhangsan 登录", "GET /api/reservation/calendar?month=<当月>", "校验"],
    "当月日历", "code=200，返回当月已通过预约事件（含日期、教室、时段），日期为 yyyy-MM-dd", ["REQ-021"], [])

# ===== AI =====
add("TC-AI-001", AI, "智能教室推荐 Top3（本地规则/降级路径）", "P1", "api",
    ["zhangsan 登录", "POST /api/ai/recommend body{userId:2}", "校验"],
    "有预约历史学生", "code=200，返回 Top3 推荐教室（含教室信息与推荐理由）；AI 关闭/降级时仍可用", ["REQ-007", "REQ-038"], ["RM-AI-001"])
add("TC-AI-002", AI, "自然语言预约解析", "P1", "api",
    ["zhangsan 登录", "POST /api/ai/parse-reservation body{text:我想要一个机房，明天下午两小时}", "校验结构化结果"],
    "口语化需求", "code=200，返回 date/startTime/endTime/capacity/roomType/purpose 结构化字段，解析合理", ["REQ-008"], ["RM-AI-001"])
add("TC-AI-003", AI, "预约合规校验命中违规", "P1", "api",
    ["admin 登录", "POST /api/ai/compliance-check body{purpose:商业推销宣讲会}", "校验"],
    "违规用途（商业推销）", "code=200，compliant=false 或本地关键词命中违规提示原因", ["REQ-027"], ["RM-AI-001"])
add("TC-AI-004", AI, "智能助手场景限定：无关话题返回预设话术", "P2", "api",
    ["zhangsan 登录", "POST /api/ai/chat body{question:今天天气怎么样}", "校验"],
    "无关话题", "code=200，返回预设话术（仅回答预约业务）而非真实答案", ["REQ-020", "REQ-036"], ["RM-AI-003"])
add("TC-AI-005", AI, "AI 只读不写：调用链无业务写入", "P0", "api",
    ["zhangsan 登录", "调用 recommend/parse/chat/compliance 各一次", "比对前后预约/收藏/用户表数据"],
    "AI 全接口调用", "数据库预约/收藏/用户表无 AI 直接产生的变更（快照对比一致）", ["REQ-037"], ["RM-AI-002"], "hybrid",
    so={"side_effect": "无 AI 业务写入"}, ev=["db_snapshot"])
add("TC-AI-006", AI, "AI 双开关：关闭时入口隐藏/降级", "P1", "api",
    ["确认 ai.enable=false（当前配置）", "访问前端教室列表页", "检查 AI 推荐卡/快速预约入口；调用 AI 接口"],
    "双开关 false", "前端不展示 AI 推荐卡与快速预约入口；AI 接口返回降级/关闭提示，页面可用", ["REQ-038", "REQ-039"], ["RM-AI-001"], "hybrid",
    so={"technical_outcome": "AI 入口隐藏，业务可用"})

# ===== UI E2E（浏览器） =====
add("TC-UI-001", "前端E2E", "学生登录→教室列表渲染→筛选", "P1", "ui_e2e",
    ["打开 http://127.0.0.1:5173", "以 zhangsan/123456 学生角色登录", "进入教室列表页，观察卡片/状态标签/分页", "执行楼栋筛选", "检查控制台错误"],
    "浏览器 Chrome", "登录成功跳转 /student/home；教室卡片渲染含状态标签；筛选生效；无控制台报错", ["REQ-001", "REQ-004", "REQ-005"], [], "automated", ev=["screenshot", "console_log"])
add("TC-UI-002", "前端E2E", "教室详情→预约弹窗→前端冲突检测提示", "P1", "ui_e2e",
    ["进入某教室详情页", "点击预约申请", "选择与已通过冲突时段", "观察冲突提示与提交禁用", "选择无冲突时段提交"],
    "浏览器 Chrome", "冲突时段实时提示并禁用提交；无冲突可提交成功进入待审核", ["REQ-009", "REQ-011", "REQ-031"], ["RM-CORE-001"], "automated", ev=["screenshot"])
add("TC-UI-003", "前端E2E", "我的预约页状态筛选与取消", "P1", "ui_e2e",
    ["学生进入我的预约页", "切换状态标签（待审核/已通过/已驳回/已取消）", "尝试取消一条预约（二次确认）"],
    "浏览器 Chrome", "状态切换正确；取消弹二次确认；确认后状态更新为已取消", ["REQ-013", "REQ-014", "REQ-015"], [], "automated", ev=["screenshot"])
add("TC-UI-004", "前端E2E", "预约日历总览渲染", "P1", "ui_e2e",
    ["学生进入日历页", "月/周视图切换", "观察已通过预约事件色块"],
    "浏览器 Chrome", "日历渲染无 JS 错误；已通过预约以事件展示且日期正确", ["REQ-021"], [], "automated", ev=["screenshot"])
add("TC-UI-005", "前端E2E", "管理端登录→审核流程（通过/驳回/快捷原因/批量）", "P1", "ui_e2e",
    ["admin/admin123 管理员登录", "进入预约审核页", "对一条待审核执行通过", "对另一条执行驳回并选快捷原因", "勾选批量通过"],
    "浏览器 Chrome", "审核操作生效且状态即时刷新；快捷原因可回填；批量操作提示成功", ["REQ-024", "REQ-025", "REQ-026"], ["RM-BATCH-001"], "automated", ev=["screenshot"])
add("TC-UI-006", "前端E2E", "数据看板三图表渲染", "P1", "ui_e2e",
    ["管理员进入数据看板页", "观察柱状图/折线图/饼图渲染", "切换时间范围"],
    "浏览器 Chrome", "三图表正常渲染无报错；数据与接口一致", ["REQ-030"], [], "automated", ev=["screenshot"])
add("TC-UI-007", "前端E2E", "登录提醒与个人中心概览", "P2", "ui_e2e",
    ["以含今日即将开始预约的学生（如 lisi，今日 15:00 电路实验已通过）登录", "观察顶部提醒", "进入个人中心观察数据卡片/常用教室"],
    "浏览器 Chrome", "登录后顶部温和提醒显示；个人中心数据卡片与收藏入口渲染", ["REQ-003", "REQ-017", "REQ-018"], [], "automated", ev=["screenshot"])

run["cases"] = C

with io.open(PATH, "w", encoding="utf-8") as f:
    json.dump(run, f, ensure_ascii=False, indent=2)

print("cases added:", len(C))
