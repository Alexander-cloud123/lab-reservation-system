# R2 启动说明 —— 管理端基础（用户管理 + 教室资源管理）

> 适用目录：`R2-delivery-admin-management`（在 R1 基线上增量开发，R1 目录保持不变）

---

## 一、环境要求

| 项 | 版本/要求 | 验证命令 |
|----|-----------|----------|
| JDK | 21 LTS（Temurin/OpenJDK） | `java -version` |
| Maven | IDEA 内置 3.9.x（命令行可用 IDEA 自带：`<IDEA安装目录>\plugins\maven\lib\maven3\bin\mvn.cmd`） | 见下方启动步骤 |
| Node.js | 22.x | `node -v` |
| npm | 10.x | `npm -v` |
| Docker | 任意版本（用于 MySQL 容器） | `docker ps` |
| MySQL | 本项目专用容器 **reservation-mysql**（mysql:8.0，映射 3306:3306，库 `reservation`，root/root，`--restart unless-stopped`） | `docker ps` 见容器 running |

> **容器注意事项**：若 `reservation-mysql` 停止，只需 `docker start reservation-mysql`，**禁止重建容器或重新导数据**（数据卷保留）；禁止启动旧项目容器（ssm-mysql / student-manage-mysql）。

## 二、启动步骤

### 1. 启动数据库容器（如未运行）

```bash
docker start reservation-mysql
# 验证：docker ps 中 reservation-mysql 状态为 Up
```

> 首次环境（新机器）才需要创建容器与导入数据库：
> ```bash
> docker run -d --name reservation-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e TZ=Asia/Shanghai -v reservation-mysql-data:/var/lib/mysql --restart unless-stopped mysql:8.0
> # 导入建库建表+测试数据（本目录 database/init_db.sql，可重复执行）
> Get-Content database/init_db.sql -Raw | docker exec -i reservation-mysql mysql -uroot -proot
> ```

### 2. 启动后端（reservation-server）

```powershell
cd R2-delivery-admin-management\code\reservation-server
# 方式一（命令行，使用 IDEA 内置 Maven）：
& "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.3\plugins\maven\lib\maven3\bin\mvn.cmd" spring-boot:run
# 方式二（IDEA）：Open 该目录 → Maven 面板 Reload → 运行 ReservationApplication
```

- 启动成功标志：控制台出现 `Started ReservationApplication`，端口 8080。
- 接口文档：浏览器访问 `http://localhost:8080/doc.html`（Knife4j，R2 新增 9 个接口已入文档）。

### 3. 启动前端（reservation-web）

```powershell
cd R2-delivery-admin-management\code\reservation-web
npm install        # 首次（package-lock 锁定版本，秒级完成；禁止自动升级大版本）
npm run dev        # 开发服务器，端口 5173
```

- 浏览器访问 `http://localhost:5173`，自动跳转登录页。

## 三、账号与数据基线

| 账号 | 密码 | 角色 |
|------|------|------|
| admin | admin123 | 管理员 |
| zhangsan / lisi / wangwu / zhaoliu | 123456 | 学生 |

数据库初始数据：用户 5、教室 12、预约 13（四状态齐全）、收藏 6、AI 配置 5。R2 测试脚本执行后会自动恢复该基线。

## 四、验证方法（负责人验收路径）

### 1. 快速接口验证（可复跑）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docs\test-script.ps1
# 期望输出：用例总数: 71  通过: 71  失败: 0
```

### 2. 浏览器手工走查（建议）

1. 打开 `http://localhost:5173` → 管理员登录（admin/admin123，角色选「管理员」）。
2. 登录后进入管理后台（顶栏 + 左侧菜单：首页 / 用户管理 / 教室资源管理）。
3. **用户管理** `/admin/users`：
   - 搜索：关键词（账号/姓名/学号）、角色、状态筛选 + 查询/重置；
   - 对 zhangsan 执行「禁用」（二次确认）→ 注销后用 zhangsan/123456 登录应提示账号已被禁用；再以管理员恢复「启用」；
   - 对 zhangsan 执行「重置密码」（二次确认）→ 新密码为 123456，旧密码失效；当前 admin 行的操作按钮应禁用（不可操作自己）。
4. **教室资源管理** `/admin/classrooms`：
   - 新增教室（弹窗表单校验：必填 + 容量 > 0）；
   - 编辑、删除（对有预约记录的教室删除应提示「该教室存在预约记录，禁止删除」）；
   - 状态开关（停用/启用，二次确认）；
   - 勾选多行 → 批量启用/停用（二次确认）；
   - 分页、多条件搜索（关键词/楼栋/类型/状态）。
5. **权限验证**：以学生账号登录（zhangsan/123456，角色选「学生」）→ 直接访问 `http://localhost:5173/admin/users` 应被路由守卫跳回学生首页；用学生 Token 调管理接口返回 403。

### 3. 关键验收点对照

- 所有列表/详情接口**不含 password 字段**（响应 JSON 核对）；
- 数据库密码全部 BCrypt：`docker exec reservation-mysql mysql -uroot -proot reservation -e 'SELECT COUNT(*) FROM sys_user WHERE password NOT LIKE "$2a%" AND password NOT LIKE "$2b%";'` 结果为 0；
- 关键操作均有二次确认弹窗（删除/禁用/重置密码/批量启用禁用）。

## 五、常见问题

| 现象 | 处理 |
|------|------|
| 后端 500 数据库连接失败 | 检查 `docker ps` 中 reservation-mysql 是否 Up；停止则 `docker start reservation-mysql`，稍后重启后端 |
| 前端登录后跳转异常/401 循环 | 清除浏览器 localStorage 或换无痕窗口重新登录 |
| npm install 报错 | 确认 npm 镜像为 https://registry.npmmirror.com（`npm config get registry`），删除 node_modules 后重装 |
| 端口被占用（8080/5173） | 找到占用进程释放端口，或确认无重复启动的旧实例 |
