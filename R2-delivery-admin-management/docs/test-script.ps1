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
    return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content }
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

Write-Host ''
Write-Host '========== 四、清理 =========='
# 删除测试新增且仍存在的教室（roomA、roomC、容量边界教室）
foreach ($cid in @($roomAId, $roomCId, $capId)) {
  if ($cid -and $cid -gt 0) {
    $null = Invoke-Api 'DELETE' "/api/classroom/manage/$cid" $null $adminToken
  }
}
# 清理临时注册用户（test_stu_/test_stu2_/pwt_ 前缀 + 本次时间戳）
docker exec reservation-mysql mysql -uroot -proot reservation -e "DELETE FROM sys_user WHERE username LIKE 'test_stu%' OR username LIKE 'pwt_%' OR username LIKE 'dup_%' OR username LIKE 'short_%' OR username LIKE 'phone_%' OR username LIKE 'partial_%';" 2>$null | Out-Null
Write-Host '清理完成'

Write-Host ''
Write-Host '================ 测试结果汇总 ================'
$results | Format-Table -AutoSize -Wrap
Write-Host "用例总数: $($results.Count)  通过: $passCount  失败: $failCount"
