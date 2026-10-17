# 启动说明（R13 · AI 体验增强）

> 与 R11 相比，本轮**无新增环境变量、无新增 `application.yml` 配置键、无新增数据库表/字段**。
> 唯一差异是**新增 3 个依赖**（后端 `spring-webflux`、前端 `markdown-it` / `dompurify`），
> 前端首次启动前需重新 `npm install`。

## 一、环境要求（与既有基线一致）

| 项 | 要求 |
|---|---|
| JDK | 21 LTS（后端统一 `jakarta.*`，禁止 `javax.*`） |
| 构建工具 | IDEA 内置 Maven 3.9.x（无系统 Maven 时见下方「命令行构建」） |
| MySQL | 8.0，容器 `reservation-mysql`（宿主 3307 → 容器 3306），库 `reservation`，utf8mb4 |
| Redis | 7，容器 `reservation-redis`（宿主 6380 → 容器 6379）——AI 结果缓存复用该 Redis |
| Node.js | 22.x（前端与 E2E 需要） |
| 上游密钥 | 环境变量 `AGNES_API_KEY`（禁止硬编码、禁止提交仓库） |

## 二、新增依赖（本轮唯一环境差异，务必先装）

| 位置 | 依赖 | 说明 |
|---|---|---|
| 后端 `server/pom.xml` | `org.springframework:spring-webflux` | 版本由 `spring-boot-parent` 3.2.10 BOM 管理，**仅用其 WebClient**（上游流式消费）；**未引入** `spring-boot-starter-webflux` / `reactor-netty` / Netty，服务端仍是 Spring MVC + Tomcat |
| 前端 `web/package.json` | `markdown-it@^14`、`dompurify@^3` | AI 回答 Markdown 渲染 + XSS 清洗 |

```powershell
# 后端依赖随构建自动拉取（首次 mvn 会下载 spring-webflux / reactor-core）
# 前端必须重新安装依赖（新增两个包）
cd web ; npm install
```

## 三、启动步骤

```powershell
# 1. 数据库与缓存容器（若未运行）
docker start reservation-mysql reservation-redis

# 2. 后端（cwd = server；IDEA 内置 Maven 或下方命令行 Maven）
$env:AI_ENABLE      = "true"          # 与 ai_config.ai_enable 组成双开关，二者均为 true 才真正启用 AI
$env:AGNES_API_KEY  = "<你的密钥>"     # 上游密钥，仅环境变量注入
& mvn -B spring-boot:run

# 3. 前端（cwd = web）
npm install      # 若尚未执行
npm run dev
```

端口覆盖（非默认映射时）：`MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` / `REDIS_PORT`。

**命令行构建（本机无系统 Maven 时）**：

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
& "C:\Users\72797\.m2\wrapper\dists\apache-maven-3.9.16\56ba1f9f\bin\mvn.cmd" -B spring-boot:run
```

> 若后端 jar 正在运行，`mvn package` 前须先停止该 java 进程，否则 fat jar 被锁定、`repackage` 报错。

## 四、验证方法

1. **启动自检**：启动日志应出现 4 条 `[AI 自检] … 校验通过`（Prompt 与 `response_format=json_object` 形态一致）与 1 条 Prompt 归属报告；无 WARN 即为正常。
2. **结构化 SSE 帧序（推荐接口，需登录态）**：
   ```powershell
   curl -N -b "<登录后的 Cookie>" http://localhost:8080/api/ai/recommend?stream=true
   ```
   期望帧序：`meta → phase → result → done`（缓存未命中）；再次调用（缓存命中）应为 `meta → result → done`（**无 phase**）。
3. **chat 真流式（打字机）**：
   ```powershell
   curl -N -b "<登录后的 Cookie>" "http://localhost:8080/api/ai/chat?stream=true" `
     -H "Content-Type: application/json" `
     -d '{"question":"明天下午有空的教室吗？"}'
   ```
   期望：`meta → delta…（多帧） → done`；携带 `history` 时第二轮回填上下文。
4. **关闭态**：令 `ai_config.ai_enable=false`（或 `AI_ENABLE=false`）后重试，结构化流应以 `meta{enabled:false,degraded:true} → result（disabled VO） → done` 收尾，前端隐藏 AI 入口且不发探测请求。
5. **非流式契约未破坏**：去掉 `?stream=true`，同路径应返回 `Result<VO>` JSON（`Content-Type: application/json`），结构与本轮前一致。
6. **回归**：后端 `mvn -B test`（期望 **118/118** BUILD SUCCESS）；前端 `npm run lint` + `npm run build` 通过；E2E 在 `web` 目录 `npx playwright test`（期望 **118 passed / 0 failed**）。

## 五、开关与降级说明

| 情形 | 行为 |
|---|---|
| `ai.enable=false` 或 `ai_config.ai_enable=false`（或 `AI_ENABLE` 未设） | AI 接口返回 `enabled=false` 友好提示，前端隐藏 AI 入口；结构化流式与 chat 流式均走关闭态事件序列 |
| 上游限流 / 超时 / 服务异常 / 输出非法 | 沿用既有 4 桶降级，切换本地规则模式；结构化流以 `meta{degraded:true,message}` + 本地规则 VO 收尾，chat 流以 FAQ 降级分段收尾 |
| 浏览器中途断开（停止生成/关页） | sink 写失败 → 中止上游调用、`dispose()` 上游订阅、JDK HttpClient 关闭连接；日志为 debug「客户端已断开」，不产生异常栈、不追加错误文案 |
| SSE 70s 超时 | chat 流式在 `onTimeout` 补发 `error` 帧再 `complete()`；结构化流以 `error` 终态收尾，避免前端打字机光标永驻 |
| 模型 / Prompt 变更 | 缓存 Key 版本段随之变化，旧缓存自动失效（受 5s 配置缓存 TTL 影响） |

## 六、交付产物位置

```
docs/iterations/R13-ai-experience-enhancement/
├── docs/
│   ├── task-description.md   # 本轮任务说明（范围、需求依据、技术裁决、验收）
│   ├── api-list.md           # 接口清单（路由分流、事件协议 V2、history 字段）
│   └── test-report.md        # 测试报告（后端 118/118、前端 lint/build、E2E 118）
└── startup-guide.md          # 本文件
```
