# Redis 加分项接入说明（需求设计文档 2.2 第 145 行・经负责人确认实施）

> 需求依据：《需求设计文档.md》V3.1 第 2.2 节技术栈表第 145 行
> 可选加分 | Redis | 7.0.x | Docker 容器方式运行（reservation-redis，映射 6379:6379），缓存热门教室数据、存储登录 Token（须经负责人确认）
> 实施范围严格限定为该条定义的两件事：
>
> **① 缓存热门教室数据；② 存储登录 Token**
>
> 。未新增任何需求外功能，未改动预约冲突检测、状态流转、权限控制与 AI 模块逻辑，既有接口路径 / 参数 / 返回结构保持不变。



***

## 1. 环境与部署（与 MySQL 一致，Docker 容器）



```
\# 1. 启动容器（官方 redis:7.0，本机演示默认无密码，仅监听 localhost）

docker run -d --name reservation-redis -p 6379:6379 redis:7.0

\# 2. 连通性验证（返回 PONG）

docker exec reservation-redis redis-cli ping

\# 3. 观察 Key（登录/访问后）

docker exec reservation-redis redis-cli keys 'auth:token:\*'   # 登录会话

docker exec reservation-redis redis-cli keys 'cache:\*'        # 业务缓存
```



* 镜像实测版本：`redis:7.0` → Redis 7.0.15；容器名 `reservation-redis`，端口 `6379:6379`。

* 如需密码：`docker run -d --name reservation-redis -p 6379:6379 redis:7.0 redis-server --requirepass 你的密码`，并通过环境变量 `REDIS_PASSWORD` 注入后端，**不在代码 /yml 明文写死**。



***

## 2. 改动文件清单

### 2.1 后端（server/）



| 文件                                                             | 改动类型 | 说明                                                                                                                                              |
| -------------------------------------------------------------- | ---- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `pom.xml`                                                      | 修改   | 新增 `spring-boot-starter-data-redis`（版本由 spring-boot-starter-parent 3.2.10 统一管理，不写版本号；用默认 Lettuce，未引入 Redisson 等任何额外组件）                          |
| `src/main/resources/application.yml`                           | 修改   | 新增 `spring.data.redis`（host/port/password/database/timeout，全部环境变量占位）与自定义段 `redis.enable / redis.token / redis.cache`                            |
| `config/RedisProperties.java`                                  | 新增   | 绑定 `redis.*` 配置（总开关、Token Key 前缀、两类缓存 TTL），风格对齐 `ai` 模块 `AiProperties`                                                                          |
| `config/RedisConfig.java`                                      | 新增   | 显式声明 `RedisTemplate<String,String>`：Key/Value/Hash 全部 String 序列化，避免 redis-cli 乱码；另提供缓存专用 `ObjectMapper`（注册 JSR-310 时间模块，独立于 MVC 的 ObjectMapper） |
| `config/RedisCache.java`                                       | 新增   | **唯一 Redis 访问封装**：会话 save/validate/remove、对象 JSON 缓存 get/set、SCAN 批量失效；所有操作 try-catch 降级，不向上抛异常                                                 |
| `common/JwtUtil.java`                                          | 修改   | 新增 `getExpireSeconds()`，供会话 Key 设置与 JWT 一致的 TTL（单一数据源，不重复写 24h）                                                                                 |
| `service/UserService.java`、`service/impl/UserServiceImpl.java` | 修改   | 登录签发 JWT 后写入 Redis 会话；新增 `logout()` 删除会话                                                                                                        |
| `controller/UserController.java`                               | 修改   | **新增** `POST /api/user/logout`（需携带 Token；唯一新增接口，其余接口不变）                                                                                         |
| `common/AuthInterceptor.java`                                  | 修改   | JWT 验签 + 账号状态校验通过后，增加 Redis 会话校验：会话失效→401，Redis 未启用 / 异常→降级为仅 JWT 放行                                                                            |
| `service/impl/StatsServiceImpl.java`                           | 修改   | 数据看板 3 个聚合接口（使用率 / 月度趋势 / 时段分布）加读缓存，60s TTL                                                                                                     |
| `service/impl/ClassroomServiceImpl.java`                       | 修改   | 学生端教室列表加短缓存 60s；教室新增 / 编辑 / 删除 / 启停 / 批量启停后主动失效缓存                                                                                               |
| `service/impl/ReservationServiceImpl.java`                     | 修改   | 预约提交 / 取消 / 单条审核 / 批量审核后主动失效缓存（冲突检测、状态流转逻辑原样保留，仅在写库成功后追加一行失效调用）                                                                                 |

### 2.2 前端（web/，最小改动，接口路径参数不变）



| 文件                                                                    | 改动                                                                                  |
| --------------------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `src/api/user.js`                                                     | 新增 `logout()` 调用 `POST /user/logout`（`silent` 静默，失败不弹错误提示）                          |
| `src/stores/user.js`                                                  | `logout()` 改为 async：先请求后端注销会话，无论成败都清除本地登录态                                          |
| `src/utils/request.js`                                                | 支持请求配置 `silent`（登出等请求失败时不弹全局错误提示）；401 统一处理逻辑不变                                      |
| `src/views/student/StudentLayout.vue`、`src/views/admin/AdminHome.vue` | 登出改为 `await userStore.logout()` 后再跳登录页                                              |
| `src/views/student/Profile.vue`                                       | 修改密码后的登出走统一 `await userStore.logout()`（避免先清 Token 导致注销请求未携带凭证），移除不再使用的 clearAuth 引入 |



***

## 3. 缓存 / 会话 Key 设计



| Key                                                                     | 类型       | Value                     | TTL                                               | 失效方式                                                   |
| ----------------------------------------------------------------------- | -------- | ------------------------- | ------------------------------------------------- | ------------------------------------------------------ |
| `auth:token:{userId}`                                                   | String   | 该用户当前有效 JWT               | 与 JWT 一致（24h，`app.jwt.expire-hours`）              | 退出登录删除；同账号新登录覆盖（单点会话）                                  |
| `cache:stats:{接口名}:{开始日期}:{结束日期}`                                       | JSON 字符串 | 看板聚合结果（List）              | 60s（`redis.cache.stats-ttl-seconds`）              | 预约 / 教室变更时 SCAN 删除 `cache:stats:*`；或 TTL 自然过期          |
| `cache:classroom:list:{page}:{size}:{keyword}:{building}:{type}:{date}` | JSON 字符串 | `PageResult<ClassroomVO>` | 60s（`redis.cache.classroom-ttl-seconds`，动态数据只短缓存） | 预约 / 教室变更时 SCAN 删除 `cache:classroom:list:*`；或 TTL 自然过期 |

设计要点：



* 看板日期区间经 `resolveRange` 归一化后再拼 Key，缺省「近 30 天」与显式传同一区间共享一份缓存；空筛选参数以 `-` 占位，不同条件互不串缓存。

* 批量失效使用 **SCAN 游标**（`ScanOptions`，count=200）而非 `KEYS *`，避免阻塞 Redis；本项目数据量小，失效为瞬时操作。

* Value 以 JSON 文本存储，`redis-cli` 直接可读、无 JDK 序列化乱码；反序列化失败按缓存未命中处理并清除脏 Key。



***

## 4. 开关与故障降级（答辩亮点，风格对齐 AI 模块）



* **一键启停**：`application.yml` 的 `redis.enable`（环境变量 `REDIS_ENABLE`，默认 true）。置为 false 后 `RedisCache` 所有读写直接空转 / 跳过，系统退化为「纯 JWT 无状态校验 + 直接查库」，核心功能完全可用。

* **连接失败 / 命令超时自动降级**（`spring.data.redis.timeout=3000ms` 快速失败），三态会话校验是关键：



| `RedisCache.validateToken` 结果 | 含义                                 | 拦截器动作                   |
| ----------------------------- | ---------------------------------- | ----------------------- |
| VALID                         | Redis 中会话存在且 Token 一致              | 放行                      |
| INVALID                       | Redis 正常但无会话（已登出 / 过期 / 被同账号新登录顶掉） | **401「登录已过期，请重新登录」**    |
| SKIP                          | `redis.enable=false` 或 Redis 连接异常  | **降级为仅 JWT 校验放行，不阻断业务** |



* 查询接口在 Redis 异常时 `getObject` 返回 null → 自动回源数据库，返回结果与不启用缓存完全一致；写缓存 / 失效失败仅告警、不影响主事务。后端日志以 `WARN ... RedisCache` 记录每次降级，便于答辩展示。

* **Redis 不是持久化依赖**：任何功能在 Redis 不可用时都可独立运行；容器重启后下次请求自动重建缓存、下次登录重新建立会话。



***

## 5. 接口影响说明（向后兼容）



* 仅**新增** `POST /api/user/logout`（无请求体，需携带 Authorization），返回统一 `Result` 结构；不修改任何既有接口的路径、参数与返回结构。

* 登录接口返回结构不变（仍是 token + user），只是服务端额外在 Redis 留存会话；前端在原「清除本地态」基础上多一次最佳努力的后端注销调用。



***

## 6. 验证结果（2026-09-13 实机走查，后端 8080 + MySQL 容器 + Redis 容器）

### 6.1 构建



* 后端：IDEA 内置 Maven `mvn compile` **通过**（新增 config 包 5 个 class 全部生成）。

* 前端：`npm run build` **通过**（Vite 构建 6.56s，无报错 / 无未使用变量告警）。

### 6.2 Token 会话（Test A，全部符合预期）



| 用例                        | 结果                                                               |
| ------------------------- | ---------------------------------------------------------------- |
| 学生登录                      | HTTP 200；Redis 出现 `auth:token:2`，Value 等于 Token，TTL=86399s（≈24h） |
| Token 访问 `/api/user/info` | HTTP 200                                                         |
| 同账号二次登录                   | Redis 值被新 Token 覆盖；**旧 Token 再访问 → 401**（单点会话）；新 Token → 200     |
| 退出登录                      | HTTP 200；会话 Key 立即删除；**登出前的 Token 再访问 → 401**（可注销会话）             |

### 6.3 缓存与一致性（Test B，全部符合预期）



| 用例                    | 结果                                                                                  |
| --------------------- | ----------------------------------------------------------------------------------- |
| 看板 usage-rate 冷 / 热两次 | 均 200、数据一致；耗时 37ms → 17ms；生成 `cache:stats:usage-rate:2026-08-15:2026-09-13`，TTL 60s |
| 教室列表冷 / 热两次           | 均 200、数据一致；耗时 63ms → 16ms；生成 `cache:classroom:list:...`，TTL 60s                     |
| 提交一条预约                | 缓存 Key 由 2 → **0**（写后主动失效）；再次请求看板缓存自动重建、数据正确                                        |
| 数据基线                  | 走查前预约 14 条；测试预约（id=32）用后即删，**走查后恢复 14 条**，未污染基线                                     |

### 6.4 故障降级（Test C，全部符合预期）

`docker stop reservation-redis` 期间：学生登录 200（正常签发 JWT）、Token 访问 200（降级仅 JWT）、教室列表 200（回源，total=12）、看板 200（回源，12 间排行）；后端日志可见「Redis 校验登录会话失败，退化为仅 JWT 校验」「读取缓存失败，回源查库」等 WARN。

`docker start reservation-redis` 后 `ping=PONG`，重新登录即重新写入会话 Key，缓存能力恢复。



***

## 7. 配置项与环境变量一览



| 配置                                  | 环境变量             | 默认值       | 说明               |
| ----------------------------------- | ---------------- | --------- | ---------------- |
| `spring.data.redis.host`            | `REDIS_HOST`     | 127.0.0.1 | Redis 主机         |
| `spring.data.redis.port`            | `REDIS_PORT`     | 6379      | Redis 端口         |
| `spring.data.redis.password`        | `REDIS_PASSWORD` | 空         | 密码，默认无密码         |
| `spring.data.redis.database`        | `REDIS_DATABASE` | 0         | 逻辑库              |
| `spring.data.redis.timeout`         | —                | 3000ms    | 命令超时（故障时快速失败以降级） |
| `redis.enable`                      | `REDIS_ENABLE`   | true      | Redis 总开关        |
| `redis.cache.stats-ttl-seconds`     | —                | 60        | 看板缓存 TTL         |
| `redis.cache.classroom-ttl-seconds` | —                | 60        | 教室列表缓存 TTL       |



***

## 8. 边界约束与说明



1. 严格只实现「缓存热门教室数据 + 存储登录 Token」，**未**加入分布式锁、消息队列、排行榜等需求文档未定义能力。

2. 未改动数据库表结构、预约冲突检测公式、状态流转、权限矩阵与 AI 模块；缓存失效仅在既有写库成功后追加一行调用。

3. 唯一新增依赖为 `spring-boot-starter-data-redis`，未引入 Redisson / 连接池 commons-pool2 等额外组件（Lettuce 默认单连接共享，本项目演示并发足够）。

4. 连接信息与密钥全部走环境变量占位，无硬编码。

5. 已知表现（非缺陷）：Redis 容器停止瞬间，Lettuce 命令按配置最长等待 3s 才判定超时降级，因此**故障期间**单次请求在 Redis 环节最多增加约 3s 延迟但不失败；容器恢复后即恢复正常。这是「3s 超时 + 降级」的预期取舍，答辩可主动说明。