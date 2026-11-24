#!/usr/bin/env node
// =====================================================================
// 页面加载时间基准（Playwright Chromium，直击 nginx 托管的构建产物）
//
// 用法：
//   node web/scripts/bench-page.mjs
//   node web/scripts/bench-page.mjs --base=http://127.0.0.1:8088 --samples=10 --out=.devtmp/bench/pages.md
//
// 口径：
//   - 页面来自 web 容器（8088，nginx 托管 dist，gzip 已开），不是 Vite dev server；
//   - 登录走真实界面（学生 / 管理员各一次），之后同一 context 复用会话；
//   - 指标 = 从 goto 到「首屏关键元素可见」的墙钟时间（含网络、JS 执行与组件渲染）；
//   - 对应《需求设计文档》§1.5「普通页面加载 ≤2s」；首次访问（冷缓存）单独标注。
// =====================================================================
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const opts = {
  base: 'http://127.0.0.1:8088',
  samples: 10,
  out: '',
};
for (const arg of process.argv.slice(2)) {
  if (!arg.startsWith('--')) continue;
  const [key, value = 'true'] = arg.slice(2).split('=');
  if (key === 'base') opts.base = value.replace(/\/$/, '');
  if (key === 'samples') opts.samples = Math.max(1, Number(value));
  if (key === 'out') opts.out = value;
}

const VIEWPORT = { width: 1440, height: 900 };
const ACCOUNTS = {
  student: { user: 'zhangsan', password: '123456', role: '学生', done: /\/student\// },
  admin: { user: 'admin', password: 'admin123', role: '管理员', done: /\/admin\// },
};

const pages = [
  { label: '登录页 `/login`', path: '/login' },
  { label: '学生首页 `/student/home`', path: '/student/home', as: 'student', ready: '.room-card' },
  { label: '教室详情 `/student/classrooms/3`', path: '/student/classrooms/3', as: 'student', ready: 'button:has-text("预约申请")' },
  { label: '我的预约 `/student/my-reservations`', path: '/student/my-reservations', as: 'student', ready: '.el-table' },
  { label: '日历总览 `/student/calendar`', path: '/student/calendar', as: 'student', ready: '.fc-daygrid-day' },
  { label: '管理首页 `/admin/home`', path: '/admin/home', as: 'admin', ready: 'text=欢迎使用' },
  { label: '数据看板 `/admin/dashboard`', path: '/admin/dashboard', as: 'admin', ready: '.chart-box' },
];

function fail(msg) { console.error(`  FAIL  ${msg}`); process.exit(1); }

function percentile(values, p) {
  const sorted = [...values].sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

async function loginContext(browser, roleKey) {
  const account = ACCOUNTS[roleKey];
  const context = await browser.newContext({ viewport: VIEWPORT });
  const page = await context.newPage();
  await page.goto(`${opts.base}/login`, { waitUntil: 'load', timeout: 30000 });
  await page.getByPlaceholder('请输入登录账号').fill(account.user);
  await page.getByPlaceholder('请输入密码').fill(account.password);
  await page.locator('.role-group .el-radio-button').filter({ hasText: account.role }).click();
  await page.locator('.login-btn').click();
  await page.waitForURL(account.done, { timeout: 30000 });
  return context;
}

async function main() {
  console.log(`页面基准：base=${opts.base}  samples=${opts.samples}`);
  const browser = await chromium.launch();
  const contexts = {};
  const rows = [];
  try {
    for (const roleKey of ['student', 'admin']) {
      contexts[roleKey] = await loginContext(browser, roleKey);
      console.log(`  已登录：${ACCOUNTS[roleKey].role}`);
    }

    for (const target of pages) {
      const context = target.as ? contexts[target.as] : await browser.newContext({ viewport: VIEWPORT });
      const page = await context.newPage();
      const measureReady = async () => {
        const t0 = performance.now();
        await page.goto(`${opts.base}${target.path}`, { waitUntil: 'load', timeout: 30000 });
        if (target.ready) {
          try {
            await page.locator(target.ready).first().waitFor({ state: 'visible', timeout: 20000 });
          } catch (err) {
            console.error(`  DEBUG ${target.label}: url=${page.url()}`);
            throw err;
          }
        }
        return performance.now() - t0;
      };
      const first = await measureReady();
      const samples = [];
      for (let i = 0; i < opts.samples; i++) samples.push(await measureReady());
      rows.push({
        label: target.label,
        samples: samples.length,
        first,
        p50: percentile(samples, 50),
        p95: percentile(samples, 95),
        max: Math.max(...samples),
      });
      await page.close();
      if (!target.as) await context.close();
      console.log(`  ${target.label} 完成`);
    }
  } finally {
    await browser.close();
  }

  const lines = [];
  lines.push('| 页面 | 样本 | 首次 (ms) | p50 (ms) | p95 (ms) | max (ms) | 判定 |');
  lines.push('|---|---:|---:|---:|---:|---:|---|');
  for (const row of rows) {
    const verdict = row.p95 <= 2000 ? 'PASS（≤2000ms）' : 'FAIL（>2000ms）';
    lines.push(`| ${row.label} | ${row.samples} | ${row.first.toFixed(0)} | ${row.p50.toFixed(0)} | ${row.p95.toFixed(0)} | ${row.max.toFixed(0)} | ${verdict} |`);
  }
  lines.push('');
  lines.push(`> 环境：Playwright Chromium / ${opts.base}（nginx 托管构建产物）/ 每页 ${opts.samples} 次 / ${new Date().toISOString()}`);
  lines.push('> 口径：`goto` 到首屏关键元素可见的总时间（含网络、JS 执行与组件渲染）；`首次` 为新建 context 的第一次访问，其余为同页面连续访问（静态资源已缓存）。');

  const table = lines.join('\n');
  console.log('\n' + table + '\n');
  if (opts.out) {
    mkdirSync(dirname(opts.out), { recursive: true });
    writeFileSync(opts.out, table + '\n', 'utf8');
    console.log(`  报告已写入 ${opts.out}`);
  }
  const exceeded = rows.filter((r) => r.p95 > 2000);
  if (exceeded.length > 0) fail(`页面 p95 超过 2000ms：${exceeded.map((r) => r.label).join('、')}`);
  process.exit(0);
}

main().catch((err) => fail(err && err.message ? err.message : String(err)));
