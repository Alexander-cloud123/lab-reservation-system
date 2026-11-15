#!/usr/bin/env node
// =====================================================================
// 关键接口性能基准（Node 22 内置 fetch，无第三方依赖）
//
// 用法：
//   node web/scripts/bench-api.mjs
//   node web/scripts/bench-api.mjs --samples=50 --rounds=3 --flush-stats --out=.devtmp/bench/report.md
//
// 口径：
//   - 直连 api 容器（默认 8080），不经 nginx，排除网关变量；
//   - 每个接口先 warmup 次预热，再采样 rounds × samples 次，取各百分位的中位数；
//   - 冲突检测按《需求设计文档》§1.5「冲突检测查询 ≤500ms」判定，p95 超阈退出码 1；
//   - --flush-stats 时先删 `cache:stats:*` 再测统计接口，得到冷/热两组数据。
// =====================================================================
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

const DEFAULTS = {
  base: 'http://127.0.0.1:8080',
  user: 'zhangsan',
  password: '123456',
  admin: 'admin',
  adminPassword: 'admin123',
  samples: 50,
  rounds: 3,
  warmup: 5,
  timeout: 8000,
  out: '',
  flushStats: false,
};

function parseArgs(argv) {
  const opts = { ...DEFAULTS };
  for (const arg of argv) {
    if (!arg.startsWith('--')) continue;
    const body = arg.slice(2);
    const eq = body.indexOf('=');
    const key = eq >= 0 ? body.slice(0, eq) : body;
    const value = eq >= 0 ? body.slice(eq + 1) : 'true';
    switch (key) {
      case 'base': opts.base = value.replace(/\/$/, ''); break;
      case 'user': opts.user = value; break;
      case 'password': opts.password = value; break;
      case 'admin': opts.admin = value; break;
      case 'admin-password': opts.adminPassword = value; break;
      case 'samples': opts.samples = Math.max(1, Number(value)); break;
      case 'rounds': opts.rounds = Math.max(1, Number(value)); break;
      case 'warmup': opts.warmup = Math.max(0, Number(value)); break;
      case 'timeout': opts.timeout = Math.max(1000, Number(value)); break;
      case 'out': opts.out = value; break;
      case 'flush-stats': opts.flushStats = value !== 'false'; break;
      default: break;
    }
  }
  return opts;
}

function pad(n) { return String(n).padStart(2, '0'); }
function formatDate(d) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; }

const opts = parseArgs(process.argv.slice(2));
const today = new Date();
const tomorrow = new Date(today.getTime() + 24 * 3600 * 1000);
const monthStart = new Date(today.getFullYear(), today.getMonth(), 1);
const monthEnd = new Date(today.getFullYear(), today.getMonth() + 1, 0);



function fail(message) {
  console.error(`  FAIL  ${message}`);
  process.exit(1);
}

async function request(url, token, timeoutMs = opts.timeout) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(url, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal: controller.signal,
    });
    const text = await resp.text();
    let json = null;
    try { json = JSON.parse(text); } catch { /* 非 JSON 响应按错误处理 */ }
    return { status: resp.status, json, text };
  } finally {
    clearTimeout(timer);
  }
}

async function login(username, password, role) {
  const payload = JSON.stringify({ username, password, role });
  const r = await fetch(`${opts.base}/api/user/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload,
    signal: AbortSignal.timeout(opts.timeout),
  });
  const json = await r.json().catch(() => null);
  if (r.status !== 200 || !json || json.code !== 200 || !json.data || !json.data.token) {
    fail(`登录失败：${username}（HTTP ${r.status}，${json && json.message ? json.message : '无消息'}）`);
  }
  return json.data.token;
}

async function measureOnce(url, token) {
  const started = performance.now();
  const { status, json } = await request(url, token);
  const elapsed = performance.now() - started;
  if (status !== 200) throw new Error(`HTTP ${status}：${url}`);
  if (!json || json.code !== 200) throw new Error(`业务码 ${json && json.code}：${url}`);
  return elapsed;
}

function percentile(sorted, p) {
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1));
  return sorted[idx];
}

async function measureEndpoint(label, url, token) {
  for (let i = 0; i < opts.warmup; i++) {
    try { await measureOnce(url, token); } catch (err) { fail(`${label} 预热失败：${err.message}`); }
  }
  const perRound = [];
  for (let round = 0; round < opts.rounds; round++) {
    const samples = [];
    for (let i = 0; i < opts.samples; i++) {
      try { samples.push(await measureOnce(url, token)); }
      catch (err) { fail(`${label} 采样失败：${err.message}`); }
    }
    samples.sort((a, b) => a - b);
    perRound.push({
      p50: percentile(samples, 50),
      p95: percentile(samples, 95),
      p99: percentile(samples, 99),
      max: samples[samples.length - 1],
    });
  }
  const medianOf = (key) => {
    const values = perRound.map((r) => r[key]).sort((a, b) => a - b);
    return values[Math.floor(values.length / 2)];
  };
  return {
    label,
    samples: opts.samples * opts.rounds,
    p50: medianOf('p50'),
    p95: medianOf('p95'),
    p99: medianOf('p99'),
    max: medianOf('max'),
  };
}

function redisDeleteStatsCache() {
  const list = spawnSync('docker', ['compose', 'exec', '-T', 'redis', 'redis-cli', '--scan', '--pattern', 'cache:stats:*'], { encoding: 'utf8' });
  if (list.status !== 0) return -1;
  const keys = (list.stdout || '').split(/\r?\n/).map((s) => s.trim()).filter((s) => s.startsWith('cache:stats:'));
  if (keys.length === 0) return 0;
  const del = spawnSync('docker', ['compose', 'exec', '-T', 'redis', 'redis-cli', 'del', ...keys], { encoding: 'utf8' });
  return del.status === 0 ? keys.length : -1;
}

// 冷缓存：每次采样前先清空统计缓存，保证测到的是「未命中 -> 回源」的完整成本
async function measureCold(label, url, token) {
  const samples = [];
  let deletedTotal = 0;
  for (let i = 0; i < opts.samples; i++) {
    const deleted = redisDeleteStatsCache();
    if (deleted < 0) fail(`${label} 清理缓存失败`);
    deletedTotal += deleted;
    try { samples.push(await measureOnce(url, token)); }
    catch (err) { fail(`${label} 采样失败：${err.message}`); }
  }
  samples.sort((a, b) => a - b);
  return {
    label: `${label}（冷缓存，逐次清理）`,
    samples: samples.length,
    p50: percentile(samples, 50),
    p95: percentile(samples, 95),
    p99: percentile(samples, 99),
    max: samples[samples.length - 1],
    deleted: deletedTotal,
  };
}

function fmt(ms) { return Number.isFinite(ms) ? ms.toFixed(1) : '-'; }

async function main() {
  console.log(`性能基准：base=${opts.base}  samples=${opts.samples}  rounds=${opts.rounds}  warmup=${opts.warmup}`);

  const studentToken = await login(opts.user, opts.password, 0);
  const adminToken = await login(opts.admin, opts.adminPassword, 1);
  console.log('  登录就绪（学生 + 管理员）\n');

  const targets = [
    {
      key: 'conflict',
      label: '冲突检测 `/api/reservation/conflict`',
      url: `${opts.base}/api/reservation/conflict?classroomId=3&date=${formatDate(tomorrow)}&startTime=14:00&endTime=15:00`,
      token: studentToken,
      threshold: 500,
    },
    { key: 'classroom-list', label: '教室列表 `/api/classroom/list`', url: `${opts.base}/api/classroom/list?page=1&size=12`, token: studentToken },
    { key: 'mine', label: '我的预约 `/api/reservation/mine`', url: `${opts.base}/api/reservation/mine?page=1&size=10`, token: studentToken },
    {
      key: 'calendar',
      label: '日历总览 `/api/reservation/calendar`',
      url: `${opts.base}/api/reservation/calendar?startDate=${formatDate(monthStart)}&endDate=${formatDate(monthEnd)}`,
      token: studentToken,
    },
    { key: 'admin-manage', label: '审核列表 `/api/reservation/manage`', url: `${opts.base}/api/reservation/manage?page=1&size=10`, token: adminToken },
    { key: 'stats-overview', label: '看板概览 `/api/stats/overview`', url: `${opts.base}/api/stats/overview`, token: adminToken },
    {
      key: 'stats-usage-rate',
      label: '看板使用率 `/api/stats/usage-rate`',
      url: `${opts.base}/api/stats/usage-rate?startDate=${formatDate(monthStart)}&endDate=${formatDate(monthEnd)}`,
      token: adminToken,
    },
  ];

  const results = [];
  for (const target of targets) {

    process.stdout.write(`  测量 ${target.label} ... `);
    const result = await measureEndpoint(target.label, target.url, target.token);
    results.push({ ...result, threshold: target.threshold });
    console.log('完成');
  }

  let statsRows = [];
  if (opts.flushStats) {
    const usageTarget = targets.find((t) => t.key === 'stats-usage-rate');
    // 先请求一次，确认统计接口确实写缓存；写不进去就没有冷/热对比的意义
    await measureOnce(usageTarget.url, usageTarget.token);
    const deletedProbe = redisDeleteStatsCache();
    if (deletedProbe <= 0) {
      console.log('  跳过冷/热对比：统计接口未写入 `cache:stats:*`（该实现无缓存或 Redis 降级）');
    } else {
      console.log(`  统计缓存探测：清理 ${deletedProbe} 个键（说明回源后确有回填）`);
      process.stdout.write('  测量 看板使用率（冷缓存） ... ');
      statsRows.push(await measureCold(usageTarget.label, usageTarget.url, usageTarget.token));
      console.log('完成');
      process.stdout.write('  测量 看板使用率（热缓存） ... ');
      statsRows.push(await measureEndpoint(`${usageTarget.label}（热缓存）`, usageTarget.url, usageTarget.token));
      console.log('完成');
    }
  }

  const lines = [];
  lines.push('| 接口 | 样本 | p50 (ms) | p95 (ms) | p99 (ms) | max (ms) | 判定 |');
  lines.push('|---|---:|---:|---:|---:|---:|---|');
  for (const row of [...results, ...statsRows]) {
    let verdict = '-';
    if (row.threshold) verdict = row.p95 <= row.threshold ? `PASS（≤${row.threshold}ms）` : `FAIL（>${row.threshold}ms）`;
    lines.push(`| ${row.label} | ${row.samples} | ${fmt(row.p50)} | ${fmt(row.p95)} | ${fmt(row.p99)} | ${fmt(row.max)} | ${verdict} |`);
  }
  lines.push('');
  lines.push(`> 环境：Node ${process.version} / ${opts.base} / samples=${opts.samples} × rounds=${opts.rounds} / warmup=${opts.warmup} / ${new Date().toISOString()}`);
  lines.push('> 口径：直连 api 容器（不经 nginx）；单机回环、单客户端，反映「满足需求下限」而非生产并发容量。');

  const table = lines.join('\n');
  console.log('\n' + table + '\n');

  if (opts.out) {
    mkdirSync(dirname(opts.out), { recursive: true });
    writeFileSync(opts.out, table + '\n', 'utf8');
    console.log(`  报告已写入 ${opts.out}`);
  }

  const conflict = results.find((r) => r.key === 'conflict' || r.label.includes('冲突检测'));
  if (!conflict) fail('缺少冲突检测样本');
  if (conflict.p95 > 500) fail(`冲突检测 p95=${fmt(conflict.p95)}ms 超过 500ms 需求上限`);
  process.exit(0);
}

main().catch((err) => fail(err && err.message ? err.message : String(err)));
