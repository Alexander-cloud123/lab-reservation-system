# R8 答辩准备 —— 任务说明书（task-description）

> 轮次：R8-delivery-defense-prep（答辩准备）｜日期：2026-09-12｜基线提交：6f9c604（R7）
> 依据：spec.md 第 7 节 R8 行 + 第 8 节交付 Checklist；需求设计文档第四章「冲优秀答辩核心策略」+ 第五章「关键设计决策记录」；AGENTS.md 协作章程与第 5 节交付格式。

## 一、任务范围

执行 spec.md 第 7 节 R8 轮次（答辩准备）：交付四件套——实训报告（docx + md 源稿）、答辩 PPT（pptx）、标准演示脚本 + 测试数据、AI 演示脚本 + 兜底预案；配套 startup-guide、任务说明、测试报告；全部按需求文档第四章「冲优秀答辩核心策略」立意与顺序组织（先核心业务闭环，再 AI 压轴）。交付目录 `R8-delivery-defense-prep/`。

**工程来源**：自 R7-delivery-ai-integration 复制（robocopy，排除 target/node_modules/dist；前端新目录 npm install 完成）。R8 为材料轮，业务代码零改动（唯一前端缺陷修复见「决策四」与 test-report 缺陷记录）。

## 二、关键决策（4 项 + 1 项环境决策）

### 决策一：材料轮不改业务代码

- 原则：R8 只做材料与演示准备，原则上不改业务代码；复制工程仅为保证交付自包含。
- 例外：演示实测发现 1 处真实缺陷（AI 快速预约冲突校验参数名，见 test-report 缺陷 #1），属本轮允许的修复范围，已修复并在测试报告记录、回归受影响部分（228/234 全过）。

### 决策二：演示主路径 = 双开关开启 + 规则模拟降级

- 依据：需求文档第五章记载 Agnes「永久免费无额度上限」，但 R7 真实密钥实测：免费档已触达 rate limit（HTTP 429），拿不到真实模型输出；同时实测证明接入链路完全正确（请求送达 Agnes 服务器并收到官方响应，base-url/Bearer 认证/模型名/OpenAI v1 请求格式均正确）。
- 决策：R8 演示主路径 = 双开关开启 + 规则模拟降级（无需真实密钥，输出可用流畅）；真实接入链路已在 R7 验证并写入实训报告技术亮点。口径统一为「真实接入已实测链路正确 + 免费档限流极紧（单次/低频可用）+ 自动降级兜底零翻车」的双保险，**禁止写「永久免费无额度上限」**。
- 全过程密钥零硬编码：`AGNES_API_KEY` 仅环境变量注入；交付物（报告/PPT/脚本/说明）不出现任何密钥字样或真实密钥。

### 决策三：演示数据一律用初始基线，可复跑，跑完恢复

- 演示/测试数据全部来自 `database/init_db.sql` 初始基线（预约 13 四状态、教室 12、用户 5、收藏 6、ai_config 5），禁止新增或修改数据（演示过程临时新增的预约、临时改动的用途在演示后删除/还原）。
- 恢复基线标准动作：`cmd /c "docker exec -i reservation-mysql mysql --default-character-set=utf8mb4 -uroot -proot reservation < R8-delivery-defense-prep\database\init_db.sql"`（**必须带 --default-character-set=utf8mb4**，否则中文双重编码，见 test-report「基线重导说明」）。
- 开关复位：`application.yml ai.enable=false` + 重启后端 + `ai_config.ai_enable='false'`。

### 决策四：交付目录与成品命名

- 交付目录 `R8-delivery-defense-prep/`（延续 R1-R7 惯例 `R{n}-delivery-xxx`，若负责人另有指定以负责人为准）；`training-report.docx` / `defense-ppt.pptx` 为延续惯例建议名。
- 目录与文件名一律不使用中文；R1-R7 交付目录与项目根其余文件只读不动。

### 环境决策：AI 客户端 base-url 节点取舍（工作区未提交改动处置）

- 项目根工作区存在一组 R7 之后未提交的改动：base-url `apihub.agnes-ai.cn` → `api.agnes-ai.cn`、timeout-seconds 3→60、新增 max-tokens:1024（AgnesClient 与 AiProperties 同步实现）。
- 实测：`api.agnes-ai.cn` 对该密钥返回 **401 无效令牌**，`apihub` 节点正常（200/429）——工作区改错了节点。
- 决策：**采纳超时 60s 与 max-tokens 1024 修复；base-url 恢复 `https://apihub.agnes-ai.cn/v1`**（保留 60s/max-tokens），并在本说明如实记录，供负责人知悉（相关文件位于 R8 交付目录 code/ 内，工作区其余未提交改动维持原状，由负责人决定去留）。

## 三、演示实测结果摘要

（详见 `docs/test-report.md`，本节约取结论）

- 回归测试：交付配置 **228/228**、联调模式 **234/234** 全过（R7 原脚本日期硬编码跨天问题以日期适配副本复跑，原因与处理见 test-report）。
- 浏览器实测：标准演示 9 步（登录提醒/筛选/预约/冲突/取消/管理端/审核含批量/看板/日历）全跑通；AI 演示 4 步（推荐 Top3/快速预约解析与提交/合规校验标签/悬浮助手问答）全跑通，全程「AI 生成，仅供参考」；三类兜底场景验证成立。
- 实测缺陷：AiQuickReserve.vue 冲突校验参数名错误（`reserveDate` → `date`）已修复并回归；批量勾选为浏览器自动化工具限制（非产品缺陷），批量审核改用接口直调验证，真机由答辩人现场勾选。
- 恢复复核：reservation=13、user_favorite=6、sys_user=5、ai_config=5、ai_enable=false；四状态 3/6/2/2；id=13 用途 HEX 校验 UTF-8 正确。双开关已复位，工程处于交付配置（ai.enable=false）。

## 四、spec.md 第 8 节交付 Checklist 逐项自查

| # | 检查项 | 自查结果 |
|---|---|---|
| 1 | 代码可运行：后端 `mvn spring-boot:run` 启动无错；前端 `npm run dev` 正常 | ✅ 交付配置后端启动无错（8080）；前端 dev（5173）正常；冒烟登录/教室列表/AI 关闭友好提示均 200 |
| 2 | 表结构、字段、索引与需求文档 2.3 完全一致 | ✅ 未改表结构（五表自 R7 复制，init_db.sql 与 R7 一致）；基线重导后按 init_db 校验通过 |
| 3 | 核心规则 100% 实现：冲突检测公式、状态流转、双重校验 | ✅ 冲突检测（前端实时+后端兜底）浏览器实测；状态机 0/1/2/3 流转实测；228/234 回归覆盖 |
| 4 | 统一返回 Result、全局异常、`jakarta.*` 包名 | ✅ 未改后端代码；回归用例覆盖统一返回与全局异常；jakarta 包名沿用 |
| 5 | 关键操作有二次确认；空状态友好；无硬编码魔法值 | ✅ 取消/审核/退出均有二次确认（实测）；空状态文案沿用；魔法值走 Constants |
| 6 | 密钥不硬编码（环境变量/配置）；前端不接触 AI 密钥 | ✅ `AGNES_API_KEY` 仅环境变量；交付物无任何密钥字样；前端零接触密钥（R7 起未变） |
| 7 | 测试报告覆盖：正常流程、边界场景、异常操作、权限校验 | ✅ R8 test-report 覆盖回归 228/234、浏览器实测 9+4 步、兜底三场景、缺陷修复回归；R1-R7 累计报告基线未变 |
| 8 | 本轮任务说明书/接口清单/测试报告/启动说明齐全 | ✅ 本说明（含接口速览见 startup-guide.md 第八节）；test-report.md；startup-guide.md；demo-script.md；ai-demo-script.md 齐备 |

8 项全部通过，无遗留未决项。

## 五、交付清单

```
R8-delivery-defense-prep/
├── code/                          # 自 R7 复制（排除 node_modules/dist/target；前端已 npm install，自包含可运行）
│   ├── reservation-server/        #   含 base-url 修正（apihub + 60s + max-tokens）与 ai.enable=false 复位
│   └── reservation-web/           #   含 AiQuickReserve.vue 缺陷修复
├── database/
│   └── init_db.sql                # 自 R7 复制，自包含（DROP+CREATE+INSERT，CURDATE() 动态日期）
├── docs/
│   ├── task-description.md        # 本说明
│   ├── training-report.md         # 实训报告源稿（与 docx 内容一致）
│   ├── demo-script.md             # 标准演示脚本 + 测试数据说明 + 恢复基线 SQL
│   ├── ai-demo-script.md          # AI 演示脚本 + 兜底预案（三场景）+ 开关复位/恢复 SQL
│   └── test-report.md             # R8 演示实测记录（回归/浏览器实测/缺陷/基线复核）
├── materials/
│   ├── training-report.docx       # 实训报告成品（可编辑 Word）
│   └── defense-ppt.pptx           # 答辩 PPT 成品（13-15 页，可放映）
└── startup-guide.md               # 环境/启动/演示准备/开关复位/回归运行/接口速览
```

不入库：node_modules/target/dist/.devtmp/.log（.gitignore 覆盖）；`.devtmp\gen\` 为生成脚本与日期适配副本（含 gen_docx.py、gen_adapt.py、test-script-r8.ps1、运行日志），本地保留可复现。

## 六、已知限制与说明

1. **测试脚本跨天日期**：startup-guide 第六节引用的 R7 原脚本含 09-11 硬编码日期，跨天复跑需用日期适配副本（`.devtmp\gen\test-script-r8.ps1`，按当天固化字面日期；生成方式见 gen_adapt.py）。R8 已按 09-12 复跑 228/234 全过。
2. **批量审核勾选**：浏览器自动化会话无法触发 el-table 复选框选中（工具限制），演示脚本已注记「API 直调备选」，真机演示由答辩人现场操作。
3. **真实密钥演示**：若负责人持有可用密钥并临时开启，演示仍走真实链路；默认（无密钥/限流）即降级主路径，两态均可演示，脚本与话术已覆盖。
4. R1-R7 交付目录与项目根其余文件本轮未改动（除项目根工作区既存未提交改动——见「环境决策」处置说明）。
