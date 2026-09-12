# 启动说明（R3-delivery-reservation-core）

## 环境基线（禁止降级安装，禁止启动旧项目容器）

| 组件 | 版本/要求 |
|---|---|
| JDK | 21 LTS |
| Maven | IDEA 内置 3.9.x（mvn 不在 PATH，用下方完整路径） |
| Node.js | 22.x |
| Docker | 运行 MySQL 8.0 容器 `reservation-mysql`（映射 localhost:3306，库 reservation，utf8mb4，root/root，`--restart unless-stopped`） |
| 后端 | Spring Boot 3.2.10 / MyBatis-Plus 3.5.7 / jakarta.* |
| 前端 | Vue 3.4.38 / Element Plus 2.7.8 / Vue Router 4.4.3 / Pinia 2.1.7 / Vite 5.4.x |

> 容器若停止只需 `docker start reservation-mysql`，**禁止重建或重新导数据**；禁止启动 ssm-mysql / student-manage-mysql 旧容器。

## 一、数据库

运行期库已就绪（五表 + 测试数据），无需操作。若需重建环境：
1. `docker start reservation-mysql`
2. 导入自包含脚本：`docker exec -i reservation-mysql mysql -uroot -proot reservation < database/init_db.sql`
3. 验证：`docker exec -i reservation-mysql mysql -uroot -proot reservation -e "SELECT COUNT(*) FROM classroom;"`（应为 12）

## 二、后端（reservation-server）

```powershell
cd code\reservation-server
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
```

- 监听 http://localhost:8080；日志中应出现 Tomcat started / Started ReservationServerApplication
- 编译检查（可选）：`& "...mvn.cmd" -q compile -DskipTests`
- 默认账号：管理员 `admin / admin123`（role=1）；学生 `zhangsan / 123456`（role=0）等

## 三、前端（reservation-web）

```powershell
cd code\reservation-web
npm install        # 依赖已由 package-lock 锁定；registry 可指向 npmmirror
npm run dev        # 开发模式
```

- 监听 http://127.0.0.1:5173，`/api` 代理到 8080
- 页面入口：
  - 学生端：登录后进入 `/student/home`（教室列表，原占位首页已被替换）→ 教室详情 `/student/classrooms/:id`（预约申请）→ `/student/my-reservations`
  - 管理端：登录后 `/admin` 布局，导航含 首页 / 用户管理 / 教室管理 / **预约审核**（/admin/audits）
- 生产构建（可选）：`npm run build`

## 四、接口冒烟

```powershell
$base='http://localhost:8080'
# 学生登录
$r = Invoke-RestMethod "$base/api/user/login" -Method Post -ContentType 'application/json' -Body '{"username":"zhangsan","password":"123456","role":0}'
$token = $r.data.token
# 教室列表（学生）
$h = @{ Authorization = "Bearer $token" }
(Invoke-RestMethod "$base/api/classroom/list?page=1&size=50" -Headers $h).data.total   # 12
# 冲突检测：教室12 今天 10:00-12:00（旧 08:00-10:00 首尾相接 → 不冲突）
(Invoke-RestMethod "$base/api/reservation/conflict?classroomId=12&date=2026-09-11&startTime=10:00&endTime=12:00" -Headers $h).data.conflict   # False
```

## 五、全量回归测试

```powershell
cd docs
powershell -NoProfile -ExecutionPolicy Bypass -File test-script.ps1
```

- 125 条用例（R1 21 + R2 50 回归 + R3 54 断言）全部通过
- 脚本自带清理，运行结束数据库恢复初始基线（教室 12 间、预约 13 条四状态、无临时用户）
- 输出含逐条结果与失败明细，可 `Tee-Object test-run-output.log` 留存

## 六、目录结构

```
R3-delivery-reservation-core/
├── code/
│   ├── reservation-server/   # 后端（R2 基线增量：预约/教室学生端模块）
│   └── reservation-web/      # 前端（新增学生端 4 页 + 管理端审核页）
├── database/init_db.sql      # 自包含建库脚本（五表 + 测试数据）
├── docs/
│   ├── task-description.md   # 任务说明（规则与决策标注、状态标签口径）
│   ├── api-list.md           # 接口清单（9 个新接口 + 权限矩阵）
│   ├── test-report.md        # 测试报告（四类用例 + 回归关系）
│   └── test-script.ps1       # 可复跑测试脚本（T01-T127）
└── startup-guide.md          # 本文件
```
