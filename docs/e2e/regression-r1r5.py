# -*- coding: utf-8 -*-
"""R1-R5 修复回归验证脚本（API 级 + 库级不变量）
前置：MySQL/Redis/后端(8080)/前端(5173) 已启动；脚本自动登录（口令可用环境变量覆盖）
"""
import json
import subprocess
import sys
import threading
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
RESULTS = []


def call(method, path, token=None, body=None):
    req = urllib.request.Request(BASE + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8"))
        except Exception:
            return e.code, {}
    except Exception as e:
        return -1, {"error": str(e)}


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print(("PASS" if ok else "FAIL") + " | " + name + (" | " + detail if detail else ""))


def concurrent(calls):
    n = len(calls)
    barrier = threading.Barrier(n)
    results = [None] * n

    def worker(i, fn):
        barrier.wait()
        results[i] = fn()

    ts = [threading.Thread(target=worker, args=(i, c)) for i, c in enumerate(calls)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    return results


def db(query):
    p = subprocess.run(
        ["docker", "exec", "reservation-mysql", "mysql", "-uroot", "-proot", "-N", "-e", "USE reservation; " + query],
        capture_output=True, text=True, encoding="utf-8")
    return p.stdout.strip()


import os
def login(username, password, role):
    st, body = call("POST", "/api/user/login",
                    body={"username": username, "password": password, "role": role})
    assert st == 200 and body.get("code") == 200, f"{username} 登录失败: {body}"
    return body["data"]["token"]
def main():
    # 口令可用环境变量覆盖；默认值与 README.md 的演示账号一致
    ADMIN_PW = os.environ.get("REGRESSION_ADMIN_PW", "admin123")
    STU_PW   = os.environ.get("REGRESSION_STU_PW", "123456")
    ADMIN  = login("admin",    ADMIN_PW, 1)
    STU    = login("zhangsan", STU_PW,   0)
    WANGWU = login("wangwu",   STU_PW,   0)
    print("== 登录 OK，开始回归 ==")

    # ========== R4：预约用途长度校验 ==========
    long_purpose = "用" * 300
    code, r = call("POST", "/api/reservation", STU,
                   {"classroomId": 12, "reserveDate": "2026-09-25", "startTime": "14:00", "endTime": "15:00",
                    "purpose": long_purpose})
    check("R4 直调 300 字用途被 400 拦截", r.get("code") == 400 and "过长" in r.get("message", ""),
          "http=%s code=%s msg=%s" % (code, r.get("code"), r.get("message")))
    code, r = call("POST", "/api/reservation", STU,
                   {"classroomId": 12, "reserveDate": "2026-09-25", "startTime": "14:00", "endTime": "15:00",
                    "purpose": "   "})
    check("R4 空用途仍 400（既有行为不回归）", r.get("code") == 400 and "不能为空" in r.get("message", ""),
          "http=%s code=%s msg=%s" % (code, r.get("code"), r.get("message")))

    # ========== R5 准备：wangwu 在教室 6 的 09-18 新增并审核通过一条预约 ==========
    code, r = call("POST", "/api/reservation", WANGWU,
                   {"classroomId": 6, "reserveDate": "2026-09-18", "startTime": "10:00", "endTime": "12:00",
                    "purpose": "王五的测试用途"})
    wangwu_res_id = r.get("data") if r.get("code") == 200 else None
    check("R5 准备：wangwu 提交教室6/09-18 10-12 预约成功", r.get("code") == 200 and wangwu_res_id, "id=%s" % wangwu_res_id)
    code, r = call("PUT", "/api/reservation/%s/audit" % wangwu_res_id, ADMIN, {"status": 1})
    check("R5 准备：管理员审核通过", r.get("code") == 200, "http=%s code=%s msg=%s" % (code, r.get("code"), r.get("message")))

    # ========== R5：详情路径按身份裁剪 ==========
    code, r = call("GET", "/api/classroom/6?date=2026-09-18", STU)
    slots = r.get("data", {}).get("occupiedSlots", []) if code == 200 else []
    mine_slot = next((s for s in slots if s["startTime"] == "08:00"), None)
    other_slot = next((s for s in slots if s["startTime"] == "10:00"), None)
    check("R5 学生详情：本人(08:00)用途可见且 mine=true",
          mine_slot and mine_slot.get("purpose") and mine_slot.get("mine") is True)
    check("R5 学生详情：他人(10:00)用途为 null 且 mine=false",
          other_slot and other_slot.get("purpose") is None and other_slot.get("mine") is False)
    code, r = call("GET", "/api/classroom/6?date=2026-09-18", ADMIN)
    slots = r.get("data", {}).get("occupiedSlots", []) if code == 200 else []
    admin_other = next((s for s in slots if s["startTime"] == "10:00"), None)
    check("R5 管理员详情：他人用途可见", admin_other and admin_other.get("purpose") is not None)

    # ========== R5：列表路径（共享缓存）不返回用途 ==========
    code, r = call("GET", "/api/classroom/list?page=1&size=20&date=2026-09-18", STU)
    rooms = r.get("data", {}).get("records", []) if code == 200 else []
    c6 = next((x for x in rooms if x["id"] == 6), None)
    list_slots = (c6 or {}).get("occupiedSlots", []) or []
    check("R5 学生列表：共享缓存路径全部时段不带用途且无 mine",
          len(list_slots) == 2 and all(s.get("purpose") is None and s.get("mine") is None for s in list_slots),
          "slots=%d" % len(list_slots))

    # ========== R3：日历用途口径（管理员或本人可见） ==========
    code, r = call("GET", "/api/reservation/calendar?startDate=2026-09-01&endDate=2026-09-30", STU)
    evs = r.get("data", []) if code == 200 else []
    own = next((e for e in evs if e["id"] == 33), None)
    other = next((e for e in evs if e["id"] == wangwu_res_id), None)
    check("R3 学生日历：本人预约(09-18 08:00)用途可见且 mine=true",
          own and own.get("purpose") and own.get("mine") is True)
    check("R3 学生日历：他人预约(09-18 10:00)用途为 null 且 mine=false",
          other and other.get("purpose") is None and other.get("mine") is False)
    code, r = call("GET", "/api/reservation/calendar?startDate=2026-09-01&endDate=2026-09-30", ADMIN)
    evs = r.get("data", []) if code == 200 else []
    other = next((e for e in evs if e["id"] == wangwu_res_id), None)
    check("R3 管理员日历：他人用途可见", other and other.get("purpose") is not None)

    # ========== R1：收藏上限并发 ==========
    _, r = call("GET", "/api/favorite/list", STU)
    favs = {x["classroomId"] for x in r.get("data", [])}
    free = [cid for cid in range(1, 13) if cid not in favs]
    check("R1 前置：zhangsan 当前收藏数=%d" % len(favs), 0 <= len(favs) <= 8)
    pre_add, targets = free[:7], free[7:9]
    for cid in pre_add:  # 逐间加到 9
        code, r = call("POST", "/api/favorite/%d" % cid, STU)
        assert code == 200, "前置收藏失败 classroom=%d code=%s" % (cid, code)
    _, r = call("GET", "/api/favorite/list", STU)
    check("R1 前置：已收藏 9 间", len(r.get("data", [])) == 9, "count=%d" % len(r.get("data", [])))
    results = concurrent([
        lambda: call("POST", "/api/favorite/%d" % targets[0], STU),
        lambda: call("POST", "/api/favorite/%d" % targets[1], STU),
    ])
    codes = [x[1].get("code") for x in results]
    check("R1 并发提交 2 间不同教室：无 500/异常", all(x[0] == 200 and c in (200, 400) for x, c in zip(results, codes)),
          "http=%s codes=%s" % ([x[0] for x in results], codes))
    check("R1 并发提交 2 间不同教室：恰好 1 成功 1 拒绝",
          sorted(codes) == [200, 400], "codes=%s" % codes)
    final_count = int(db("SELECT COUNT(*) FROM user_favorite WHERE user_id=2;"))
    check("R1 库级不变量：zhangsan 恰好 10 条收藏", final_count == 10, "count=%d" % final_count)
    # 清理 R1：取消 7 间前置 + 1 间并发成功者，恢复 2 条
    winner = targets[0] if results[0][1].get("code") == 200 else targets[1]
    for cid in pre_add + [winner]:
        call("POST", "/api/favorite/%d" % cid, STU)
    _, r = call("GET", "/api/favorite/list", STU)
    check("R1 清理：zhangsan 收藏恢复 %d 条" % len(r.get("data", [])), len(r.get("data", [])) == 2)

    # ========== R2：删除教室与预约提交并发 ==========
    code, r = call("POST", "/api/classroom/manage", ADMIN,
                   {"name": "回归测试教室", "building": "测试楼", "roomNo": "T101", "type": 1, "capacity": 30,
                    "equipment": "测试", "description": "回归测试用"})
    new_room_id = r.get("data") if r.get("code") == 200 else None
    check("R2 前置：新建无预约教室 id=%s" % new_room_id, r.get("code") == 200 and new_room_id)
    results = concurrent([
        lambda: call("DELETE", "/api/classroom/manage/%s" % new_room_id, ADMIN),
        lambda: call("POST", "/api/reservation", STU,
                     {"classroomId": new_room_id, "reserveDate": "2026-09-25", "startTime": "09:00", "endTime": "10:00",
                      "purpose": "并发测试"}),
    ])
    codes = [x[1].get("code") for x in results]
    msgs = [x[1].get("message", "") for x in results]
    check("R2 并发删除/预约：无 500 且恰一侧成功",
          all(x[0] == 200 for x in results) and sorted(codes) == [200, 400],
          "http=%s codes=%s msgs=%s" % ([x[0] for x in results], codes, msgs))
    dangling = db("SELECT COUNT(*) FROM reservation r LEFT JOIN classroom c ON r.classroom_id=c.id WHERE c.id IS NULL;")
    check("R2 库级不变量：无 classroom_id 悬空预约", dangling == "0", "dangling=%s" % dangling)
    # 清理 R2：若教室仍在（预约先成功），删除其预约记录与教室；若已删除则无需处理
    room_left = db("SELECT COUNT(*) FROM classroom WHERE id=%s;" % new_room_id)
    if room_left == "1":
        db("DELETE FROM reservation WHERE classroom_id=%s;" % new_room_id)
        call("DELETE", "/api/classroom/manage/%s" % new_room_id, ADMIN)
        check("R2 清理：测试教室已恢复删除", True)

    # ========== R5/R3 清理：删除 wangwu 测试预约，恢复种子状态 ==========
    if wangwu_res_id:
        db("DELETE FROM reservation WHERE id=%s;" % wangwu_res_id)
    code, r = call("GET", "/api/classroom/6?date=2026-09-18", STU)
    slots = r.get("data", {}).get("occupiedSlots", []) if code == 200 else []
    check("R5/R3 清理：教室6 09-18 恢复为仅本人 1 个时段", len(slots) == 1, "slots=%d" % len(slots))

    # ========== 汇总 ==========
    passed = sum(1 for _, ok, _ in RESULTS if ok)
    print("\n== 汇总：%d/%d 通过 ==" % (passed, len(RESULTS)))
    for name, ok, detail in RESULTS:
        if not ok:
            print("  FAIL -> " + name + (" | " + detail if detail else ""))
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
