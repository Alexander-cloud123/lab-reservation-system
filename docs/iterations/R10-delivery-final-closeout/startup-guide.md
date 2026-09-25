# R10 交付启动说明 —— 高校实验室预约管理系统（最终交付）

> 交付包根目录：`archive/R10-delivery-final-closeout/`
> 代码快照：后端 `code/reservation-server/`（= 项目根 `server/`，排除 `target/` 与日志）、前端 `code/reservation-web/`（= 项目根 `web/`，排除 `node_modules/`、`dist/`、`e2e-report/`、`test-results/` 与日志）；数据库脚本：`database/init_db.sql`。
> 对应仓库提交：HEAD `188e4bc`（阶段5 无障碍修复 + 41-a11y 无障碍回归用例；`web/src` 业务代码与 `11862a9` 一致）；后端冻结标签：`v1.0-backend-freeze`。
> 技术栈：Spring Boot 3.2.10 / Vue 3.4 + Vite 5 / Element Plus 2.7 / MySQL 8.0 / Redis 7.0。

## 一、环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 21 LTS |
| Maven | IDEA 内置 Maven 3.9.x（`mvn` 不在 PATH；命令行示例：`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd`） |
| Node.js | 22.x |
| MySQL | 8.0.x（库 `reservation`，字符集 `utf8mb4`） |
| Redis | 7.0.x（可选加分项；`redis.enable=false` 时自动降级为纯 JWT 无会话模式，核心业务不受影响） |
| 浏览器 | Chrome / Edge（前端 `http://localhost:5173`） |
| 端口 | 后端 8080、前端 5173、MySQL 与 Redis 端口见第三节（**本机开发容器映射非默认端口，务必先核对**） |

禁止降级安装任何环境；禁止启动无关的旧项目容器。

## 二、数据库初始化

- `database/init_db.sql` 为**自包含**脚本（`DROP` + `CREATE` + `INSERT`），可直接初始化空库。
- 初始基线数据：用户 5 / 教室 12 / 预约 13 / 收藏 6 / `ai_config` 5 行（`ai_enable=false`）。
- 默认账号（见第五节）：管理员 `admin / admin123`；学生 `zhangsan`、`lisi`、`wangwu`、`zhaoliu`，密码均 `123456`（BCrypt 存储）。

导入（示例，容器名按实际替换）：

```powershell
cmd /c "docker exec -i reservation-mysql mysql --default-character-set=utf8mb4 -uroot -proot reservation < database\init_db.sql"
```

> **必须带 `--default-character-set=utf8mb4`**：否则容器内 mysql 客户端按 latin1 解析 UTF-8 字节流，中文会被双重编码写入（表现为中文筛选失效、姓名乱码）。这是本项目已记录过的坑。

## 三、后端启动（含端口与 `MYSQL_URL` 覆盖示例）

```powershell
cd archive\R10-delivery-final-closeout\code\reservation-server
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' spring-boot:run
```

### 3.1 端口坑（本项目最高频故障）

`server/src/main/resources/application.yml` 中的连接信息**默认值**为 MySQL `3306`、Redis `6379`；但本机开发容器把 MySQL 映射到 **3307**、Redis 映射到 **6380**。端口不符时后端**不会启动失败**，而是所有接口返回 HTTP 200 + 响应体 `code=500`「系统异常」，登录接口也会 500。

### 3.2 用环境变量覆盖（推荐，先设置再启动）

```powershell
$env:MYSQL_URL='jdbc:mysql://localhost:3307/reservation?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:MYSQL_USERNAME='root'
$env:MYSQL_PASSWORD='root'
$env:REDIS_PORT='6380'          # 容器 Redis 映射端口（默认 6379）
$env:REDIS_PASSWORD=''          # 如容器设置了密码，通过该变量注入（禁止硬编码）
# 可选：$env:REDIS_ENABLE='false'（纯 JWT 降级） / $env:KNIFE4J_ENABLE='false'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' spring-boot:run
```

支持的环境变量（均来自 `application.yml` 占位符，**默认值仅为本地开发用**）：

| 变量 | 作用 | 默认值 |
| --- | --- | --- |
| `MYSQL_URL` | JDBC 连接串（含端口与库名） | `jdbc:mysql://localhost:3306/reservation?...` |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | 数据库账号 | `root` / `root` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | Redis 连接 | `127.0.0.1` / `6379` / 空 / `0` |
| `REDIS_ENABLE` | Redis 总开关（false = 纯 JWT 降级） | `true` |
| `JWT_SECRET` | Token 签名密钥（生产/演示必须注入） | 仅本地开发默认值 |
| `AGNES_API_KEY` | AI 密钥（仅后端读取，前端零接触） | 空 |
| `KNIFE4J_ENABLE` | 接口文档开关 | `true` |

- 后端端口 8080；接口文档 `http://localhost:8080/doc.html`。
- 启动后自检：`http://localhost:8080/api/config/booking-rules` 可访问（需 Token），或直接走第六节验证流程。

## 四、前端启动

```powershell
cd archive\R10-delivery-final-closeout\code\reservation-web
npm install   # 首次（快照不含 node_modules，需按 package-lock.json 还原）
npm run dev   # http://localhost:5173
```

- 快照已包含 `package.json` / `package-lock.json` / `vite.config.js` / `playwright.config.js`。
- 前端通过 Vite 代理转发 `/api` 到后端（代理目标可用 `VITE_API_TARGET` 指定，默认 `http://localhost:8080`）。

## 五、默认账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `admin123` |
| 学生 | `zhangsan` / `lisi` / `wangwu` / `zhaoliu` | `123456` |

> 数据库会话为「单账号单会话」：同一账号再次登录会使旧 Token 立即失效（Redis 键 `auth:token:{userId}` 只保留最近一次登录）。

## 六、验证方法（核心闭环，5 步）

1. **登录**：打开 `http://localhost:5173` → 学生 `zhangsan / 123456` 登录 → 进入教室列表页；若当日有 24h 内即将开始的已通过预约，顶部出现温和提醒（不阻断跳转）。
2. **教室列表**：按关键词/楼栋/类型/日期筛选，卡片显示「当前空闲 / 使用中 / 已结束」与今日剩余时段；筛选条件离开再返回仍保留。
3. **提交预约**：进入某教室详情 → 打开预约弹窗 → 选日期与时段 → 填写用途 → 提交。若与「已通过」预约时段重叠，前端实时提示冲突并禁用提交（后端提交接口还有二次冲突校验兜底）；成功后提示「预约提交成功，待管理员审核」，可在「我的预约」看到待审核记录。
4. **管理员审核**：退出后以 `admin / admin123` 登录 → 预约审核页 → 对待审核记录执行「通过 / 驳回」（驳回必填备注，有快捷原因）；可勾选多条批量审核（仅待审核参与）。可顺带查看「教室资源管理 / 用户管理 / 预约记录（Excel 导出）」。
5. **数据看板**：进入数据看板页，ECharts 三图（教室使用率排行、月度预约趋势、热门时段分布）渲染，切换近 7/30/90 天或自定义范围后联动刷新；另可在预约日历总览页切换月/周视图查看色块。

补充核验点：个人中心（资料修改、改密、消息通知已读）、收藏（上限 10 间）、越权（未登录访问受保护页面跳登录页；学生调用管理员接口返回 403）。

## 七、AI 功能开关

- **交付默认关闭**：`application.yml` 中 `ai.enable=false`（数据库 `ai_config.ai_enable=false`），AI 接口返回 `enabled=false` 友好提示，前端隐藏 AI 入口，系统为纯预约系统，核心功能不受影响。
- **启用方式（双开关必须同时为 true）**：
  1. `code/reservation-server/src/main/resources/application.yml` 中 `ai.enable` 改为 `true`，重启后端；
  2. 数据库执行：`UPDATE ai_config SET config_value='true' WHERE config_key='ai_enable';`
  3. （可选）如需真实模型输出，注入环境变量 `AGNES_API_KEY`（前端零接触密钥）。
- **降级说明**：无密钥 / 上游限流（RPM 20，全站聚合上限 100）/ 超时（60s）/ 连接失败时自动降级为本地规则模拟，接口仍返回 200 且结果可用，降级原因透出在 `data.message`，不阻断业务。
- **复位**：演示结束后把 `ai.enable` 改回 `false` 并重启，同时 `UPDATE ai_config SET config_value='false' WHERE config_key='ai_enable';`。

## 八、测试运行

### 8.1 后端单元测试（JUnit 5 + Surefire，34 条）

```powershell
cd archive\R10-delivery-final-closeout\code\reservation-server
# 必需：测试会真实连接容器 MySQL / Redis，端口需按第 3.2 节覆盖
$env:MYSQL_URL='jdbc:mysql://localhost:3307/reservation?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:MYSQL_PASSWORD='root'; $env:REDIS_PORT='6380'
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' -B test
```

- 实测结果：`Tests run: 34, Failures: 0, Errors: 0, Skipped: 0`，**BUILD SUCCESS**，耗时 1:48（其中 `UserServiceImplLoginLockTest` 按真实时间推进登录锁定窗口，单类约 96s）。本次实测用的是 `~/.m2/wrapper/dists` 下的 Apache Maven 3.9.16，与 IDEA 内置 3.9.x 等价，换用任一 3.9.x 均可。
- `ConcurrencyIntegrationTest` 与 `AuthAndValidationSmokeTest` 使用 `@SpringBootTest`，会真实连库；其余为纯单元测试（Mockito）。`webEnvironment` 默认 `MOCK`，**不绑定端口**，与已在运行的 8080 / 8081 实例不冲突。
- 用例自带数据清理（跑完行数不变），但仍建议与 E2E 一样在结束后按第二节命令重新导入 `init_db.sql`，让自增计数等也回到基线。

### 8.2 端到端测试（E2E）运行

```powershell
# 前置：MySQL / Redis 容器在线，后端已启动（Playwright 不自动起后端，仅探活）
cd archive\R10-delivery-final-closeout\code\reservation-web
npm run test:e2e            # 全量 16 spec / 105 用例（串行 1 worker，Chromium）
npm run test:e2e:smoke      # 仅骨架冒烟
npm run test:e2e:report     # 查看 HTML 报告（e2e-report/html）
```

- 默认后端端口 8080；若被占用：后端以 `--server.port=8081` 启动，并设 `$env:E2E_API_PORT='8081'` 后重跑。
- 用例串行执行（共享同一 MySQL 库），前端 dev server 由 Playwright 自动拉起（`reuseExistingServer: true`）。
- 实测基线（最终交付口径）：`npm run lint` 0 error / 0 warning；`npm run build` 成功（2101 modules）；E2E **105/105 通过**，耗时 2.0m（最近一次全量回归实测后端为**交付默认端口 8080**；交付打包首次实测时 8080 曾一度被占用，当时经 `E2E_API_PORT=8081` + `VITE_API_TARGET=http://localhost:8081` 覆盖跑通，两种端口下结论一致）；后端 `mvn test` **34/34 通过**（BUILD SUCCESS，1:48）；回归后数据库 预约 13 / 用户 5 / 教室 12 / E2E 临时教室残留 0。

## 九、常见故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 所有接口 HTTP 200 但 `code=500`「系统异常」，登录也失败 | 后端连错 MySQL 端口（`application.yml` 默认 3306，容器实际映射 3307） | 按 3.2 设置 `MYSQL_URL`（端口 3307）后重启；先看后端日志的 `Connection refused` |
| Redis 相关报错 / 会话异常 | Redis 端口不符（默认 6379，容器映射 6380） | 设 `REDIS_PORT=6380`；或临时 `REDIS_ENABLE=false` 降级为纯 JWT |
| 数据库中文乱码、中文筛选返回 0 条 | 导入 `init_db.sql` 时未带 `--default-character-set=utf8mb4` | 按第二节命令带参数重新导入 |
| 端口 8080 被占用 | 本机其他项目占用（部分环境还会注入 `SERVER__PORT`） | 以 `--server.port=8081` 启动后端并相应设置 `E2E_API_PORT`；必要时清除环境变量 `SERVER__PORT` |
| Playwright 报「后端探活失败」 | 后端未启动 / 端口不符 / MySQL、Redis 未就绪 | 按提示先确认容器在线与后端已启动、端口一致 |
| 前端页面空白或接口 404 | 前端 dev server 的代理目标不是后端实际端口 | 设 `VITE_API_TARGET=http://localhost:<后端端口>` 后重启 dev server |
| AI 入口不显示 | `ai.enable=false`（交付默认）或数据库 `ai_enable=false` | 按第七节开启双开关 |
| 同一账号登录后旧页面立刻 401 | 单账号单会话：新登录顶掉旧 Token | 用同一账号只保留一个活跃会话 |

## 十、接口速览（详见 `docs/api-list.md`）

| 模块 | 端点数 | 说明 |
| --- | --- | --- |
| 用户 `/api/user` | 10 | 登录/登出/注册/信息/个人统计/改资料/改密/管理（分页、启停、重置密码） |
| 教室（学生端）`/api/classroom` | 2 | 列表（含实时状态与日期占用）、详情 |
| 教室（管理端）`/api/classroom/manage` | 6 | 分页/新增/编辑/删除/启停/批量启停 |
| 预约 `/api/reservation` | 9 | 冲突检测/提交（后端二次校验）/我的/取消/全量/导出/审核/批量审核/日历 |
| 收藏 `/api/favorite` | 2 | 收藏切换 / 我的收藏 |
| 统计 `/api/stats` | 4 | 概览 / 使用率排行 / 月度趋势 / 时段分布（管理员） |
| 系统配置 `/api/config` | 1 | 预约规则下发（供前端实时校验与后端同口径） |
| AI `/api/ai` | 4 | 推荐/解析/问答/合规（登录即可、只读、可降级） |
| **合计** | **38** | 统一返回 `Result{code,message,data}` |

- 权限：未登录 → 401；学生访问管理员专属接口 → 403；AI 接口登录即可。
- 密钥安全：`AGNES_API_KEY`、`JWT_SECRET`、数据库/Redis 密码均仅从环境变量或配置读取，未硬编码，前端不接触 AI 密钥。