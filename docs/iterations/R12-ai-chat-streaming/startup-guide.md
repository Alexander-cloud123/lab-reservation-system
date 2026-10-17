# 启动说明（R12 · 智能问答 SSE 流式输出）

> 与 R11 相比，本轮**无新增环境变量、无新增依赖、无新增数据库表/字段**。
> 唯一新增配置为 `application.yml` 中 AI 段的**流式线程池参数**（`ai.stream.executor.*`，带缺省值，不改也可运行）。
> **归档说明**：本说明为 R12 交付后**回补归档**，依据提交 `f413bc8`。

## 一、环境要求（与既有基线一致）

| 项 | 要求 |
|---|---|
| JDK | 21 LTS（后端统一 `jakarta.*`，禁止 `javax.*`） |
| 构建工具 | IDEA 内置 Maven 3.9.x（无系统 Maven 时见下方「命令行构建」） |
| MySQL | 8.0，容器 `reservation-mysql`（宿主 3307 → 容器 3306），库 `reservation`，utf8mb4 |
| Redis | 7，容器 `reservation-redis`（宿主 6380 → 容器 6379）——AI 结果缓存复用该 Redis |
| Node.js | 22.x（前端与 E2E 需要） |
| 上游密钥 | 环境变量 `AGNES_API_KEY`（禁止硬编码、禁止提交仓库） |

## 二、新增配置（本轮唯一环境差异）

`server/src/main/resources/application.yml` 的 `ai` 段新增：

```yaml
ai:
  stream:
    executor:                 # SSE 流式问答专用线程池（仅承载 chat?stream=true）
      core-pool-size: 2
      max-pool-size: 8
      queue-capacity: 100
```

- **带缺省值**：不配置时按 `2 / 8 / 100` 生效（`AiProperties.Stream.Executor` 内同理），故升级后**无需改配置即可运行**。
- 线程池为 `ThreadPoolTaskExecutor` + **有界队列 + `AbortPolicy`**（打满时 controller 捕获拒绝并回 `error` 事件），daemon 线程。
- **无新增依赖**：本轮零新增（线程池用框架自带组件，前端用浏览器原生 `fetch`/`ReadableStream`）。

## 三、启动步骤

```powershell
# 1. 数据库与缓存容器（若未运行）
docker start reservation-mysql reservation-redis

# 2. 后端（cwd = server；IDEA 内置 Maven 或下方命令行 Maven）
$env:AI_ENABLE      = "true"          # 与 ai_config.ai_enable 组成双开关，二者均为 true 才真正启用 AI
$env:AGNES_API_KEY  = "<你的密钥>"     # 上游密钥，仅环境变量注入
& mvn -B spring-boot:run

# 3. 前端（cwd = web）
npm install    # 若尚未执行（本轮无新增前端依赖，沿用既有 node_modules）
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

1. **启动自检**：启动日志应出现 4 条 `[AI 自检] … 校验通过`（Prompt 与 `response_format=json_object` 形态一致）与 1 条 Prompt 归属报告；无 WARN 即为正常（流式 `CHAT_STREAM` 不参与该 JSON 自检）。
2. **流式问答（打字机）**：
   ```powershell
   curl -N -b "<登录后的 Cookie>" "http://localhost:8080/api/ai/chat?stream=true" `
     -H "Content-Type: application/json" -d '{"question":"怎么取消预约？"}'
   ```
   期望帧序：`meta(enabled=true,degraded=false)` → `delta…（多帧）` → `done(stop)`。
3. **降级态**：断开上游或触发限流后重试，期望 `meta(enabled=true,degraded=true,message=降级原因)` → `delta*`（FAQ 分段） → `done(fallback)`。
4. **关闭态**：令 `ai_config.ai_enable=false`（或 `AI_ENABLE=false`）后重试，期望 `meta(enabled=false,degraded=true,message=关闭文案)` → `done(disabled)`（**中间无 delta**）；前端隐藏 AI 入口且不发探测请求。
5. **非流式契约未破坏**：去掉 `?stream=true`，同路径应返回 `Result<AiChatVO>` JSON（`Content-Type: application/json`），结构与本轮前一致。
6. **回归**：后端 `mvn -B test`（期望 **88/88**）；前端 `npm run lint` + `npm run build` 通过；E2E 在 `web` 目录 `npx playwright test`（期望 **110 通过 + 3 守卫跳过 / 0 失败**）。

## 五、开关与降级说明

| 情形 | 行为 |
|---|---|
| `ai.enable=false` 或 `ai_config.ai_enable=false`（或 `AI_ENABLE` 未设） | AI 接口返回 `enabled=false` 友好提示，前端隐藏 AI 入口；流式走 `meta(enabled=false)` → `done(disabled)` |
| 上游限流 / 超时 / 服务异常 / 输出非法 | 沿用既有 4 桶降级，切换本地规则模式；降级 FAQ 以分段 `delta` 推送，`done(fallback)` 收尾 |
| 浏览器中途断开（关抽屉/卸载组件） | sink 写失败 → 中止上游读取 → 静默结束；日志为 debug「客户端已断开」，不产生异常栈、不追加错误文案 |
| SSE 70s 超时 | `onTimeout` 补发 `error` 帧再 `complete()`，避免前端打字机光标永驻 |
| 流式线程池打满 | 回 `error`「AI 服务繁忙，请稍后再试」；不压垮 Tomcat 请求线程 |
| 模型 / Prompt 变更 | 缓存 Key 版本段随之变化，旧缓存自动失效（受 5s 配置缓存 TTL 影响）；流式缓存取 `CHAT_STREAM` 版本段，与非流式独立 |

## 六、交付产物位置

```
docs/iterations/R12-ai-chat-streaming/
├── docs/
│   ├── task-description.md   # 本轮任务说明（范围、需求依据、设计裁决、验收）
│   ├── api-list.md           # 接口清单（路由分流、SSE 事件协议、降级/关闭序列）
│   └── test-report.md        # 测试报告（后端 88/88、前端 lint/build、E2E 110+3）
└── startup-guide.md          # 本文件
```
