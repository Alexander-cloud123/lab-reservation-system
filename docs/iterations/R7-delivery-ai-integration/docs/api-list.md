# R7 轮次接口清单（新增 4 个 AI 只读接口 + 权限口径变更）

> 全部接口统一返回 `Result` 结构：`{ "code": 200, "message": "...", "data": ... }`；
> 业务失败 code=400 并携带友好 message；未登录访问受保护接口返回 HTTP 401（拦截器统一处理）。
> 本轮新增 4 个 AI 接口均为**只读**（不写业务数据）；既有 31 个端点契约不变（复用清单见第三节）。

## 一、本轮变更总览

| 接口 | 变更 | 说明 |
| --- | --- | --- |
| POST /api/ai/recommend | **新增** | 智能教室推荐 Top3（含推荐理由），AI 能力模块 |
| POST /api/ai/parse-reservation | **新增** | 自然语言预约解析 → 结构化参数，AI 能力模块 |
| POST /api/ai/chat | **新增** | 预约智能问答（场景绝对限定），AI 能力模块 |
| POST /api/ai/compliance-check | **新增** | 预约用途合规校验，AI 能力模块 |
| 权限矩阵 | **变更（仅新增范围）** | `/api/ai/*` 从管理员前缀移除 → 「登录即可」（仅 401 校验）；既有接口权限矩阵零回归 |
| 其余 31 个接口 | 无变更 | 契约与 R1-R6 完全一致（复用清单见第三节） |

## 二、R7 接口详情（4 个 AI 只读接口）

### 通用说明

- **权限**：登录即可（携带有效 Token 即 200；未登录 401；无角色区分，学生/管理员均可访问）。
- **双开关口径**：`ai.enable`（application.yml）AND `ai_config.ai_enable`（数据库）都为 true 时 AI 功能可用；
  任一为 false 时接口仍返回 HTTP 200 + `code=200`，但 `data.enabled=false` 且 `data.message` 为友好提示（如「AI 服务未启用，当前为纯预约系统模式」），**不报 500、不阻断核心业务**。
- **降级口径**：AI 上游不可用（无密钥 / 限流 RPM=20 / 超时 60s / 连接失败 / 服务异常）时自动切换本地规则模拟，
  接口仍返回 HTTP 200 且结果可用（规则推荐 / 正则解析 / FAQ 问答 / 关键词合规），`data.message` 透出降级原因（如「AI 请求过于频繁，已自动切换为本地规则模式」）。
- **只读**：四个接口均不写任何业务数据（测试已断言调用前后预约数不变）。

### 1. 智能教室推荐（POST /api/ai/recommend）

| 项 | 说明 |
| --- | --- |
| 请求体 | `{ "userId": 2 }`（用户 ID，必填；缺省时 400 友好提示） |
| 响应 data | `{ "enabled": true, "message": "...", "date": "2026-09-12", "recommendations": [ { "classroomId", "name", "building", "roomNo", "type", "capacity", "reason" } ] }` |
| 逻辑 | 按用户历史预约习惯（楼栋/类型/时段/平均容量）预筛候选 → 大模型排序 Top3 附理由；模型失败自动规则打分排序；候选基于「明日已通过预约占用」计算空闲/占用 |

### 2. 自然语言预约解析（POST /api/ai/parse-reservation）

| 项 | 说明 |
| --- | --- |
| 请求体 | `{ "text": "明天下午2点 40人 机房 做课程设计" }`（口语化文本，必填） |
| 响应 data | `{ "enabled": true, "message": "...", "date": "2026-09-12", "startTime": "14:00", "endTime": "16:00", "capacity": 40, "roomType": "机房", "purpose": "课程设计" }` |
| 逻辑 | Prompt 模板取 `ai_config.prompt_parse`；模型失败/非法 JSON → 正则降级（今天/明天/后天/周X/月日/年-月-日、HH:mm/下午N点/半、N人、机房/实验室/普通教室、用途关键词） |

### 3. 预约智能问答（POST /api/ai/chat）

| 项 | 说明 |
| --- | --- |
| 请求体 | `{ "question": "怎么取消预约？" }`（必填） |
| 响应 data | `{ "enabled": true, "message": "...", "answer": "..." }` |
| 逻辑 | System Prompt 场景绝对限定（仅解答预约/教室/个人记录相关问题）+ 用户最近预约检索增强；模型失败 → FAQ 关键词库；无关问题返回预设话术（「抱歉，我只能解答高校教室预约系统的相关问题…」） |

### 4. 预约合规校验（POST /api/ai/compliance-check）

| 项 | 说明 |
| --- | --- |
| 请求体 | `{ "purpose": "商业推销活动" }`（预约用途，必填） |
| 响应 data | `{ "enabled": true, "message": "...", "compliant": false, "reason": "包含违规关键词：商业推销" }` |
| 逻辑 | Prompt 模板取 `ai_config.prompt_compliance`；模型失败 → `compliance_keywords` 本地关键词判定（命中返回 reason=「包含违规关键词：X」） |

## 三、既有接口复用清单（31 个端点，R1-R6 契约不变）

| # | 方法 | 路径 | 权限 | 来源 |
| --- | --- | --- | --- | --- |
| 1 | POST | /api/user/login | 公开 | R1 |
| 2 | POST | /api/user/register | 公开 | R1 |
| 3 | GET | /api/user/info | 登录 | R1 |
| 4 | GET | /api/user/manage | 管理员 | R2 |
| 5 | PUT | /api/user/manage/{id}/status | 管理员 | R2 |
| 6 | PUT | /api/user/manage/{id}/reset-password | 管理员 | R2 |
| 7 | POST | /api/user/manage | 管理员 | R2 |
| 8 | PUT | /api/user/manage/{id} | 管理员 | R2 |
| 9 | DELETE | /api/user/manage/{id} | 管理员 | R2 |
| 10 | GET | /api/classroom/list | 登录 | R2 |
| 11 | GET | /api/classroom/list-all | 登录 | R2 |
| 12 | GET | /api/classroom/{id} | 登录 | R2 |
| 13 | POST | /api/classroom/manage | 管理员 | R2 |
| 14 | PUT | /api/classroom/manage/{id} | 管理员 | R2 |
| 15 | DELETE | /api/classroom/manage/{id} | 管理员 | R2 |
| 16 | PUT | /api/classroom/manage/{id}/status | 管理员 | R2 |
| 17 | GET | /api/classroom/occupancy?date= | 登录 | R3 |
| 18 | POST | /api/reservation | 登录 | R3 |
| 19 | POST | /api/reservation/check-conflict | 登录 | R3 |
| 20 | PUT | /api/reservation/{id}/audit | 管理员 | R3 |
| 21 | PUT | /api/reservation/{id}/cancel | 登录 | R3 |
| 22 | PUT | /api/reservation/batch-audit | 管理员 | R3 |
| 23 | GET | /api/reservation/mine | 登录 | R3 |
| 24 | GET | /api/reservation/manage | 管理员 | R3（R6 增 classroomId 可选参数） |
| 25 | GET | /api/reservation/export | 管理员 | R6 |
| 26 | POST | /api/favorite | 登录 | R4 |
| 27 | DELETE | /api/favorite/{classroomId} | 登录 | R4 |
| 28 | GET | /api/favorite/list | 登录 | R4 |
| 29 | GET | /api/statistics/overview | 管理员 | R5 |
| 30 | GET | /api/statistics/classroom-rank | 管理员 | R5 |
| 31 | GET | /api/statistics/monthly | 管理员 | R5 |

> 完整参数/响应契约见 R1-R6 交付目录 docs/api-list.md；本轮未修改上述任一端点。
