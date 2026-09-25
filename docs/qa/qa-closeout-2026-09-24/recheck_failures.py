# -*- coding: utf-8 -*-
"""复查失败用例：区分产品缺陷 vs 测试脚本问题"""
import urllib.request, urllib.parse, json, io, datetime

BASE = "http://127.0.0.1:8080"

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

STU = login("zhangsan", "123456", 0)
ADM = login("admin", "admin123", 1)

print("=" * 70)
print("1) TC-CLASS-006 教室列表原始字段（statusLabel / occupiedSlots）")
s, d = api("GET", "/api/classroom/list?page=1&size=3", token=STU)
for r in d.get("data", {}).get("records", []):
    print("  id=%s name=%s statusLabel=%r occupiedSlots=%r" % (r.get("id"), r.get("name"), r.get("statusLabel"), r.get("occupiedSlots")))

print("=" * 70)
print("2) TC-FAV-004 收藏上限：当前 zhangsan 收藏数 + 逐间加到 10 再试第 11")
s, d = api("GET", "/api/favorite/list", token=STU)
cur = [f.get("classroomId") for f in (d.get("data") or [])]
print("  当前收藏:", cur, "count=", len(cur))
# 取消全部再重加，观察去重与上限
for cid in list(cur):
    api("POST", "/api/favorite/%d" % cid, token=STU)
s, d = api("GET", "/api/favorite/list", token=STU)
print("  全切后收藏:", [f.get("classroomId") for f in (d.get("data") or [])])
# 补齐到 10
need = [c for c in range(1, 13) if c not in [f.get("classroomId") for f in (d.get("data") or [])]][:10 - len(d.get("data") or [])]
for cid in need:
    s, d2 = api("POST", "/api/favorite/%d" % cid, token=STU)
    print("  收藏 %d → code=%s msg=%s" % (cid, d2.get("code"), d2.get("message")))
s, d = api("GET", "/api/favorite/list", token=STU)
cur2 = [f.get("classroomId") for f in (d.get("data") or [])]
print("  现有收藏数:", len(cur2))
# 第 11 间
for cid in range(1, 13):
    if cid not in cur2:
        s, d2 = api("POST", "/api/favorite/%d" % cid, token=STU)
        print("  第11间 收藏 %d → code=%s msg=%s" % (cid, d2.get("code"), d2.get("message")))
        break
# 清理回种子状态（zhangsan 种子收藏 1,3）
for cid in cur2:
    if cid not in (1, 3):
        api("POST", "/api/favorite/%d" % cid, token=STU)

print("=" * 70)
print("3) TC-AUD-006 批量审核接口实际行为")
s, d = api("POST", "/api/reservation", {"classroomId": 5, "reserveDate": (datetime.date.today() + datetime.timedelta(days=6)).strftime("%Y-%m-%d"), "startTime": "14:00:00", "endTime": "15:00:00", "purpose": "QA批量复查1"}, token=LISI if False else STU)
print("  提交预约1:", d.get("code"), d.get("message"), d.get("data"))
b1 = d.get("data")
s, d = api("POST", "/api/reservation", {"classroomId": 5, "reserveDate": (datetime.date.today() + datetime.timedelta(days=6)).strftime("%Y-%m-%d"), "startTime": "15:30:00", "endTime": "16:30:00", "purpose": "QA批量复查2"}, token=STU)
print("  提交预约2:", d.get("code"), d.get("message"), d.get("data"))
b2 = d.get("data")
s, d = api("POST", "/api/reservation/batch-audit", {"ids": [b1, b2], "status": 1}, token=ADM)
print("  批量通过 → http=%s code=%s msg=%s data=%s" % (s, d.get("code"), d.get("message"), d.get("data")))
# 查状态
s, d = api("GET", "/api/reservation/manage?page=1&size=100", token=ADM)
st = {r.get("id"): r.get("status") for r in (d.get("data", {}).get("records") or [])}
print("  批量后状态 b1=%s b2=%s" % (st.get(b1), st.get(b2)))

print("=" * 70)
print("4) TC-PROF-002 修改密码接口实际行为（新密码格式）")
s, d = api("PUT", "/api/user/password", {"oldPassword": "123456", "newPassword": "qa_new_pass_9"}, token=STU)
print("  正确旧密码+新密码(qa_new_pass_9) → code=%s msg=%s" % (d.get("code"), d.get("message")))
if d.get("code") == 200:
    api("PUT", "/api/user/password", {"oldPassword": "qa_new_pass_9", "newPassword": "123456"}, token=STU)
    print("  已恢复密码")

print("=" * 70)
print("5) TC-CAL-001 日历接口：month 参数格式与返回结构")
for m in ["2026-09", "2026-09-01", None]:
    path = "/api/reservation/calendar" + (("?month=%s" % m) if m else "")
    s, d = api("GET", path, token=STU)
    data = d.get("data")
    if isinstance(data, dict):
        print("  month=%s → code=%s keys=%s" % (m, d.get("code"), list(data.keys())))
    elif isinstance(data, list):
        print("  month=%s → code=%s events=%d first=%s" % (m, d.get("code"), len(data), json.dumps(data[0], ensure_ascii=False)[:120] if data else None))
    else:
        print("  month=%s → code=%s data=%r" % (m, d.get("code"), data))

print("=" * 70)
print("6) TC-AI-001 推荐接口关闭时返回结构")
s, d = api("POST", "/api/ai/recommend", {"userId": 2}, token=STU)
print("  code=%s msg=%s data=%s" % (d.get("code"), d.get("message"), json.dumps(d.get("data"), ensure_ascii=False)[:300]))
