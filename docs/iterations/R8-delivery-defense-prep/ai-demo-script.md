# R8 AI 演示脚本 + 兜底预案（答辩压轴）

> 定位：先完成核心业务闭环演示（见 `docs/demo-script.md`），再执行本脚本——**AI 做压轴，层层递进**。
> 主路径口径：**双开关开启 + 规则模拟降级**。即：即使不配置真实密钥、或真实密钥因免费档限流不可用，四项 AI 能力均由本地规则引擎输出可用结果，演示零翻车；真实接入链路已完成实测验证（见实训报告第 8 章）。
> 全程所有 AI 输出均标注「AI 生成，仅供参考」，并提供手动修改入口。
> 演示后必须执行「开关复位」与「恢复基线」。

## 第 0 步：开启双开关（演示前置）

```powershell
# 1) 后端开关：application.yml 中 ai.enable 改为 true 并重启后端
#    R8-delivery-defense-prep\code\reservation-server\src\main\resources\application.yml
#    cd code\reservation-server; & mvn.cmd spring-boot:run
# 2) 数据库开关：
docker exec reservation-mysql mysql -uroot -proot reservation -e "UPDATE ai_config SET config_value='true' WHERE config_key='ai_enable';"
# 3) 刷新前端页面（登录后可见 AI 推荐卡、AI 快速预约、悬浮助手、审核页 AI 校验标签）
```

> 建议在**答辩演示前 5-10 分钟**完成开启并冒烟一次（推荐 1 次 + 解析 1 次），确认页面正常。若开启后偶发降级提示属预期（免费档限流），按「兜底预案」话术应对。

## 第 1 步：AI 为你推荐（教室列表页 Top3）

| 项 | 内容 |
|---|---|
| 操作位置 | 学生端 · 教室列表页顶部「AI 为你推荐」卡片区 |
| 数据用例 | 当前登录 zhangsan（userId=2）：历史预约集中在信息楼/实验楼、实验室类、40-60 人规模；推荐基于「用户历史习惯（楼栋/类型/时段/容量）+ 明日已通过预约占用」计算 |
| 操作 | 进入列表页 → 观察 Top3 推荐卡片（教室名、楼栋、类型、容量 + 一句话推荐理由） |
| 预期结果 | 返回 3 间教室与理由；点击卡片直达教室详情；卡片标注「AI 生成，仅供参考」 |
| 话术 | 传统系统只能看列表，这里 AI 根据我常用的楼栋、教室类型和人数，直接给出最合适的 3 间教室并说明理由——这就是「智能化资源调度」的体现 |

## 第 2 步：AI 快速预约（自然语言解析 → 参数预填 → 双重校验提交）

| 项 | 内容 |
|---|---|
| 操作位置 | 学生端 · 教室列表页搜索栏旁「AI 快速预约」入口 |
| 输入 | 「**明天下午2点 40人 机房 做课程设计**」 |
| 预期结果 | AI 解析出结构化参数并预填：日期=明天（规则引擎按本地日期计算，正确）、14:00-16:00、40 人、机房、用途「做课程设计」；参数可手动修改 |
| 后续操作 | 按类型+容量过滤选教室（机房 4 A301/50 人、7 B201/60 人、11 C301/80 人）→ 选择与已通过预约不冲突的时段 → 确认提交（提交仍走**前后端双重冲突校验**，AI 只预填不代提交） |
| 话术 | 一句话描述需求，AI 自动解析成结构化预约参数并匹配可用教室；提交仍然走系统的双重冲突校验，AI 绝对只读不写、不绕过业务规则 |

## 第 3 步：AI 预约合规校验（管理端审核页）

| 项 | 内容 |
|---|---|
| 操作位置 | 管理端 · 预约审核管理页（admin/admin123 登录） |
| 数据用例 | 待审核记录 id=1（用途：课程设计小组讨论）→ 正常通过标签；违规演示：临时将 id=13 用途改为「商业推销活动」（含违规关键词） |
| 操作 | ① 待审核记录显示「AI 校验」标签：id=1 显示绿色「通过」；② 违规演示：临时改库（见下）后刷新 → id=13 用途红色高亮 + 「AI 校验：违规」标签，悬浮显示原因「包含违规关键词：商业推销」；③ 强调：AI 只提示不改状态，审核动作仍由管理员手动执行 |
| 违规临时改库与恢复 | 改库（PowerShell 直传中文会 GBK 乱码，须用 HEX，实测验证）：`docker exec reservation-mysql mysql -uroot -proot reservation -e "UPDATE reservation SET status=0, purpose=CONVERT(0xE59586E4B89AE68EA8E99480E6B4BBE58AA8 USING utf8mb4), audit_remark=NULL, auditor_id=NULL, audit_time=NULL WHERE id=13;"`（`商业推销活动` UTF-8 HEX）；演示后恢复 `purpose=CONVERT(0xE5B08FE7BB84E5AE9EE9AA8CE58786E5A487 USING utf8mb4)`（`小组实验准备`）+ status 复位为基线值（见恢复小节） |
| 话术 | 传统审核靠管理员逐条看用途，现在 AI 自动预判用途合规性并给出原因，命中违规词红色高亮——辅助审核、降低漏审风险，同时严守「只读不写」，最终决定权始终在人 |

## 第 4 步：全局悬浮 AI 预约助手

| 项 | 内容 |
|---|---|
| 操作位置 | 学生端任意页面右下角悬浮球 → 侧边对话窗口 |
| 相关问题 | 「怎么取消预约？」→ 命中 FAQ 返回「我的预约页找到对应记录，开始前 1 小时内不可取消…」等场景内回答 |
| 无关问题 | 「今天天气怎么样？」→ 预设话术「抱歉，我只能解答高校教室预约系统的相关问题…」 |
| 预期结果 | 场景绝对限定：只答预约/教室/个人记录相关问题；无关问题统一预设话术；当前会话不持久化 |
| 话术 | AI 不是通用聊天，而是严格限定在预约业务场景内的助手，个人数据通过检索增强注入上下文，回答有据可依 |

---

## 兜底预案（演示零翻车）

### 场景 1：无网络 / API 不可用（主路径即此）

- **现象**：未配置 `AGNES_API_KEY`，或网络不可达，或免费档限流（429）。
- **系统行为**：AgnesClient 检测到密钥缺失/超时（读取 60s、连接 10s）/报错/限流（RPM≈20）→ 自动切换本地规则模拟：推荐按规则打分、解析走正则模板、问答命中 FAQ、合规走关键词库；接口仍返回 HTTP 200，**输出可用、页面照常**。
- **演示话术**：本次演示正是主路径——即使大模型不可用，AI 能力全部由本地规则引擎降级输出，功能完整、演示不中断。这就是本项目「降级容错」的设计亮点：不依赖第三方服务稳定性，核心系统永远可用。

### 场景 2：演示中途 AI 报错 / 限流

- **现象**：演示中某次 AI 调用返回降级。
- **系统行为**：降级不阻断，页面照常展示规则结果；响应 `data.message` 透出降级原因（如「AI 请求过于频繁，已自动切换为本地规则模式」），可通过浏览器 Network 面板现场展示，反成加分点（可观测性）。
- **应对**：不要慌张，直接说「看，触发了自动降级——接口仍然 200，原因通过 message 透出，业务完全不受影响」。

### 场景 3：需要纯业务演示

- **操作**：`application.yml` 中 `ai.enable` 改回 `false` 并重启后端（数据库开关可保持 true，双开关 AND 任一 false 即关闭）。
- **系统行为**：4 个 AI 接口返回 `enabled=false` + 友好提示；前端 AI 推荐卡、快速预约入口、悬浮球全部隐藏，审核页无 AI 校验标签——系统完全退化为 R6 纯预约系统（13 页闭环不回退）。
- **话术**：AI 可插拔——一键关闭后就是一个纯预约管理系统，核心业务零影响。

---

## 恢复基线与开关复位（演示结束后必须执行）

```powershell
# 1) 开关复位：yml ai.enable 改回 false 并重启后端 + 数据库开关置 false
docker exec reservation-mysql mysql -uroot -proot reservation -e "UPDATE ai_config SET config_value='false' WHERE config_key='ai_enable';"

# 2) 删除 AI 演示中新增提交的预约（id>13）
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM reservation WHERE id>13;"

# 3) 还原第 3 步临时改动的用途（中文必须 HEX，PowerShell 直传会 GBK 乱码）
docker exec reservation-mysql mysql -uroot -proot reservation -e "UPDATE reservation SET purpose=CONVERT(0xE5B08FE7BB84E5AE9EE9AA8CE58786E5A487 USING utf8mb4), status=0 WHERE id=13;"
#    更稳妥的恢复方式（推荐）：直接 utf8mb4 重导初始基线，一步恢复全部数据与开关
#    cmd /c "docker exec -i reservation-mysql mysql --default-character-set=utf8mb4 -uroot -proot reservation < R8-delivery-defense-prep\database\init_db.sql"

# 4) 复核基线（期望 reservation=13、user_favorite=6、sys_user=5、ai_config=5 且 ai_enable=false）
docker exec reservation-mysql mysql -uroot -proot reservation -e "SELECT (SELECT COUNT(*) FROM reservation) AS r,(SELECT COUNT(*) FROM user_favorite) AS f,(SELECT COUNT(*) FROM sys_user) AS u,(SELECT COUNT(*) FROM ai_config) AS a,(SELECT config_value FROM ai_config WHERE config_key='ai_enable') AS ai_enable;"
```
