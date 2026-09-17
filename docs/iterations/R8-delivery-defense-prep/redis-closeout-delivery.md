# R8 收尾交付说明 —— Redis 加分项并入答辩材料、提交、验收

> 交付时间：2026-09-17
> 提交 commit：`e27b4dc`（在 `050b5b6` 之上）
> 范围：Redis 加分项从「功能有」收尾为「材料全、可复现、已验收」。**未新增任何业务功能**。

## 一、本次收尾改动清单（commit e27b4dc，26 文件，+2553/-217）

### 1. 答辩材料（本轮新增 Redis 内容）
- `materials/training-report.docx`：从更新后的 md 重新生成，18 页。摘要「三大亮点→四大亮点」、接口数 35→36、技术栈表加 Redis 行、5.1 架构加 Redis、5.5 会话增强、新增 6.5「Redis 加分项实现」、新增 7.5「Redis 加分项验证」、9.1 总结加 Redis 条、9.2 展望改为「分布式锁/消息队列」。PDF 逐页渲染核查通过。
- `materials/defense-ppt.pptx`：在线 Slides 编辑后导回，14→15 页。新增第 10 页「09 Redis 加分项：会话与缓存」（2×2 四卡片：Docker 容器化 / 可注销会话·单点 / 缓存一致性 / 故障降级）；目录页右列扩为 6 项；后续页章节号与页码顺延（10 关键设计 / 11 学生端 / 11 管理端 / 12 测试 / 13 总结）；架构页「35 接口→36 接口、JWT 登录→JWT+Redis 会话」；总结页加 Redis 条与展望。
- `docs/iterations/R8-delivery-defense-prep/training-report.md`：报告源稿同步补 Redis 章节（与 docx 一致）。

### 2. 启动与演示文档
- `docs/iterations/R8-delivery-defense-prep/startup-guide.md`：工程路径由旧 R8 复制目录改回根目录 `server/`、`web/`；环境表加 Redis 行；第二节加 `docker exec reservation-redis redis-cli ping` 启动前验证；接口速览用户模块 10→11（新增 logout）；新增第八节 Redis 快速验证命令。
- `docs/iterations/R8-delivery-defense-prep/demo-script.md`：前置加 Redis 容器与 ping 检查；新增「第 10 步（可选压轴）：Redis 加分项现场演示」——登录写 Key → 二次登录顶旧 → 登出删 Key → 缓存一致性 → docker stop/start 降级演示，口径与根目录 `docs/演示脚本.md` 5.6 一致。

### 3. Redis 代码（上轮已完成并实机验证，本轮仅随材料一并提交）
- 后端修改：`pom.xml`、`application.yml`、`common/AuthInterceptor.java`、`common/JwtUtil.java`、`controller/UserController.java`（新增 POST /api/user/logout）、`service/UserService.java`、`service/impl/UserServiceImpl.java`、`service/impl/StatsServiceImpl.java`、`service/impl/ClassroomServiceImpl.java`、`service/impl/ReservationServiceImpl.java`
- 后端新增：`config/RedisProperties.java`、`config/RedisConfig.java`、`config/RedisCache.java`（唯一 Redis 封装，全 try-catch 降级）
- 前端修改：`web/src/api/user.js`、`web/src/stores/user.js`、`web/src/utils/request.js`、`web/src/views/student/StudentLayout.vue`、`web/src/views/admin/AdminHome.vue`、`web/src/views/student/Profile.vue`
- 设计文档：`docs/Redis加分项接入说明.md`（上轮新建，本轮首次入库）、`docs/演示脚本.md`（上轮补 5.6 节 Redis 问答，本轮首次入库）

## 二、Redis 验证结果摘要

### 上轮（2026-09-13，实机全量）
- Docker `reservation-redis`（redis:7.0，6379）运行，`redis-cli ping`=PONG
- 登录写 `auth:token:{userId}`，TTL 与 JWT 24h 一致
- 二次登录旧 Token 401；登出后 Key 消失、旧 Token 401
- 看板/教室列表缓存命中：冷 37ms→热 17ms、63ms→16ms
- 提交预约后看板缓存 2→0 主动失效
- 停 Redis 容器期间登录/鉴权/列表/看板全部 200，自动降级；重启后恢复

### 本轮冒烟（2026-09-17）
- `mvn compile` 通过
- 后端 8080 启动成功（1.97s）
- 学生 zhangsan/123456 登录 → Redis 出现 `auth:token:2`，`TTL`=86400
- `/api/user/info` 200、教室列表 200
- `POST /api/user/logout` → Redis Key 消失
- 旧 Token 再调 `/api/user/info` → HTTP 401

## 三、启动步骤（含 Redis 容器）

```powershell
# 1) 容器
docker start reservation-mysql
docker start reservation-redis
docker exec reservation-redis redis-cli ping   # 期望 PONG

# 2) 后端
cd C:\Users\72797\Course\reservation-system\server
& 'C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd' spring-boot:run
# 端口 8080

# 3) 前端
cd C:\Users\72797\Course\reservation-system\web
npm run dev   # http://localhost:5173
```

默认账号：`admin/admin123`、`zhangsan/123456`。

## 四、未完成项与原因

无遗留未完成项。R8 收尾范围内所有子项（材料同步、冒烟、git 分组提交、交付说明）均已完成。

## 五、验收三步法核对

1. **文档核验**：materials 两份成品含 Redis 四讲点；startup-guide/demo-script 含 Redis 步骤；报告 md 源稿与 docx 一致。✅
2. **规范核验**：
   - 仅引入 `spring-boot-starter-data-redis`，无其他新依赖 ✅
   - 全 `jakarta.*` 包，无 `javax.*` ✅
   - Redis 连接信息、JWT 密钥走 `application.yml` / 环境变量，无硬编码 ✅
   - 唯一新增接口 `POST /api/user/logout`，未改既有接口路径/参数/返回 ✅
   - 未改预约冲突检测、状态流转、权限控制、AI 模块逻辑 ✅
   - 用途严格限定在需求文档 2.2 第 145 行两处（缓存热门教室数据 + 存储登录 Token），未上分布式锁/消息队列/排行榜 ✅
3. **运行验证**：`mvn compile` 通过；后端启动；登录写 Key → 登出删 Key → 旧 Token 401 → 列表/看板 200。✅

## 六、git 提交说明

- 本次提交：`e27b4dc feat(Redis): 缓存热门教室数据与登录 Token（需求文档 2.2 第 145 行）`，26 文件。
- 历史遗留未提交改动（`AGENTS.md`、`web/index.html`、`web/src/assets/main.css`、AI 模块相关、`web/public/`、`.agents/`、`docs/e2e/` 等）**未动、未提交**，按 R8 task-description「环境决策」记录原样保留，由负责人决定去留。
