# R1 接口测试脚本（正常/边界/异常/权限 四类用例）
# 依赖：后端已启动于 http://localhost:8080
$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'
$results = @()

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
  $script:results += [pscustomobject]@{ 用例编号 = $no; 用例名称 = $name; 类别 = $category; 实际结果 = $actual; 期望结果 = $expect }
}

# ================= 一、正常流程 =================
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

# T03 学生注册成功
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'test_student_01'; password = 'abc123'; confirmPassword = 'abc123'; name = '测试学生'; studentNo = '2025099'; phone = '13900000001'; email = 'test01@stu.edu.cn' }
Add-Case 'T03' '学生注册成功' '正常流程' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200'

# T04 携带Token获取当前用户信息
$r = Invoke-Api 'GET' '/api/user/info' $null $stuToken
Add-Case 'T04' '携带Token获取当前用户信息' '正常流程' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200 返回zhangsan信息'

# T05 管理员登录后获取信息（角色区分）
$r = Invoke-Api 'GET' '/api/user/info' $null $adminToken
$roleCheck = $false
if ($r.Status -eq 200 -and $r.Body) { $b = $r.Body | ConvertFrom-Json; if ($b.data.role -eq 1) { $roleCheck = $true } }
Add-Case 'T05' '管理员信息role=1（角色区分）' '正常流程' "HTTP=$($r.Status) roleIsAdmin=$roleCheck" 'HTTP=200 role=1'

# ================= 二、边界场景 =================
Write-Host '== 边界场景 =='

# T06 注册：两次密码不一致
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'dup_01'; password = 'abc123'; confirmPassword = 'abc124'; name = '测试'; studentNo = '2025001' }
Add-Case 'T06' '注册两次密码不一致' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 两次输入的密码不一致'

# T07 注册：密码长度=6（最小边界，应成功）
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'test_student_02'; password = '123456'; confirmPassword = '123456'; name = '边界学生'; studentNo = '2025098' }
Add-Case 'T07' '注册密码恰好6位（最小边界）' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=200 code=200'

# T08 注册：密码长度=5（不足，应拒绝）
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'test_student_03'; password = '12345'; confirmPassword = '12345'; name = '边界学生'; studentNo = '2025097' }
Add-Case 'T08' '注册密码仅5位（应拒绝）' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 密码长度不能少于6位'

# T09 登录：密码正确但角色选错（学生账号以管理员登录）
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan'; password = '123456'; role = 1 }
Add-Case 'T09' '学生账号以管理员角色登录' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 角色选择与账号类型不匹配'

# T10 注册：手机号格式错误
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'test_student_04'; password = 'abc123'; confirmPassword = 'abc123'; name = '手机号'; studentNo = '2025096'; phone = '12345' }
Add-Case 'T10' '注册手机号格式错误' '边界场景' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 手机号格式不正确'

# ================= 三、异常操作 =================
Write-Host '== 异常操作 =='

# T11 登录：密码错误
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan'; password = 'wrong-pass'; role = 0 }
Add-Case 'T11' '登录密码错误' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 账号或密码错误'

# T12 登录：账号不存在
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'no_such_user'; password = '123456'; role = 0 }
Add-Case 'T12' '登录账号不存在' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 账号或密码错误'

# T13 注册：账号重复
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'zhangsan'; password = 'abc123'; confirmPassword = 'abc123'; name = '重复'; studentNo = '2025095' }
Add-Case 'T13' '注册重复账号' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 该账号已被注册'

# T14 注册：必填项缺失
$r = Invoke-Api 'POST' '/api/user/register' @{ username = 'partial_01'; password = 'abc123'; confirmPassword = 'abc123' }
Add-Case 'T14' '注册缺少姓名/学号' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 请完整填写必填信息'

# T15 登录：请求体缺字段
$r = Invoke-Api 'POST' '/api/user/login' @{ username = 'zhangsan' }
Add-Case 'T15' '登录缺少密码/角色' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 账号、密码、角色不能为空'

# T16 登录：非法JSON结构
$r = Invoke-Api 'POST' '/api/user/login' 'not-json'
Add-Case 'T16' '登录请求体非法JSON' '异常操作' "HTTP=$($r.Status) body=$($r.Body)" 'code=400 请求体格式错误'

# ================= 四、权限校验 =================
Write-Host '== 权限校验 =='

# T17 未登录访问受保护接口
$r = Invoke-Api 'GET' '/api/user/info'
Add-Case 'T17' '未登录访问受保护接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# T18 伪造Token访问
$r = Invoke-Api 'GET' '/api/user/info' $null 'fake.token.12345'
Add-Case 'T18' '伪造Token访问受保护接口' '权限校验' "HTTP=$($r.Status) body=$($r.Body)" 'HTTP=401'

# T19 学生Token访问管理员接口路径
$r = Invoke-Api 'GET' '/api/user/manage' $null $stuToken
Add-Case 'T19' '学生Token访问管理员接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=403'

# T20 管理员Token访问普通受保护接口（应放行）
$r = Invoke-Api 'GET' '/api/user/info' $null $adminToken
Add-Case 'T20' '管理员Token访问普通接口' '权限校验' "HTTP=$($r.Status)" 'HTTP=200'

# T21 过期/非法Token（篡改签名）
$r = Invoke-Api 'GET' '/api/user/info' $null 'eyJhbGciOiJIUzI1NiJ9.eyJ1c2VySWQiOiIyIn0.tampered'
Add-Case 'T21' '篡改签名Token访问' '权限校验' "HTTP=$($r.Status)" 'HTTP=401'

# ================= 输出 =================
Write-Host ''
Write-Host '================ 测试结果汇总 ================'
$results | Format-Table -AutoSize -Wrap
$pass = ($results | Where-Object { $_.实际结果 -like "*HTTP=200*" -or $_.实际结果 -like "*code=200*" -or $_.实际结果 -like "*HTTP=401*" -or $_.实际结果 -like "*HTTP=403*" -or $_.实际结果 -like "*code=400*" }) | Measure-Object | Select-Object -ExpandProperty Count
Write-Host "用例总数: $($results.Count)"
