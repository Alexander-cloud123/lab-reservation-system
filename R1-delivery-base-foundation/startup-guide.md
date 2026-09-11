# R1 启动说明

> 适用：本机（Windows 11）验收 R1 交付物。所有命令在 PowerShell 中执行。

---

## 一、环境要求（均已验证）

| 项 | 要求 | 本机现状 |
|----|------|----------|
| JDK | 21 LTS | ✅ 21.0.11 |
| Maven | 3.9.x（IDEA 内置即可） | ✅ 3.9.11（`C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd`） |
| Node.js | 22.x，npm 镜像 npmmirror | ✅ 22.23.2 |
| Docker | Docker Desktop 运行中 | 需确保运行（本项目数据库在容器中） |
| 端口 | 3306（MySQL）、8080（后端）、5173（前端） | 未被占用 |

> ⚠️ 旧容器（ssm-mysql / student-manage-mysql 等）保持**停止**即可，无需删除；本项目使用独立容器 `reservation-mysql`。

## 二、数据库（已就绪，重建方法）

```powershell
# 1. 若容器不存在，创建（root 密码 root，映射 3306）
docker run -d --name reservation-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e TZ=Asia/Shanghai -v reservation-mysql-data:/var/lib/mysql mysql:8.0
# 2. 容器加自愈策略（防止 Docker 重启后 MySQL 掉线）
docker update --restart unless-stopped reservation-mysql
# 3. 初始化库表与测试数据（脚本幂等，可重复执行）
cmd /c "docker exec -i reservation-mysql mysql -uroot -proot --default-character-set=utf8mb4 < C:\Users\72797\Course\reservation-system\R1-delivery-base-foundation\database\init_db.sql"
# 4. 验证
docker exec reservation-mysql mysql -uroot -proot -e "USE reservation; SHOW TABLES; SELECT COUNT(*) FROM sys_user;"
```

## 三、启动步骤

### 后端（reservation-server）

```powershell
cd "C:\Users\72797\Course\reservation-system\R1-delivery-base-foundation\code\reservation-server"
# 方式 A：命令行（使用 IDEA 内置 Maven）
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
# 方式 B：IDEA 打开工程 → Maven 面板 → 选择 Runner JRE 为 Project SDK(21) → 启动 ReservationApplication
```

可选环境变量：`MYSQL_PASSWORD`（默认 root）、`JWT_SECRET`（Token 签名密钥，默认仅限本地开发）。

### 前端（reservation-web）

```powershell
cd "C:\Users\72797\Course\reservation-system\R1-delivery-base-foundation\code\reservation-web"
npm install   # 首次（已锁定 spec 版本，含 package-lock.json 可离线安装）
npm run dev   # 启动后访问 http://localhost:5173
```

## 四、验证方法

| 验收项 | 方法 | 期望 |
|--------|------|------|
| 接口文档 | 浏览器打开 `http://localhost:8080/doc.html` | Knife4j 页面，含登录/注册/当前用户 3 接口 |
| 后端启动 | 启动日志 | 无 ERROR，`Started ReservationApplication` |
| 前端页面 | `http://localhost:5173` → `/login`、`/register` | 登录页（角色单选）/ 注册页正常渲染 |
| 未登录拦截 | 直接访问 `http://localhost:5173/student/home` | 跳转 `/login?redirect=…` |
| 学生登录 | zhangsan / 123456（角色选学生） | 进入学生首页，显示张三信息 |
| 管理员登录 | admin / admin123（角色选管理员） | 进入管理端首页，显示系统管理员 |
| 角色越权 | 学生登录后手动访问 `http://localhost:5173/admin/home` | 被守卫跳回学生首页 |
| Token 过期 | 等待 24h 或重新部署后直接访问受保护接口 | 401 → 前端跳转登录页 |
| 密码加密 | `SELECT username,password FROM reservation.sys_user;` | 全部为 `$2a$/$2b$` 密文，无明文 |

> 接口级回归：执行 `docs/test-script.ps1`（需后端已启动）即可复跑 21 条用例。

## 五、当前运行状态

交付时后端（8080）与前端 dev（5173）已通过运行验证；当前目录重命名为英文后服务已停止，按上文第三节重新启动即可（启动成功后 `http://localhost:8080/doc.html` 与 `http://localhost:5173` 可访问）。
