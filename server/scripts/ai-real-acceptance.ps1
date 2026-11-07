<#
.SYNOPSIS
    真实 Agnes 模型验收脚本（4 个 AI 接口 + SSE 流式，逐个重试直到真实成功）

.DESCRIPTION
    对运行中的后端逐项发起**真实上游调用**，用「接口响应 message 为空」为主证据、
    /api/stats/ai/metrics 增量为旁证，证明走的是真实模型而不是本地规则降级：
      1) POST /ai/recommend           推荐（Top3 + 理由）
      2) POST /ai/parse-reservation   自然语言预约解析
      3) POST /ai/compliance-check    用途合规校验
      4) POST /ai/chat?stream=true    SSE 流式问答（meta/delta/done，degraded=false）

    额度现实：该免费账号上游限流很紧（本项目实测常见连续 2~3 次即 429），
    因此脚本按 -IntervalSeconds 间隔调用，失败（429/超时等自动降级）时按 -RetryWaitSeconds
    重试，最多 -MaxAttempts 次；**判定标准是每个接口最终真实成功**，
    过程中的降级次数如实打印（证明「限流/超时自动降级不阻断业务」，不伪造零降级）。

    硬性断言：每个端点 message 为空；ΔmodelCallCount ≥ 4；ΔtotalTokens > 0；
    NO_KEY 降级增量必须为 0（证明密钥确实注入，而不是靠无密钥降级“蒙混”通过）。
    任一项不满足即失败退出（非零退出码）。

    密钥安全：本脚本不读取、不打印 AGNES_API_KEY；密钥只在后端进程环境中注入。

.PARAMETER Base
    后端接口根地址，默认 http://127.0.0.1:8080/api

.PARAMETER IntervalSeconds
    两次调用之间的基础间隔（默认 90 秒；账号实测额度很紧，短间隔容易 429）

.PARAMETER MaxAttempts
    每个接口最多尝试次数（默认 2；含首次）

.PARAMETER RetryWaitSeconds
    单次失败后的重试等待（默认 120 秒）

.PARAMETER ClearAiCache
    执行前清理 Redis 中的 AI 结果缓存（cache:ai:*），确保每次都是上游真实调用

.PARAMETER RedisContainer
    Redis 容器名，默认 reservation-redis（仅 -ClearAiCache 时使用）

.EXAMPLE
    # 前置：api 容器已以 AI_ENABLE=true + AGNES_API_KEY 重建，且 ai_config.ai_enable='true'
    pwsh -ExecutionPolicy Bypass -File server/scripts/ai-real-acceptance.ps1 -ClearAiCache

.NOTES
    推荐使用 PowerShell 7（pwsh）；需种子账号 admin/admin123 与 zhangsan/123456。
#>
param(
    [string]$Base = 'http://127.0.0.1:8080/api',
    [int]$IntervalSeconds = 90,
    [int]$MaxAttempts = 2,
    [int]$RetryWaitSeconds = 120,
    [switch]$ClearAiCache,
    [string]$RedisContainer = 'reservation-redis'
)

$ErrorActionPreference = 'Stop'
$base = $Base.TrimEnd('/')
$results = @()
$nonce = (Get-Date).ToString('yyyyMMddHHmmss')
# 已发生的降级次数（按原因累计，仅作观测记录）
$degradeSeen = @{}

function Show($name, $ok, $detail) {
    $script:results += [pscustomobject]@{ Step = $name; OK = $ok; Detail = $detail }
    Write-Host ("[{0}] {1} :: {2}" -f ($(if ($ok) { 'PASS' } else { 'FAIL' })), $name, $detail)
}

function Call($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = "$base$path"; Method = $method; Headers = $headers; ContentType = 'application/json; charset=utf-8' }
    if ($body -ne $null) { $params['Body'] = ([System.Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 8 -Compress))) }
    try {
        $resp = Invoke-WebRequest @params -UseBasicParsing -TimeoutSec 150
        $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        return @{ status = [int]$resp.StatusCode; body = $text }
    } catch {
        $response = $_.Exception.Response
        $status = $null
        if ($response) { $status = [int]$response.StatusCode }
        $bodyText = $null
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $bodyText = $_.ErrorDetails.Message
        } elseif ($response -is [System.Net.Http.HttpResponseMessage]) {
            try { $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { $bodyText = $null }
        } elseif ($response) {
            $sr = New-Object System.IO.StreamReader($response.GetResponseStream())
            $bodyText = $sr.ReadToEnd()
        }
        if ($bodyText -ne $null) { return @{ status = $status; body = $bodyText } }
        throw
    }
}

function J($r) { if ($r.body) { return ($r.body | ConvertFrom-Json) } else { return $null } }

function Kb([string]$t, [int]$n = 120) { if (-not $t) { return '' }; return $t.Substring(0, [Math]::Min($n, $t.Length)) }

function Note-Degrade($message) {
    if ($null -eq $message -or "$message" -eq '') { return }
    $key = "$message"
    if ($degradeSeen.ContainsKey($key)) { $degradeSeen[$key] = $degradeSeen[$key] + 1 } else { $degradeSeen[$key] = 1 }
}

# 通用重试包装：$probe 返回 @{ok; detail; message}；message 非空视为降级（记录后重试）
function Try-Endpoint($name, $probe) {
    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        $r = & $probe
        Note-Degrade $r.message
        if ($r.ok) {
            Show $name $true ($r.detail + " attempts=$attempt")
            return $true
        }
        Write-Host ("[INFO] {0} 第 {1}/{2} 次未取到真实结果：{3}" -f $name, $attempt, $MaxAttempts, $r.message)
        if ($attempt -lt $MaxAttempts) { Start-Sleep -Seconds $RetryWaitSeconds }
    }
    Show $name $false ("重试耗尽仍降级：" + $r.message)
    return $false
}

# ---- 0. 可选：清理 AI 结果缓存，确保真实上游调用 ----
if ($ClearAiCache) {
    $keys = docker exec $RedisContainer redis-cli --scan --pattern 'cache:ai:*' 2>$null
    if ($keys) {
        $keyList = @($keys | Where-Object { $_ })
        docker exec $RedisContainer redis-cli del @keyList 2>$null | Out-Null
        Write-Host "已清理 AI 结果缓存 Key：$($keyList.Count) 个"
    } else {
        Write-Host 'AI 结果缓存为空（无需清理）'
    }
}

# ---- 1. 登录与 AI 开关前置检查 ----
$r = Call 'POST' '/user/login' $null @{ username = 'admin'; password = 'admin123'; role = 1 }
$adminBody = J $r
$adminToken = $adminBody.data.token
Show '管理员登录' ($r.status -eq 200 -and $adminToken) "status=$($r.status)"

$r = Call 'POST' '/user/login' $null @{ username = 'zhangsan'; password = '123456'; role = 0 }
$stuBody = J $r
$stuToken = $stuBody.data.token
$aiEnabled = [bool]$stuBody.data.aiEnabled
Show '学生登录（AI 开关随登录下发）' ($r.status -eq 200 -and $stuToken) "status=$($r.status) aiEnabled=$aiEnabled"
if (-not $aiEnabled) {
    Write-Host ''
    Write-Host '前置不满足：aiEnabled=false。请确认 api 容器以 AI_ENABLE=true 重建，且 ai_config.ai_enable=''true''。'
    exit 2
}

# ---- 2. 指标基线 ----
$r = Call 'GET' '/stats/ai/metrics' $adminToken $null
$m0 = (J $r).data
Show '读取 AI 指标基线' ($r.status -eq 200 -and $m0) "modelCallCount=$($m0.modelCallCount) degradeTotal=$($m0.degradeTotal) totalTokens=$($m0.totalTokens)"

# ---- 3. 推荐 ----
Start-Sleep -Seconds $IntervalSeconds
$ok1 = Try-Endpoint '真实调用·推荐' {
    $r = Call 'POST' '/ai/recommend' $stuToken @{}
    $b = J $r
    $d = $b.data
    # 注意：@($null).Count 为 1，必须先判空再取下标，避免对空数组索引
    $recs = @()
    if ($null -ne $d.recommendations) { $recs = @($d.recommendations) }
    $top = $recs.Count
    $firstReason = ''
    if ($top -gt 0 -and $null -ne $recs[0].reason) { $firstReason = Kb $recs[0].reason 60 }
    $detail = "message=" + $(if ($null -eq $d.message) { '<null>' } else { $d.message }) + " top=$top firstReason=$firstReason"
    @{ ok = ($r.status -eq 200 -and $b.code -eq 200 -and $d.enabled -eq $true -and $null -eq $d.message -and $top -gt 0); detail = $detail; message = $d.message }
}

# ---- 4. 预约解析 ----
Start-Sleep -Seconds $IntervalSeconds
$ok2 = Try-Endpoint '真实调用·预约解析' {
    $r = Call 'POST' '/ai/parse-reservation' $stuToken @{ text = "验收$nonce：后天下午三点到五点，40 人，普通教室，课程设计" }
    $b = J $r
    $d = $b.data
    $detail = "date=$($d.date) $($d.startTime)-$($d.endTime) capacity=$($d.capacity) roomType=$($d.roomType) message=" + $(if ($null -eq $d.message) { '<null>' } else { $d.message })
    @{ ok = ($r.status -eq 200 -and $b.code -eq 200 -and $d.enabled -eq $true -and $null -eq $d.message -and $d.date -and $d.startTime -and $d.endTime); detail = $detail; message = $d.message }
}

# ---- 5. 合规校验 ----
Start-Sleep -Seconds $IntervalSeconds
$ok3 = Try-Endpoint '真实调用·合规校验' {
    $r = Call 'POST' '/ai/compliance-check' $stuToken @{ purpose = "验收$nonce：小组实验准备" }
    $b = J $r
    $d = $b.data
    $detail = "compliant=$($d.compliant) reason=$($d.reason) message=" + $(if ($null -eq $d.message) { '<null>' } else { $d.message })
    @{ ok = ($r.status -eq 200 -and $b.code -eq 200 -and $d.enabled -eq $true -and $null -eq $d.message -and $null -ne $d.compliant); detail = $detail; message = $d.message }
}

# ---- 6. SSE 流式问答 ----
Start-Sleep -Seconds $IntervalSeconds
$ok4 = Try-Endpoint '真实调用·SSE 流式问答' {
    $r = Call 'POST' '/ai/chat?stream=true' $stuToken @{ question = "怎么预约教室？验收$nonce"; history = @() }
    $sse = $r.body
    $metaOk = ($sse -match 'event:meta') -and ($sse -match '"degraded":false')
    $deltaOk = ($sse -match 'event:delta')
    $doneOk = ($sse -match 'event:done')
    $errorAbsent = -not ($sse -match 'event:error')
    $ok = ($r.status -eq 200 -and $metaOk -and $deltaOk -and $doneOk -and $errorAbsent)
    $msg = ''
    if (-not $metaOk) {
        $m = [regex]::Match($sse, 'event:meta\s*\r?\n?data:(\{.*?\})')
        if ($m.Success) {
            try { $msg = ($m.Groups[1].Value | ConvertFrom-Json).message } catch { $msg = 'meta degraded' }
        } elseif ($sse -match 'event:error') { $msg = 'SSE error 事件' } else { $msg = 'meta 缺失或 degraded=true' }
    }
    $detail = "status=$($r.status) meta=$metaOk delta=$deltaOk done=$doneOk error=$([bool]($sse -match 'event:error')) len=$($sse.Length)"
    @{ ok = $ok; detail = $detail; message = $msg }
}

# ---- 7. 指标增量（旁证） ----
$r = Call 'GET' '/stats/ai/metrics' $adminToken $null
$m1 = (J $r).data
$dCall = [int]$m1.modelCallCount - [int]$m0.modelCallCount
$dTokens = [int]$m1.totalTokens - [int]$m0.totalTokens
$dNoKey = [int]$m1.degradeByReason.NO_KEY - [int]$m0.degradeByReason.NO_KEY
$dUpstream429 = [int]$m1.upstream429 - [int]$m0.upstream429
$dRejected = [int]$m1.rateLimitRejected - [int]$m0.rateLimitRejected

Show '指标：确有上游真实调用与 token 消耗' ($dCall -ge 4 -and $dTokens -gt 0) "ΔmodelCall=$dCall Δtokens=$dTokens Δstop=$([int]$m1.finishReasonByReason.stop - [int]$m0.finishReasonByReason.stop)"
Show '指标：密钥已注入（NO_KEY 降级为 0）' ($dNoKey -eq 0) "ΔNO_KEY=$dNoKey"
Show '四个端点最终均真实成功' ($ok1 -and $ok2 -and $ok3 -and $ok4) "recommend=$ok1 parse=$ok2 compliance=$ok3 chatStream=$ok4"

if ($degradeSeen.Count -gt 0) {
    Write-Host ''
    Write-Host '----- 过程中的自动降级（已由后端自动兜底，业务未中断） -----'
    $degradeSeen.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  ×{0}  {1}" -f $_.Value, $_.Name) }
    Write-Host ("上游 429 次数 Δ={0}，本地限流拒绝 Δ={1}" -f $dUpstream429, $dRejected)
}

Write-Host ''
Write-Host '===== 汇总 ====='
$fail = $results | Where-Object { -not $_.OK }
Write-Host ("总计 {0} 项，通过 {1} 项，失败 {2} 项" -f $results.Count, ($results.Count - $fail.Count), $fail.Count)
$fail | ForEach-Object { Write-Host ("FAILED: {0} :: {1}" -f $_.Step, $_.Detail) }

if ($fail.Count -gt 0) { exit 1 }
exit 0
