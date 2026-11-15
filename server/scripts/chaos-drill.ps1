# =====================================================================
# 故障演练（针对 docker compose 起的整套栈）
# 用法：pwsh server/scripts/chaos-drill.ps1
#       pwsh server/scripts/chaos-drill.ps1 -IncludeSlow     # 额外验证 token_version 撤销的 60s 快照窗口
# 说明：演练会真的停止/启动 redis 与 mysql 容器，仅限本机开发栈；结尾一定会把服务恢复并校验数据。
# 断言口径：
#   - Redis 不可用：受保护接口 fail closed（401），登录允许降级但仍受本地失败计数保护；
#   - MySQL 不可用：接口返回统一错误包（HTTP 200 + code=500 + 友好文案），不泄露堆栈，30s 内返回；
#   - 恢复后：核心接口可用、种子数据不变（13 预约 / 5 用户 / 12 教室）。
# 任一项失败以退出码 1 结束。
# =====================================================================
param(
    [string]$WebUrl = 'http://127.0.0.1:8088',
    [string]$ApiUrl = 'http://127.0.0.1:8080',
    [string]$StudentUser = 'zhangsan',
    [string]$StudentPassword = '123456',
    [switch]$IncludeSlow
)

$ErrorActionPreference = 'Stop'
$script:checks = 0
$script:failures = 0
$script:logs = @()

function Write-Pass([string]$name, [string]$detail = '') {
    $script:checks++
    Write-Host ("  PASS  {0}{1}" -f $name, $(if ($detail) { "  ($detail)" } else { '' })) -ForegroundColor Green
}
function Write-Fail([string]$name, [string]$detail) {
    $script:checks++
    $script:failures++
    Write-Host ("  FAIL  {0}  ->  {1}" -f $name, $detail) -ForegroundColor Red
}
function Write-Step([string]$text) {
    Write-Host ""
    Write-Host ("== " + $text) -ForegroundColor Cyan
}

function Invoke-Compose([string[]]$Arguments) {
    $out = & docker compose @Arguments 2>&1
    $script:logs += ($Arguments -join ' ')
    return ($out -join "`n")
}

function Wait-ServiceHealthy([string]$Service, [int]$TimeoutSec = 90) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Milliseconds 800
        $status = ''
        try { $status = (& docker inspect --format '{{.State.Health.Status}}' $Service 2>$null) -join '' } catch { }
    } until ($status -eq 'healthy' -or $sw.Elapsed.TotalSeconds -ge $TimeoutSec)
    return @{ Healthy = ($status -eq 'healthy'); Seconds = [Math]::Round($sw.Elapsed.TotalSeconds, 1) }
}

function Get-Response([string]$uri, [hashtable]$headers = @{}, [string]$method = 'Get', [string]$body = $null, [int]$timeoutSec = 45) {
    $params = @{
        Uri                = $uri
        Method             = $method
        Headers            = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec         = $timeoutSec
    }
    if ($body) { $params['Body'] = $body; $params['ContentType'] = 'application/json' }
    return Invoke-WebRequest @params
}

function Get-LoginToken {
    $body = @{ username = $StudentUser; password = $StudentPassword; role = 0 } | ConvertTo-Json -Compress
    $resp = Get-Response "$ApiUrl/api/user/login" @{} 'Post' $body
    $json = $resp.Content | ConvertFrom-Json
    if ([int]$resp.StatusCode -eq 200 -and $json.data.token) { return [string]$json.data.token }
    return $null
}

# 统一错误包：HTTP 200 + code=500 + 友好文案 + 无堆栈
function Test-GenericErrorBody([string]$content) {
    $hasStack = $content -match 'Exception|at com\.example|java\.|org\.springframework|Caused by'
    $json = $null
    try { $json = $content | ConvertFrom-Json } catch { }
    $isGeneric = ($json -and [int]$json.code -eq 500 -and $json.message -match '系统异常')
    return @{ Ok = ($isGeneric -and -not $hasStack); Stack = $hasStack; Code = $(if ($json) { [int]$json.code } else { $null }) }
}

function Get-RowCount([string]$table) {
    $dbName = 'reservation'
    $sql = "select count(*) from $dbName.$table;"
    $out = Invoke-Compose @('exec', '-T', 'mysql', 'mysql', '-uroot', '-proot', '-N', '-e', $sql)
    $match = [regex]::Match($out, '\d+')
    if ($match.Success) { return [int]$match.Value }
    return -1
}

# 数据兜底：演练可能改过 sys_user.token_version（-IncludeSlow），结束时恢复
$restoreTokenVersion = $false
$originalTokenVersion = 0

try {
    Write-Host "故障演练：web=$WebUrl  api=$ApiUrl  栈=本机 docker compose"

    # -----------------------------------------------------------------
    Write-Step "0. 前置检查"
    # -----------------------------------------------------------------
    $ps = Invoke-Compose @('ps', '--format', '{{.Service}} {{.Status}}')
    $unhealthy = @()
    foreach ($service in 'api', 'mysql', 'redis', 'web') {
        if ($ps -notmatch "(?m)^$service .*healthy") { $unhealthy += $service }
    }
    if ($unhealthy.Count -eq 0) { Write-Pass '四服务 healthy' } else { Write-Fail '四服务 healthy' ("异常：" + ($unhealthy -join ', ')) }

    $baselineToken = Get-LoginToken
    if ($baselineToken) { Write-Pass '基线登录' } else { Write-Fail '基线登录' '无法获取学生 Token' }

    $baseline = $null
    try { $baseline = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $baselineToken" } } catch { }
    if ($baseline -and [int]$baseline.StatusCode -eq 200) { Write-Pass '基线受保护接口' '200' } else { Write-Fail '基线受保护接口' ("HTTP " + $(if ($baseline) { [int]$baseline.StatusCode } else { 'ERR' })) }

    $baselineRows = Get-RowCount 'reservation'
    if ($baselineRows -eq 13) { Write-Pass '基线种子数据' ('预约 ' + $baselineRows + ' 条') } else { Write-Fail '基线种子数据' ('预约 ' + $baselineRows + ' 条，期望 13') }

    # -----------------------------------------------------------------
    Write-Step "A. Redis 不可用（会话中心 fail closed）"
    # -----------------------------------------------------------------
    Invoke-Compose @('stop', 'redis') | Out-Null
    Start-Sleep -Seconds 2

    # A1 受保护接口：必须有 Token 也 401，不允许放行
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $infoDown = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $baselineToken" }
    $sw.Stop()
    $infoDownJson = $null
    try { $infoDownJson = $infoDown.Content | ConvertFrom-Json } catch { }
    $failClosed = ([int]$infoDown.StatusCode -eq 401 -and $infoDownJson -and [int]$infoDownJson.code -eq 401)
    $stackFree = ($infoDown.Content -notmatch 'Exception|at com\.example|java\.')
    if ($failClosed -and $stackFree) {
        Write-Pass 'Redis 停：受保护接口 fail closed' ("HTTP 401，耗时 " + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + "s（重试放大，非瞬时）")
    } else {
        Write-Fail 'Redis 停：受保护接口 fail closed' ("HTTP " + [int]$infoDown.StatusCode + "，code=" + $(if ($infoDownJson) { $infoDownJson.code } else { '?' }))
    }

    # A2 登录：允许降级成功（登录锁计数有内存兜底），但必须受超时约束且不泄露堆栈
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $loginDown = Get-Response "$ApiUrl/api/user/login" @{} 'Post' (@{ username = $StudentUser; password = $StudentPassword; role = 0 } | ConvertTo-Json -Compress)
    $sw.Stop()
    $loginDownJson = $null
    try { $loginDownJson = $loginDown.Content | ConvertFrom-Json } catch { }
    $loginDownSecs = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
    $noStack = ($loginDown.Content -notmatch 'Exception|at com\.example|java\.')
    if ($loginDownSecs -le 45 -and $noStack) {
        $mode = if ($loginDownJson -and [int]$loginDownJson.code -eq 200) { '降级放行（Redis 不可用不阻断登录，锁计数走内存兜底）' } else { '拒绝' }
        Write-Pass 'Redis 停：登录响应' ("HTTP " + [int]$loginDown.StatusCode + "，code=" + $(if ($loginDownJson) { $loginDownJson.code } else { '?' }) + "，" + $loginDownSecs + "s；" + $mode)
    } else {
        Write-Fail 'Redis 停：登录响应' ($loginDownSecs + "s，含堆栈=" + (-not $noStack))
    }

    # A3 降级期间签发的 Token 同样不可用（会话中心缺失 => fail closed 一致）
    $degradedToken = if ($loginDownJson -and $loginDownJson.data) { [string]$loginDownJson.data.token } else { $null }
    if ($degradedToken) {
        $infoDegraded = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $degradedToken" }
        if ([int]$infoDegraded.StatusCode -eq 401) {
            Write-Pass 'Redis 停：降级签发的 Token 不可用' '401（会话中心缺失，接口不发数据）'
        } else {
            Write-Fail 'Redis 停：降级签发的 Token 不可用' ("HTTP " + [int]$infoDegraded.StatusCode)
        }
    } else {
        Write-Pass 'Redis 停：降级签发的 Token 不可用' '登录已拒绝，无 Token 可测'
    }

    # A4 恢复
    Invoke-Compose @('start', 'redis') | Out-Null
    $redisReady = $false
    $sw = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Milliseconds 500
        try { $pong = (& docker compose exec -T redis redis-cli ping 2>$null) -join '' } catch { $pong = '' }
        $redisReady = ($pong -match 'PONG')
    } until ($redisReady -or $sw.Elapsed.TotalSeconds -ge 30)
    if ($redisReady) {
        Write-Pass 'Redis 恢复' ("PONG，" + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + "s")
    } else {
        Write-Fail 'Redis 恢复' 'PONG 超时'
    }

    # 恢复后自愈：Lettuce 重连 + 会话重建可能滞后几秒，轮询 15s 断言「最终可用」
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $infoUp = $null
    do {
        Start-Sleep -Milliseconds 1500
        $recoveredToken = Get-LoginToken
        if ($recoveredToken) {
            $infoUp = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $recoveredToken" }
            if ([int]$infoUp.StatusCode -eq 200) { break }
        }
    } until ($sw.Elapsed.TotalSeconds -ge 30)

    if ($infoUp -and [int]$infoUp.StatusCode -eq 200) {
        Write-Pass 'Redis 恢复后受保护接口' ("200，自愈 " + [Math]::Round($sw.Elapsed.TotalSeconds, 1) + "s")
    } elseif (-not $recoveredToken) {
        Write-Fail 'Redis 恢复后登录' '30s 内无法获取 Token'
    } else {
        Write-Fail 'Redis 恢复后受保护接口' ("HTTP " + [int]$infoUp.StatusCode + "，30s 内未自愈")
    }

    # -----------------------------------------------------------------
    Write-Step "B. MySQL 不可用（数据层故障）"
    # -----------------------------------------------------------------
    $bToken = Get-LoginToken
    if (-not $bToken) { Write-Fail 'MySQL 演练前置登录' '无法获取学生 Token' }

    Invoke-Compose @('stop', 'mysql') | Out-Null
    Start-Sleep -Seconds 2

    # B1 列表接口：统一错误包，不泄露堆栈，不超过连接池等待
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $listDown = Get-Response "$ApiUrl/api/classroom/list?page=1&size=12" @{ Authorization = "Bearer $bToken" }
    $sw.Stop()
    $listDownSecs = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
    $verdict = Test-GenericErrorBody $listDown.Content
    if ($verdict.Ok -and $listDownSecs -le 45) {
        Write-Pass 'MySQL 停：列表接口统一错误包' ("HTTP " + [int]$listDown.StatusCode + "，code=500，无堆栈，" + $listDownSecs + "s（连接池等待上限）")
    } else {
        Write-Fail 'MySQL 停：列表接口统一错误包' ("HTTP " + [int]$listDown.StatusCode + "，code=" + $verdict.Code + "，堆栈=" + $verdict.Stack + "，" + $listDownSecs + "s")
    }

    # B2 登录：同样只允许统一错误包（不能抛 500 页面/堆栈）
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $loginDbDown = Get-Response "$ApiUrl/api/user/login" @{} 'Post' (@{ username = $StudentUser; password = $StudentPassword; role = 0 } | ConvertTo-Json -Compress)
    $sw.Stop()
    $loginDownSecs2 = [Math]::Round($sw.Elapsed.TotalSeconds, 1)
    $verdict2 = Test-GenericErrorBody $loginDbDown.Content
    if ($verdict2.Ok -and $loginDownSecs2 -le 45) {
        Write-Pass 'MySQL 停：登录统一错误包' ("HTTP " + [int]$loginDbDown.StatusCode + "，code=500，" + $loginDownSecs2 + "s")
    } else {
        Write-Fail 'MySQL 停：登录统一错误包' ("code=" + $verdict2.Code + "，堆栈=" + $verdict2.Stack + "，" + $loginDownSecs2 + "s")
    }

    # B3 恢复
    Invoke-Compose @('start', 'mysql') | Out-Null
    $mysqlReady = Wait-ServiceHealthy 'reservation-mysql' 120
    if ($mysqlReady.Healthy) {
        Write-Pass 'MySQL 恢复' ("healthy，" + $mysqlReady.Seconds + "s")
    } else {
        Write-Fail 'MySQL 恢复' ('healthy 超时')
    }

    $tokenAfterDb = Get-LoginToken
    if ($tokenAfterDb) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $listUp = Get-Response "$ApiUrl/api/classroom/list?page=1&size=12" @{ Authorization = "Bearer $tokenAfterDb" }
        $sw.Stop()
        $listUpJson = $null
        try { $listUpJson = $listUp.Content | ConvertFrom-Json } catch { }
        if ([int]$listUp.StatusCode -eq 200 -and $listUpJson -and [int]$listUpJson.code -eq 200 -and $listUpJson.data.total -ge 1) {
            Write-Pass 'MySQL 恢复后列表接口' ("200，total=" + $listUpJson.data.total + "，" + [Math]::Round($sw.Elapsed.TotalSeconds, 2) + "s")
        } else {
            Write-Fail 'MySQL 恢复后列表接口' ("HTTP " + [int]$listUp.StatusCode + "，body=" + $listUp.Content.Substring(0, [Math]::Min(120, $listUp.Content.Length)))
        }
    } else {
        Write-Fail 'MySQL 恢复后登录' '无法获取 Token'
    }

    # -----------------------------------------------------------------
    if ($IncludeSlow) {
        Write-Step "C. 会话撤销窗口（token_version 快照）"
        # -----------------------------------------------------------------
        $versionOut = Invoke-Compose @('exec', '-T', 'mysql', 'mysql', '-uroot', '-proot', '-N', '-e', 'select token_version from reservation.sys_user where id=2;')
        $match = [regex]::Match($versionOut, '\d+')
        if ($match.Success) {
            $originalTokenVersion = [int]$match.Value
            $restoreTokenVersion = $true
        }

        # 先暖一遍缓存，保证后面的 200 是「快照窗口」而不是「缓存未命中回源」
        $warmToken = Get-LoginToken
        $null = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $warmToken" }

        Invoke-Compose @('exec', '-T', 'mysql', 'mysql', '-uroot', '-proot', '-N', '-e', 'update reservation.sys_user set token_version = token_version + 1 where id = 2;') | Out-Null

        $withinWindow = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $warmToken" }
        if ([int]$withinWindow.StatusCode -eq 200) {
            Write-Pass '撤销后 60s 窗口内旧 Token 仍可用（已知边界）' '200（Redis 快照 TTL=60s）'
        } else {
            Write-Fail '撤销后 60s 窗口内旧 Token 仍可用（已知边界）' ("HTTP " + [int]$withinWindow.StatusCode + "（快照已过期，窗口未被观察到）")
        }

        Start-Sleep -Seconds 65
        $afterWindow = Get-Response "$ApiUrl/api/user/info" @{ Authorization = "Bearer $warmToken" }
        if ([int]$afterWindow.StatusCode -eq 401) {
            Write-Pass '撤销后 60s 窗口外旧 Token 失效' '401'
        } else {
            Write-Fail '撤销后 60s 窗口外旧 Token 失效' ("HTTP " + [int]$afterWindow.StatusCode)
        }
    }

} catch {
    Write-Fail '演练执行异常' $_.Exception.Message
} finally {
    # -----------------------------------------------------------------
    Write-Step "恢复服务与数据"
    # -----------------------------------------------------------------
    try {
        $psNow = Invoke-Compose @('ps', '--format', '{{.Service}} {{.State}}')
        if ($psNow -notmatch "(?m)^redis running") { Invoke-Compose @('start', 'redis') | Out-Null }
        if ($psNow -notmatch "(?m)^mysql running") { Invoke-Compose @('start', 'mysql') | Out-Null }
        $null = Wait-ServiceHealthy 'reservation-mysql' 120
        $ready = $false
        $sw = [Diagnostics.Stopwatch]::StartNew()
        do {
            Start-Sleep -Milliseconds 500
            try { $pong2 = (& docker compose exec -T redis redis-cli ping 2>$null) -join '' } catch { $pong2 = '' }
            $ready = ($pong2 -match 'PONG')
        } until ($ready -or $sw.Elapsed.TotalSeconds -ge 30)

        if ($restoreTokenVersion) {
            Invoke-Compose @('exec', '-T', 'mysql', 'mysql', '-uroot', '-proot', '-N', '-e', "update reservation.sys_user set token_version = $originalTokenVersion where id = 2;") | Out-Null
            # 清掉用户快照，避免恢复后的 Token 仍按旧版本判定
            Invoke-Compose @('exec', '-T', 'redis', 'redis-cli', 'FLUSHALL') | Out-Null
        }

        $final = Invoke-Compose @('ps', '--format', '{{.Service}} {{.Status}}')
        $notHealthy = @()
        foreach ($service in 'api', 'mysql', 'redis', 'web') {
            if ($final -notmatch "(?m)^$service .*healthy") { $notHealthy += $service }
        }
        $rows = Get-RowCount 'reservation'
        $users = Get-RowCount 'sys_user'
        $rooms = Get-RowCount 'classroom'
        if ($notHealthy.Count -eq 0) { Write-Pass '结尾四服务 healthy' } else { Write-Fail '结尾四服务 healthy' ("异常：" + ($notHealthy -join ', ')) }
        if ($rows -eq 13 -and $users -eq 5 -and $rooms -eq 12) {
            Write-Pass '结尾数据完整' ("预约 $rows / 用户 $users / 教室 $rooms")
        } else {
            Write-Fail '结尾数据完整' ("预约 $rows / 用户 $users / 教室 $rooms")
        }
    } catch {
        Write-Fail '恢复阶段异常' $_.Exception.Message
    }
}

Write-Host ""
if ($script:failures -eq 0) {
    Write-Host ("故障演练通过：{0}/{0} 项" -f $script:checks) -ForegroundColor Green
    exit 0
}
Write-Host ("故障演练失败：{0} 项失败 / 共 {1} 项" -f $script:failures, $script:checks) -ForegroundColor Red
exit 1
