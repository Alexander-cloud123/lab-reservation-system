<#
.SYNOPSIS
    预约管理系统 AI 双开关快捷开关（默认开启，-Off 关闭）

.DESCRIPTION
    AI 是「双开关」：application.yml 的 ai.enable（静态总开关，由 .env 的 AI_ENABLE 注入）
    与 ai_config.ai_enable（动态开关，可在库中随时改）。本脚本只改动态开关并清理缓存：

      1. update reservation.ai_config set config_value=... where config_key='ai_enable'
      2. redis-cli 删除 cache:ai:*（AI 回答缓存）
      3. 调登录接口校验 LoginVO.aiEnabled 是否与开关一致

    默认口径（2026-11 调整）：docker compose 演示默认开启 AI（compose 注入 AI_ENABLE=true、
    init_db.sql 种子 ai_enable=true），全新启动无需再跑本脚本。本脚本用于：切关演示 AI、
    或在沿用旧数据卷（ai_enable 仍为 false）的存量部署里补齐动态开关。
    AI 开关在服务端有 5 秒进程内缓存，脚本会等待 6 秒再校验。

    注意：本脚本要求 docker compose 栈已启动（api / mysql / redis）。
    关闭 AI 后已登录的浏览器不会立刻感知，刷新页面即可（开关会在下次登录/探测时更新）。

.PARAMETER Off
    关闭 AI（默认不带此参数 = 开启）。

.PARAMETER ApiUrl
    后端接口根地址，默认 http://127.0.0.1:8080（compose 只把 api 绑在回环地址）。

.PARAMETER WebUrl
    系统入口地址，仅用于结尾提示，默认 http://127.0.0.1:8088。

.PARAMETER StudentUser
    校验用学生账号，默认 zhangsan。

.PARAMETER StudentPassword
    校验用学生密码，默认 123456。

.EXAMPLE
    pwsh -ExecutionPolicy Bypass -File server/scripts/ai-switch.ps1
    pwsh -ExecutionPolicy Bypass -File server/scripts/ai-switch.ps1 -Off

.NOTES
    只改 ai_config 的一行配置，不改表结构、不碰业务数据。
#>
param(
    [switch]$Off,
    [string]$ApiUrl = 'http://127.0.0.1:8080',
    [string]$WebUrl = 'http://127.0.0.1:8088',
    [string]$StudentUser = 'zhangsan',
    [string]$StudentPassword = '123456'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$value = if ($Off) { 'false' } else { 'true' }
$label = if ($Off) { '关闭' } else { '开启' }

Write-Host "[1/4] 写入 ai_config.ai_enable=$value ..."
docker compose exec -T mysql mysql -uroot -proot -N -e "update reservation.ai_config set config_value='$value' where config_key='ai_enable'" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw '写入失败：请确认 docker compose 栈已启动（docker compose ps），且 mysql 服务 healthy'
}
$current = (docker compose exec -T mysql mysql -uroot -proot -N -e "select config_value from reservation.ai_config where config_key='ai_enable'").Trim()
if ($current -ne $value) {
    throw "校验失败：ai_enable 期望 $value，实际 $current"
}
Write-Host "       ai_enable=$current"

Write-Host '[2/4] 清理 Redis 中的 AI 回答缓存（cache:ai:*） ...'
$keys = docker compose exec -T redis redis-cli --scan --pattern 'cache:ai:*'
if ($LASTEXITCODE -ne 0) {
    throw 'Redis 不可用：请确认 redis 服务 healthy'
}
$deleted = 0
foreach ($key in @($keys)) {
    if ($key) {
        docker compose exec -T redis redis-cli del $key | Out-Null
        $deleted++
    }
}
Write-Host "       已清理 $deleted 个缓存键"

Write-Host '[3/4] 等待服务端开关缓存过期（5 秒） ...'
Start-Sleep -Seconds 6

Write-Host '[4/4] 校验登录接口下发的 aiEnabled ...'
$body = @{ username = $StudentUser; password = $StudentPassword; role = 0 } | ConvertTo-Json
$resp = Invoke-RestMethod -Uri "$ApiUrl/api/user/login" -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 30
if ($resp.code -ne 200) {
    throw "登录校验失败：code=$($resp.code)，请确认 api 服务 healthy"
}
$expected = ($value -eq 'true')
Write-Host "       登录返回 aiEnabled=$($resp.data.aiEnabled)（期望 $expected）"

Write-Host ''
if ($resp.data.aiEnabled -eq $expected) {
    Write-Host "AI 已$label。" -ForegroundColor Green
} else {
    Write-Host '警告：登录返回与动态开关不一致。请检查 .env 的 AI_ENABLE（静态总开关）与 AGNES_API_KEY。' -ForegroundColor Yellow
}
Write-Host "提示：浏览器需刷新页面才会重新探测；AI 悬浮球只在学生端页面出现（$WebUrl，学生账号登录）。"
