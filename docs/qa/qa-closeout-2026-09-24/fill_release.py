# -*- coding: utf-8 -*-
"""填写 release_decision / disclosures / coverage 完善"""
import json, io

PATH = r"C:\Users\72797\Course\reservation-system\qa-results\reservation-e2e\qa-run.json"
with io.open(PATH, encoding="utf-8") as f:
    run = json.load(f)

run["release_decision"] = {
    "decision": "conditional_go",
    "rationale": (
        "44 项需求全部映射为 80 个测试用例（API 73 + UI E2E 7），79 通过、1 阻塞；核心业务规则均有正式执行证据且通过："
        "冲突检测公式六组边界（无重叠/部分重叠/完全包含/首尾相接/仅已通过参与/后端二次校验直调重叠拒绝）、状态流转四态迁移与非法迁移拒绝、"
        "取消时限（开始前 1 小时内 400 拒绝 + 超过可取消）、收藏上限 10（第 11 间 400）、批量审核仅待审核且不覆盖已审核、"
        "权限拦截（无 token 401 / 学生访问管理接口 403 / 禁用账号登录 403 / 管理员禁自己 400）、AI 双开关关闭时降级可用且零业务写入、"
        "Excel 导出完整、日历区间数据正确、参数校验（page<1、size>500、非法 status、时间倒置、时段外均 400）。"
        "API 层零产品缺陷；Playwright 浏览器 E2E 全套 90 例 87 通过、2 例失败与 1 例跳过均为『晚间运行导致测试造数被业务时段校验正确拒绝』，非产品缺陷。"
        "唯一阻塞用例 TC-UI-007（登录提醒弹窗/今日预约置顶）依赖『当日未来时段预约』样本，当前 21:30+ 可预约时段 08:00-22:00 无法构造。"
    ),
    "conditions": [
        "白天空闲时段补验 2 个依赖当日未来时段的 UI 场景：登录提醒弹窗（REQ-003）与我的预约今日置顶（REQ-014）；责任人：测试验证 Agent；时限：下一轮迭代开始前；监控：重跑 Playwright 10-auth 登录提醒与 13-my-reservation 今日置顶 2 例",
        "若需验证真实 Agnes AI 大模型链路，注入 AGNES_API_KEY 环境变量后将 ai.enable 双开关置 true 后复测 AI 四接口（本轮已验证关闭态降级路径与零写入）",
    ],
}

run["coverage"] = {
    "requirement_total": 44,
    "requirement_linked": 44,
    "requirement_unlinked": 0,
    "p0_requirement_total": 13,
    "p0_requirement_linked": 13,
    "case_total": 80,
    "case_status_counts": {"通过": 79, "阻塞": 1},
    "execution_total": 86,
    "execution_status_counts": {"passed": 85, "blocked": 1},
    "bug_total": 0,
    "root_cause_total": 0,
    "unverified_scope_note": (
        "未验证范围：① 真实 Agnes AI 大模型链路（双开关 false，验证了关闭态降级与零写入）；② Safari/Firefox 浏览器兼容；"
        "③ 弱网/断网/慢网恢复；④ 大规模并发与性能压测；⑤ TC-UI-007 登录提醒/今日置顶依赖当日未来时段，晚间无法构造样本（阻塞，白天空闲时段补验）。"
    ),
}

run["disclosures"] = [
    {
        "id": "DISC-001",
        "title": "需求 risk 字段未使用枚举（门禁 REPORT 项）",
        "detail": "44 条需求 risk 字段填写为描述性文本，门禁要求枚举 P0/P1/P2/P3；非阻塞，已在各需求行内标注具体风险描述，后续版本可将 risk 改为枚举并保留描述于 behavior。",
        "resolution": "作为披露项保留；不影响结论。",
    },
    {
        "id": "DISC-002",
        "title": "R8 接口清单文档与代码的口径差异",
        "detail": "R8 接口清单将教室类型写为 0-普通/1-多媒体/2-机房，与需求设计文档 2.3（1-普通教室/2-实验室/3-机房）及当前代码枚举不一致；测试以需求文档与代码实况为准。",
        "resolution": "文档口径差异，非产品缺陷；建议同步更新接口清单。",
    },
    {
        "id": "DISC-003",
        "title": "测试环境修复记录",
        "detail": "① Docker 容器网络丢失（docker-proxy 未监听 3306/6379）→ docker network connect bridge 恢复；② zhangsan 多次错误密码触发登录锁定（R9 防爆破亮点，Redis auth:login:lock）→ 清除锁定并管理员重置密码恢复；③ 修改密码接口要求 confirmPassword 字段（R8 接口清单未列，实际实现一致于前端调用）。",
        "resolution": "均为环境与文档层面，产品行为正确。",
    },
]

with io.open(PATH, "w", encoding="utf-8") as f:
    json.dump(run, f, ensure_ascii=False, indent=2)

print("release_decision:", run["release_decision"]["decision"])
print("disclosures:", len(run["disclosures"]))
print("coverage case counts:", run["coverage"]["case_status_counts"])
