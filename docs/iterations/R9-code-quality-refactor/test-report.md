# 代码质量重构轮 · 回归结果

- 被测版本：`c7fddf8`（工作区 clean，无 `+dirty`）
- 本轮基线：`890f729`（重构前 HEAD）
- 执行时间：2026-09-18
- 环境：JDK 21 · Maven 3.9.x（IDEA 内置）· Node 22 · Docker（reservation-mysql / reservation-redis 在线）

## 一、提交明细（6 笔独立 commit）

| # | Commit | 内容 | 变更统计 |
|---|--------|------|----------|
| 1 | `a65c3f2` | 前端 lint 工具链（ESLint 9 flat config + Prettier + EditorConfig + npm scripts） | web/eslint.config.js、web/.prettierrc.json、根 .editorconfig、web/package.json、package-lock.json |
| 2 | `6ebd40a` | 前端教室类型/预约状态字典收敛为 `utils/dict.js`（10 个 .vue 移除本地函数） | 新增 web/src/utils/dict.js；改 10 个 .vue |
| 3 | `9f4b07d` | 后端分页/教室存在性校验收敛（PageValidator/ClassroomValidator）+ 删死代码 JwtUtil.parseToken + 修 2 处失实注释 | 新增 common/PageValidator.java、ClassroomValidator.java；改 4 个 ServiceImpl + JwtUtil/AuthInterceptor/AgnesClient |
| 4 | `7edef32` | 6 处裸 `static ObjectMapper` 统一为 Spring `@Resource` 注入；AI 类型文案收敛为 AiConstants | 改 AuthInterceptor/AgnesClient + 4 个 AI ServiceImpl |
| 5 | `7d0fecf` | ReservationConverter 外移（转换逻辑从 ServiceImpl 迁出）+ EasyExcel 版本号收敛 `${easyexcel.version}` | 新增 service/converter/ReservationConverter.java；改 ReservationServiceImpl、ReservationServiceImplTest、pom.xml |
| 6 | `c7fddf8` | 鉴权与参数校验 MockMvc 冒烟测试（401/403/分页 400） | 新增 controller/AuthAndValidationSmokeTest.java |

## 二、验证结果

### 1. 后端编译
- `mvn compile`：通过（6 笔期间多次编译全绿）

### 2. 后端单元/冒烟测试（`mvn test`，全量 29 条）
| 测试类 | 结果 |
|--------|------|
| RateLimiterTest | 4/4 通过 |
| ConstantsWindowConsistencyTest | 3/3 通过 |
| JacksonDateSerializationTest | 3/3 通过 |
| **AuthAndValidationSmokeTest（本轮新增）** | **4/4 通过** |
| ClassroomServiceImplTest | 2/2 通过 |
| FavoriteServiceImplTest | 2/2 通过 |
| ReservationServiceImplTest | 6/6 通过 |
| UserServiceImplLoginLockTest | 5/5 通过 |
| **合计** | **29/29，Failures 0，Errors 0，BUILD SUCCESS** |

### 3. 前端构建与 Lint
- `npm run build`：通过（vite build，6.70s）
- `npm run lint`：68 errors / 0 warnings —— 与第 1 笔建立基线一致（本轮仅记录基线，不 auto-fix、不加 --max-warnings，符合方案口径）

### 4. 端到端回归（真实启动后端 :8080 + Docker MySQL/Redis，HTTP 断言）
| # | 场景 | 期望 | 实际 | 结果 |
|---|------|------|------|------|
| T1 | 无 token 访问 /api/user/info | 401 + code=401 | 401 + code=401 "未登录或登录已过期" | ✅ |
| T2 | admin/admin123 登录 | 200 + token | 200 + token（163 字符） | ✅ |
| T3 | admin GET /api/classroom/manage?page=0 | 400 "页码必须大于等于 1" | 200 + code=400 + 文案逐字匹配 | ✅ |
| T4 | admin page=1&size=501 | 400 "每页条数必须在 1-500 之间" | 200 + code=400 + 文案逐字匹配 | ✅ |
| T5 | admin 正常分页 | 200 + records | 200 + records=10 | ✅ |
| T6 | 学生 token 访问管理接口 | 403 + code=403 | 403 + code=403 "无权限访问" | ✅ |
| T7 | 学生 GET /api/reservation/mine | 200 + records | 200 + records（ReservationConverter 链路） | ✅ |

## 三、偏差与说明

1. **无偏差项**：6 笔全部按方案落地，无"不适合抽所以没抽"的区块。
2. 首轮 lint 基线 68 errors 未清零属方案既定口径（工具链引入轮只建基线），后续轮收敛建议见回报第 5 段。
3. 分页/教室校验消重未改变任何用户可见文案（4 处分页、9 处"教室不存在"原文案逐字保留）。
4. 第 6 笔冒烟测试账号：种子学生为 zhangsan/lisi/wangwu/zhaoliu（无 student01），已按 init_db.sql 实况取 zhangsan。
