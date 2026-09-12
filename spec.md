# spec.md — 项目技术开发规格书（面向全部开发 Agent）

> **使用方式**：本规格书是 Agents 开发执行时的直接技术依据，与《需求设计文档.md》（业务依据）、《AGENTS.md》（协作章程）配套使用。开发前先读本文件，按规格执行；规格与需求文档冲突时，以需求文档为准并向架构师上报。
>
> **项目基线**：JDK 21 LTS · Spring Boot 3.2.10 · MyBatis-Plus 3.5.7 · MySQL 8.0 · Node 22 · Vue 3.4 + Vite 5 · Element Plus 2.7 · Agnes AI（agnes-2.0-flash）

---

## 1. 环境基线

| 项 | 版本/要求 | 验证命令 |
|----|-----------|----------|
| JDK | 21 LTS（本地现有，禁止要求降级安装） | `java -version` |
| Maven | IDEA 内置 3.9.x（Runner JRE 选 Project SDK） | IDEA Maven 面板 Reload 无红 |
| Node.js | 22.x（本地现有） | `node -v` / `npm -v` |
| npm 镜像 | https://registry.npmmirror.com | `npm config get registry` |
| MySQL | **Docker 容器方式运行**（reservation-mysql，mysql:8.0，映射 3306:3306，库名 `reservation`，utf8mb4） | `docker ps` 见容器 running；`mysql -h127.0.0.1 -P3306 -uroot -p` 可登录 |
| Redis（可选加分） | **Docker 容器方式运行**（reservation-redis，redis:7-alpine，映射 6379:6379） | `docker ps` 见容器 running |
| 数据库连接 | 后端 `application.yml` 配置数据源指向 `localhost:3306`，连接无报错 | 启动日志无报错 |

> 1. 前端依赖注意：Node 22 下禁止使用 `node-sass`（原生编译易失败），统一使用 `sass`（纯 JS 实现）。
> 2. 数据库与 Redis 统一使用 Docker 运行，禁止要求本机安装 MySQL/Redis 服务。
> 3. 本项目使用**独立专用容器**，严禁复用或启动旧项目容器（ssm-mysql 映射 3307、student-manage-mysql 映射 3306 等均为旧项目数据，避免数据污染）。
> 4. 旧容器 `student-manage-mysql` 曾占用宿主 3306 端口（已停止，端口已释放），本项目容器映射 3306 前无需删除旧容器，仅需保持其停止状态。

---

## 2. 后端工程规格（reservation-server）

### 2.1 工程骨架
- 创建方式：IDEA → New Project → Spring Initializr
- 坐标：groupId `com.example`，artifactId `reservation-server`，Java 21，Jar 打包
- 初始依赖：Spring Web、MySQL Driver、Lombok
- 主类：`com.example.reservation.ReservationApplication`

### 2.2 核心依赖（pom.xml 必须包含）
```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.7</version>
</dependency>
<dependency>
    <groupId>com.github.xiaoymin</groupId>
    <artifactId>knife4j-openapi3-jakarta-spring-boot-starter</artifactId>
    <version>4.4.0</version>
</dependency>
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>easyexcel</artifactId>
    <version>3.4.0</version>
</dependency>
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
    <version>5.8.32</version>
</dependency>
```

### 2.3 application.yml 基础模板
```yaml
server:
  port: 8080

spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/reservation?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: ${MYSQL_PASSWORD:root}

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto

knife4j:
  enable: true
  openapi:
    title: 教室预约系统接口文档
    version: 3.1
    group:
      default:
        group-name: 默认分组
        api-rule: package
        api-rule-resources: com.example.reservation.controller

# ===== AI 模块配置（第 7 轮启用）=====
ai:
  enable: false              # 总开关，默认关闭，不影响核心系统
  base-url: https://api.agnes-ai.cn/v1
  api-key: ${AGNES_API_KEY:}  # 密钥从环境变量读取，禁止硬编码
  model: agnes-2.0-flash
  timeout-seconds: 60          # 读取超时（实测模型响应可达数十秒，3s 过短会频繁降级）
  max-tokens: 1024             # 单次输出上限（防止长输出超时/乱码）
  rpm-limit: 20
```

### 2.4 后端分层与包结构
```
com.example.reservation
├── common/          # Result、GlobalExceptionHandler、BusinessException、常量类、MybatisPlusConfig、Knife4jConfig
├── entity/          # SysUser、Classroom、Reservation、UserFavorite、AiConfig
├── mapper/          # 对应 Mapper 接口
├── service/         # 业务接口
│   └── impl/        # 业务实现
├── controller/      # 控制器（只做参数接收与结果返回）
├── ai/              # AI 独立包（第7轮）
│   ├── config/      # AiProperties、AgnesClient 封装
│   ├── dto/         # AiRecommendDTO、AiParseDTO、AiChatDTO、AiComplianceDTO
│   └── service/     # AiRecommendService、AiParseService、AiChatService、AiComplianceService
└── ReservationApplication.java
```

### 2.5 统一返回 Result 约定
```java
{ "code": 200, "message": "操作成功", "data": {} }
```
- code=200 成功；业务错误用 4xx/5xx 语义化错误码（如 400 参数错误、401 未登录、403 无权限、500 系统异常）
- 分页数据统一结构：`{ "total": N, "records": [...] }`
- 鉴权：登录后签发 Token，前端请求头携带 `Authorization: Bearer {token}`；后端拦截器校验（管理员接口校验角色）

---

## 3. 前端工程规格（reservation-web）

### 3.1 工程骨架
```bash
npm create vite@latest reservation-web -- --template vue
cd reservation-web
npm install element-plus vue-router pinia axios echarts fullcalendar dayjs sass
```

### 3.2 目录结构
```
src
├── api/             # 按模块拆分的接口封装（user.js / classroom.js / reservation.js / stats.js / ai.js）
├── components/
│   ├── common/      # 空状态组件、状态标签组件、分页等
│   └── ai/          # AiAssistant.vue（悬浮助手）、AiRecommendCard.vue、AiQuickReserve.vue
├── router/index.js  # 路由表 + 全局前置守卫（未登录跳登录页、管理员路由校验角色）
├── stores/          # user.js（用户信息+Token）、app.js
├── utils/           # request.js（Axios 封装：Token 注入、401 跳转、统一错误提示）、time.js
├── views/
│   ├── common/Login.vue、common/Register.vue
│   ├── student/ClassroomList.vue、ClassroomDetail.vue、MyReservation.vue、Profile.vue、CalendarOverview.vue
│   └── admin/AdminHome.vue、ClassroomManage.vue、AuditManage.vue、UserManage.vue、ReservationRecord.vue、Dashboard.vue
├── App.vue
└── main.js          # 注册 Element Plus、Pinia、Router
```

### 3.3 关键交互规格
- 弹窗/抽屉统一用于新增、编辑、提交、审核驳回；不新增独立页面
- 所有删除、取消、审核、重置密码等关键操作必须二次确认（ElMessageBox.confirm）
- 冲突检测前端校验：选择日期时段后实时查询该教室该日已通过时段，重叠即禁用提交并提示
- 空状态统一使用友好组件（插画 + 引导文案 + 操作按钮）
- 筛选条件记忆、预约草稿：localStorage 存取（key 带用户维度）

---

## 4. 数据库 SQL 规格

### 4.0 Docker 容器启动（环境准备）
```bash
# MySQL 8.0（本项目专用容器，root 密码与容器名以负责人确认为准）
docker run -d --name reservation-mysql \
  -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root \
  -e TZ=Asia/Shanghai \
  -v reservation-mysql-data:/var/lib/mysql \
  mysql:8.0

# Redis 7（可选加分项，仅当负责人确认接入时启用）
docker run -d --name reservation-redis \
  -p 6379:6379 \
  redis:7-alpine
```
> 容器创建后执行下方建库建表；连接串统一为 `jdbc:mysql://localhost:3306/reservation`。

### 4.1 建库
```sql
CREATE DATABASE IF NOT EXISTS reservation DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE reservation;
```

### 4.2 建表（严格按需求文档 2.3 节字段定义）
- `sys_user`、`classroom`、`reservation`、`user_favorite`、`ai_config` 五张表
- 字段类型、约束、默认值必须与需求文档表结构一致
- 索引：
  - `reservation`：`idx_class_date(classroom_id, reserve_date)`
  - `user_favorite`：唯一索引 `idx_user_class(user_id, classroom_id)`
- 时间字段：`create_time`/`update_time` 使用 `datetime`，后端统一填充（MyBatis-Plus 自动填充）

### 4.3 初始测试数据要求
- 管理员账号 1 个（预置，username：admin，BCrypt 加密密码）
- 学生账号 3-5 个（含不同预约历史，便于 AI 推荐演示）
- 教室 10-15 间（覆盖普通教室/实验室/机房 3 类、多个楼栋、不同容量与设备）
- 预约数据：覆盖待审核/已通过/已驳回/已取消 4 种状态；含当日、本周、本月数据（支撑日历、看板、推荐演示）；含一条「已通过但即将开始」数据（支撑登录提醒演示）

---

## 5. 接口设计规范

### 5.1 通用规范
- 全部 RESTful 风格，路径 `/api/模块/资源`
- 全部返回统一 `Result`
- 除登录/注册外，均需鉴权（Token）；管理员接口额外校验角色
- 分页参数：`page`、`size`；排序统一按 `create_time DESC`

### 5.2 核心接口清单（开发蓝图）
| 模块 | 接口 | 说明 |
|------|------|------|
| 用户 | POST /api/user/login | 登录，返回 Token + 用户信息 |
| 用户 | POST /api/user/register | 学生注册（校验账号唯一、两次密码一致） |
| 用户 | GET /api/user/info | 当前用户信息 |
| 用户 | PUT /api/user/info | 修改个人信息 |
| 用户 | PUT /api/user/password | 修改密码（验证原密码） |
| 用户 | GET/POST/PUT/DELETE /api/user/manage | 管理员用户管理（列表分页、禁用/启用、重置密码） |
| 用户 | GET /api/user/stats | 个人预约数据统计（累计/本月/通过率/最近一次） |
| 教室 | GET /api/classroom/list | 列表（关键词、楼栋、类型、日期、状态筛选 + 实时状态标签） |
| 教室 | GET /api/classroom/{id} | 详情 + 指定日期时段占用 |
| 教室 | POST/PUT/DELETE /api/classroom/manage | 管理员增删改查 |
| 教室 | POST /api/favorite/{classroomId} | 收藏/取消收藏 |
| 教室 | GET /api/favorite/list | 我的收藏（上限 10） |
| 预约 | POST /api/reservation | 提交预约（前端已校验，后端二次冲突检测） |
| 预约 | PUT /api/reservation/{id}/cancel | 取消（开始前 1 小时内禁止） |
| 预约 | GET /api/reservation/mine | 我的预约（状态筛选、今日置顶） |
| 预约 | GET /api/reservation/manage | 管理员全量查询（多条件） |
| 预约 | PUT /api/reservation/{id}/audit | 审核通过/驳回（驳回带备注） |
| 预约 | POST /api/reservation/batch-audit | 批量审核（仅待审核） |
| 预约 | GET /api/reservation/conflict | 冲突检测（教室+日期+时段 → 是否冲突） |
| 预约 | GET /api/reservation/export | Excel 导出（多条件过滤） |
| 统计 | GET /api/stats/overview | 首页概览（今日预约、待审核、教室数、用户数） |
| 统计 | GET /api/stats/usage-rate | 教室使用率排行 |
| 统计 | GET /api/stats/trend | 月度预约趋势 |
| 统计 | GET /api/stats/time-distribution | 热门时段分布 |
| AI | POST /api/ai/recommend | 智能推荐 Top3 |
| AI | POST /api/ai/parse-reservation | 自然语言解析预约参数 |
| AI | POST /api/ai/chat | 场景限定问答 |
| AI | POST /api/ai/compliance-check | 用途合规校验 |

---

## 6. AI 模块接入规格（Agnes AI）

### 6.1 接入要点
- 接口兼容 OpenAI v1：`POST {base-url}/chat/completions`
- 请求头：`Authorization: Bearer {API_KEY}`，`Content-Type: application/json`
- 请求体标准字段：`model`、`messages`、`temperature`、`response_format`（结构化输出用 `{"type":"json_object"}`）、`max_tokens`（默认 1024）
- 国内节点：`https://api.agnes-ai.cn/v1`；模型：`agnes-2.0-flash`（默认）、`agnes-2.5-pro-beta`（复杂语义可选）
- 限流：RPM≈20 次/分钟，必须实现调用计数与限流保护，触发时返回友好提示并降级

### 6.2 四个 AI 能力的 Prompt 规格
1. **自然语言解析**：System 限定「你是教室预约解析器，只输出 JSON」；输出结构：`{"date":"YYYY-MM-DD","startTime":"HH:mm","endTime":"HH:mm","capacity":int,"roomType":"普通教室|实验室|机房|null","purpose":"string"}`；识别失败返回 `{"error":"无法解析"}`。
2. **智能推荐**：后端先按规则预筛（用户历史偏好 + 实时空闲），大模型对候选教室按匹配度排序，返回 Top3 及理由（一句话）。
3. **场景问答**：System 限定「只回答高校教室预约系统相关问题：如何预约/取消、教室信息、个人预约记录、审核状态；无关问题统一回复：抱歉，我只能解答预约相关问题」。个人数据通过检索增强注入上下文。
4. **合规校验**：System 限定「判断预约用途是否合规（是否与教学/实验/自习/竞赛等正当用途相关）」，输出 `{"compliant":true|false,"reason":"string"}`。

### 6.3 降级兜底（必须实现）
- API 超时(60s)/报错/限流/密钥缺失 → 自动切换本地规则模拟版：
  - 解析：正则+关键词模板（覆盖「明天下午2点 40人 机房」等常用句式）
  - 推荐：按用户历史偏好 + 空闲状态规则排序
  - 问答：关键词匹配 FAQ 库
  - 合规：本地违规关键词库匹配
- `ai.enable=false` 时，前端隐藏 AI 入口，系统完全退化为纯预约管理系统
- 所有 AI 输出前端标注「AI 生成，仅供参考」，并提供手动修改入口

---

## 7. 迭代任务拆解（Agents 按轮执行）

| 轮次 | 任务单元 | 完成标准（验收清单） |
|------|----------|----------------------|
| **R1 基础底座** | ①建库建表+测试数据 ②后端骨架（依赖/配置/Result/全局异常/Knife4j）③前端骨架（依赖/目录/request封装/路由守卫）④登录注册+权限拦截 | 五表+测试数据导入成功；后端启动无错、doc.html 可访问；前端 dev 可运行；登录/注册/Token 拦截全通 |
| **R2 管理端基础** | ①用户管理（CRUD+禁用+重置密码）②教室管理（CRUD+批量+状态开关） | 两模块后端接口+前端页面完整；管理员权限校验生效；规范符合 AGENTS 4.3 |
| **R3 预约核心** | ①教室列表/详情页 ②预约提交+冲突检测（前后端双重）③我的预约+取消 ④审核流程+批量审核+快捷驳回 | 冲突检测边界用例（首尾相接/完全包含/部分重叠/无重叠）全部正确；状态流转正确；批量审核仅限待审核 |
| **R4 体验升级** | ①个人中心（数据概览+常用教室+消息通知）②收藏全链路 ③体验细节（草稿/筛选记忆/状态标签/空状态/今日置顶/登录提醒） | 个人中心三升级点全实现；细节全部生效；学生端 5 页闭环 |
| **R5 亮点功能** | ①日历总览页（FullCalendar 月/周视图、点击快速预约）②数据看板（ECharts 三图+时间筛选） | 日历数据准确、色块正确；看板图表数据与库中一致；13 页全部完成 |
| **R6 联调优化** | ①全流程联调 ②异常/边界补全 ③注释完善 ④全量回归测试 | 学生+管理员全流程无断点；边界操作不报错；代码注释齐全；测试报告完整 |
| **R7 AI 模块** | ①Agnes 客户端封装+配置 ②4 个 AI 接口+降级 ③前端 AI 交互（助手/推荐/快速预约/合规标签）④联调+测试 | 标准演示场景流畅；降级机制生效；业务边界合规（只读不写）；`ai.enable=false` 不影响核心 |
| **R8 答辩准备** | ①实训报告 ②答辩 PPT ③演示脚本+测试数据 ④AI 演示脚本+兜底 | 交付材料齐全；演示全流程顺畅 |

---

## 8. 交付与验收 Checklist

每轮交付前，由测试 Agent 逐项自检，负责人按此验收：

- [ ] 代码可运行：后端 `mvn spring-boot:run` 启动无错；前端 `npm run dev` 正常
- [ ] 表结构、字段、索引与需求文档 2.3 完全一致
- [ ] 核心规则 100% 实现：冲突检测公式、状态流转、双重校验
- [ ] 统一返回 Result、全局异常、`jakarta.*` 包名
- [ ] 关键操作有二次确认；空状态友好；无硬编码魔法值
- [ ] 密钥不硬编码（环境变量/配置）；前端不接触 AI 密钥
- [ ] 测试报告覆盖：正常流程、边界场景、异常操作、权限校验
- [ ] 本轮任务说明书/接口清单/测试报告/启动说明齐全
