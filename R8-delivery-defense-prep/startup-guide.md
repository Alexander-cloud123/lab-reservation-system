# R8 交付启动说明 —— 高校实验室预约管理系统（答辩准备）

> 项目根：`C:\Users\72797\Course\reservation-system\R8-delivery-defense-prep\`
> 本轮为答辩准备轮：工程从 R7 复制（含 R7 工作区未提交修复：读取超时 60s、max-tokens 1024；base-url 经 R8 实测复核维持 `apihub.agnes-ai.cn/v1`，详见 `docs/task-description.md`），未修改业务代码。

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
- 初始基线：用户 5 / 教室 12 / 预约 13（待审核3·已通过6·已驳回2·已取消2）/ 收藏 6 / ai_config 5（ai_enable=false）。
- 若需初始化空库：执行 `database\init_db.sql`（自包含：五表 + 基线数据）。

## 三、后端启动（交付配置：AI 总开关默认关闭）

```powershell
cd C:\Users\72797\Course\reservation-system\R8-delivery-defense-prep\code\reservation-server
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' spring-boot:run
```

- 端口 8080；application.yml 中 `ai.enable=false`（交付默认，核心系统完全正常）。
- 密钥零硬编码：`ai.api-key=${AGNES_API_KEY:}`，仅从环境变量读取。

## 四、前端启动

```powershell
cd C:\Users\72797\Course\reservation-system\R8-delivery-defense-prep\code\reservation-web
npm install   # 首次（有 package-lock，约 107 packages）
npm run dev   # http://localhost:5173
```

默认账号：管理员 `admin / admin123`；学生 `zhangsan / 123456`（另有 lisi/wangwu/zhaoliu，密码均 123456）。

## 五、演示准备（双开关启用 AI）

> 答辩演示流程与话术见 `docs/demo-script.md`（核心业务闭环）与 `docs/ai-demo-script.md`（AI 压轴 + 兜底预案）。

1. **后端开关**：`code\reservation-server\src\main\resources\application.yml` 中 `ai.enable` 改为 `true`，重启后端。
2. **数据库开关**：
   ```powershell
   docker exec reservation-mysql mysql -uroot -proot reservation -e "UPDATE ai_config SET config_value='true' WHERE config_key='ai_enable';"
   ```
3. 刷新前端页面（登录后可见 AI 推荐卡、AI 快速预约、悬浮助手、审核页 AI 校验标签）。
4. **AI 口径说明**：演示主路径 = 双开关开启 + **规则模拟降级**——不配置真实密钥即可流畅演示（推荐/解析/问答/合规全部由本地规则引擎输出，功能完整）；真实接入链路已完成实测验证（OpenAI v1 格式、Bearer 认证、结构化 JSON 均正确，Agnes 免费档限流 429 时自动降级）。如需接真实密钥：设置环境变量 `AGNES_API_KEY`（前端不接触密钥）后按同一步骤启用，限流时仍自动降级，无需改代码。
5. **开关复位（演示结束）**：yml `ai.enable` 改回 `false` 并重启 + 上述 SQL 置 `'false'`。

## 六、运行回归测试（可复跑，结束后自动恢复数据库基线）

```powershell
# 交付配置（yml ai.enable=false）下：228 条全过（210 基线 + 18 条 AI 关闭路径/权限/边界/只读）
powershell -ExecutionPolicy Bypass -File C:\Users\72797\Course\reservation-system\R7-delivery-ai-integration\docs\test-script.ps1

# 联调模式（yml ai.enable=true + db ai_enable=true）下：234 条全过（额外执行开启路径功能/降级 11 条）
# 步骤：先按第五节开启双开关并重启后端，再运行同一脚本
```

测试脚本自动分支：检测到 AI 开启执行开启路径用例（T231-T241），否则执行关闭路径用例（T220-T224）；结束后删除测试数据恢复初始基线（预约 13 条四状态 / 收藏 6 条 / ai_config 5 行）。

## 七、接口速览（详见 R7 docs/api-list.md，R8 无新增接口）

| 模块 | 接口数 | 说明 |
| --- | --- | --- |
| 用户 | 10 | 登录/注册/信息/密码/管理/个人统计 |
| 教室 | 8 | 列表/详情/占用/管理/收藏 |
| 预约 | 9 | 提交（后端二次冲突校验）/取消/我的/审核/批量/全量/冲突/导出 |
| 统计 | 4 | 概览/使用率/趋势/时段分布 |
| AI（只读） | 4 | 推荐/解析/问答/合规（登录即可，双开关 AND，限流/超时/密钥缺失自动降级） |
