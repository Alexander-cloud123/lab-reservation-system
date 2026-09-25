/**
 * 全局前置：探活后端，避免"后端没起"时每条用例各报一次超时
 * 失败信息直接给出修法（端口冲突是本项目的高频坑）
 */
const API_BASE = process.env.E2E_API_BASE || `http://127.0.0.1:${process.env.E2E_API_PORT || '8080'}`

export default async function globalSetup() {
  const url = `${API_BASE}/api/user/login`
  try {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin123', role: 1 })
    })
    if (!resp.ok) {
      throw new Error(`HTTP ${resp.status}`)
    }
    const body = await resp.json()
    if (body.code !== 200) {
      throw new Error(`业务码 ${body.code}：${body.message}`)
    }
  } catch (e) {
    throw new Error(
      `后端探活失败（${url}）：${e.message}\n` +
        '请确认：① MySQL/Redis 容器在线；② 后端已启动。\n' +
        '若本机 8080 被占用，请以 --server.port=8081 启动后端，并设 E2E_API_PORT=8081 后重跑。'
    )
  }
}