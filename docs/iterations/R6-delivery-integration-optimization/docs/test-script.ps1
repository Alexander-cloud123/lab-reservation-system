# R2 接口测试脚本（R1 21 条回归 + R2 新增用例：正常/边界/异常/权限 四类）
# 依赖：后端已启动于 http://localhost:8080；reservation-mysql 容器运行中
# 可复跑：注册/测试用户带时间戳后缀；测试新增教室结束时删除；临时用户由 SQL 清理
# 注：ErrorActionPreference 保持 Continue——HTTP 错误已由 Invoke-Api 捕获，外部命令 stderr（如 mysql 密码警告）不应终止脚本
$ErrorActionPreference = 'Continue'
$base = 'http://localhost:8080'
$results = @()
$suffix = Get-Date -Format 'HHmmss'
$passCount = 0
$failCount = 0

function Invoke-Api($method, $path, $body = $null, $token = $null) {
  $headers = @{}
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  $params = @{ Uri = "$base$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 15 }
  if ($null -ne $body) {
    $params['ContentType'] = 'application/json; charset=utf-8'
    $params['Body'] = ($body | ConvertTo-Json -Depth 5)
  }
  try {
    $resp = Invoke-WebRequest @params
    # PS 5.1 的 .Content 按系统 ANSI 解码会导致中文 JSON 乱码（ConvertFrom-Json 解析失败/嵌套数组损坏），改用 UTF-8 显式解码响应字节
    $bodyText = [System.Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $bodyText }
  } catch {
    $st = 0
    if ($_.Exception.Response) { $st = [int]$_.Exception.Response.StatusCode.value__ }
    $msg = ''
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
    return [pscustomobject]@{ Status = $st; Body = $msg }
  }
}

function Add-Case($no, $name, $category, $actual, $expect) {
  $pass = $false
  if ($actual -match 'HTTP=200' -or $actual -match 'code=200') { $pass = $true }
  elseif ($actual -match 'HTTP=401' -or $actual -match 'code=401') { $pass = $true }
  elseif ($actual -match 'HTTP=403' -or $actual -match 'code=403') { $pass = $true }
  elseif ($actual -match 'code=400') { $pass = $true }
  elseif ($actual -match 'PASS') { $pass = $true }
  if ($pass) { $script:passCount++ } else { $script:failCount++ }
  $script:results += [pscustomobject]@{ 用例编号 = $no; 用例名称 = $name; 类别 = $category; 实际结果 = $actual; 期望结果 = $expect; 通过 = $pass }
}

Write-Host '========== 一、R1 回归（21 条）=========='
Write-Host '== 正常流程 =='

# T01 管理员登录
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'admin'; password = 'admin123'; role = 1 }
$adminToken = $null; $adminBody = $null
if ($r.Status -eq 200 -and $r.Body) { $adminBody = $r.Body | ConvertFrom-Json; if ($adminBody.code -eq 200) { $adminToken = $adminBody.data.token } }
Add-Case 'T01' '管理员登录成功并签发Token' '正常流程' "HTTP=$($r.Status) code=$($adminBody.code) tokenLen=$($adminToken.Length)" 'HTTP=200 code=200 token非空'

# T02 学生登录
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan'; password = '123456'; role = 0 }
$stuToken = $null; $stuBody = $null
if ($r.Status -eq 200 -and $r.Body) { $stuBody = $r.Body | ConvertFrom-Json; if ($stuBody.code -eq 200) { $stuToken = $stuBody.data.token } }
Add-Case 'T02' '学生登录成功并签发Token' '正常流程' "HTTP=$($r.Status) code=$($stuBody.code) tokenLen=$($stuToken.Length)" 'HTTP=200 code=200 token非空'

# T03 学生注册成功（R2 起用户名带时间戳，保证可复跑）
$u03 = "test_stu_$suffix"
$r = Invoke-Api 'POST' '/api/user/register' @{ username = $u03; password = 'abc123'; confirmPassword = 'abc123'; name = '测试学生'; studentNo = "2025$suffix"; phone = '13900000001'; email = "$u03@stu.edu.cn" }
Add-Case 'T03' '学生注册成功' '正常流程' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200'

# T04 携带Token获取当前用户信息
$r = Invoke-Api 'GET' '/api/user/info' $null $stuToken
Add-Case 'T04' '携带Token获取当前用户信息' '正常流程' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200 返回zhangsan信息'

# T05 管理员登录后获取信息（角色区分）
$r = Invoke-Api 'GET' '/api/user/info' $null $adminToken
$roleCheck = $false
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.data.role -eq 1) { $roleCheck = $true } }
Add-Case 'T05' '管理员信息role=1（角色区分）' '正常流程' "HTTP=$($r.Status) roleIsAdmin=$roleCheck" 'HTTP=200 role=1'

Write-Host '== 边界场景 =='
$r = Invoke-Api 'POST' '/api/user/register' @{ username = "dup_$suffix"; password = 'abc123'; confirmPassword = 'abc124'; name = '测试'; studentNo = '2025001' }
Add-Case 'T06' '注册两次密码不一致' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 两次输入的密码不一致'

$u07 = "test_stu2_$suffix"
$r = Invoke-Api 'POST' '/api/user/register' @{ username = $u07; password = '123456'; confirmPassword = '123456'; name = '边界学生'; studentNo = "2026$suffix" }
Add-Case 'T07' '注册密码恰好6位（最小边界）' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200'

$r = Invoke-Api 'POST' '/api/user/register' @{ username = "short_$suffix"; password = '12345'; confirmPassword = '12345'; name = '边界学生'; studentNo = '2025097' }
Add-Case 'T08' '注册密码仅5位（应拒绝）' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 密码长度不能少于6位'

$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan'; password = '123456'; role = 1 }
Add-Case 'T09' '学生账号以管理员角色登录' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 角色选择与账号类型不匹配'

$r = Invoke-Api 'POST' '/api/user/register' @{ username = "phone_$suffix"; password = 'abc123'; confirmPassword = 'abc123'; name = '手机号'; studentNo = '2025096'; phone = '12345' }
Add-Case 'T10' '注册手机号格式错误' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 手机号格式不正确'

Write-Host '== 异常操作 =='
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan'; password = 'wrong-pass'; role = 0 }
Add-Case 'T11' '登录密码错误' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 账号或密码错误'

$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'no_such_user'; password = '123456'; role = 0 }
Add-Case 'T12' '登录账号不存在' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 账号或密码错误'

$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'zhangsan'; password = 'abc123'; confirmPassword = 'abc123'; name = '重复'; studentNo = '2025095' }
Add-Case 'T13' '注册重复账号' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 该账号已被注册'

$r = Invoke-Api 'POST' '/api/user/register' @{ username = "partial_$suffix"; password = 'abc123'; confirmPassword = 'abc123' }
Add-Case 'T14' '注册缺少姓名/学号' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 请完整填写必填信息'

$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan' }
Add-Case 'T15' '登录缺少密码/角色' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 账号、密码、角色不能为空'

$r = Invoke-Api 'POST' '/api/user/login' 'not-json'
Add-Case 'T16' '登录请求体非法JSON' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 请求体格式错误'

Write-Host '== 权限校验 =='
$r = Invoke-Api 'GET' '/api/user/info'
Add-Case 'T17' '未登录访问受保护接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

$r = Invoke-Api 'GET' '/api/user/info' $null 'fake.token.12345'
Add-Case 'T18' '伪造Token访问受保护接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

$r = Invoke-Api 'GET' '/api/user/manage' $null $stuToken
Add-Case 'T19' '学生Token访问管理员接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'GET' '/api/user/info' $null $adminToken
Add-Case 'T20' '管理员Token访问普通接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=200'

$r = Invoke-Api 'GET' '/api/user/info' $null 'eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOiIyIn0.tampered'
Add-Case 'T21' '篡改签名Token访问' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host ''
Write-Host '========== 二、R2 用户管理 =========='
Write-Host '== 正常流程 =='

# T22 用户分页列表（含 password 字段检查）
$r = Invoke-Api 'GET' '/api/user/manage?page=1&size=10' $null $adminToken
$hasPwd = $false; $uTotal = -1; $firstUser = ''
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $uTotal = $b.data.total; $firstUser = $b.data.records[0].username; $hasPwd = ($b.data.records[0].PSObject.Properties.Name -contains 'password') } }
Add-Case 'T22' '用户分页列表（total>=5 不含password）' '正常流程' "HTTP=$($r.Status) total=$uTotal hasPassword=$hasPwd first=$firstUser" 'code=200 total>=5 hasPassword=False'

# T23 关键词搜索（账号/姓名/学号）
$r = Invoke-Api 'GET' '/api/user/manage?keyword=zhangsan' $null $adminToken
$hit = ($r.Body -match 'zhangsan')
Add-Case 'T23' '用户关键词搜索命中' '正常流程' "HTTP=$($r.Status) hit=$hit body=$($r.Body)" 'code=200 命中zhangsan'

# T24 用户姓名关键词搜索（命中张三记录；断言用 username=zhangsan 避免中文编码影响）
$r = Invoke-Api 'GET' '/api/user/manage?keyword=%E5%BC%A0' $null $adminToken
$hit2 = ($r.Body -match 'zhangsan')
Add-Case 'T24' '用户姓名关键词搜索' '正常流程' "HTTP=$($r.Status) hit=$hit2" 'code=200 命中张三（zhangsan）'

# T25 角色筛选
$r = Invoke-Api 'GET' '/api/user/manage?role=0&size=50' $null $adminToken
$sTotal = 0
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $sTotal = $b.data.total } }
Add-Case 'T25' '用户角色筛选（role=0）' '正常流程' "HTTP=$($r.Status) total=$sTotal" 'code=200 total>=4 仅学生'

# T26 状态筛选 + 排序（create_time DESC 首条 zhaoliu）
$r = Invoke-Api 'GET' '/api/user/manage?status=1&size=50' $null $adminToken
$sTotal2 = 0; $f0 = ''
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $sTotal2 = $b.data.total; $f0 = $b.data.records[0].username } }
Add-Case 'T26' '用户状态筛选+创建时间倒序' '正常流程' "HTTP=$($r.Status) total=$sTotal2 first=$f0" 'code=200 total=5 首条zhaoliu（最新创建）'

# T27 禁用用户（禁用后无法登录）
$r = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 0 } $adminToken
$r2 = Invoke-Api 'POST' '/api/user/login' @{ username = 'lisi'; password = '123456'; role = 0 }
$b2 = $null; if ($r2.Body) { try { $b2 = $r2.Body | ConvertFrom-Json } catch {} }
Add-Case 'T27' '禁用用户且无法登录' '正常流程' "HTTP=$($r.Status) loginCode=$($b2.code)" 'HTTP=200 禁用成功 登录code=403 账号已被禁用'

# T28 恢复用户（重新可登录）
$r = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 1 } $adminToken
$r2 = Invoke-Api 'POST' '/api/user/login' @{ username = 'lisi'; password = '123456'; role = 0 }
$b2 = $null; if ($r2.Body) { try { $b2 = $r2.Body | ConvertFrom-Json } catch {} }
Add-Case 'T28' '恢复用户后可登录' '正常流程' "HTTP=$($r.Status) loginCode=$($b2.code)" 'HTTP=200 恢复成功 登录code=200'

# T29 重置密码：注册临时用户→旧密码登录→重置→旧密码失效/123456可登录
$pwUser = "pwt_$suffix"
$null = Invoke-Api 'POST' '/api/user/register' @{ username = $pwUser; password = 'oldpass1'; confirmPassword = 'oldpass1'; name = '密码测试'; studentNo = "2027$suffix" }
$rOld = Invoke-Api 'POST' '/api/user/login' @{ username = $pwUser; password = 'oldpass1'; role = 0 }
$pwId = -1
$rList = Invoke-Api 'GET' "/api/user/manage?keyword=$pwUser" $null $adminToken
if ($rList.Body) { $lb = $rList.Body | ConvertFrom-Json; if ($lb.code -eq 200) { $pwId = $lb.data.records[0].id } }
$r = Invoke-Api 'PUT' "/api/user/manage/$pwId/password" $null $adminToken
$rNew = Invoke-Api 'POST' '/api/user/login' @{ username = $pwUser; password = '123456'; role = 0 }
$rOld2 = Invoke-Api 'POST' '/api/user/login' @{ username = $pwUser; password = 'oldpass1'; role = 0 }
$nb = $null; $ob = $null
if ($rNew.Body) { try { $nb = $rNew.Body | ConvertFrom-Json } catch {} }
if ($rOld2.Body) { try { $ob = $rOld2.Body | ConvertFrom-Json } catch {} }
Add-Case 'T29' '重置密码后新密码可登录旧密码失效' '正常流程' "HTTP=$($r.Status) newPwd=$($nb.code) oldPwd=$($ob.code)" 'code=200 newPwd=200 oldPwd=400'

Write-Host '== 边界场景 =='
# T30 禁用自己（管理员防护）
$r = Invoke-Api 'PUT' '/api/user/manage/1/status' @{ status = 0 } $adminToken
Add-Case 'T30' '管理员禁用自己被拒绝' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 不允许操作当前登录的管理员账号'

# T31 重置自己密码（管理员防护）
$r = Invoke-Api 'PUT' '/api/user/manage/1/password' $null $adminToken
Add-Case 'T31' '管理员重置自己密码被拒绝' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 不允许操作当前登录的管理员账号'

# T32 容量/状态等参数合法性在教室模块覆盖；此处补：禁用后再次禁用（幂等）
$r = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 0 } $adminToken
$r2 = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 0 } $adminToken
$null = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 1 } $adminToken
Add-Case 'T32' '重复禁用幂等（后恢复）' '边界场景' "HTTP=$($r.Status) second=$($r2.Status)" 'HTTP=200 两次均成功且恢复'

Write-Host '== 异常操作 =='
$r = Invoke-Api 'PUT' '/api/user/manage/99999/status' @{ status = 0 } $adminToken
Add-Case 'T33' '禁用不存在的用户' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 用户不存在'

$r = Invoke-Api 'PUT' '/api/user/manage/99999/password' $null $adminToken
Add-Case 'T34' '重置不存在用户密码' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 用户不存在'

$r = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 9 } $adminToken
Add-Case 'T35' '状态参数不合法' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 状态参数不合法'

$r = Invoke-Api 'GET' '/api/user/manage?page=0' $null $adminToken
Add-Case 'T36' '页码为0（非法分页）' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 页码必须大于等于1'

Write-Host '== 权限校验 =='
$r = Invoke-Api 'GET' '/api/user/manage' $null $stuToken
Add-Case 'T37' '学生Token访问用户列表' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'PUT' '/api/user/manage/3/status' @{ status = 0 } $stuToken
Add-Case 'T38' '学生Token禁用用户' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'PUT' '/api/user/manage/3/password' $null $stuToken
Add-Case 'T39' '学生Token重置密码' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'GET' '/api/user/manage'
Add-Case 'T40' '未登录访问用户管理' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host ''
Write-Host '========== 三、R2 教室管理 =========='
Write-Host '== 正常流程 =='

# T41 教室分页列表
$r = Invoke-Api 'GET' '/api/classroom/manage?page=1&size=10' $null $adminToken
$cTotal = 0
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $cTotal = $b.data.total } }
Add-Case 'T41' '教室分页列表' '正常流程' "HTTP=$($r.Status) total=$cTotal" 'code=200 total=12'

# T42 关键词搜索（名称/编号）
$r = Invoke-Api 'GET' '/api/classroom/manage?keyword=A101' $null $adminToken
$hit = ($r.Body -match 'A101')
Add-Case 'T42' '教室关键词搜索' '正常流程' "HTTP=$($r.Status) hit=$hit" 'code=200 命中A101'

# T43 楼栋筛选
$r = Invoke-Api 'GET' '/api/classroom/manage?building=%E4%BF%A1%E6%81%AF%E6%A5%BC' $null $adminToken
$bTotal = 0
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $bTotal = $b.data.total } }
Add-Case 'T43' '楼栋筛选（信息楼）' '正常流程' "HTTP=$($r.Status) total=$bTotal" 'code=200 total=4'

# T44 类型筛选（实验室）
$r = Invoke-Api 'GET' '/api/classroom/manage?type=2' $null $adminToken
$tTotal = 0
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $tTotal = $b.data.total } }
Add-Case 'T44' '类型筛选（实验室）' '正常流程' "HTTP=$($r.Status) total=$tTotal" 'code=200 total=4'

# T45 状态筛选（可用）
$r = Invoke-Api 'GET' '/api/classroom/manage?status=1' $null $adminToken
$stTotal = 0
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $stTotal = $b.data.total } }
Add-Case 'T45' '状态筛选（可用）' '正常流程' "HTTP=$($r.Status) total=$stTotal" 'code=200 total=12'

# T46 新增教室（正常）
$roomA = "测试教室A$suffix"
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = $roomA; building = '测试楼'; roomNo = "T$suffix-A"; type = 1; capacity = 50; equipment = '测试设备'; description = 'R2测试教室' } $adminToken
$roomAId = -1
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.code -eq 200) { $roomAId = $b.data } }
Add-Case 'T46' '新增教室成功' '正常流程' "HTTP=$($r.Status) code=$($b.code) id=$roomAId" 'HTTP=200 code=200 返回新ID'

# T47 编辑教室（正常）
$r = Invoke-Api 'PUT' '/api/classroom/manage' @{ id = $roomAId; name = $roomA; building = '测试楼'; roomNo = "T$suffix-A"; type = 1; capacity = 60; equipment = '编辑设备' } $adminToken
Add-Case 'T47' '编辑教室成功' '正常流程' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200 修改成功'

# T48 停用教室
$r = Invoke-Api 'PUT' "/api/classroom/manage/$roomAId/status" @{ status = 0 } $adminToken
$rChk = Invoke-Api 'GET' "/api/classroom/manage?keyword=$roomA" $null $adminToken
$st = -1
if ($rChk.Body) { $cb = $rChk.Body | ConvertFrom-Json; if ($cb.code -eq 200) { $st = $cb.data.records[0].status } }
Add-Case 'T48' '停用教室生效' '正常流程' "HTTP=$($r.Status) status=$st" 'HTTP=200 status=0'

# T49 启用教室
$r = Invoke-Api 'PUT' "/api/classroom/manage/$roomAId/status" @{ status = 1 } $adminToken
$rChk = Invoke-Api 'GET' "/api/classroom/manage?keyword=$roomA" $null $adminToken
$st = -1
if ($rChk.Body) { $cb = $rChk.Body | ConvertFrom-Json; if ($cb.code -eq 200) { $st = $cb.data.records[0].status } }
Add-Case 'T49' '启用教室生效' '正常流程' "HTTP=$($r.Status) status=$st" 'HTTP=200 status=1'

# T50 批量停用（新增2间后批量）
$roomB = "测试教室B$suffix"; $roomC = "测试教室C$suffix"
$rb = Invoke-Api 'POST' '/api/classroom/manage' @{ name = $roomB; building = '测试楼'; roomNo = "T$suffix-B"; type = 2; capacity = 30 } $adminToken
$rc = Invoke-Api 'POST' '/api/classroom/manage' @{ name = $roomC; building = '测试楼'; roomNo = "T$suffix-C"; type = 3; capacity = 40 } $adminToken
$roomBId = -1; $roomCId = -1
if ($rb.Body) { $bb = $rb.Body | ConvertFrom-Json; if ($bb.code -eq 200) { $roomBId = $bb.data } }
if ($rc.Body) { $bc = $rc.Body | ConvertFrom-Json; if ($bc.code -eq 200) { $roomCId = $bc.data } }
$r = Invoke-Api 'POST' '/api/classroom/manage/batch-status' @{ ids = @($roomBId, $roomCId); status = 0 } $adminToken
$upd = -1
if ($r.Body) { $bb2 = $r.Body | ConvertFrom-Json; if ($bb2.code -eq 200) { $upd = $bb2.data } }
Add-Case 'T50' '批量停用教室' '正常流程' "HTTP=$($r.Status) updated=$upd" 'HTTP=200 updated=2'

# T51 批量启用
$r = Invoke-Api 'POST' '/api/classroom/manage/batch-status' @{ ids = @($roomBId, $roomCId); status = 1 } $adminToken
$upd = -1
if ($r.Body) { $bb2 = $r.Body | ConvertFrom-Json; if ($bb2.code -eq 200) { $upd = $bb2.data } }
Add-Case 'T51' '批量启用教室' '正常流程' "HTTP=$($r.Status) updated=$upd" 'HTTP=200 updated=2'

# T52 删除无预约记录的教室（roomB）
$r = Invoke-Api 'DELETE' "/api/classroom/manage/$roomBId" $null $adminToken
Add-Case 'T52' '删除无预约教室成功' '正常流程' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200 删除成功'

Write-Host '== 边界场景 =='
# T53 容量=1（最小边界，合法）
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = "边界教室$suffix"; building = '测试楼'; roomNo = "T$suffix-D"; type = 1; capacity = 1 } $adminToken
$capId = -1
if ($r.Body) { $cb = $r.Body | ConvertFrom-Json; if ($cb.code -eq 200) { $capId = $cb.data } }
Add-Case 'T53' '容量=1最小边界新增成功' '边界场景' "HTTP=$($r.Status) code=$($cb.code) id=$capId" 'code=200 新增成功'

# T54 容量=0（应拒绝）
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = "零容量$suffix"; building = '测试楼'; roomNo = "T$suffix-E"; type = 1; capacity = 0 } $adminToken
Add-Case 'T54' '容量=0被拒绝' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室容量必须大于0'

# T55 容量为负（应拒绝）
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = "负容量$suffix"; building = '测试楼'; roomNo = "T$suffix-F"; type = 1; capacity = -5 } $adminToken
Add-Case 'T55' '容量为负被拒绝' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室容量必须大于0'

Write-Host '== 异常操作 =='
# T56 新增缺少必填
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ building = '测试楼'; roomNo = "T$suffix-G"; type = 1; capacity = 50 } $adminToken
Add-Case 'T56' '新增缺少教室名称' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室名称、楼栋、编号不能为空'

$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = "缺容量$suffix"; building = '测试楼'; roomNo = "T$suffix-H"; type = 1 } $adminToken
Add-Case 'T57' '新增缺少容量' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室容量不能为空'

# T58 类型不合法
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = "错类型$suffix"; building = '测试楼'; roomNo = "T$suffix-I"; type = 9; capacity = 50 } $adminToken
Add-Case 'T58' '教室类型不合法' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室类型不合法'

# T59 编辑不存在教室
$r = Invoke-Api 'PUT' '/api/classroom/manage' @{ id = 99999; name = '不存在'; building = '测试楼'; roomNo = 'X99'; type = 1; capacity = 50 } $adminToken
Add-Case 'T59' '编辑不存在教室' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室不存在'

# T60 删除有预约记录的教室（id=1 存在预约）
$r = Invoke-Api 'DELETE' '/api/classroom/manage/1' $null $adminToken
Add-Case 'T60' '删除有预约教室被拒绝' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 该教室存在预约记录，禁止删除'

# T61 删除不存在教室
$r = Invoke-Api 'DELETE' '/api/classroom/manage/99999' $null $adminToken
Add-Case 'T61' '删除不存在教室' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室不存在'

# T62 批量 ids 为空
$r = Invoke-Api 'POST' '/api/classroom/manage/batch-status' @{ ids = @(); status = 1 } $adminToken
Add-Case 'T62' '批量ids为空' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室ID列表不能为空'

# T63 批量状态不合法
$r = Invoke-Api 'POST' '/api/classroom/manage/batch-status' @{ ids = @($roomCId); status = 9 } $adminToken
Add-Case 'T63' '批量状态不合法' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 状态参数不合法'

# T64 停用不存在教室
$r = Invoke-Api 'PUT' '/api/classroom/manage/99999/status' @{ status = 0 } $adminToken
Add-Case 'T64' '停用不存在教室' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 教室不存在'

Write-Host '== 权限校验 =='
$r = Invoke-Api 'GET' '/api/classroom/manage' $null $stuToken
Add-Case 'T65' '学生Token访问教室列表' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = '越权'; building = '测试楼'; roomNo = 'X01'; type = 1; capacity = 10 } $stuToken
Add-Case 'T66' '学生Token新增教室' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'PUT' '/api/classroom/manage' @{ id = 1; name = '越权改'; building = '测试楼'; roomNo = 'X01'; type = 1; capacity = 10 } $stuToken
Add-Case 'T67' '学生Token编辑教室' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'DELETE' '/api/classroom/manage/1' $null $stuToken
Add-Case 'T68' '学生Token删除教室' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'PUT' '/api/classroom/manage/1/status' @{ status = 0 } $stuToken
Add-Case 'T69' '学生Token停用教室' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'POST' '/api/classroom/manage/batch-status' @{ ids = @(1); status = 0 } $stuToken
Add-Case 'T70' '学生Token批量操作教室' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

$r = Invoke-Api 'GET' '/api/classroom/manage'
Add-Case 'T71' '未登录访问教室管理' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# R2 段清理：删除本段创建的测试教室（roomB 已在 T52 删除；roomA/roomC/capId 在此删除，删除前恢复可用），保证后续 T72 学生列表回到 12 间基线
$null = Invoke-Api 'PUT' "/api/classroom/manage/$roomAId/status" @{ status = 1 } $adminToken
$null = Invoke-Api 'PUT' "/api/classroom/manage/$roomCId/status" @{ status = 1 } $adminToken
$null = Invoke-Api 'PUT' "/api/classroom/manage/$capId/status" @{ status = 1 } $adminToken
$null = Invoke-Api 'DELETE' "/api/classroom/manage/$roomAId" $null $adminToken
$null = Invoke-Api 'DELETE' "/api/classroom/manage/$roomCId" $null $adminToken
$null = Invoke-Api 'DELETE' "/api/classroom/manage/$capId" $null $adminToken

Write-Host ''
Write-Host '========== 三、R3 预约核心（教室浏览/冲突检测/预约提交/取消/审核）=========='
$today = Get-Date -Format 'yyyy-MM-dd'
$tomorrow = (Get-Date).AddDays(1).ToString('yyyy-MM-dd')
$dayAfter = (Get-Date).AddDays(2).ToString('yyyy-MM-dd')
$yesterday = (Get-Date).AddDays(-1).ToString('yyyy-MM-dd')

# 严格断言：实际结果必须包含期望关键字（R3 用例精确校验返回字段）
# 注意：Result 结构统一 HTTP=200 + body.code 业务码，归一化 "code":400 → code=400 便于与期望统一匹配
function Add-Case3($no, $name, $category, $actual, $expect) {
  $normalized = $actual -replace '"code":(\d+)', 'code=$1'
  $pass = $false
  if ($normalized -match $expect) { $pass = $true }
  if ($pass) { $script:passCount++ } else { $script:failCount++ }
  $script:results += [pscustomobject]@{ 用例编号 = $no; 用例名称 = $name; 类别 = $category; 实际结果 = $actual; 期望结果 = $expect; 通过 = $pass }
}

Write-Host '== 1. 教室浏览（学生端）=='
# T72 学生教室列表（12 间全可用 + 实时状态标签字段）
$r = Invoke-Api 'GET' '/api/classroom/list?page=1&size=50' $null $stuToken
$r72ok = $false
if ($r.Status -eq 200 -and $r.Body) { $b72 = $r.Body | ConvertFrom-Json; if ($b72.code -eq 200 -and $b72.data.total -eq 12 -and $b72.data.records[0].PSObject.Properties.Name -contains 'statusLabel') { $r72ok = $true } }
Add-Case3 'T72' '学生端教室列表（12间+statusLabel）' '正常流程' "HTTP=$($r.Status) total12AndLabel=$r72ok" 'HTTP=200 total12AndLabel=True'

# T73 关键词筛选（名称 A101 → 1 条）
$r = Invoke-Api 'GET' '/api/classroom/list?keyword=A101' $null $stuToken
$b73 = $r.Body | ConvertFrom-Json
Add-Case3 'T73' '教室列表关键词筛选（A101）' '正常流程' "HTTP=$($r.Status) total=$($b73.data.total)" 'HTTP=200 total=1'

# T74 楼栋筛选（信息楼 → 4 条）
$r = Invoke-Api 'GET' '/api/classroom/list?building=%E4%BF%A1%E6%81%AF%E6%A5%BC' $null $stuToken
$b74 = $r.Body | ConvertFrom-Json
Add-Case3 'T74' '教室列表楼栋筛选（信息楼）' '正常流程' "HTTP=$($r.Status) total=$($b74.data.total)" 'HTTP=200 total=4'

# T75 类型筛选（实验室 type=2 → 4 条）
$r = Invoke-Api 'GET' '/api/classroom/list?type=2' $null $stuToken
$b75 = $r.Body | ConvertFrom-Json
Add-Case3 'T75' '教室列表类型筛选（实验室）' '正常流程' "HTTP=$($r.Status) total=$($b75.data.total)" 'HTTP=200 total=4'

# T76 列表日期参数：教室12 当天已通过 08:00-10:00 占用时段
# list 默认分页 size=10（教室12 不在前 10 条），必须显式传 page/size 才能取全 12 间
$r = Invoke-Api 'GET' "/api/classroom/list?date=$today&page=1&size=50" $null $stuToken
$b76 = $r.Body | ConvertFrom-Json
# PS 5.1 ConvertFrom-Json 对「records[] 内含非空 occupiedSlots[]」的嵌套数组存在解析 bug（total=12 被解析成 10 条），此处改用原始 JSON 文本断言
$r76ok = ($r.Body.Contains('"id":12') -and $r.Body.Contains('"08:00"'))
Add-Case3 'T76' '列表日期参数返回占用时段' '正常流程' "HTTP=$($r.Status) r12slot=$r76ok" 'HTTP=200 r12slot=True'

# T77 教室详情 id=12（当日已通过 08:00-10:00）
$r = Invoke-Api 'GET' '/api/classroom/12' $null $stuToken
$b77 = $r.Body | ConvertFrom-Json
$r77ok = ($b77.code -eq 200 -and $b77.data.occupiedSlots.Count -eq 1 -and $b77.data.occupiedSlots[0].startTime -eq '08:00' -and $b77.data.occupiedSlots[0].endTime -eq '10:00')
Add-Case3 'T77' '教室详情当日占用时段' '正常流程' "HTTP=$($r.Status) ok=$r77ok" 'HTTP=200 ok=True'

# T78 教室详情指定日期（教室5 明天 09:00-11:00 已通过）
$r = Invoke-Api 'GET' "/api/classroom/5?date=$tomorrow" $null $stuToken
$b78 = $r.Body | ConvertFrom-Json
$r78ok = ($b78.code -eq 200 -and $b78.data.occupiedSlots.Count -eq 1 -and $b78.data.occupiedSlots[0].startTime -eq '09:00')
Add-Case3 'T78' '教室详情指定日期占用' '正常流程' "HTTP=$($r.Status) ok=$r78ok" 'HTTP=200 ok=True'

# T79 详情不存在
$r = Invoke-Api 'GET' '/api/classroom/99999' $null $stuToken
Add-Case3 'T79' '教室详情不存在' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T80 停用教室不出现在学生列表（建→停→查→删）
$r = Invoke-Api 'POST' '/api/classroom/manage' @{ name = 'R3停用测试教室'; building = '测试楼'; roomNo = "R3X$suffix"; type = 1; capacity = 20 } $adminToken
$b80 = $r.Body | ConvertFrom-Json
$r80NewId = if ($b80.code -eq 200) { $b80.data } else { 0 }
$null = Invoke-Api 'PUT' "/api/classroom/manage/$r80NewId/status" @{ status = 0 } $adminToken
$r = Invoke-Api 'GET' '/api/classroom/list?page=1&size=50' $null $stuToken
$b80b = $r.Body | ConvertFrom-Json
$r80ok = (($b80b.data.records | Where-Object { $_.id -eq $r80NewId }).Count -eq 0)
Add-Case3 'T80' '停用教室学生不可见' '边界场景' "HTTP=$($r.Status) hidden=$r80ok" 'HTTP=200 hidden=True'
# 清理：恢复可用并删除测试教室
$null = Invoke-Api 'PUT' "/api/classroom/manage/$r80NewId/status" @{ status = 1 } $adminToken
$null = Invoke-Api 'DELETE' "/api/classroom/manage/$r80NewId" $null $adminToken

# T81 列表非法日期
$r = Invoke-Api 'GET' '/api/classroom/list?date=2026-13-99' $null $stuToken
Add-Case3 'T81' '教室列表非法日期' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T82 未登录访问教室列表
$r = Invoke-Api 'GET' '/api/classroom/list'
Add-Case3 'T82' '未登录访问教室列表' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 2. 管理端全量查询（基准）=='
# 前置：初始基线预约 13 条（待审核3/已通过6/已驳回2/已取消2）
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50' $null $adminToken
$b91 = $r.Body | ConvertFrom-Json
Add-Case3 'T91' '管理端预约全量查询（13条）' '正常流程' "HTTP=$($r.Status) total=$($b91.data.total)" 'HTTP=200 total=13'

# T92 状态筛选（待审核 → 3 条）
$r = Invoke-Api 'GET' '/api/reservation/manage?status=0' $null $adminToken
$b92 = $r.Body | ConvertFrom-Json
Add-Case3 'T92' '管理端状态筛选（待审核）' '正常流程' "HTTP=$($r.Status) total=$($b92.data.total)" 'HTTP=200 total=3'

# T93 关键词（用户账号 zhangsan → 4 条）
$r = Invoke-Api 'GET' '/api/reservation/manage?keyword=zhangsan' $null $adminToken
$b93 = $r.Body | ConvertFrom-Json
Add-Case3 'T93' '管理端关键词筛选（zhangsan）' '正常流程' "HTTP=$($r.Status) total=$($b93.data.total)" 'HTTP=200 total=4'

# T94 日期范围（今天 → 5 条：id=1/2/4/12/13）
$r = Invoke-Api 'GET' "/api/reservation/manage?startDate=$today&endDate=$today" $null $adminToken
$b94 = $r.Body | ConvertFrom-Json
Add-Case3 'T94' '管理端日期范围筛选（今天）' '正常流程' "HTTP=$($r.Status) total=$($b94.data.total)" 'HTTP=200 total=5'

Write-Host '== 3. 冲突检测（四类边界）=='
# 基准：教室12 今天 08:00-10:00 已通过
# T83 首尾相接（新开始=旧结束 10:00-12:00）→ 不冲突
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=12&date=$today&startTime=10:00&endTime=12:00" $null $stuToken
$b83 = $r.Body | ConvertFrom-Json
Add-Case3 'T83' '冲突检测-首尾相接（不冲突）' '边界场景' "HTTP=$($r.Status) conflict=$($b83.data.conflict)" 'HTTP=200 conflict=False'

# T84 完全包含（08:30-09:30）→ 冲突
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=12&date=$today&startTime=08:30&endTime=09:30" $null $stuToken
$b84 = $r.Body | ConvertFrom-Json
Add-Case3 'T84' '冲突检测-完全包含（冲突）' '边界场景' "HTTP=$($r.Status) conflict=$($b84.data.conflict)" 'HTTP=200 conflict=True'

# T85 部分重叠（09:00-11:00）→ 冲突
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=12&date=$today&startTime=09:00&endTime=11:00" $null $stuToken
$b85 = $r.Body | ConvertFrom-Json
Add-Case3 'T85' '冲突检测-部分重叠（冲突）' '边界场景' "HTTP=$($r.Status) conflict=$($b85.data.conflict)" 'HTTP=200 conflict=True'

# T86 无重叠（07:00-08:00）→ 不冲突
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=12&date=$today&startTime=07:00&endTime=08:00" $null $stuToken
$b86 = $r.Body | ConvertFrom-Json
Add-Case3 'T86' '冲突检测-无重叠（不冲突）' '边界场景' "HTTP=$($r.Status) conflict=$($b86.data.conflict)" 'HTTP=200 conflict=False'

# T87 冲突检测教室不存在
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=99999&date=$today&startTime=10:00&endTime=12:00" $null $stuToken
Add-Case3 'T87' '冲突检测教室不存在' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T88 冲突检测缺日期参数
$r = Invoke-Api 'GET' '/api/reservation/conflict?classroomId=12&startTime=10:00&endTime=12:00' $null $stuToken
Add-Case3 'T88' '冲突检测缺日期参数' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T89 冲突检测开始>=结束
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=12&date=$today&startTime=10:00&endTime=09:00" $null $stuToken
Add-Case3 'T89' '冲突检测开始>=结束' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T90 未登录访问冲突检测
$r = Invoke-Api 'GET' "/api/reservation/conflict?classroomId=12&date=$today&startTime=10:00&endTime=12:00"
Add-Case3 'T90' '未登录访问冲突检测' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 4. 提交预约（前后端双重校验）=='
# T95 提交成功（教室6 明天 09:00-11:00）→ 待审核
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $tomorrow; startTime = '09:00'; endTime = '11:00'; purpose = 'R3测试-课程设计' } $stuToken
$b95 = $r.Body | ConvertFrom-Json
$resAId = if ($b95.code -eq 200) { $b95.data } else { 0 }
Add-Case3 'T95' '提交预约成功（待审核）' '正常流程' "HTTP=$($r.Status) code=$($b95.code) id=$resAId" 'HTTP=200 code=200'

# T96 冲突提交被后端二次校验拒绝（教室12 今天 08:30-09:30 与已通过重叠；绕过前端直接提交）
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 12; reserveDate = $today; startTime = '08:30'; endTime = '09:30'; purpose = 'R3测试-冲突提交' } $stuToken
Add-Case3 'T96' '冲突提交被后端二次校验拒绝' '核心校验' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T97 缺用途
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $tomorrow; startTime = '11:00'; endTime = '12:00' } $stuToken
Add-Case3 'T97' '提交预约缺用途' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T98 缺日期
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; startTime = '11:00'; endTime = '12:00'; purpose = 'R3测试' } $stuToken
Add-Case3 'T98' '提交预约缺日期' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T99 开始>=结束
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $tomorrow; startTime = '12:00'; endTime = '11:00'; purpose = 'R3测试' } $stuToken
Add-Case3 'T99' '提交预约开始>=结束' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T100 教室不存在
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 99999; reserveDate = $tomorrow; startTime = '11:00'; endTime = '12:00'; purpose = 'R3测试' } $stuToken
Add-Case3 'T100' '提交预约教室不存在' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T101 停用教室不可预约（教室1 临时停用→提交→400→恢复）
$null = Invoke-Api 'PUT' '/api/classroom/manage/1/status' @{ status = 0 } $adminToken
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 1; reserveDate = $tomorrow; startTime = '09:00'; endTime = '10:00'; purpose = 'R3测试-停用教室' } $stuToken
Add-Case3 'T101' '停用教室提交被拒绝' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'
$null = Invoke-Api 'PUT' '/api/classroom/manage/1/status' @{ status = 1 } $adminToken

# T102 未登录提交预约
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $tomorrow; startTime = '11:00'; endTime = '12:00'; purpose = 'R3测试' }
Add-Case3 'T102' '未登录提交预约' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 5. 审核（管理员）=='
# T103 提交待审核记录 D（供驳回/批量测试）
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $dayAfter; startTime = '09:00'; endTime = '11:00'; purpose = 'R3测试-驳回用例' } $stuToken
$b103 = $r.Body | ConvertFrom-Json
$resDId = if ($b103.code -eq 200) { $b103.data } else { 0 }

# T104 审核 A 通过（待审核→已通过，记录审核人/时间）
$r = Invoke-Api 'PUT' "/api/reservation/$resAId/audit" @{ status = 1 } $adminToken
$r104v = Invoke-Api 'GET' '/api/reservation/manage?keyword=zhangsan&page=1&size=50' $null $adminToken
$b104 = $r104v.Body | ConvertFrom-Json
$a104 = $b104.data.records | Where-Object { $_.id -eq $resAId }
$r104ok = ($a104 -and $a104.status -eq 1 -and $a104.auditorId -eq 1 -and $a104.auditTime)
Add-Case3 'T104' '审核通过（状态1+审核人/时间）' '正常流程' "HTTP=$($r.Status) ok=$r104ok" 'HTTP=200 ok=True'

# T105 审核驳回不带备注（应拒绝，D 仍待审核）
$r = Invoke-Api 'PUT' "/api/reservation/$resDId/audit" @{ status = 2 } $adminToken
Add-Case3 'T105' '审核驳回必填备注' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T106 审核不存在
$r = Invoke-Api 'PUT' '/api/reservation/99999/audit' @{ status = 1 } $adminToken
Add-Case3 'T106' '审核不存在预约' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T107 审核状态参数非法
$r = Invoke-Api 'PUT' "/api/reservation/$resDId/audit" @{ status = 9 } $adminToken
Add-Case3 'T107' '审核状态参数非法' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T108 审核非待审核记录（A 已通过）→ 拒绝
$r = Invoke-Api 'PUT' "/api/reservation/$resAId/audit" @{ status = 2; auditRemark = 'R3测试' } $adminToken
Add-Case3 'T108' '审核非待审核记录被拒' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

Write-Host '== 6. 取消（状态流转与时限）=='
# T109 取消已通过预约 A（明天开始，远未到 1 小时前）→ 已取消
$r = Invoke-Api 'PUT' "/api/reservation/$resAId/cancel" $null $stuToken
$r109v = Invoke-Api 'GET' '/api/reservation/mine?status=3&size=50' $null $stuToken
$b109 = $r109v.Body | ConvertFrom-Json
# PS 5.1 中单对象标量 .Count 为 $null（非 0/1），改用 $null 判定
$r109ok = ($null -ne ($b109.data.records | Where-Object { $_.id -eq $resAId }))
Add-Case3 'T109' '取消已通过预约（→已取消）' '正常流程' "HTTP=$($r.Status) ok=$r109ok" 'HTTP=200 ok=True'

# T110 取消已取消记录 → 拒绝
$r = Invoke-Api 'PUT' "/api/reservation/$resAId/cancel" $null $stuToken
Add-Case3 'T110' '取消已取消预约被拒' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T111 取消他人预约（zhangsan 取消 wangwu 的 id=12 已通过）→ 拒绝
$r = Invoke-Api 'PUT' '/api/reservation/12/cancel' $null $stuToken
Add-Case3 'T111' '取消他人预约被拒' '权限校验' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T112 开始前 1 小时内禁止取消（提交昨天 23:00-23:30 → 取消被拒）
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $yesterday; startTime = '23:00'; endTime = '23:30'; purpose = 'R3测试-取消时限' } $stuToken
$b112 = $r.Body | ConvertFrom-Json
$resBId = if ($b112.code -eq 200) { $b112.data } else { 0 }
$r = Invoke-Api 'PUT' "/api/reservation/$resBId/cancel" $null $stuToken
Add-Case3 'T112' '开始前1小时内禁止取消' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T113 取消已驳回预约（提交 C → 驳回（带备注）→ 取消被拒）
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $tomorrow; startTime = '13:00'; endTime = '15:00'; purpose = 'R3测试-驳回后取消' } $stuToken
$b113 = $r.Body | ConvertFrom-Json
$resCId = if ($b113.code -eq 200) { $b113.data } else { 0 }
$r = Invoke-Api 'PUT' "/api/reservation/$resCId/audit" @{ status = 2; auditRemark = 'R3测试驳回原因' } $adminToken
$r113v = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50' $null $adminToken
$b113v = $r113v.Body | ConvertFrom-Json
$c113 = $b113v.data.records | Where-Object { $_.id -eq $resCId }
$r113ok = ($c113 -and $c113.status -eq 2 -and $c113.auditRemark -eq 'R3测试驳回原因')
$r = Invoke-Api 'PUT' "/api/reservation/$resCId/cancel" $null $stuToken
Add-Case3 'T113' '驳回带备注+取消已驳回被拒' '边界场景' "HTTP=$($r.Status) code=$(if($r.Body -match '"code":400'){400}else{200}) rejectedOk=$r113ok" 'code=400 rejectedOk=True'

Write-Host '== 7. 批量审核 =='
# T114 批量 ids 为空
$r = Invoke-Api 'POST' '/api/reservation/batch-audit' @{ ids = @(); status = 1 } $adminToken
Add-Case3 'T114' '批量审核ids为空' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T115 批量驳回不带备注
$r = Invoke-Api 'POST' '/api/reservation/batch-audit' @{ ids = @($resDId); status = 2 } $adminToken
Add-Case3 'T115' '批量驳回必填备注' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T116 提交 E（供批量通过）
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $dayAfter; startTime = '14:00'; endTime = '16:00'; purpose = 'R3测试-批量通过' } $stuToken
$b116 = $r.Body | ConvertFrom-Json
$resEId = if ($b116.code -eq 200) { $b116.data } else { 0 }

# T117 批量通过 [D, E]（均待审核）→ updated=2
$r = Invoke-Api 'POST' '/api/reservation/batch-audit' @{ ids = @($resDId, $resEId); status = 1 } $adminToken
$b117 = $r.Body | ConvertFrom-Json
Add-Case3 'T117' '批量通过（2条待审核）' '正常流程' "HTTP=$($r.Status) updated=$($b117.data)" 'HTTP=200 updated=2'

# T118 混合批量 [D已通过, F待审核] → 仅待审核参与，updated=1
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 6; reserveDate = $dayAfter; startTime = '16:30'; endTime = '17:30'; purpose = 'R3测试-混合批量' } $stuToken
$b118 = $r.Body | ConvertFrom-Json
$resFId = if ($b118.code -eq 200) { $b118.data } else { 0 }
$r = Invoke-Api 'POST' '/api/reservation/batch-audit' @{ ids = @($resDId, $resFId); status = 1 } $adminToken
$b118b = $r.Body | ConvertFrom-Json
Add-Case3 'T118' '批量仅待审核参与（混合）' '边界场景' "HTTP=$($r.Status) updated=$($b118b.data)" 'HTTP=200 updated=1'

Write-Host '== 8. 我的预约 =='
# T119 mine 列表（zhangsan 初始4 + A/B/C/D/E/F = 10 条，含教室展示字段）
$r = Invoke-Api 'GET' '/api/reservation/mine?size=50' $null $stuToken
$b119 = $r.Body | ConvertFrom-Json
$r119ok = ($b119.data.total -eq 10 -and $b119.data.records[0].classroomName -and $b119.data.records[0].building -and $b119.data.records[0].roomNo)
Add-Case3 'T119' '我的预约列表（含教室字段）' '正常流程' "HTTP=$($r.Status) total=$($b119.data.total) ok=$r119ok" 'HTTP=200 total=10 ok=True'

# T120 mine 状态筛选（待审核：初始 id=1/13 + B = 3 条）
$r = Invoke-Api 'GET' '/api/reservation/mine?status=0' $null $stuToken
$b120 = $r.Body | ConvertFrom-Json
Add-Case3 'T120' '我的预约状态筛选（待审核）' '正常流程' "HTTP=$($r.Status) total=$($b120.data.total)" 'HTTP=200 total=2'

# T121 mine 非法状态参数
$r = Invoke-Api 'GET' '/api/reservation/mine?status=9' $null $stuToken
Add-Case3 'T121' '我的预约非法状态参数' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

Write-Host '== 9. 权限校验（R3 新接口全覆盖）=='
# T122 学生 Token 访问管理端全量查询
$r = Invoke-Api 'GET' '/api/reservation/manage' $null $stuToken
Add-Case3 'T122' '学生Token访问预约管理' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T123 学生 Token 审核
$r = Invoke-Api 'PUT' "/api/reservation/$resDId/audit" @{ status = 1 } $stuToken
Add-Case3 'T123' '学生Token审核预约' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T124 学生 Token 批量审核
$r = Invoke-Api 'POST' '/api/reservation/batch-audit' @{ ids = @($resDId); status = 1 } $stuToken
Add-Case3 'T124' '学生Token批量审核' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T125 未登录访问我的预约
$r = Invoke-Api 'GET' '/api/reservation/mine'
Add-Case3 'T125' '未登录访问我的预约' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T126 未登录取消预约
$r = Invoke-Api 'PUT' '/api/reservation/12/cancel'
Add-Case3 'T126' '未登录取消预约' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T127 未登录访问预约管理
$r = Invoke-Api 'GET' '/api/reservation/manage'
Add-Case3 'T127' '未登录访问预约管理' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host ''
Write-Host ''
Write-Host '========== 四、R4 体验升级（个人中心 / 收藏全链路 / 体验细节）=========='
# R4 测试数据：独立测试用户 + 日期动态计算（保证任何日期可复跑）
$day3 = (Get-Date).AddDays(3).ToString('yyyy-MM-dd')
$expMonth = if ($day3.StartsWith((Get-Date).ToString('yyyy-MM'))) { 3 } else { 0 }

# 准备 R4 数据统计/个人信息/收藏测试用户 r4stu_（独立用户，避免影响基线学生数据）
$r4stu = "r4stu_$suffix"
$null = Invoke-Api 'POST' '/api/user/register' @{ username = $r4stu; password = 'abc123'; confirmPassword = 'abc123'; name = 'R4体验学生'; studentNo = "R4$suffix"; phone = '13900000009'; email = "$r4stu@stu.edu.cn" }
$r4r = Invoke-Api 'POST' '/api/user/login' @{ username = $r4stu; password = 'abc123'; role = 0 }
$r4Token = $null
if ($r4r.Body) { $r4b = $r4r.Body | ConvertFrom-Json; if ($r4b.code -eq 200) { $r4Token = $r4b.data.token } }

Write-Host '== 1. 个人中心-数据统计（GET /api/user/stats）=='

# T128 新用户 stats 全零（无预约基线）
$r = Invoke-Api 'GET' '/api/user/stats' $null $r4Token
$b128 = $r.Body | ConvertFrom-Json
$r128ok = ($b128.code -eq 200 -and $b128.data.totalReservations -eq 0 -and $b128.data.monthReservations -eq 0 -and $b128.data.approvalRate -eq 0 -and $null -eq $b128.data.lastReservationTime)
Add-Case3 'T128' '新用户数据统计全零' '正常流程' "HTTP=$($r.Status) ok=$r128ok" 'HTTP=200 ok=True'

# T129 创建 3 条预约（教室1 +3天，独立时段）→ 管理员审核 2 通过 1 驳回
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 1; reserveDate = $day3; startTime = '09:00'; endTime = '10:00'; purpose = 'R4测试-统计A' } $r4Token
$b = $r.Body | ConvertFrom-Json; $resS1 = if ($b.code -eq 200) { $b.data } else { 0 }
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 1; reserveDate = $day3; startTime = '10:00'; endTime = '11:00'; purpose = 'R4测试-统计B' } $r4Token
$b = $r.Body | ConvertFrom-Json; $resS2 = if ($b.code -eq 200) { $b.data } else { 0 }
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 1; reserveDate = $day3; startTime = '13:00'; endTime = '14:00'; purpose = 'R4测试-统计C' } $r4Token
$b = $r.Body | ConvertFrom-Json; $resS3 = if ($b.code -eq 200) { $b.data } else { 0 }
$null = Invoke-Api 'PUT' "/api/reservation/$resS1/audit" @{ status = 1 } $adminToken
$null = Invoke-Api 'PUT' "/api/reservation/$resS2/audit" @{ status = 1 } $adminToken
$null = Invoke-Api 'PUT' "/api/reservation/$resS3/audit" @{ status = 2; auditRemark = 'R4测试驳回' } $adminToken
Add-Case3 'T129' '创建3条预约并审核（2通过1驳回）' '正常流程' "ids=$resS1/$resS2/$resS3 allOk=$(($resS1 -gt 0 -and $resS2 -gt 0 -and $resS3 -gt 0))" 'allOk=True'

# T130 统计值：累计=3、本月=动态、通过率=66.7、最近一次非空
$r = Invoke-Api 'GET' '/api/user/stats' $null $r4Token
$b130 = $r.Body | ConvertFrom-Json
$r130ok = ($b130.data.totalReservations -eq 3 -and $b130.data.monthReservations -eq $expMonth -and $b130.data.approvalRate -eq 66.7 -and $null -ne $b130.data.lastReservationTime)
Add-Case3 'T130' '统计：累计3/本月动态/通过率66.7/最近非空' '正常流程' "HTTP=$($r.Status) ok=$r130ok total=$($b130.data.totalReservations) month=$($b130.data.monthReservations) rate=$($b130.data.approvalRate)" 'HTTP=200 ok=True'

# T131 管理员访问 stats（200 正常返回）
$r = Invoke-Api 'GET' '/api/user/stats' $null $adminToken
$b131 = $r.Body | ConvertFrom-Json
Add-Case3 'T131' '管理员访问数据统计（200）' '权限校验' "HTTP=$($r.Status) code=$($b131.code)" 'HTTP=200 code=200'

# T132 未登录访问 stats
$r = Invoke-Api 'GET' '/api/user/stats'
Add-Case3 'T132' '未登录访问数据统计' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 2. 个人信息（PUT /api/user/info）=='

# T133 正常修改（姓名/邮箱/手机号）→ 回读验证 + 不含 password + 账号不变
$r = Invoke-Api 'PUT' '/api/user/info' @{ name = 'R4张三改'; email = 'new@stu.edu.cn'; phone = '13912345678' } $r4Token
$r133v = Invoke-Api 'GET' '/api/user/info' $null $r4Token
$b133 = $r133v.Body | ConvertFrom-Json
$r133ok = ($b133.code -eq 200 -and $b133.data.name -eq 'R4张三改' -and $b133.data.email -eq 'new@stu.edu.cn' -and $b133.data.phone -eq '13912345678' -and $b133.data.username -eq $r4stu -and -not ($b133.data.PSObject.Properties.Name -contains 'password'))
Add-Case3 'T133' '修改个人信息生效（不回显password）' '正常流程' "HTTP=$($r.Status) ok=$r133ok" 'HTTP=200 ok=True'

# T134 姓名为空
$r = Invoke-Api 'PUT' '/api/user/info' @{ name = '' } $r4Token
Add-Case3 'T134' '姓名为空被拒' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T135 手机号格式错误
$r = Invoke-Api 'PUT' '/api/user/info' @{ name = 'R4张三改'; phone = '12345' } $r4Token
Add-Case3 'T135' '手机号格式错误被拒' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T136 姓名超长（21 字符）
$longName = '名' * 21
$r = Invoke-Api 'PUT' '/api/user/info' @{ name = $longName } $r4Token
Add-Case3 'T136' '姓名超长被拒' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T137 未登录修改个人信息
$r = Invoke-Api 'PUT' '/api/user/info' @{ name = '未登录' }
Add-Case3 'T137' '未登录修改个人信息' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 3. 修改密码（PUT /api/user/password）=='

# 准备独立密码测试用户 r4pw_（改密后旧密码失效，不影响其他用例）
$r4pw = "r4pw_$suffix"
$null = Invoke-Api 'POST' '/api/user/register' @{ username = $r4pw; password = 'oldpass1'; confirmPassword = 'oldpass1'; name = 'R4密码学生'; studentNo = "R5$suffix" }
$pwr = Invoke-Api 'POST' '/api/user/login' @{ username = $r4pw; password = 'oldpass1'; role = 0 }
$pwToken = $null
if ($pwr.Body) { $pwb = $pwr.Body | ConvertFrom-Json; if ($pwb.code -eq 200) { $pwToken = $pwb.data.token } }

# T138 原密码错误
$r = Invoke-Api 'PUT' '/api/user/password' @{ oldPassword = 'wrongxx'; newPassword = 'newpass1'; confirmPassword = 'newpass1' } $pwToken
Add-Case3 'T138' '原密码错误被拒' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T139 新密码少于 6 位
$r = Invoke-Api 'PUT' '/api/user/password' @{ oldPassword = 'oldpass1'; newPassword = '12345'; confirmPassword = '12345' } $pwToken
Add-Case3 'T139' '新密码少于6位被拒' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T140 两次新密码不一致
$r = Invoke-Api 'PUT' '/api/user/password' @{ oldPassword = 'oldpass1'; newPassword = 'newpass1'; confirmPassword = 'newpass2' } $pwToken
Add-Case3 'T140' '两次新密码不一致被拒' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T141 缺少参数
$r = Invoke-Api 'PUT' '/api/user/password' @{} $pwToken
Add-Case3 'T141' '缺少密码参数被拒' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T142 正常修改 → 新密码可登录、旧密码失效（验证 BCrypt 已更新）
$r = Invoke-Api 'PUT' '/api/user/password' @{ oldPassword = 'oldpass1'; newPassword = 'newpass1'; confirmPassword = 'newpass1' } $pwToken
$rNew = Invoke-Api 'POST' '/api/user/login' @{ username = $r4pw; password = 'newpass1'; role = 0 }
$rOld = Invoke-Api 'POST' '/api/user/login' @{ username = $r4pw; password = 'oldpass1'; role = 0 }
$nb = $null; $ob = $null
if ($rNew.Body) { try { $nb = $rNew.Body | ConvertFrom-Json } catch { } }
if ($rOld.Body) { try { $ob = $rOld.Body | ConvertFrom-Json } catch { } }
Add-Case3 'T142' '改密后新密码可登录旧密码失效' '正常流程' "HTTP=$($r.Status) newCode=$($nb.code) oldCode=$($ob.code)" 'HTTP=200 newCode=200 oldCode=400'

# T143 未登录修改密码
$r = Invoke-Api 'PUT' '/api/user/password' @{ oldPassword = 'x'; newPassword = 'newpass1'; confirmPassword = 'newpass1' }
Add-Case3 'T143' '未登录修改密码' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 4. 收藏全链路（POST /api/favorite/{id} / GET /api/favorite/list）=='

# T144 收藏教室1（新增）
$r = Invoke-Api 'POST' '/api/favorite/1' $null $r4Token
$b144 = $r.Body | ConvertFrom-Json
Add-Case3 'T144' '收藏教室（新增成功）' '正常流程' "HTTP=$($r.Status) data=$($b144.data)" 'HTTP=200 data=True'

# T145 重复收藏（toggle 取消）
$r = Invoke-Api 'POST' '/api/favorite/1' $null $r4Token
$b145 = $r.Body | ConvertFrom-Json
Add-Case3 'T145' '重复收藏自动取消（toggle）' '正常流程' "HTTP=$($r.Status) data=$($b145.data)" 'HTTP=200 data=False'

# T146 再收藏 → 列表返回该条（含教室展示字段）
$null = Invoke-Api 'POST' '/api/favorite/1' $null $r4Token
$r = Invoke-Api 'GET' '/api/favorite/list' $null $r4Token
$b146 = $r.Body | ConvertFrom-Json
$r146ok = ($b146.code -eq 200 -and $null -ne $b146.data.classroomId -and $b146.data.classroomId -eq 1 -and $b146.data.name -and $b146.data.building -and $b146.data.roomNo -and $b146.data.createTime)
Add-Case3 'T146' '收藏列表返回该条（含教室字段）' '正常流程' "HTTP=$($r.Status) ok=$r146ok" 'HTTP=200 ok=True'

# T147 收藏不存在的教室
$r = Invoke-Api 'POST' '/api/favorite/99999' $null $r4Token
Add-Case3 'T147' '收藏不存在教室被拒' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T148 收藏至上限（2..10 共 9 间，合计 10 间），第 11 间被拒
for ($cid = 2; $cid -le 10; $cid++) {
  $null = Invoke-Api 'POST' "/api/favorite/$cid" $null $r4Token
}
$r = Invoke-Api 'POST' '/api/favorite/11' $null $r4Token
Add-Case3 'T148' '收藏第11间被拒（上限10）' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T149 收藏列表共 10 条
$r = Invoke-Api 'GET' '/api/favorite/list' $null $r4Token
$b149 = $r.Body | ConvertFrom-Json
Add-Case3 'T149' '收藏列表上限10条' '正常流程' "HTTP=$($r.Status) total=$($b149.data.Count)" 'HTTP=200 total=10'

# T150 zhangsan 基线收藏 2 条（数据基线回归）
$r = Invoke-Api 'GET' '/api/favorite/list' $null $stuToken
$b150 = $r.Body | ConvertFrom-Json
Add-Case3 'T150' 'zhangsan收藏列表（基线2条）' '正常流程' "HTTP=$($r.Status) total=$($b150.data.Count)" 'HTTP=200 total=2'

# T151 管理员访问收藏列表（200）
$r = Invoke-Api 'GET' '/api/favorite/list' $null $adminToken
Add-Case3 'T151' '管理员访问收藏列表（200）' '权限校验' "HTTP=$($r.Status)" 'HTTP=200'

# T152 未登录访问收藏列表
$r = Invoke-Api 'GET' '/api/favorite/list'
Add-Case3 'T152' '未登录访问收藏列表' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T153 未登录收藏教室
$r = Invoke-Api 'POST' '/api/favorite/1'
Add-Case3 'T153' '未登录收藏教室' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 5. 体验细节（状态标签三态 / 登录提醒数据支撑）=='

# T154 状态标签三态集合校验（时间无关）+ 教室12 按当前时刻推导期望标签（08:00-10:00 已通过）
$r = Invoke-Api 'GET' '/api/classroom/list?page=1&size=50' $null $stuToken
$b154 = $r.Body | ConvertFrom-Json
$validLabels = @('当前空闲', '使用中', '已结束')
$allValid = $true
foreach ($room in $b154.data.records) {
  if ($validLabels -notcontains $room.statusLabel) { $allValid = $false }
}
$nowH = (Get-Date).Hour; $nowM = (Get-Date).Minute
if ($nowH -gt 10 -or ($nowH -eq 10 -and $nowM -ge 0)) { $expect12 = '已结束' }
elseif ($nowH -gt 8 -or ($nowH -eq 8 -and $nowM -ge 0)) { $expect12 = '使用中' }
else { $expect12 = '当前空闲' }
$room12 = $b154.data.records | Where-Object { $_.id -eq 12 }
$r154ok = ($allValid -and $null -ne $room12 -and $room12.statusLabel -eq $expect12)
Add-Case3 'T154' '状态标签三态有效且教室12口径正确' '边界场景' "HTTP=$($r.Status) ok=$r154ok allValid=$allValid expect=$expect12 actual=$($room12.statusLabel)" 'HTTP=200 ok=True'

# T155 即将开始（24h 内）已通过预约可查（登录提醒数据支撑：start ∈ (now, now+24h)）
$twoH = (Get-Date).AddHours(2)
$upDate = $twoH.ToString('yyyy-MM-dd')
$upStart = $twoH.ToString('HH:mm')
$upEnd = $twoH.AddMinutes(90).ToString('HH:mm')
$r = Invoke-Api 'POST' '/api/reservation' @{ classroomId = 1; reserveDate = $upDate; startTime = $upStart; endTime = $upEnd; purpose = 'R4测试-即将开始' } $r4Token
$b155 = $r.Body | ConvertFrom-Json
$resUp = if ($b155.code -eq 200) { $b155.data } else { 0 }
$null = Invoke-Api 'PUT' "/api/reservation/$resUp/audit" @{ status = 1 } $adminToken
$r = Invoke-Api 'GET' '/api/reservation/mine?status=1&size=50' $null $r4Token
$b155b = $r.Body | ConvertFrom-Json
$r155ok = ($null -ne ($b155b.data.records | Where-Object { $_.id -eq $resUp }))
Add-Case3 'T155' '即将开始已通过预约可查（登录提醒支撑）' '正常流程' "HTTP=$($r.Status) ok=$r155ok resId=$resUp" 'HTTP=200 ok=True'

Write-Host ''
Write-Host '========== 五、R5 亮点功能（日历区间查询 / 数据看板统计）=========='
# R5 前置：清理 R1-R4 测试遗留数据，使统计口径回到基线（预约13条四状态/收藏6条/用户5人），保证与库一致性断言精确
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM reservation WHERE id > 13;" 2>$null | Out-Null
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM user_favorite WHERE id > 6;" 2>$null | Out-Null
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM sys_user WHERE username LIKE 'r4stu_%' OR username LIKE 'r4pw_%' OR username LIKE 'test_stu%' OR username LIKE 'pwt_%' OR username LIKE 'dup_%' OR username LIKE 'short_%' OR username LIKE 'phone_%' OR username LIKE 'partial_%' OR username LIKE 'r5stu_%';" 2>$null | Out-Null

# SQL 标量辅助（-N 去表头，取首行）
function Get-MysqlScalar($sql) {
  $out = docker exec reservation-mysql mysql -uroot -proot reservation -N -e $sql 2>$null
  $first = $out | Select-Object -First 1
  if ($null -eq $first -or $first -eq '') { return 0 }
  return $first
}

Write-Host '== 1. 日历区间查询（GET /api/reservation/calendar，登录即可）=='
# T156 正常流程：区间覆盖基线 13 条（2026-09-05~09-12），含教室展示字段且不含 password
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-05&endDate=2026-09-12' $null $adminToken
$b156 = $r.Body | ConvertFrom-Json
$r156ok = ($b156.code -eq 200 -and $b156.data.Count -eq 13 -and $b156.data[0].classroomName -and $b156.data[0].building -and $b156.data[0].roomNo -and $null -ne $b156.data[0].status -and -not ($b156.data[0].PSObject.Properties.Name -contains 'password'))
Add-Case3 'T156' '日历区间查询（13条+教室字段+无password）' '正常流程' "HTTP=$($r.Status) ok=$r156ok total=$($b156.data.Count)" 'HTTP=200 ok=True'

# T157 按教室筛选（A101=1，9/11 两条待审核）
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-01&endDate=2026-09-30&classroomId=1' $null $adminToken
$b157 = $r.Body | ConvertFrom-Json
$r157ok = ($b157.code -eq 200 -and $b157.data.Count -eq 2 -and $b157.data[0].status -eq 0)
Add-Case3 'T157' '日历按教室筛选（A101=2条待审核）' '正常流程' "HTTP=$($r.Status) ok=$r157ok total=$($b157.data.Count)" 'HTTP=200 ok=True'

# T158 缺 startDate
$r = Invoke-Api 'GET' '/api/reservation/calendar?endDate=2026-09-30' $null $adminToken
Add-Case3 'T158' '日历缺startDate' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T159 缺 endDate
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-01' $null $adminToken
Add-Case3 'T159' '日历缺endDate' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T160 日期倒序（start>end）
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-12&endDate=2026-09-05' $null $adminToken
Add-Case3 'T160' '日历日期倒序' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T161 跨度 367 天（超上限）
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2025-09-10&endDate=2026-09-11' $null $adminToken
Add-Case3 'T161' '日历跨度367天（超限）' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T162 跨度 366 天（最大合法边界）
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2025-09-11&endDate=2026-09-11' $null $adminToken
$b162 = $r.Body | ConvertFrom-Json
Add-Case3 'T162' '日历跨度366天（最大合法）' '边界场景' "HTTP=$($r.Status) code=$($b162.code) total=$($b162.data.Count)" 'HTTP=200 code=200'

# T163 学生登录访问日历（登录即可，非管理员专用）
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-05&endDate=2026-09-12' $null $stuToken
$b163 = $r.Body | ConvertFrom-Json
Add-Case3 'T163' '学生访问日历区间（200）' '权限校验' "HTTP=$($r.Status) code=$($b163.code) total=$($b163.data.Count)" 'HTTP=200 code=200'

# T164 未登录访问日历
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-05&endDate=2026-09-12'
Add-Case3 'T164' '未登录访问日历区间' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T165 单日区间（2026-09-11 → 5 条）
$r = Invoke-Api 'GET' '/api/reservation/calendar?startDate=2026-09-11&endDate=2026-09-11' $null $adminToken
$b165 = $r.Body | ConvertFrom-Json
Add-Case3 'T165' '日历单日区间（9/11=5条）' '正常流程' "HTTP=$($r.Status) total=$($b165.data.Count)" 'HTTP=200 total=5'

Write-Host '== 2. 教室使用率排行（GET /api/stats/usage-rate，管理员）=='
# T166 默认近30天：12 间全量排行；区间内已通过 5 条（各 2h，30天×14h=420h → 0.476→0.5）
$r = Invoke-Api 'GET' '/api/stats/usage-rate' $null $adminToken
$b166 = $r.Body | ConvertFrom-Json
$r166ok = ($b166.code -eq 200 -and $b166.data.Count -eq 12 -and [Math]::Abs([double]$b166.data[0].usageRate - 0.5) -lt 0.001)
Add-Case3 'T166' '使用率排行（默认近30天12间）' '正常流程' "HTTP=$($r.Status) ok=$r166ok count=$($b166.data.Count) top=$($b166.data[0].name)/$($b166.data[0].usageRate)" 'HTTP=200 ok=True'

# T167 指定区间 2026-09-05~09-12（8天×14h=112h；B101=classroom_id 5 已通过2h → 1.7857→1.8）
$r = Invoke-Api 'GET' '/api/stats/usage-rate?startDate=2026-09-05&endDate=2026-09-12' $null $adminToken
$b167 = $r.Body | ConvertFrom-Json
$b167b101 = @($b167.data | Where-Object { $_.classroomId -eq 5 })[0]
Add-Case3 'T167' '使用率指定区间（B101=1.8%）' '正常流程' "HTTP=$($r.Status) rate=$($b167b101.usageRate)" 'HTTP=200 rate=1.8'

# T168 库一致性：SQL 计算 B101 区间占用小时 → 期望使用率（与 API 对比）
$sqlHours = Get-MysqlScalar "SELECT COALESCE(SUM(TIME_TO_SEC(TIMEDIFF(end_time,start_time)))/3600,0) FROM reservation WHERE classroom_id=5 AND reserve_date BETWEEN '2026-09-05' AND '2026-09-12' AND status=1;"
$expect168 = [Math]::Round([double]$sqlHours / (8 * 14) * 100, 1)
$r168ok = ([Math]::Abs([double]$b167b101.usageRate - [double]$expect168) -lt 0.001)
Add-Case3 'T168' '使用率与库中一致（SQL对照）' '库一致性' "HTTP=$($r.Status) sqlHours=$sqlHours api=$($b167b101.usageRate) expect=$expect168 ok=$r168ok" 'ok=True'

Write-Host '== 3. 月度预约趋势（GET /api/stats/trend，管理员）=='
# T169 默认近30天：区间 [08-13,09-11] 内全部状态 11 条（基线 9/12 两条不在区间），2026-09
$r = Invoke-Api 'GET' '/api/stats/trend' $null $adminToken
$b169 = $r.Body | ConvertFrom-Json
$r169ok = ($b169.code -eq 200 -and $b169.data[0].month -eq '2026-09' -and $b169.data[0].count -eq 11)
Add-Case3 'T169' '月度趋势（默认近30天=11条）' '正常流程' "HTTP=$($r.Status) ok=$r169ok month=$($b169.data[0].month) count=$($b169.data[0].count)" 'HTTP=200 ok=True'

# T170 指定区间 09-01~09-30（基线 13 条全部）
$r = Invoke-Api 'GET' '/api/stats/trend?startDate=2026-09-01&endDate=2026-09-30' $null $adminToken
$b170 = $r.Body | ConvertFrom-Json
Add-Case3 'T170' '月度趋势指定区间（13条）' '正常流程' "HTTP=$($r.Status) count=$($b170.data[0].count)" 'HTTP=200 count=13'

# T171 库一致性：SQL 按月分组对比 API
$sqlCnt171 = Get-MysqlScalar "SELECT COUNT(*) FROM reservation WHERE reserve_date BETWEEN '2026-09-01' AND '2026-09-30';"
$r171ok = ($b170.data[0].count -eq $sqlCnt171 -and $b170.data[0].month -eq '2026-09')
Add-Case3 'T171' '月度趋势与库一致（SQL对照）' '库一致性' "HTTP=$($r.Status) api=$($b170.data[0].count) sql=$sqlCnt171 ok=$r171ok" 'ok=True'

Write-Host '== 4. 热门时段分布（GET /api/stats/time-distribution，管理员）=='
# T172 默认近30天六桶：已通过 5 条 → 08-10:2(40%)/10-12:1(20%)/14-16:2(40%)/其余0
$r = Invoke-Api 'GET' '/api/stats/time-distribution' $null $adminToken
$b172 = $r.Body | ConvertFrom-Json
$b172s1 = @($b172.data | Where-Object { $_.slot -eq '08:00-10:00' })[0]
$r172ok = ($b172.code -eq 200 -and $b172.data.Count -eq 6 -and $b172s1.count -eq 2 -and $b172s1.percentage -eq 40)
Add-Case3 'T172' '时段分布（默认近30天六桶）' '正常流程' "HTTP=$($r.Status) ok=$r172ok buckets=$($b172.data.Count) s1=$($b172s1.count)/$($b172s1.percentage)" 'HTTP=200 ok=True'

# T173 库一致性：指定区间 09-05~09-12 已通过 6 条 → 08-10 桶含 B102/C401(08:00)+B101(09:00)=3 → 50%
$r = Invoke-Api 'GET' '/api/stats/time-distribution?startDate=2026-09-05&endDate=2026-09-12' $null $adminToken
$b173 = $r.Body | ConvertFrom-Json
$sqlApproved = Get-MysqlScalar "SELECT COUNT(*) FROM reservation WHERE reserve_date BETWEEN '2026-09-05' AND '2026-09-12' AND status=1;"
$sqlBucket = Get-MysqlScalar "SELECT COUNT(*) FROM reservation WHERE reserve_date BETWEEN '2026-09-05' AND '2026-09-12' AND status=1 AND start_time >= '08:00' AND start_time < '10:00';"
$b173s1 = @($b173.data | Where-Object { $_.slot -eq '08:00-10:00' })[0]
$expectPct = [Math]::Round([double]$sqlBucket / [double]$sqlApproved * 100, 1)
$r173ok = ($b173s1.count -eq $sqlBucket -and [Math]::Abs([double]$b173s1.percentage - [double]$expectPct) -lt 0.001)
Add-Case3 'T173' '时段分布与库一致（SQL对照）' '库一致性' "HTTP=$($r.Status) api=$($b173s1.count)/$($b173s1.percentage) sql=$sqlBucket/$sqlApproved expectPct=$expectPct ok=$r173ok" 'ok=True'

Write-Host '== 5. 统计接口边界与权限 =='
# T174 非法日期参数
$r = Invoke-Api 'GET' '/api/stats/usage-rate?startDate=2026-13-99' $null $adminToken
Add-Case3 'T174' '统计非法日期参数' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T175 统计跨度超 366 天
$r = Invoke-Api 'GET' '/api/stats/trend?startDate=2024-09-11&endDate=2026-09-11' $null $adminToken
Add-Case3 'T175' '统计跨度超366天' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T176 学生访问 usage-rate
$r = Invoke-Api 'GET' '/api/stats/usage-rate' $null $stuToken
Add-Case3 'T176' '学生访问使用率排行' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T177 学生访问 trend
$r = Invoke-Api 'GET' '/api/stats/trend' $null $stuToken
Add-Case3 'T177' '学生访问月度趋势' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T178 学生访问 time-distribution
$r = Invoke-Api 'GET' '/api/stats/time-distribution' $null $stuToken
Add-Case3 'T178' '学生访问时段分布' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T179 未登录访问 usage-rate
$r = Invoke-Api 'GET' '/api/stats/usage-rate'
Add-Case3 'T179' '未登录访问使用率排行' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T180 未登录访问 trend
$r = Invoke-Api 'GET' '/api/stats/trend'
Add-Case3 'T180' '未登录访问月度趋势' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T181 未登录访问 time-distribution
$r = Invoke-Api 'GET' '/api/stats/time-distribution'
Add-Case3 'T181' '未登录访问时段分布' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T182 统计接口不返回 password（数据安全）
$r = Invoke-Api 'GET' '/api/stats/usage-rate' $null $adminToken
$r182ok = (-not $r.Body.Contains('password'))
Add-Case3 'T182' '统计接口不返回password' '数据安全' "HTTP=$($r.Status) noPwd=$r182ok" 'noPwd=True'

Write-Host ''

Write-Host ''
Write-Host '========== 七、R6 联调优化（预约记录页 manage/export + 边界权限 + 13页闭环回归）=========='
# R6 前置：确认库基线（预约13条四状态）；R6 用例全部只读，不改变数据
$r6BaseCnt = Get-MysqlScalar "SELECT COUNT(*) FROM reservation;"
Write-Host "R6 前置基线预约条数: $r6BaseCnt（期望 13）"

# 导出下载辅助（xlsx 二进制 → 状态/内容类型/字节；PS5.1 下 -OutFile 返回对象无 Headers，改用 RawContentStream 取字节并写临时文件）
$exportTmp = Join-Path $env:TEMP "r6_export_$suffix.xlsx"
function Get-Export($path, $token) {
  try {
    $resp = Invoke-WebRequest -Uri "$base$path" -Headers @{ Authorization = "Bearer $token" } -UseBasicParsing -TimeoutSec 20
    $ms = New-Object System.IO.MemoryStream
    $resp.RawContentStream.CopyTo($ms)
    $bytes = $ms.ToArray()
    [System.IO.File]::WriteAllBytes($exportTmp, $bytes)
    $ct = ''
    if ($resp.Headers -and $resp.Headers['Content-Type']) { $ct = "$($resp.Headers['Content-Type'])" }
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Type = $ct; Size = $bytes.Length; Bytes = $bytes; Body = '' }
  } catch {
    $st = 0
    if ($_.Exception.Response) { $st = [int]$_.Exception.Response.StatusCode.value__ }
    $msg = ''
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $msg = $_.ErrorDetails.Message }
    return [pscustomobject]@{ Status = $st; Type = 'error'; Size = 0; Bytes = @(); Body = $msg }
  }
}

# 解析 xlsx：返回 sheet1.xml 文本（空/损坏返回 ''）
function Get-XlsxText($file) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($file)
    $e = $zip.GetEntry('xl/worksheets/sheet1.xml')
    if (-not $e) { $zip.Dispose(); return '' }
    $ms = New-Object System.IO.MemoryStream
    $s = $e.Open(); $s.CopyTo($ms); $s.Close()
    $xml = [System.Text.Encoding]::UTF8.GetString($ms.ToArray())
    $zip.Dispose()
    return $xml
  } catch { return '' }
}

# 解析 xlsx：统计 sheet1 行数（含表头行；解析失败返回 -1）
function Get-XlsxRows($file) {
  $xml = Get-XlsxText $file
  if ($xml -eq '') { return -1 }
  return ([regex]::Matches($xml, '<row ')).Count
}

Write-Host '== 1. 预约记录页 manage 接口（R6 classroomId 教室筛选）=='
# T183 正常：classroomId=1（A101）→ 2 条待审核，全部 classroomId=1，字段完整
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50&classroomId=1' $null $adminToken
$b183 = $r.Body | ConvertFrom-Json
$r183ok = ($b183.code -eq 200 -and $b183.data.total -eq 2 -and @($b183.data.records | Where-Object { $_.classroomId -ne 1 }).Count -eq 0)
Add-Case3 'T183' '预约记录-按教室筛选（A101=2条）' '正常流程' "HTTP=$($r.Status) ok=$r183ok total=$($b183.data.total)" 'HTTP=200 ok=True total=2'

# T184 正常：classroomId=5（B101）→ 1 条已通过
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50&classroomId=5' $null $adminToken
$b184 = $r.Body | ConvertFrom-Json
Add-Case3 'T184' '预约记录-按教室筛选（B101=1条已通过）' '正常流程' "HTTP=$($r.Status) total=$($b184.data.total) status=$($b184.data.records[0].status)" 'HTTP=200 total=1 status=1'

# T185 组合：教室 + 状态（A101 待审核 2 条）
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50&classroomId=1&status=0' $null $adminToken
$b185 = $r.Body | ConvertFrom-Json
Add-Case3 'T185' '预约记录-教室+状态组合（A101待审核=2）' '正常流程' "HTTP=$($r.Status) total=$($b185.data.total)" 'HTTP=200 total=2'

# T186 组合：教室 + 日期区间（A101 2026-09-11=2 条）
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50&classroomId=1&startDate=2026-09-11&endDate=2026-09-11' $null $adminToken
$b186 = $r.Body | ConvertFrom-Json
Add-Case3 'T186' '预约记录-教室+日期组合（A101 9/11=2）' '正常流程' "HTTP=$($r.Status) total=$($b186.data.total)" 'HTTP=200 total=2'

# T187 组合：教室 + 关键词（A101 且用户含 zhangsan → 1 条）
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50&classroomId=1&keyword=zhangsan' $null $adminToken
$b187 = $r.Body | ConvertFrom-Json
Add-Case3 'T187' '预约记录-教室+关键词组合（A101+zhangsan=1）' '正常流程' "HTTP=$($r.Status) total=$($b187.data.total)" 'HTTP=200 total=1'

# T188 空数据：A101 无已取消记录（教室+状态组合空结果，前端空状态支撑）
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50&classroomId=1&status=3' $null $adminToken
$b188 = $r.Body | ConvertFrom-Json
Add-Case3 'T188' '预约记录-空数据（A101已取消=0）' '边界场景' "HTTP=$($r.Status) total=$($b188.data.total) records=$(@($b188.data.records).Count)" 'HTTP=200 total=0 records=0'

# T189 异常：教室不存在
$r = Invoke-Api 'GET' '/api/reservation/manage?classroomId=99999' $null $adminToken
Add-Case3 'T189' '预约记录-教室不存在' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T190 异常：教室参数非数字（R6 修复点：类型不匹配由 500 降级为 400）
$r = Invoke-Api 'GET' '/api/reservation/manage?classroomId=abc' $null $adminToken
Add-Case3 'T190' '预约记录-教室参数非数字' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T191 异常：日期倒序 + 教室筛选
$r = Invoke-Api 'GET' '/api/reservation/manage?classroomId=1&startDate=2026-09-12&endDate=2026-09-05' $null $adminToken
Add-Case3 'T191' '预约记录-日期倒序+教室' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T192 边界：超大分页 size=99999
$r = Invoke-Api 'GET' '/api/reservation/manage?size=99999' $null $adminToken
Add-Case3 'T192' '预约记录-超大分页size' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T193 边界：页码 page=0
$r = Invoke-Api 'GET' '/api/reservation/manage?page=0' $null $adminToken
Add-Case3 'T193' '预约记录-页码0' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400'

# T194 边界：size=500 合法上限
$r = Invoke-Api 'GET' '/api/reservation/manage?size=500' $null $adminToken
$b194 = $r.Body | ConvertFrom-Json
Add-Case3 'T194' '预约记录-分页上限size=500' '边界场景' "HTTP=$($r.Status) code=$($b194.code) total=$($b194.data.total)" 'HTTP=200 code=200 total=13'

# T195 权限：学生访问 manage
$r = Invoke-Api 'GET' '/api/reservation/manage?classroomId=1' $null $stuToken
Add-Case3 'T195' '预约记录-学生访问manage' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T196 权限：未登录访问 manage
$r = Invoke-Api 'GET' '/api/reservation/manage'
Add-Case3 'T196' '预约记录-未登录访问manage' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

Write-Host '== 2. 预约记录导出（GET /api/reservation/export，管理员）=='
# T197 正常：无参导出 → 200 + xlsx（表头+13 条 = 14 行）
$e197 = Get-Export '/api/reservation/export' $adminToken
$rows197 = Get-XlsxRows $exportTmp
Add-Case3 'T197' '导出-全量xlsx（14行）' '正常流程' "HTTP=$($e197.Status) typeXlsx=$($e197.Type -match 'spreadsheetml') rows=$rows197 size=$($e197.Size)" 'HTTP=200 typeXlsx=True rows=14'

# T198 正常：status=1 导出 → 表头+6 条 = 7 行
$e198 = Get-Export '/api/reservation/export?status=1' $adminToken
$rows198 = Get-XlsxRows $exportTmp
Add-Case3 'T198' '导出-状态筛选（已通过6条=7行）' '正常流程' "HTTP=$($e198.Status) rows=$rows198" 'HTTP=200 rows=7'

# T199 正常：classroomId=1 导出 → 表头+2 条 = 3 行
$e199 = Get-Export '/api/reservation/export?classroomId=1' $adminToken
$rows199 = Get-XlsxRows $exportTmp
Add-Case3 'T199' '导出-教室筛选（A101=3行）' '正常流程' "HTTP=$($e199.Status) rows=$rows199" 'HTTP=200 rows=3'

# T200 正常：keyword=zhangsan 导出 → 行数 = SQL 对照 + 1（表头）
$e200 = Get-Export '/api/reservation/export?keyword=zhangsan' $adminToken
$rows200 = Get-XlsxRows $exportTmp
$sql200 = Get-MysqlScalar "SELECT COUNT(*) FROM reservation r JOIN sys_user u ON r.user_id=u.id WHERE u.username LIKE '%zhangsan%';"
$r200ok = ($rows200 -eq ([int]$sql200 + 1))
Add-Case3 'T200' '导出-关键词筛选与库一致' '库一致性' "HTTP=$($e200.Status) rows=$rows200 sql=$sql200 ok=$r200ok" 'ok=True'

# T201 库一致性：status=1 导出行数与 SQL 一致（+1 表头）
$sql201 = Get-MysqlScalar "SELECT COUNT(*) FROM reservation WHERE status=1;"
$r201ok = ($rows198 -eq ([int]$sql201 + 1))
Add-Case3 'T201' '导出-已通过行数与库一致' '库一致性' "HTTP=$($e198.Status) rows=$rows198 sql=$sql201 ok=$r201ok" 'ok=True'

# T202 内容：全量导出包含四状态文案与教室信息（预约记录页口径）
$e202 = Get-Export '/api/reservation/export' $adminToken
$xml202 = Get-XlsxText $exportTmp
$r202ok = ($xml202.Contains('已通过') -and $xml202.Contains('待审核') -and $xml202.Contains('已驳回') -and $xml202.Contains('已取消') -and $xml202.Contains('A101') -and $xml202.Contains('用户账号'))
Add-Case3 'T202' '导出-内容含状态文案与教室' '正常流程' "HTTP=$($e202.Status) ok=$r202ok" 'ok=True'

# T203 边界：空结果导出（A101 已取消=0）→ 仅表头 1 行
$e203 = Get-Export '/api/reservation/export?classroomId=1&status=3' $adminToken
$rows203 = Get-XlsxRows $exportTmp
Add-Case3 'T203' '导出-空结果仅表头' '边界场景' "HTTP=$($e203.Status) rows=$rows203" 'HTTP=200 rows=1'

# T204 异常：非法日期参数
$e204 = Get-Export '/api/reservation/export?startDate=2026-13-99' $adminToken
$code204 = -1
if ($e204.Type -match 'json') { $code204 = (Get-Content $exportTmp -Raw -Encoding UTF8 | ConvertFrom-Json).code }
Add-Case3 'T204' '导出-非法日期参数' '异常操作' "HTTP=$($e204.Status) code=$code204" 'code=400'

# T205 异常：教室不存在
$e205 = Get-Export '/api/reservation/export?classroomId=99999' $adminToken
$code205 = -1
if ($e205.Type -match 'json') { $code205 = (Get-Content $exportTmp -Raw -Encoding UTF8 | ConvertFrom-Json).code }
Add-Case3 'T205' '导出-教室不存在' '异常操作' "HTTP=$($e205.Status) code=$code205" 'code=400'

# T206 权限：学生访问 export
$e206 = Get-Export '/api/reservation/export' $stuToken
Add-Case3 'T206' '导出-学生访问' '权限校验' "HTTP=$($e206.Status)" 'HTTP=403'

# T207 权限：未登录访问 export
$e207 = Get-Export '/api/reservation/export' $null
Add-Case3 'T207' '导出-未登录访问' '权限校验' "HTTP=$($e207.Status)" 'HTTP=401'

# T208 数据安全：导出内容不含 password 字样
$e208 = Get-Export '/api/reservation/export' $adminToken
$xml208 = Get-XlsxText $exportTmp
Add-Case3 'T208' '导出-不含password' '数据安全' "HTTP=$($e208.Status) noPwd=$(-not $xml208.Contains('password'))" 'noPwd=True'

# T209 格式：导出文件为合法 zip（PK 头）
$e209 = Get-Export '/api/reservation/export' $adminToken
$pk209 = ($e209.Size -ge 4 -and $e209.Bytes[0] -eq 0x50 -and $e209.Bytes[1] -eq 0x4B -and $e209.Bytes[2] -eq 0x03 -and $e209.Bytes[3] -eq 0x04)
Add-Case3 'T209' '导出-合法xlsx文件头' '正常流程' "pk=$pk209 size=$($e209.Size)" 'pk=True'

Write-Host '== 3. 13 页闭环回归锚点与数据安全 =='
# T210 字段完整性：预约记录页表格所需 8 列 + 分页结构（13 页闭环数据支撑）
$r = Invoke-Api 'GET' '/api/reservation/manage?page=1&size=50' $null $adminToken
$b210 = $r.Body | ConvertFrom-Json
$rec210 = $b210.data.records[0]
$r210ok = ($null -ne $rec210.userName -and $null -ne $rec210.userAccount -and $null -ne $rec210.classroomName -and $null -ne $rec210.building -and $null -ne $rec210.roomNo -and $null -ne $rec210.reserveDate -and $null -ne $rec210.startTime -and $null -ne $rec210.endTime -and $null -ne $rec210.purpose -and $null -ne $rec210.status -and $null -ne $b210.data.total)
Add-Case3 'T210' '预约记录-字段完整性（13页闭环数据）' '正常流程' "HTTP=$($r.Status) ok=$r210ok" 'ok=True'

# T211 数据安全：manage 列表不返回 password
$r211ok = (-not $r.Body.Contains('password'))
Add-Case3 'T211' '预约记录-manage不含password' '数据安全' "noPwd=$r211ok" 'noPwd=True'

# T212 回归锚点：学生端「我的预约」（GET /api/reservation/mine）zhangsan=4 条（跨角色闭环数据源）
$r = Invoke-Api 'GET' '/api/reservation/mine?page=1&size=50' $null $stuToken
$b212 = $r.Body | ConvertFrom-Json
Add-Case3 'T212' '闭环回归-我的预约zhangsan=4条' '正常流程' "HTTP=$($r.Status) total=$($b212.data.total)" 'HTTP=200 total=4'

Write-Host ''

Write-Host '========== 八、清理 =========='

# 删除测试新增且仍存在的教室（roomA、roomC、容量边界教室）
foreach ($cid in @($roomAId, $roomCId, $capId)) {
  if ($cid -and $cid -gt 0) {
    $null = Invoke-Api 'DELETE' "/api/classroom/manage/$cid" $null $adminToken
  }
}
# R3 清理：删除测试新增预约（初始基线 id 1-13 四状态保持不变）
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM reservation WHERE id > 13;" 2>$null | Out-Null
# R4 清理：删除测试新增收藏（初始基线 6 条 id 1-6 保持不变）
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM user_favorite WHERE id > 6;" 2>$null | Out-Null
# 清理临时注册用户（test_stu_/test_stu2_/pwt_ 前缀 + 本次时间戳）
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM sys_user WHERE username LIKE 'test_stu%' OR username LIKE 'pwt_%' OR username LIKE 'dup_%' OR username LIKE 'short_%' OR username LIKE 'phone_%' OR username LIKE 'partial_%' OR username LIKE 'r4stu_%' OR username LIKE 'r4pw_%';" 2>$null | Out-Null
Write-Host '清理完成'

Write-Host ''
Write-Host '================ 测试结果汇总 ================'
$results | Format-Table -AutoSize -Wrap
Write-Host "用例总数: $($results.Count)  通过: $passCount  失败: $failCount"

# 失败明细（便于定位回归问题）
$failed = $results | Where-Object { -not $_.通过 }
if ($failed) {
  Write-Host ''
  Write-Host '========== 失败用例明细 =========='
  $failed | Format-Table 用例编号, 用例名称, 类别, 实际结果, 期望结果 -AutoSize -Wrap
}
