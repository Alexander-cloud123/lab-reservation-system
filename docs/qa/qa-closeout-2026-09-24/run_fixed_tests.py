# -*- coding: utf-8 -*-
"""修正版重跑失败/未执行用例：TC-CLASS-006, FAV-004, RES-001/006/012, AUD-003/004/006, PROF-002, CAL-001, AI-001"""
import urllib.request, urllib.parse, json, io, datetime

BASE = "http://127.0.0.1:8080"
OUT = r"C:\Users\72797\Course\reservation-system\qa-results\reservation-e2e\api-results-fixed.json"
results = []

def api(method, path, body=None, token=None, timeout=20):
    url = BASE + path
    data = None
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = "Bearer " + token
    if body is not None: data = json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())

def login(u, p, r):
    s, d = api("POST", "/api/user/login", {"username": u, "password": p, "role": r})
    return d.get("data", {}).get("token") if d.get("code") == 200 else None

def days(n, fmt="%Y-%m-%d"):
    return (datetime.date.today() + datetime.timedelta(days=n)).strftime(fmt)

def to_min(t):
    h, m = map(int, t.split(":"))
    return h * 60 + m

def check(cid, cond, actual, extra=None):
    results.append({"case_id": cid, "status": "passed" if cond else "failed", "actual": actual, **(extra or {})})
    print(("[PASS] " if cond else "[FAIL] ") + cid + " | " + actual[:170])

STU = login("zhangsan", "123456", 0)
ADM = login("admin", "admin123", 1)

# ---- TC-CLASS-006 状态标签（用 statusLabel 字段）----
s, d = api("GET", "/api/classroom/list?date=%s" % days(0), token=STU)
recs = d.get("data", {}).get("records", [])
labels = set(r.get("statusLabel") for r in recs)
check("TC-CLASS-006", "使用中" in labels or "当前空闲" in labels,
      "statusLabel 取值=%s" % json.dumps(list(labels), ensure_ascii=False))

# ---- TC-FAV-004 收藏上限：先清空再补齐到 10，第 11 间应被拒 ----
s, d = api("GET", "/api/favorite/list", token=STU)
cur = [f.get("classroomId") for f in (d.get("data") or [])]
for cid in list(cur):
    api("POST", "/api/favorite/%d" % cid, token=STU)  # 切换取消
s, d = api("GET", "/api/favorite/list", token=STU)
now_ids = [f.get("classroomId") for f in (d.get("data") or [])]
need = [c for c in range(1, 13) if c not in now_ids][:10 - len(now_ids)]
for cid in need:
    api("POST", "/api/favorite/%d" % cid, token=STU)
s, d = api("GET", "/api/favorite/list", token=STU)
count10 = len(d.get("data") or [])
ids10 = set(f.get("classroomId") for f in (d.get("data") or []))
eleven = [c for c in range(1, 13) if c not in ids10][0]
s, d2 = api("POST", "/api/favorite/%d" % eleven, token=STU)
check("TC-FAV-004", count10 >= 10 and d2.get("code") == 400 and "上限" in (d2.get("message") or ""),
      "收藏数=%d 第11间code=%s msg=%s" % (count10, d2.get("code"), d2.get("message")))
# 清理回空（种子变化前为 [8,11,12]，恢复到 8,11,12）
for cid in ids10:
    if cid not in (8, 11, 12):
        api("POST", "/api/favorite/%d" % cid, token=STU)
for cid in (8, 11, 12):
    if cid not in ids10:
        api("POST", "/api/favorite/%d" % cid, token=STU)

# ---- TC-RES-001/003/004 用未来日参照（取一条未来日已通过预约）----
s, d = api("GET", "/api/reservation/manage?page=1&size=100&status=1", token=ADM)
approved = [r for r in (d.get("data", {}).get("records") or [])]
ref = None
for r in approved:
    if r.get("reserveDate") >= days(1) and r.get("classroomId") in (1, 3, 5):
        ref = r
        break
if not ref:
    for r in approved:
        if r.get("reserveDate") >= days(0):
            ref = r
            break
print("参照预约: cid=%s date=%s %s-%s" % (ref["classroomId"], ref["reserveDate"], ref["startTime"], ref["endTime"]))
rc, rd, rs, re = ref["classroomId"], ref["reserveDate"], ref["startTime"], ref["endTime"]

# 找一个绝对空闲时段（10:00-11:00 若与参照无重叠即用，否则 18:00-20:00）
def conflict_of(cid, date, st, et):
    s, d = api("GET", "/api/reservation/conflict?classroomId=%s&date=%s&startTime=%s&endTime=%s" % (cid, date, st, et), token=STU)
    return d.get("data", {}).get("conflict")

free_st, free_et = "18:00", "20:00"
if conflict_of(rc, rd, free_st, free_et):
    free_st, free_et = "06:00", "07:00"
s, d = api("GET", "/api/reservation/conflict?classroomId=%s&date=%s&startTime=%s&endTime=%s" % (rc, rd, free_st, free_et), token=STU)
check("TC-RES-001", d.get("data", {}).get("conflict") is False,
      "无重叠(%s-%s) conflict=%s" % (free_st, free_et, d.get("data", {}).get("conflict")))

# 部分重叠（参照前移 30 分钟）
over_st = "%02d:%02d" % ((to_min(rs) + 30) // 60, (to_min(rs) + 30) % 60)
over_et = "%02d:%02d" % ((to_min(re) + 30) // 60, (to_min(re) + 30) % 60)
s, d = api("GET", "/api/reservation/conflict?classroomId=%s&date=%s&startTime=%s&endTime=%s" % (rc, rd, over_st, over_et), token=STU)
check("TC-RES-002", d.get("data", {}).get("conflict") is True,
      "部分重叠(%s-%s) conflict=%s" % (over_st, over_et, d.get("data", {}).get("conflict")))

# 首尾相接（新开始=旧结束）
adj_st = re
adj_et = "%02d:%02d" % ((to_min(re) + 120) // 60, (to_min(re) + 120) % 60)
s, d = api("GET", "/api/reservation/conflict?classroomId=%s&date=%s&startTime=%s&endTime=%s" % (rc, rd, adj_st, adj_et), token=STU)
check("TC-RES-003", d.get("data", {}).get("conflict") is False,
      "首尾相接(%s-%s) conflict=%s" % (adj_st, adj_et, d.get("data", {}).get("conflict")))

# 完全包含（参照前后各扩 1 小时）
inc_st = "%02d:%02d" % ((to_min(rs) - 60) // 60, (to_min(rs) - 60) % 60)
inc_et = "%02d:%02d" % ((to_min(re) + 60) // 60, (to_min(re) + 60) % 60)
s, d = api("GET", "/api/reservation/conflict?classroomId=%s&date=%s&startTime=%s&endTime=%s" % (rc, rd, inc_st, inc_et), token=STU)
check("TC-RES-004", d.get("data", {}).get("conflict") is True,
      "完全包含(%s-%s) conflict=%s" % (inc_st, inc_et, d.get("data", {}).get("conflict")))

# ---- TC-RES-006 后端兜底：直接提交重叠时段（未来日参照）----
s, d = api("POST", "/api/reservation", {"classroomId": rc, "reserveDate": rd, "startTime": over_st, "endTime": over_et, "purpose": "QA冲突兜底测试"}, token=STU)
check("TC-RES-006", d.get("code") == 400 and "冲突" in (d.get("message") or ""),
      "重叠提交被拒 code=%s msg=%s" % (d.get("code"), d.get("message")))

# ---- TC-RES-012 取消时限：造数（一次正确提交 + 审核通过）----
soon_st = (datetime.datetime.now() + datetime.timedelta(minutes=30)).strftime("%H:%M")
soon_et = (datetime.datetime.now() + datetime.timedelta(minutes=90)).strftime("%H:%M")
# 确保不与参照冲突：用不同教室（教室2 或未来日）
s, d = api("POST", "/api/reservation", {"classroomId": 2, "reserveDate": days(0), "startTime": soon_st, "endTime": soon_et, "purpose": "QA时限测试"}, token=STU)
soon_id = d.get("data")
print("时限造数 id=%s code=%s msg=%s" % (soon_id, d.get("code"), d.get("message")))
if isinstance(soon_id, int):
    api("PUT", "/api/reservation/%s/audit" % soon_id, {"status": 1}, token=ADM)
    s, d = api("PUT", "/api/reservation/%s/cancel" % soon_id, token=STU)
    check("TC-RES-012", d.get("code") == 400 and "1 小时" in (d.get("message") or ""),
          "1小时内取消 code=%s msg=%s" % (d.get("code"), d.get("message")))
else:
    # 教室2 今日可能已占用；改用明日 + 未来半小时
    s, d = api("POST", "/api/reservation", {"classroomId": 12, "reserveDate": days(1), "startTime": soon_st, "endTime": soon_et, "purpose": "QA时限测试2"}, token=STU)
    soon_id = d.get("data")
    print("时限造数(2) id=%s code=%s msg=%s" % (soon_id, d.get("code"), d.get("message")))
    if isinstance(soon_id, int):
        api("PUT", "/api/reservation/%s/audit" % soon_id, {"status": 1}, token=ADM)
        s, d = api("PUT", "/api/reservation/%s/cancel" % soon_id, token=STU)
        check("TC-RES-012", d.get("code") == 400 and "1 小时" in (d.get("message") or ""),
              "1小时内取消(2) code=%s msg=%s" % (d.get("code"), d.get("message")))

# ---- TC-AUD-003/004/005 审核样本：创建 3 条（时间不冲突）----
audit_ids = []
for i in range(3):
    st = "%02d:00" % (8 + i * 2)
    et = "%02d:00" % (9 + i * 2)
    s, d = api("POST", "/api/reservation", {"classroomId": 6, "reserveDate": days(7), "startTime": st, "endTime": et, "purpose": "QA审核样本%d" % (i + 1)}, token=STU)
    if isinstance(d.get("data"), int):
        audit_ids.append(d["data"])
print("audit 样本:", audit_ids)
if len(audit_ids) >= 3:
    # AUD-002 通过
    s, d = api("PUT", "/api/reservation/%s/audit" % audit_ids[0], {"status": 1}, token=ADM)
    s2, d2 = api("GET", "/api/reservation/manage?page=1&size=200", token=ADM)
    rec = [r for r in (d2.get("data", {}).get("records") or []) if r.get("id") == audit_ids[0]]
    check("TC-AUD-002", s == 200 and d.get("code") == 200 and rec and rec[0].get("status") == 1
          and rec[0].get("auditorId") and rec[0].get("auditTime"),
          "通过 code=%s 状态=%s auditorId=%s" % (d.get("code"), rec[0].get("status") if rec else None, rec[0].get("auditorId") if rec else None))
    # AUD-003 驳回带备注
    s, d = api("PUT", "/api/reservation/%s/audit" % audit_ids[1], {"status": 2, "auditRemark": "用途不明确，请补充具体教学/实验用途"}, token=ADM)
    s2, d2 = api("GET", "/api/reservation/manage?page=1&size=200", token=ADM)
    rec = [r for r in (d2.get("data", {}).get("records") or []) if r.get("id") == audit_ids[1]]
    check("TC-AUD-003", s == 200 and d.get("code") == 200 and rec and rec[0].get("status") == 2,
          "驳回 code=%s 状态=%s 备注=%s" % (d.get("code"), rec[0].get("status") if rec else None, rec[0].get("auditRemark") if rec else None))
    # AUD-004 驳回缺备注
    s, d = api("PUT", "/api/reservation/%s/audit" % audit_ids[2], {"status": 2}, token=ADM)
    check("TC-AUD-004", d.get("code") == 400 and "备注" in (d.get("message") or ""),
          "缺备注 code=%s msg=%s" % (d.get("code"), d.get("message")))
    # AUD-005 非待审核再审核（已通过的样本1）
    s, d = api("PUT", "/api/reservation/%s/audit" % audit_ids[0], {"status": 2, "auditRemark": "重复审核"}, token=ADM)
    check("TC-AUD-005", d.get("code") == 400 and "待审核" in (d.get("message") or ""),
          "非待审核再审核 code=%s msg=%s" % (d.get("code"), d.get("message")))
else:
    print("audit 样本不足，跳过 AUD-002/003/004/005")

# ---- TC-AUD-006 批量审核（用复查已通过的 79/80 与新建两条）----
b1, b2 = None, None
s, d = api("POST", "/api/reservation", {"classroomId": 7, "reserveDate": days(8), "startTime": "10:00:00", "endTime": "11:00:00", "purpose": "QA批量A"}, token=STU)
b1 = d.get("data") if isinstance(d.get("data"), int) else None
s, d = api("POST", "/api/reservation", {"classroomId": 7, "reserveDate": days(8), "startTime": "11:30:00", "endTime": "12:30:00", "purpose": "QA批量B"}, token=STU)
b2 = d.get("data") if isinstance(d.get("data"), int) else None
if b1 and b2:
    s, d = api("POST", "/api/reservation/batch-audit", {"ids": [b1, b2], "status": 1}, token=ADM)
    s2, d2 = api("GET", "/api/reservation/manage?page=1&size=200", token=ADM)
    st = {r.get("id"): r.get("status") for r in (d2.get("data", {}).get("records") or [])}
    check("TC-AUD-006", s == 200 and d.get("code") == 200 and st.get(b1) == 1 and st.get(b2) == 1,
          "批量通过 code=%s data=%s 状态=%s/%s" % (d.get("code"), d.get("data"), st.get(b1), st.get(b2)))
    # AUD-007 混入已审核
    s, d = api("POST", "/api/reservation/batch-audit", {"ids": [b1, b2], "status": 2, "auditRemark": "批量驳回测试"}, token=ADM)
    s2, d2 = api("GET", "/api/reservation/manage?page=1&size=200", token=ADM)
    st = {r.get("id"): r.get("status") for r in (d2.get("data", {}).get("records") or [])}
    check("TC-AUD-007", st.get(b1) == 1 and st.get(b2) == 1,
          "混入已审核后 b1=%s b2=%s（保持已通过不被覆盖）" % (st.get(b1), st.get(b2)))
else:
    print("批量样本创建失败 b1=%s b2=%s" % (b1, b2))

# ---- TC-PROF-002 修改密码（补 confirmPassword）----
s, d = api("PUT", "/api/user/password", {"oldPassword": "wrongold", "newPassword": "qa_newpass", "confirmPassword": "qa_newpass"}, token=STU)
bad = d.get("code") == 400
s, d = api("PUT", "/api/user/password", {"oldPassword": "123456", "newPassword": "qa_newpass", "confirmPassword": "qa_newpass"}, token=STU)
ok = s == 200 and d.get("code") == 200
t2 = login("zhangsan", "qa_newpass", 0)
new_login = t2 is not None
api("PUT", "/api/user/password", {"oldPassword": "qa_newpass", "newPassword": "123456", "confirmPassword": "123456"}, token=STU)
check("TC-PROF-002", bad and ok and new_login,
      "旧密码错拒绝=%s 改密=%s 新密码登录=%s" % (bad, ok, new_login))

# ---- TC-CAL-001 日历（startDate/endDate 区间）----
s, d = api("GET", "/api/reservation/calendar?startDate=%s&endDate=%s" % (days(-7), days(30)), token=STU)
events = d.get("data") if isinstance(d.get("data"), list) else []
check("TC-CAL-001", s == 200 and d.get("code") == 200 and isinstance(events, list) and len(events) > 0,
      "日历区间 events=%d 首条=%s" % (len(events), json.dumps(events[0], ensure_ascii=False)[:100] if events else None))

# ---- TC-AI-001 推荐（关闭时结构化降级 / 开启时 Top3）----
s, d = api("POST", "/api/ai/recommend", {"userId": 2}, token=STU)
data = d.get("data") or {}
if data.get("enabled") is False:
    cond = s == 200 and d.get("code") == 200 and "enabled" in data
    check("TC-AI-001", cond, "AI关闭降级 code=%s data=%s" % (d.get("code"), json.dumps(data, ensure_ascii=False)[:120]))
else:
    recs = data.get("recommendations") or []
    check("TC-AI-001", isinstance(recs, list) and len(recs) <= 3,
          "推荐条数=%d" % len(recs))

passed = sum(1 for r in results if r["status"] == "passed")
failed = sum(1 for r in results if r["status"] == "failed")
with io.open(OUT, "w", encoding="utf-8") as f:
    json.dump({"total": len(results), "passed": passed, "failed": failed, "results": results}, f, ensure_ascii=False, indent=2)
print("=" * 60)
print("FIXED RUN total=%d passed=%d failed=%d" % (len(results), passed, failed))
