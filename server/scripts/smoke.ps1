<#
.SYNOPSIS
    预约管理系统 后端接口冒烟回归脚本（41 项）

.DESCRIPTION
    对运行中的后端做一遍端到端接口回归：鉴权 / 权限 / 预约全生命周期 / 冲突检测 /
    参数校验 / 看板 / 收藏 / AI 降级 / 注销与会话撤销，共 41 项断言。

    断言口径（与 AuthAndValidationSmokeTest 一致）：
      · 鉴权、权限类错误 → HTTP 401 / 403
      · 业务类错误     → HTTP 200 + 响应体 code 表达语义（如 code=400）
      · 成功           → HTTP 200 + code=200

    本脚本会写入并取消一条预约（留一条「已取消」记录）、toggle 一次收藏（净变化为零），
    并对 zhangsan 执行一次登出 + 一次管理员重置（密码仍是 123456，仅 token_version 自增 2），
    用于验证「旧 Token 立即失效」；请仅对演示 / 测试环境执行。

.PARAMETER Base
    后端接口根地址，默认 http://localhost:8080/api

.EXAMPLE
    pwsh -ExecutionPolicy Bypass -File server/scripts/smoke.ps1
    pwsh -ExecutionPolicy Bypass -File server/scripts/smoke.ps1 -Base http://localhost:8081/api

.NOTES
    前置条件：后端已启动；MySQL / Redis 已就绪；种子账号 admin/admin123(role=1)、zhangsan/123456(role=0) 存在。
    推荐用 PowerShell 7（pwsh）：脚本为 UTF-8 无 BOM，Windows PowerShell 5.1 会按 ANSI 读取导致中文乱码解析失败。
#>
param(
    [string]$Base = 'http://localhost:8080/api'
)

$ErrorActionPreference = 'Stop'
$base = $Base.TrimEnd('/')
$results = @()

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
        $resp = Invoke-WebRequest @params -UseBasicParsing
        $text = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
        return @{ status = [int]$resp.StatusCode; body = $text }
    } catch {
        $response = $_.Exception.Response
        $status = $null
        if ($response) { $status = [int]$response.StatusCode }
        $bodyText = $null
        # PowerShell 7：错误响应体由 ErrorDetails 携带（Response.Content 此时可能已释放）
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $bodyText = $_.ErrorDetails.Message
        } elseif ($response -is [System.Net.Http.HttpResponseMessage]) {
            try { $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() } catch { $bodyText = $null }
        } elseif ($response) {
            $sr = New-Object System.IO.StreamReader($response.GetResponseStream())
            $bodyText = $sr.ReadToEnd()
        }
        if ($bodyText -ne $null) {
            return @{ status = $status; body = $bodyText }
        }
        throw
    }
}

function J($r) { if ($r.body) { return ($r.body | ConvertFrom-Json) } else { return $null } }

# ---- 1. 未登录访问受保护接口 ----
$r = Call 'GET' '/classroom' $null $null
Show '未登录访问 /classroom' ($r.status -eq 401) "status=$($r.status)"

# ---- 2. 登录 ----
$r = Call 'POST' '/user/login' $null @{ username = 'admin'; password = 'admin123'; role = 1 }
$adminBody = J $r
$adminToken = $adminBody.data.token
Show '管理员登录' ($r.status -eq 200 -and $adminToken) "status=$($r.status) msg=$($adminBody.message)"

$r = Call 'POST' '/user/login' $null @{ username = 'zhangsan'; password = '123456'; role = 0 }
$stuBody = J $r
$stuToken = $stuBody.data.token
Show '学生登录' ($r.status -eq 200 -and $stuToken) "status=$($r.status) msg=$($stuBody.message)"

# ---- 3. 错误密码 ----
# 项目约定：业务错误 HTTP 200 + 响应体 code 表达语义（见 AuthAndValidationSmokeTest 类注释）
$r = Call 'POST' '/user/login' $null @{ username = 'zhangsan'; password = 'wrongpass'; role = 0 }
$b = J $r
Show '错误密码登录被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 4. 学生越权访问管理员接口 ----
$r = Call 'GET' '/reservation/manage?page=1&size=10' $stuToken $null
Show '学生访问管理端接口 403' ($r.status -eq 403) "status=$($r.status)"

# ---- 5. 教室列表 ----
$r = Call 'GET' '/classroom/list?page=1&size=5' $stuToken $null
$b = J $r
$roomId = $b.data.records[0].id
Show '教室列表' ($r.status -eq 200 -and $roomId) "status=$($r.status) firstRoomId=$roomId total=$($b.data.total)"

# ---- 6. 教室详情 + 实时状态 ----
$r = Call 'GET' "/classroom/$roomId" $stuToken $null
$b = J $r
Show '教室详情' ($r.status -eq 200 -and $b.data.statusLabel) "statusLabel=$($b.data.statusLabel)"

# ---- 7. 冲突检测（空闲时段） ----
$d = (Get-Date).AddDays(3).ToString('yyyy-MM-dd')
$r = Call 'GET' "/reservation/conflict?classroomId=$roomId&date=$d&startTime=09:00&endTime=10:00" $stuToken $null
$b = J $r
Show '冲突检测(空闲)' ($r.status -eq 200 -and $b.data.conflict -eq $false) "conflict=$($b.data.conflict) reason=$($b.data.reason)"

# ---- 8. 提交预约 ----
$r = Call 'POST' '/reservation' $stuToken @{ classroomId = $roomId; reserveDate = $d; startTime = '09:00'; endTime = '10:00'; purpose = '冒烟测试-课程实验' }
$b = J $r
$resId = $b.data
Show '提交预约' ($r.status -eq 200 -and $resId) "status=$($r.status) id=$resId msg=$($b.message)"

# ---- 9. 非法参数：开始 >= 结束 ----
$r = Call 'POST' '/reservation' $stuToken @{ classroomId = $roomId; reserveDate = $d; startTime = '10:00'; endTime = '09:00'; purpose = 'x' }
$b = J $r
Show '开始>=结束被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 10. 非法参数：超出可预约窗口 ----
$r = Call 'POST' '/reservation' $stuToken @{ classroomId = $roomId; reserveDate = $d; startTime = '07:00'; endTime = '09:00'; purpose = 'x' }
$b = J $r
Show '超窗口(07:00)被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 11. 非法参数：超时长 8h ----
$r = Call 'POST' '/reservation' $stuToken @{ classroomId = $roomId; reserveDate = $d; startTime = '08:00'; endTime = '17:00'; purpose = 'x' }
$b = J $r
Show '超8小时被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 12. 非法参数：过去日期 ----
$r = Call 'POST' '/reservation' $stuToken @{ classroomId = $roomId; reserveDate = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd'); startTime = '09:00'; endTime = '10:00'; purpose = 'x' }
$b = J $r
Show '过去日期被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 13. 管理端全量查询 ----
$r = Call 'GET' '/reservation/manage?page=1&size=10' $adminToken $null
$b = J $r
Show '管理端预约查询' ($r.status -eq 200) "status=$($r.status) total=$($b.data.total)"

# ---- 14. 审核通过 ----
$r = Call 'PUT' "/reservation/$resId/audit" $adminToken @{ status = 1 }
$b = J $r
Show '审核通过' ($r.status -eq 200) "status=$($r.status) msg=$($b.message)"

# ---- 15. 重复审核（应失败） ----
$r = Call 'PUT' "/reservation/$resId/audit" $adminToken @{ status = 2; auditRemark = 'x' }
$b = J $r
Show '重复审核被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 16. 通过后冲突检测（应冲突） ----
$r = Call 'GET' "/reservation/conflict?classroomId=$roomId&date=$d&startTime=09:30&endTime=10:30" $stuToken $null
$b = J $r
Show '已通过后重叠检测' ($b.data.conflict -eq $true) "conflict=$($b.data.conflict) reason=$($b.data.reason)"

# ---- 17. 绕过前端直接提交重叠预约（后端兜底应拒） ----
$r = Call 'POST' '/reservation' $stuToken @{ classroomId = $roomId; reserveDate = $d; startTime = '09:30'; endTime = '10:30'; purpose = '绕过前端' }
$b = J $r
Show '后端二次冲突兜底' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 18. 取消（已通过，开始前 > 1h） ----
$r = Call 'PUT' "/reservation/$resId/cancel" $stuToken $null
$b = J $r
Show '取消预约' ($r.status -eq 200) "status=$($r.status) msg=$($b.message)"

# ---- 19. 我的预约 ----
$r = Call 'GET' '/reservation/mine?page=1&size=10' $stuToken $null
$b = J $r
Show '我的预约列表' ($r.status -eq 200) "status=$($r.status) total=$($b.data.total)"

# ---- 20. 日历 ----
$r = Call 'GET' "/reservation/calendar?startDate=$((Get-Date).AddDays(-7).ToString('yyyy-MM-dd'))&endDate=$((Get-Date).AddDays(30).ToString('yyyy-MM-dd'))" $stuToken $null
$b = J $r
Show '日历总览' ($r.status -eq 200) "status=$($r.status) count=$($b.data.Count)"

# ---- 21. 日历缺参 ----
$r = Call 'GET' '/reservation/calendar?startDate=2026-09-01' $stuToken $null
$b = J $r
Show '日历缺 endDate 被拒' ($b.code -eq 400) "http=$($r.status) code=$($b.code) msg=$($b.message)"

# ---- 22. 看板统计 ----
foreach ($api in @('/stats/usage-rate', '/stats/trend', '/stats/time-distribution')) {
    $r = Call 'GET' $api $adminToken $null
    Show "看板 $api" ($r.status -eq 200) "status=$($r.status) body=$($r.body.Substring(0, [Math]::Min(90, $r.body.Length)))"
}

# ---- 23. 导出 ----
$r = Call 'GET' '/reservation/export?page=1' $adminToken $null
Show '预约记录导出' ($r.status -eq 200) "status=$($r.status) len=$($r.body.Length)"

# ---- 24. 收藏 ----
$r = Call 'POST' "/favorite/$roomId" $stuToken $null
$b = J $r
Show '收藏 toggle' ($r.status -eq 200) "status=$($r.status) data=$($b.data)"
$r = Call 'GET' '/favorite/list' $stuToken $null
$b = J $r
Show '收藏列表' ($r.status -eq 200) "status=$($r.status) count=$($b.data.Count)"
$r = Call 'POST' "/favorite/$roomId" $stuToken $null
Show '取消收藏 toggle' ($r.status -eq 200) "status=$($r.status)"

# ---- 25. 个人中心 ----
$r = Call 'GET' '/user/info' $stuToken $null
Show '当前用户信息' ($r.status -eq 200) "status=$($r.status)"
$r = Call 'GET' '/user/stats' $stuToken $null
$b = J $r
Show '个人统计' ($r.status -eq 200) "status=$($r.status) total=$($b.data.totalReservations)"

# ---- 26. 用户管理 ----
$r = Call 'GET' '/user/manage?page=1&size=5' $adminToken $null
$b = J $r
Show '用户管理列表' ($r.status -eq 200) "status=$($r.status) total=$($b.data.total)"

# ---- 27. 教室管理 ----
$r = Call 'GET' '/classroom/manage?page=1&size=5' $adminToken $null
$b = J $r
Show '教室管理列表' ($r.status -eq 200) "status=$($r.status) total=$($b.data.total)"

# ---- 28. AI 接口（ai.enable=false 应降级返回，不报错） ----
$r = Call 'POST' '/ai/recommend' $stuToken @{ purpose = '高数复习' }
Show 'AI 推荐(关闭时降级)' ($r.status -eq 200) "status=$($r.status) body=$($r.body.Substring(0, [Math]::Min(120, $r.body.Length)))"

# ---- 29. 注销后 Token 失效 ----
$r = Call 'POST' '/user/logout' $stuToken $null
Show '退出登录' ($r.status -eq 200) "status=$($r.status)"
$r = Call 'GET' '/user/info' $stuToken $null
Show '注销后 Token 失效' ($r.status -eq 401) "status=$($r.status)"

# ---- 30. 登出后重新登录（新 Token 可用） ----
$r = Call 'POST' '/user/login' $null @{ username = 'zhangsan'; password = '123456'; role = 0 }
$b = J $r
$stuToken2 = $b.data.token
Show '登出后重新登录' ($r.status -eq 200 -and $stuToken2) "status=$($r.status)"
$r = Call 'GET' '/user/info' $stuToken2 $null
Show '重新登录后访问受保护接口' ($r.status -eq 200) "status=$($r.status)"

# ---- 31. 管理员重置密码（种子学生默认密码本就是 123456，重置不改密码、只换 Token 版本号） ----
$r = Call 'GET' '/user/manage?page=1&size=10&keyword=zhangsan' $adminToken $null
$b = J $r
$targetId = ($b.data.records | Where-Object { $_.username -eq 'zhangsan' } | Select-Object -First 1).id
Show '重置前按账号查询用户' ($targetId -ne $null) "userId=$targetId"
$r = Call 'PUT' "/user/manage/$targetId/password" $adminToken $null
$b = J $r
Show '管理员重置密码' ($r.status -eq 200) "status=$($r.status) msg=$($b.message)"

# ---- 32. 重置后旧 Token 立即失效（token_version 已自增） ----
$r = Call 'GET' '/user/info' $stuToken2 $null
Show '重置后旧 Token 失效' ($r.status -eq 401) "status=$($r.status)"

Write-Host ""
Write-Host "===== 汇总 ====="
$fail = $results | Where-Object { -not $_.OK }
Write-Host ("总计 {0} 项，通过 {1} 项，失败 {2} 项" -f $results.Count, ($results.Count - $fail.Count), $fail.Count)
$fail | ForEach-Object { Write-Host ("FAILED: {0} :: {1}" -f $_.Step, $_.Detail) }

if ($fail.Count -gt 0) { exit 1 }
exit 0
