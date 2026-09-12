# R7 交付启动说明 —— 高校实验室预约管理系统（AI 模块）

> 项目根：`C:\Users\72797\Course\reservation-system\R7-delivery-ai-integration\`

## 一、环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 21 LTS |
| Maven | IDEA 内置 Maven 3.9.x（mvn 不在 PATH，命令行：`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd`） |
| Node.js | 22.x |
| MySQL | Docker 运行 MySQL 8.0（reservation-mysql，映射 localhost:3306，库 reservation，root/root，utf8mb4，已设置 --restart unless-stopped） |
| 浏览器 | Chrome（访问前端 http://localhost:5173） |

禁止降级安装任何环境；禁止启动旧项目 Docker 容器（ssm-mysql / student-manage-mysql）。

## 二、数据库

- 容器已就绪（`docker start reservation-mysql` 即可，**禁止重建容器、禁止重新导数据**）。
- 若需初始化空库：执行 `database\init_db.sql`（自包含：五表 + 基线数据：用户 5 / 教室 12 / 预约 13 / 收藏 6 / ai_config 5）。

## 三、后端启动（交付配置：AI 总开关默认关闭）

```powershell
cd C:\Users\72797\Course\reservation-system\R7-delivery-ai-integration\code\reservation-server
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' spring-boot:run
```

- 端口 8080；application.yml 中 `ai.enable=false`（交付默认，核心系统完全正常）。
- 密钥零硬编码：`ai.api-key=${AGNES_API_KEY:}`，仅从环境变量读取。

## 四、前端启动

```powershell
cd C:\Users\72797\Course\reservation-system\R7-delivery-ai-integration\code\reservation-web
npm install   # 首次（有 package-lock，约 107 packages）
npm run dev   # http://localhost:5173
```

默认账号：管理员 admin / admin123（角色管理员）；学生 zhangsan / 123456（角色学生）等 5 用户。

## 五、启用 AI 功能（双开关都开启时 AI 可用；不启用则系统为纯预约系统）

1. **配置环境变量**（二选一）：
   - 设置系统/会话环境变量 `AGNES_API_KEY=<真实密钥>`（前端不接触密钥），或
   - 不配置密钥：AI 自动进入**本地规则降级模式**（推荐/解析/问答/合规全部可用，功能完整，仅不调用大模型）——适合演示与验收。
2. **后端开关**：`code\reservation-server\src\main\resources\application.yml` 中 `ai.enable` 改为 `true`，重启后端。
3. **数据库开关**：
   ```powershell
   docker exec reservation-mysql mysql -uroot -proot reservation -e "UPDATE ai_config SET config_value='true' WHERE config_key='ai_enable';"
   ```
4. 刷新前端页面（登录后可见 AI 推荐卡、AI 快速预约、悬浮助手、审核页 AI 校验标签）。
5. 恢复关闭（交付默认）：yml `ai.enable` 改回 `false` 并重启 + 上述 SQL 置 `'false'`。

> 演示场景（验收口径）：自然语言预约 → 智能推荐 → 合规校验 → 智能助手，全部走降级规则模式即可流畅演示；
> 接入真实 Agnes 密钥后自动升级为模型回答，无需改任何代码。

## 六、运行测试（可复跑，结束后自动恢复数据库基线）

```powershell
# 交付配置（yml ai.enable=false）下：228 条全过（210 基线 + 18 条 AI 关闭路径/权限/边界/只读）
powershell -ExecutionPolicy Bypass -File docs\test-script.ps1

# 联调模式（yml ai.enable=true + db ai_enable=true）下：234 条全过（额外执行开启路径功能/降级 11 条）
# 步骤：先按第五节开启双开关并重启后端，再运行同一脚本
```

测试脚本自动分支：检测到 AI 开启执行开启路径用例（T231-T241），否则执行关闭路径用例（T220-T224）；结束后删除测试数据恢复初始基线（预约 13 条四状态 / 收藏 6 条 / ai_config 5 行）。

## 七、AI 接口速览（详见 docs/api-list.md）

| 接口 | 说明 |
| --- | --- |
| POST /api/ai/recommend | 智能推荐 Top3（入参 userId） |
| POST /api/ai/parse-reservation | 自然语言解析（入参 text） |
| POST /api/ai/chat | 场景限定问答（入参 question） |
| POST /api/ai/compliance-check | 合规校验（入参 purpose） |

权限：登录即可（仅 401 校验）；只读不写；ai.enable=false 时返回 enabled=false + 友好提示，不 500。
