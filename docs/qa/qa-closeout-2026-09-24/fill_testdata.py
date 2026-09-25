# -*- coding: utf-8 -*-
import json, io
PATH = r"C:\Users\72797\Course\reservation-system\qa-results\reservation-e2e\qa-run.json"
r = json.load(io.open(PATH, encoding="utf-8"))
r["test_data"] = {
    "writes_allowed": True,
    "created_records": {
        "users": ["qa_stu_212703"],
        "reservations": [72, 74, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86],
        "classrooms": [43, 44],
        "favorites": "zhangsan 临时收藏已还原至种子状态 [8,11,12]",
    },
    "cleanup": {
        "status": "recorded",
        "required": True,
        "residuals": [
            "QA 前缀预约 12 条（QA端到端测试预约/QA审核样本/QA批量/QA首尾相接参照等）保留于开发演示库，未删除种子数据",
            "QA 教室 43/44 保留用于后续回归",
            "qa_stu_212703 测试用户保留",
        ],
        "note": "开发演示环境，QA 前缀数据保留便于复现与回归；如需清理可执行 DELETE WHERE purpose LIKE 'QA%' 等语句（已随报告交付）",
    },
    "environment_restored": True,
}
io.open(PATH, "w", encoding="utf-8").write(json.dumps(r, ensure_ascii=False, indent=2))
print("test_data recorded")
