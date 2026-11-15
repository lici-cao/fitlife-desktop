<#
.SYNOPSIS
    FitLife P1 验收自检（数据库约束 + 鉴权 + 幂等 + 统一响应体 + 密钥）。

.DESCRIPTION
    只读/低副作用：会创建一个 verify_ 前缀的临时用户用于鉴权与幂等测试，
    并给它写一条饮食记录（用于验证幂等回放）。不会修改既有用户数据。

    检查项：
      A. 数据库：users.role/status/last_login_at、uk_daily_user_date、
         idx_food_user_date、idx_workout_user_date、idempotency_key、audit_log、演示账号角色
      B. 鉴权：未登录 401、普通用户访问 /api/admin 403、管理员访问 200
      C. 幂等：同 key 同体重放（带 Idempotent-Replay: true）、同 key 异体 409
      D. 响应体：{code, success, message, data} 四字段齐全，success 与 code 一致
      E. 密钥：配置文件不含明文 JWT secret 与 GLM Key

.PARAMETER BaseUrl
    后端地址，默认 http://localhost:8080

.EXAMPLE
    pwsh -File scripts\verify-p1.ps1
    pwsh -File scripts\verify-p1.ps1 -BaseUrl http://localhost:8080 -MysqlPath "C:\phpstudy_pro\Extensions\MySQL5.7.26\bin\mysql.exe"
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$DbUser = "root",
    [string]$DbPass = "888",
    [string]$DbHost = "localhost",
    [int]$DbPort = 3306,
    [string]$DbName = "fitlife",
    [string]$MysqlPath = ""
)

$ErrorActionPreference = "Continue"
$ROOT = Split-Path -Parent $PSScriptRoot
$results = New-Object System.Collections.ArrayList

function Add-Result([string]$id, [string]$name, [string]$status, [string]$detail) {
    [void]$results.Add([pscustomobject]@{ Id = $id; Name = $name; Status = $status; Detail = $detail })
}

function Find-Mysql {
    if ($MysqlPath) {
        if (Test-Path $MysqlPath -PathType Container) {
            $p = Join-Path $MysqlPath "mysql.exe"
            if (Test-Path $p) { return $p }
        } elseif (Test-Path $MysqlPath) { return $MysqlPath }
    }
    $cmd = Get-Command "mysql" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($c in @(
            "C:\phpstudy_pro\Extensions\MySQL5.7.26\bin\mysql.exe",
            "C:\xampp\mysql\bin\mysql.exe",
            "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe")) {
        if (Test-Path $c) { return $c }
    }
    return $null
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$Token = "",
        [hashtable]$ExtraHeaders = @{},
        [string]$Body = ""
    )
    # 用 HttpWebRequest 而不是 Invoke-WebRequest：PS 5.1 的 IWR 在 4xx 时
    # 只能拿到异常，响应体常常读不到；而本脚本所有断言都依赖 4xx 的响应体与响应头。
    $uri = "$BaseUrl$Path"
    $req = [System.Net.HttpWebRequest]::Create($uri)
    $req.Method = $Method
    $req.Timeout = 30000
    $req.AllowAutoRedirect = $false
    if ($Token) { $req.Headers.Add("Authorization", "Bearer $Token") }
    foreach ($k in $ExtraHeaders.Keys) { $req.Headers.Add($k, [string]$ExtraHeaders[$k]) }
    if ($Body) {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentType = "application/json; charset=utf-8"
        $req.ContentLength = $bytes.Length
        $s = $req.GetRequestStream()
        $s.Write($bytes, 0, $bytes.Length)
        $s.Close()
    }

    $resp = $null
    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if (-not $resp) {
            return [pscustomobject]@{ Status = 0; Body = ""; Json = $null; Headers = @{} }
        }
    }

    $status = 0
    $content = ""
    $headers = @{}
    try {
        $status = [int]$resp.StatusCode
        $rs = $resp.GetResponseStream()
        if ($rs) {
            $reader = New-Object System.IO.StreamReader($rs, [System.Text.Encoding]::UTF8)
            $content = $reader.ReadToEnd()
            $reader.Close()
        }
        foreach ($key in $resp.Headers.AllKeys) { $headers[$key] = $resp.Headers[$key] }
        $resp.Close()
    } catch { }

    return [pscustomobject]@{
        Status  = $status
        Body    = $content
        Json    = if ($content) { try { $content | ConvertFrom-Json } catch { $null } } else { $null }
        Headers = $headers
    }
}

function Test-ApiShape($json, [string]$label) {
    if ($null -eq $json) {
        Add-Result "D" "$label：响应体可解析" "FAIL" "响应体不是合法 JSON"
        return $false
    }
    $keys = @("code", "success", "message", "data")
    $missing = @()
    foreach ($k in $keys) {
        if (-not ($json.PSObject.Properties.Name -contains $k)) { $missing += $k }
    }
    if ($missing.Count -gt 0) {
        Add-Result "D" "$label：四字段齐全" "FAIL" "缺字段：$($missing -join ',')"
        return $false
    }
    $code = [int]$json.code
    $expected = ($code -lt 400)
    if ([bool]$json.success -ne $expected) {
        Add-Result "D" "$label：success 与 code 一致" "FAIL" "code=$code 但 success=$($json.success)"
        return $false
    }
    Add-Result "D" "$label：统一响应体" "PASS" "code=$code success=$($json.success)"
    return $true
}

Write-Host ""
Write-Host "  FitLife P1 验收自检" -ForegroundColor White
Write-Host "  后端：$BaseUrl   数据库：$DbHost`:$DbPort/$DbName" -ForegroundColor DarkGray
Write-Host ""

# ============================================================ A. 数据库
$mysql = Find-Mysql
if (-not $mysql) {
    Add-Result "A" "数据库检查" "SKIP" "未找到 mysql.exe，跳过（可用 -MysqlPath 指定）"
} else {
    $env:MYSQL_PWD = $DbPass
    # PS 5.1 会把 "-h" "localhost" 拆成两个参数交给 .NET，host 只收到字面量；必须写成 -hxxx 拼接形式
    $dbArgs = @("-h$DbHost", "-P$DbPort", "-u$DbUser", "-N", "-B", $DbName)
    function Db-Scalar([string]$sql) {
        try {
            $out = & $mysql @dbArgs -e $sql 2>$null
            if ($LASTEXITCODE -ne 0) { return "__ERR__" }
            return ("$out".Trim())
        } catch { return "__ERR__" }
    }

    $colRole = Db-Scalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='users' AND column_name='role';"
    $colStatus = Db-Scalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='users' AND column_name='status';"
    $colLogin = Db-Scalar "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='users' AND column_name='last_login_at';"
    if ($colRole -eq "1" -and $colStatus -eq "1" -and $colLogin -eq "1") {
        Add-Result "A1" "users 具备 role/status/last_login_at" "PASS" "3/3"
    } else {
        Add-Result "A1" "users 具备 role/status/last_login_at" "FAIL" "role=$colRole status=$colStatus last_login_at=$colLogin"
    }

    $uk = Db-Scalar "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='daily_records' AND index_name='uk_daily_user_date' AND non_unique=0;"
    if ($uk -eq "1") { Add-Result "A2" "daily_records 唯一键 uk_daily_user_date" "PASS" "存在" }
    else { Add-Result "A2" "daily_records 唯一键 uk_daily_user_date" "FAIL" "未找到（重复打卡仍可能写入两条）" }

    $idxFood = Db-Scalar "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='food_intakes' AND index_name='idx_food_user_date';"
    $idxWork = Db-Scalar "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='workout_records' AND index_name='idx_workout_user_date';"
    if ($idxFood -eq "1" -and $idxWork -eq "1") {
        Add-Result "A3" "明细表 (user_id,date) 索引" "PASS" "food_intakes / workout_records"
    } else {
        Add-Result "A3" "明细表 (user_id,date) 索引" "FAIL" "food=$idxFood workout=$idxWork"
    }

    $tblIdem = Db-Scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='idempotency_key';"
    $tblAudit = Db-Scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='audit_log';"
    if ($tblIdem -eq "1" -and $tblAudit -eq "1") {
        Add-Result "A4" "idempotency_key / audit_log 表" "PASS" "存在"
    } else {
        Add-Result "A4" "idempotency_key / audit_log 表" "FAIL" "idempotency_key=$tblIdem audit_log=$tblAudit"
    }

    $dandanRole = Db-Scalar "SELECT role FROM users WHERE username='dandan' LIMIT 1;"
    if ($dandanRole -eq "ADMIN") { Add-Result "A5" "演示账号 dandan 为管理员" "PASS" "role=ADMIN" }
    elseif ($dandanRole -eq "__ERR__" -or -not $dandanRole) { Add-Result "A5" "演示账号 dandan 为管理员" "SKIP" "账号不存在" }
    else { Add-Result "A5" "演示账号 dandan 为管理员" "FAIL" "role=$dandanRole" }

    $env:MYSQL_PWD = $null
}

# ============================================================ B. 鉴权
$anon = Invoke-Api -Method "GET" -Path "/api/plan/current"
if ($anon.Status -eq 401) {
    Add-Result "B1" "未登录访问 /api/plan/current 返回 401" "PASS" "status=401"
} else {
    Add-Result "B1" "未登录访问 /api/plan/current 返回 401" "FAIL" "status=$($anon.Status)（鉴权链未生效）"
}
[void](Test-ApiShape $anon.Json "未登录 401")

$suffix = (Get-Date -Format "HHmmss") + (Get-Random -Minimum 100 -Maximum 999)
$verifyUser = "verify_$suffix"
$verifyPass = "Verify@123456"
$reg = Invoke-Api -Method "POST" -Path "/api/auth/register" -Body (@{
        username = $verifyUser
        password = $verifyPass
    } | ConvertTo-Json)
$userToken = $null
if ($reg.Status -eq 200 -and $reg.Json -and $reg.Json.data -and $reg.Json.data.token) {
    $userToken = $reg.Json.data.token
    Add-Result "B2" "注册普通用户" "PASS" "$verifyUser"
    [void](Test-ApiShape $reg.Json "注册响应")
} else {
    Add-Result "B2" "注册普通用户" "FAIL" "status=$($reg.Status) body=$($reg.Body)"
}

if ($userToken) {
    $adminDenied = Invoke-Api -Method "GET" -Path "/api/admin/users" -Token $userToken
    if ($adminDenied.Status -eq 403) {
        Add-Result "B3" "普通用户访问 /api/admin/users 返回 403" "PASS" "status=403"
    } else {
        Add-Result "B3" "普通用户访问 /api/admin/users 返回 403" "FAIL" "status=$($adminDenied.Status)"
    }
    [void](Test-ApiShape $adminDenied.Json "越权 403")
} else {
    Add-Result "B3" "普通用户访问 /api/admin/users 返回 403" "SKIP" "缺少普通用户 token"
}

$adminLogin = Invoke-Api -Method "POST" -Path "/api/auth/login" -Body (@{
        username = "dandan"
        password = "123456"
    } | ConvertTo-Json)
if ($adminLogin.Status -eq 200 -and $adminLogin.Json -and $adminLogin.Json.data -and $adminLogin.Json.data.token) {
    $adminToken = $adminLogin.Json.data.token
    $adminOk = Invoke-Api -Method "GET" -Path "/api/admin/users?page=0&size=5" -Token $adminToken
    if ($adminOk.Status -eq 200) {
        Add-Result "B4" "管理员访问 /api/admin/users 返回 200" "PASS" "status=200"
        [void](Test-ApiShape $adminOk.Json "管理员列表")
    } else {
        Add-Result "B4" "管理员访问 /api/admin/users 返回 200" "FAIL" "status=$($adminOk.Status) body=$($adminOk.Body)"
    }
} else {
    Add-Result "B4" "管理员访问 /api/admin/users 返回 200" "SKIP" "dandan/123456 登录失败（status=$($adminLogin.Status)）"
}

# ============================================================ C. 幂等
if ($userToken) {
    $idemKey = "verify-" + [guid]::NewGuid().ToString("N")
    $body1 = @{ calories = 123.0; foodId = "p1-verify"; amount = 1 } | ConvertTo-Json
    $h1 = Invoke-Api -Method "POST" -Path "/api/data/food-intake" -Token $userToken -ExtraHeaders @{ "Idempotency-Key" = $idemKey } -Body $body1
    $h2 = Invoke-Api -Method "POST" -Path "/api/data/food-intake" -Token $userToken -ExtraHeaders @{ "Idempotency-Key" = $idemKey } -Body $body1

    if ($h1.Status -eq 200 -and $h2.Status -eq 200) {
        Add-Result "C1" "同 key 同体重复提交返回 200" "PASS" "两次均 200"
    } else {
        Add-Result "C1" "同 key 同体重复提交返回 200" "FAIL" "first=$($h1.Status) second=$($h2.Status) body=$($h2.Body)"
    }

    $replay = $null
    try {
        if ($h2.Headers -and $h2.Headers["Idempotent-Replay"]) { $replay = $h2.Headers["Idempotent-Replay"] }
    } catch { $replay = $null }
    if ($replay -is [array]) { $replay = $replay[0] }
    if ($replay -eq "true") {
        Add-Result "C2" "第二次响应带 Idempotent-Replay: true" "PASS" "命中回放"
    } else {
        Add-Result "C2" "第二次响应带 Idempotent-Replay: true" "FAIL" "未命中回放（头值=$replay）"
    }

    if ($h1.Body -eq $h2.Body) {
        Add-Result "C3" "回放响应体与首次一致" "PASS" "字节一致"
    } else {
        Add-Result "C3" "回放响应体与首次一致" "FAIL" "响应体不同"
    }

    $body2 = @{ calories = 456.0; foodId = "p1-verify"; amount = 2 } | ConvertTo-Json
    $conflict = Invoke-Api -Method "POST" -Path "/api/data/food-intake" -Token $userToken -ExtraHeaders @{ "Idempotency-Key" = $idemKey } -Body $body2
    if ($conflict.Status -eq 409) {
        Add-Result "C4" "同 key 不同请求体返回 409" "PASS" "status=409"
    } else {
        Add-Result "C4" "同 key 不同请求体返回 409" "FAIL" "status=$($conflict.Status)"
    }
} else {
    Add-Result "C1" "幂等：同 key 同体重放" "SKIP" "缺少普通用户 token"
    Add-Result "C2" "幂等：回放响应头" "SKIP" "缺少普通用户 token"
    Add-Result "C3" "幂等：回放响应体一致" "SKIP" "缺少普通用户 token"
    Add-Result "C4" "幂等：同 key 异体 409" "SKIP" "缺少普通用户 token"
}

# ============================================================ E. 密钥静态扫描
function Find-Literal([string]$file, [string]$needle) {
    if (-not (Test-Path $file)) { return $false }
    return (Select-String -Path $file -SimpleMatch -Pattern $needle -Quiet)
}
$yml = Join-Path $ROOT "FitLife-java\src\main\resources\application.yml"
$ymlLocal = Join-Path $ROOT "FitLife-java\src\main\resources\application-local.yml"

$hardJwt = Find-Literal $yml "fitlife-secret-key-at-least-256-bits"
$hardGlm = Find-Literal $yml "aeb430a58e9a48a8962069de4bcf8f95"
if (-not $hardJwt -and -not $hardGlm) {
    Add-Result "E1" "application.yml 无明文 JWT secret / GLM Key" "PASS" "已外置为环境变量"
} else {
    $hits = @()
    if ($hardJwt) { $hits += "JWT secret" }
    if ($hardGlm) { $hits += "GLM Key" }
    Add-Result "E1" "application.yml 无明文 JWT secret / GLM Key" "FAIL" "仍存在：$($hits -join ',')"
}
if (Test-Path $ymlLocal) {
    Add-Result "E2" "本地开发配置独立为 application-local.yml" "PASS" "存在（仅本地，注释已说明）"
} else {
    Add-Result "E2" "本地开发配置独立为 application-local.yml" "WARN" "不存在（不影响生产，但本地启动需自行设置 JWT_SECRET）"
}

# ============================================================ 汇总
Write-Host ""
$pass = ($results | Where-Object { $_.Status -eq "PASS" }).Count
$fail = ($results | Where-Object { $_.Status -eq "FAIL" }).Count
$skip = ($results | Where-Object { $_.Status -eq "SKIP" -or $_.Status -eq "WARN" }).Count

$results | ForEach-Object {
    $color = switch ($_.Status) {
        "PASS" { "Green" }
        "FAIL" { "Red" }
        default { "Yellow" }
    }
    $line = "  {0,-4} {1,-5} {2,-44} {3}" -f $_.Id, $_.Status, $_.Name, $_.Detail
    Write-Host $line -ForegroundColor $color
}

Write-Host ""
Write-Host ("  通过 {0} / 失败 {1} / 跳过或告警 {2}" -f $pass, $fail, $skip) -ForegroundColor White
Write-Host ""

if ($fail -gt 0) { exit 1 } else { exit 0 }