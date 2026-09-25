/**
 * Playwright E2E 配置（浏览器级回归）
 *
 * 运行前置（脚本不自动启动后端，需先手动起，原因见下）：
 *   1. MySQL / Redis 容器在线
 *   2. 后端已启动（默认 8080）。本机 8080 被其他项目占用时：
 *        $env:E2E_API_PORT=8081  # 且后端需以 --server.port=8081 启动
 *   3. 前端 dev server 由本配置的 webServer 自动拉起（含 VITE_API_TARGET 注入），无需手动开
 *
 * 常用环境变量：
 *   E2E_API_PORT    后端端口，默认 8080（同时作为 webServer 的代理目标）
 *   E2E_API_BASE    后端基址，默认 http://127.0.0.1:${E2E_API_PORT}（globalSetup 探活用）
 *   E2E_WEB_PORT    前端端口，默认 5173
 *
 * 串行执行（workers=1 + fullyParallel=false）：所有用例共享同一个 MySQL 库，
 * 并发会互相污染预约时段与计数断言，故强制串行。
 */
import { defineConfig, devices } from '@playwright/test'

const WEB_PORT = Number(process.env.E2E_WEB_PORT || 5173)
const FRONTEND = `http://127.0.0.1:${WEB_PORT}`
const API_PORT = process.env.E2E_API_PORT || '8080'
const API_TARGET = process.env.E2E_API_TARGET || `http://localhost:${API_PORT}`

export default defineConfig({
  testDir: './e2e/specs',
  globalSetup: './e2e/global-setup.js',

  // 串行：共享库，禁并发
  fullyParallel: false,
  workers: 1,

  // 不自动重试：库里是真数据，重试会放大写入副作用；失败即失败，便于定位
  retries: 0,

  timeout: 60_000,
  expect: { timeout: 10_000 },

  reporter: [
    ['list'],
    ['json', { outputFile: 'e2e-report/results.json' }],
    ['html', { outputFolder: 'e2e-report/html', open: 'never' }]
  ],

  use: {
    baseURL: FRONTEND,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off'
  },

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } }
    }
  ],

  webServer: {
    command: `npm run dev -- --port ${WEB_PORT}`,
    url: FRONTEND,
    reuseExistingServer: true,
    timeout: 120_000,
    env: { VITE_API_TARGET: API_TARGET }
  }
})