# 启动说明（R11 · AI 观测补全与结果缓存）

> 本轮**无新增环境要求、无新增依赖、无新增配置键**；沿用项目既有启动方式即可。

## 一、环境要求（与既有基线一致）

| 项 | 要求 |
|---|---|
| JDK | 21 LTS |
| MySQL | 8.0，容器 `reservation-mysql`（宿主 3307 → 容器 3306），库 `reservation`，utf8mb4 |
| Redis | 7，容器 `reservation-redis`（宿主 6380 → 容器 6379）——AI 结果缓存复用该 Redis |
| Node.js | 22.x（仅前端与 E2E 需要） |
| 上游密钥 | 环境变量 `AGNES_API_KEY`（禁止硬编码） |

## 二、启动步骤

```powershell
# 1. 数据库与缓存容器（若未运行）
docker start reservation-mysql reservation-redis

# 2. 后端（IDEA 内置 Maven；cwd = server）
$env:AI_ENABLE = "true"        # 与 ai_config.ai_enable 组成双开关，二者均为 true 才启用
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" -B spring-boot:run

# 3. 前端（cwd = web）
npm run dev
```

端口覆盖（非默认映射时）：`MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD` / `REDIS_PORT`。

## 三、验证方法

1. **启动自检**：启动日志应出现 4 条 `[AI 自检] … 校验通过`（校验各 Prompt 与 `response_format=json_object` 形态一致）与 1 条 Prompt 归属报告；若出现 WARN，说明 Prompt 约定与结构化输出冲突，需检查 `ai_config`。
2. **观测字段**：管理员登录后 `GET /api/stats/ai/metrics`，应能看到 `totalTokens / finishReasonByReason / cacheHitCount / cacheMissCount`；做一次真实 AI 调用后 `totalTokens` 应增长。
3. **缓存生效**：对同一 `purpose` 连续调用两次 `POST /api/ai/compliance-check`，第二次 `cacheHitCount` +1 且 `modelCallCount` 不变。
4. **观察缓存 Key**：`docker exec -i reservation-redis redis-cli -p 6379 --scan --pattern "cache:ai:*"`，Key 形如 `cache:ai:compliance:{Prompt版本}-{关键词库哈希}-{模型}:{输入指纹}`。
5. **回归**：后端 `mvn -B test`（期望 54/54）；端到端 `npx playwright test`（在 `web` 目录，期望 106 通过 / 3 例时段跳过 / 0 失败）。

## 四、开关与降级说明

| 情形 | 行为 |
|---|---|
| `ai.enable=false` 或 `ai_config.ai_enable=false` | AI 接口返回 `enabled=false` 友好提示，前端隐藏 AI 入口；**缓存不被命中**（开关检查在缓存查询之前） |
| `redis.enable=false` 或 Redis 不可用 | 缓存读回源、写空转，AI 链路照常走模型 + 本地规则降级，绝不抛异常、不阻断业务 |
| 上游限流 / 超时 / 服务异常 / 输出非法 | 沿用既有 4 桶降级，切换本地规则模式；**降级结果不写入缓存** |
| 修改 Prompt / 违规关键词库 / 模型 | Prompt 与缓存 Key 的版本段随之变化，旧缓存自动失效（最多受 5s 配置缓存 TTL 影响） |