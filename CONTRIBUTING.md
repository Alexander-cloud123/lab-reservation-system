# 贡献指南（CONTRIBUTING）

本仓库处于课程交付/答辩冻结期。核心业务逻辑（预约冲突检测、状态流转、权限控制、并发锁）已冻结，
任何改动申请必须在不触及上述逻辑的前提下提出。

## 环境基线

| 组件 | 版本 |
|------|------|
| JDK | 21 LTS |
| 构建工具 | Maven 3.9.x（本仓库未提供 Maven Wrapper） |
| Node.js | 22.x |
| 数据库 / 缓存 | Docker：MySQL 8.0（宿主端口 3307）、Redis 7.0（宿主端口 6380） |

## 提交信息规范

采用 Conventional Commits，类型与 scope 参照仓库既有风格（提交说明用中文，scope 用英文）：

```
<type>(<scope>): <中文摘要>

示例：
feat(ai): 智能问答体验增强（R13）——停止生成/Markdown/多轮上下文/WebClient/结构化流式
fix(web): 修复日历总览页事件不渲染
docs(R11): 补 AI 观测与缓存优化的交付文档四件套
chore(.gitignore): 忽略 Agent 本地目录与数据库设计图
```

常用 type：`feat` / `fix` / `docs` / `test` / `refactor` / `chore` / `ci`。

## 入库纪律

- 日志、构建产物（`target/`、`dist/`）、依赖目录（`node_modules/`）、本地备份、环境变量文件一律不入库。
- 密钥只从环境变量读取，禁止写入仓库内任何文件（含配置文件默认值以外的明文密钥）。
- 一次性调试脚本、原始跑批输出不入库；结论性报告（测试报告、审查报告、QA 收口报告）入库。

## 代码规范

- 后端：`controller → service → mapper → entity` 四层结构，禁止跨层调用；所有接口返回统一 `Result`；
  包名统一 `jakarta.*`，禁止 `javax.*`；类、接口、核心方法必须有功能注释。
- 前端：页面、组件、路由、接口封装目录分离；Axios 统一封装。
- 前端新增/修改页面前，必须先阅读 `.agents/skills/frontend-design/SKILL.md`；交付前用
  `.agents/skills/avoid-ai-design/SKILL.md` 做 detect 自检，P0/P1 级 AI 痕迹必须清零。

## 提交前自检

```powershell
# 后端
mvn -f server/pom.xml test          # 需 MySQL/Redis 容器在线
pwsh server/scripts/smoke.ps1       # 36 项接口冒烟

# 前端
cd web
npm run lint
npm run build
npm run test:e2e                    # 需后端在线，118 个用例
```

以上全部通过，且未触及冻结逻辑，方可提交 PR。
