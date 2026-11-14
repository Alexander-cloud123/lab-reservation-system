# =====================================================================
# 上线前安全基线自检（针对 docker compose 起的整套栈）
# 用法：pwsh server/scripts/security-check.ps1
#       pwsh server/scripts/security-check.ps1 -WebUrl http://127.0.0.1:8088 -ApiUrl http://127.0.0.1:8080
# 说明：全部为只读探测 + 限流探测（注册接口只发非法请求体，不写库）。
# 任一项失败以退出码 1 结束，便于 CI / 发布前手动卡口。
# =====================================================================
param(
    [string]$WebUrl = 'http://127.0.0.1:8088',
    [string]$ApiUrl = 'http://127.0.0.1:8080',
    # 开发默认密钥：仅用于验证「部署环境不会接受它」，不是真实密钥
    [string]$DefaultSecret = 'reservation-system-jwt-secret-2026',
    [string]$StudentUser = 'zhangsan',
    [string]$StudentPassword = '123456'
)

$ErrorActionPreference = 'Stop'
$script:checks = 0
$script:failures = 0

function Write-Pass([string]$name, [string]$detail = '') {
    $script:checks++
    Write-Host ("  PASS  {0}{1}" -f $name, $(if ($detail) { "  ($detail)" } else { '' })) -ForegroundColor Green
}

function Write-Fail([string]$name, [string]$detail) {
    $script:checks++
    $script:failures++
    Write-Host ("  FAIL  {0}  ->  {1}" -f $name, $detail) -ForegroundColor Red
}

function Get-Response([string]$uri, [hashtable]$headers = @{}, [string]$method = 'Get', [string]$body = $null) {
    $params = @{
        Uri                = $uri
        Method             = $method
        Headers            = $headers
        SkipHttpErrorCheck = $true
        TimeoutSec         = 20
    }
    if ($body) { $params['Body'] = $body; $params['ContentType'] = 'application/json' }
    return Invoke-WebRequest @params
}

function ConvertTo-Base64Url([byte[]]$bytes) {
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# 用开发默认密钥手工签一个 admin Token：部署环境必须拒签，否则等于没有鉴权
function New-ForgedAdminToken([string]$secret) {
    $header = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes('{"alg":"HS256","typ":"JWT"}'))
    $exp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() + 3600
    $payloadJson = '{"userId":"1","username":"admin","role":"1","tokenVersion":"0","exp":' + $exp + '}'
    $payload = ConvertTo-Base64Url ([Text.Encoding]::UTF8.GetBytes($payloadJson))
    $signingInput = "$header.$payload"
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
    $signature = ConvertTo-Base64Url ($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($signingInput)))
    return "$signingInput.$signature"
}

Write-Host "安全基线自检：web=$WebUrl  api=$ApiUrl`n"

# ---------------------------------------------------------------------
# 1. 网关安全响应头 + 不泄露 nginx 版本
# ---------------------------------------------------------------------
try {
    $page = Get-Response "$WebUrl/"
    $missing = @()
    foreach ($name in 'X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy', 'Permissions-Policy') {
        if (-not $page.Headers[$name]) { $missing += $name }
    }
    if ($missing.Count -eq 0) {
        Write-Pass '安全响应头齐全'
    } else {
        Write-Fail '安全响应头齐全' ("缺少 " + ($missing -join ', '))
    }

    $serverHeader = [string]($page.Headers['Server'] | Select-Object -First 1)
    if ($serverHeader -match '^nginx$') {
        Write-Pass '不暴露 nginx 版本' $serverHeader
    } else {
        Write-Fail '不暴露 nginx 版本' ("Server: " + $serverHeader)
    }
} catch {
    Write-Fail '访问前端入口' $_.Exception.Message
}

# ---------------------------------------------------------------------
# 2. 接口文档在部署模式下不可访问
# ---------------------------------------------------------------------
foreach ($path in '/doc.html', '/v3/api-docs') {
    try {
        $doc = Get-Response ($ApiUrl + $path)
        # 全局异常处理器把「路径不存在」包装为 HTTP 200 + Result{code:404}，因此关闭判定看语义与内容类型
        $closedByStatus = [int]$doc.StatusCode -eq 404
        $closedByBody = $doc.Content -match '"code"\s*:\s*404'
        $isHtml = $doc.Content -match '<html|<!DOCTYPE'
        if (($closedByStatus -or $closedByBody) -and -not $isHtml) {
            Write-Pass "接口文档已关闭 $path" ("HTTP " + [int]$doc.StatusCode + "，无文档内容")
        } else {
            Write-Fail "接口文档已关闭 $path" ("HTTP " + [int]$doc.StatusCode + "，仍返回文档内容")
        }
    } catch {
        Write-Fail "接口文档已关闭 $path" $_.Exception.Message
    }
}

# ---------------------------------------------------------------------
# 3. 伪造 Token（默认密钥）必须 401；无 Token 必须 401
# ---------------------------------------------------------------------
try {
    $forged = New-ForgedAdminToken $DefaultSecret
    $forgedResp = Get-Response "$ApiUrl/api/user/manage" @{ Authorization = "Bearer $forged" }
    if ([int]$forgedResp.StatusCode -eq 401) {
        Write-Pass '默认密钥伪造 admin Token 被拒绝' '401'
    } else {
        Write-Fail '默认密钥伪造 admin Token 被拒绝' ("HTTP " + [int]$forgedResp.StatusCode + "，签名密钥仍为开发默认值")
    }
} catch {
    Write-Fail '默认密钥伪造 admin Token 被拒绝' $_.Exception.Message
}

try {
    $anon = Get-Response "$ApiUrl/api/user/info"
    if ([int]$anon.StatusCode -eq 401) {
        Write-Pass '未登录访问受保护接口' '401'
    } else {
        Write-Fail '未登录访问受保护接口' ("HTTP " + [int]$anon.StatusCode)
    }
} catch {
    Write-Fail '未登录访问受保护接口' $_.Exception.Message
}

# ---------------------------------------------------------------------
# 4. 学生 Token 访问管理员接口必须 403（body 不合法也应先被拦截器拒绝）
# ---------------------------------------------------------------------
try {
    $loginBody = @{ username = $StudentUser; password = $StudentPassword; role = 0 } | ConvertTo-Json -Compress
    $login = Get-Response "$ApiUrl/api/user/login" @{} 'Post' $loginBody
    $loginJson = $login.Content | ConvertFrom-Json
    if ([int]$login.StatusCode -eq 200 -and $loginJson.data.token) {
        $studentToken = $loginJson.data.token
        $adminResp = Get-Response "$ApiUrl/api/reservation/batch-audit" @{ Authorization = "Bearer $studentToken" } 'Post' '{"ids":[999999],"status":1}'
        if ([int]$adminResp.StatusCode -eq 403) {
            Write-Pass '学生 Token 访问管理员接口' '403'
        } else {
            Write-Fail '学生 Token 访问管理员接口' ("HTTP " + [int]$adminResp.StatusCode)
        }
    } else {
        Write-Fail '学生账号登录（前置条件）' ("HTTP " + [int]$login.StatusCode + "，" + $login.Content)
    }
} catch {
    Write-Fail '学生 Token 访问管理员接口' $_.Exception.Message
}

# ---------------------------------------------------------------------
# 5. 登录/注册按 IP 限流：连续第 11 次起 429
#    请求体故意非法（{}），避免真的创建账号；nginx 在转发前计数，同样会触发限流。
# ---------------------------------------------------------------------
try {
    $codes = @()
    for ($i = 1; $i -le 12; $i++) {
        $resp = Get-Response "$WebUrl/api/user/register" @{} 'Post' '{}'
        $codes += [int]$resp.StatusCode
    }
    $tooMany = @($codes | Where-Object { $_ -eq 429 }).Count
    if ($tooMany -ge 1 -and $codes[10] -eq 429) {
        Write-Pass '登录/注册限流' ("第 11 次起 429；状态序列 " + ($codes -join ','))
    } else {
        Write-Fail '登录/注册限流' ("未在预期位置触发 429；状态序列 " + ($codes -join ','))
    }
} catch {
    Write-Fail '登录/注册限流' $_.Exception.Message
}

# ---------------------------------------------------------------------
Write-Host ""
if ($script:failures -eq 0) {
    Write-Host ("安全基线自检通过：{0}/{0} 项" -f $script:checks) -ForegroundColor Green
    exit 0
}
Write-Host ("安全基线自检失败：{0} 项失败 / 共 {1} 项" -f $script:failures, $script:checks) -ForegroundColor Red
exit 1
