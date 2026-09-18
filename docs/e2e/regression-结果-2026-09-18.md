# R1–R5 回归运行结果（2026-09-18 08:50）

- 脚本：`docs/e2e/regression-r1r5.py`
- 命令：`python docs/e2e/regression-r1r5.py`
- 被测版本：`e902bee`
- 环境：后端 `http://localhost:8080`、MySQL/Redis 容器、账号 admin/zhangsan/wangwu
- 说明：R2 清理检查为条件项（教室仍在时多记 1 条），故总数可能为 22 或 21，通过率均为 100%

## 汇总：22/22 通过

| # | 断言 | 结果 | 备注 |
|---|---|---|---|
| 1 | R4 直调 300 字用途被 400 拦截 | PASS | http=200 code=400 msg=预约用途过长（不超过 255 字） |
| 2 | R4 空用途仍 400（既有行为不回归） | PASS | http=200 code=400 msg=预约用途不能为空 |
| 3 | R5 准备：wangwu 提交教室6/09-18 10-12 预约成功 | PASS | id=54 |
| 4 | R5 准备：管理员审核通过 | PASS | http=200 code=200 msg=审核成功 |
| 5 | R5 学生详情：本人(08:00)用途可见且 mine=true | PASS |  |
| 6 | R5 学生详情：他人(10:00)用途为 null 且 mine=false | PASS |  |
| 7 | R5 管理员详情：他人用途可见 | PASS |  |
| 8 | R5 学生列表：共享缓存路径全部时段不带用途且无 mine | PASS | slots=2 |
| 9 | R3 学生日历：本人预约(09-18 08:00)用途可见且 mine=true | PASS |  |
| 10 | R3 学生日历：他人预约(09-18 10:00)用途为 null 且 mine=false | PASS |  |
| 11 | R3 管理员日历：他人用途可见 | PASS |  |
| 12 | R1 前置：zhangsan 收藏已收敛为 2 条 | PASS |  |
| 13 | R1 前置：已收藏 9 间 | PASS | count=9 |
| 14 | R1 并发提交 2 间不同教室：无 500/异常 | PASS | http=[200, 200] codes=[400, 200] |
| 15 | R1 并发提交 2 间不同教室：恰好 1 成功 1 拒绝 | PASS | codes=[400, 200] |
| 16 | R1 库级不变量：zhangsan 恰好 10 条收藏 | PASS | count=10 |
| 17 | R1 清理：zhangsan 收藏恢复 2 条 | PASS |  |
| 18 | R2 前置：新建无预约教室 id=32 | PASS |  |
| 19 | R2 并发删除/预约：无 500 且恰一侧成功 | PASS | http=[200, 200] codes=[400, 200] msgs=['该教室存在预约记录，禁止删除', '预约提交成功，待管理员审核'] |
| 20 | R2 库级不变量：无 classroom_id 悬空预约 | PASS | dangling=0 |
| 21 | R2 清理：测试教室已恢复删除 | PASS |  |
| 22 | R5/R3 清理：教室6 09-18 恢复为仅本人 1 个时段 | PASS | slots=1 |
