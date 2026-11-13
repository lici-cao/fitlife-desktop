<#
.SYNOPSIS
    FitLife P3 验收自检（多轮对话与局部修订）。

.DESCRIPTION
    检查项：
      F. 数据库结构：chat_session / chat_message 两张新表 + 唯一约束与索引
      G. 主链路：生成 v1 → 局部替换 v2（Diff）→ 同会话移除 v3 →
         幂等回放（同 key 同体回放 / 同 key 异体 409）→ 回滚 activate →
         消息留痕落库 → 无 ACTIVE 计划时的引导回复 → 未登录 401

    不修改任何业务数据以外的东西：会注册/使用 p3check+<随机> 测试账号。
    需要 PowerShell 5.1 或 7+。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\verify-p3.ps1 -BaseUrl http://localhost:8090

.NOTES
    无 API Key 环境下意图路由走关键词规则（intentByLlm=false），
    局部修订全部在服务端规则层完成，验收不依赖大模型。
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
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$results = New-Object System.Collections.ArrayList
function Add-Result([string]$id, [string]$name, [bool]$ok, [string]$detail) {
    $tag = if ($ok) { "PASS" } else { "FAIL" }
    $color = if ($ok) { "Green" } else { "Red" }
    [void]$results.Add([pscustomobject]@{ Id = $id; Name = $name; Result = $tag; Detail = $detail })
    Write-Host ("  [{0}] {1}  {2}" -f $tag, $id, $name) -ForegroundColor $color
    if ($detail) { Write-Host ("        {0}" -f $detail) -ForegroundColor DarkGray }
}

# PS 5.1 兼容的 HTTP 调用：4xx 也能读到响应体与响应头
function Invoke-Api {
    param(
        [string]$Method = "GET",
        [string]$Path,
        [string]$Token,
        [string]$Body,
        [hashtable]$Headers
    )
    $uri = "$BaseUrl$Path"
    $req = [System.Net.HttpWebRequest]::Create($uri)
    $req.Method = $Method
    $req.Timeout = 130000
    if ($Token) { $req.Headers.Add("Authorization", "Bearer $Token") }
    if ($Headers) {
        foreach ($k in $Headers.Keys) { $req.Headers.Add($k, $Headers[$k]) }
    }
    if ($Body) {
        $req.ContentType = "application/json; charset=utf-8"
        $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
        $req.ContentLength = $bytes.Length
        $st = $req.GetRequestStream(); $st.Write($bytes, 0, $bytes.Length); $st.Close()
    }
    $status = 0; $text = ""; $respHeaders = @{}
    try {
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($resp) { $status = [int]$resp.StatusCode }
        else { return @{ status = 0; body = ""; json = $null; headers = @{} } }
    }
    if (-not $status) { $status = [int]$resp.StatusCode }
    foreach ($h in $resp.Headers.AllKeys) { $respHeaders[$h] = $resp.Headers[$h] }
    $sr = New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)
    $text = $sr.ReadToEnd(); $sr.Close(); $resp.Close()
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { }
    return @{ status = $status; body = $text; json = $json; headers = $respHeaders }
}

Write-Host ""
Write-Host "  FitLife P3 验收自检  目标：$BaseUrl" -ForegroundColor Cyan
Write-Host "  ------------------------------------------------" -ForegroundColor DarkGray

# ================================================================ F. 数据库结构
$MYSQL = $null
if ($MysqlPath -and (Test-Path $MysqlPath)) { $MYSQL = $MysqlPath }
else { $c = Get-Command mysql -ErrorAction SilentlyContinue; if ($c) { $MYSQL = $c.Source } }
if (-not $MYSQL) { $m = Get-Command mariadb -ErrorAction SilentlyContinue; if ($m) { $MYSQL = $m.Source } }
if (-not $MYSQL -and (Test-Path 'C:\fitlife_tools\mdb\mariadb-11.4.13-winx64\bin\mariadb.exe')) {
    $MYSQL = 'C:\fitlife_tools\mdb\mariadb-11.4.13-winx64\bin\mariadb.exe'
}

if ($MYSQL) {
    $env:MYSQL_PWD = $DbPass
    $tables = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name IN ('chat_session','chat_message');") 2>$null
    Add-Result "F1" "P3 两张新表存在" (($tables | Select-Object -First 1) -eq 2) "chat_session / chat_message（$($tables | Select-Object -First 1)/2）"

    $uk = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='chat_session' AND index_name IN ('uk_cs_session_key','idx_cs_user');") 2>$null
    Add-Result "F2" "chat_session 唯一键+用户索引" ((($uk | Select-Object -First 1) -as [int]) -ge 2) "uk_cs_session_key / idx_cs_user"

    $idx = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='chat_message' AND index_name='idx_cm_session';") 2>$null
    Add-Result "F3" "chat_message 会话索引" ((($idx | Select-Object -First 1) -as [int]) -ge 1) ""
    $env:MYSQL_PWD = $null
} else {
    Add-Result "F0" "数据库结构检查（未找到 mysql 客户端，跳过）" $false "用 -MysqlPath 指定"
}

# ================================================================ G. 主链路
$stamp = Get-Random -Maximum 99999
$uname = "p3check$stamp"
$token = $null

# --- G1 未登录 401
$r401 = Invoke-Api -Method POST -Path "/api/plan/revise" -Body '{"message":"把周三的深蹲换成臀桥"}'
Add-Result "G1" "未登录访问 /api/plan/revise 返回 401" ($r401.status -eq 401) "status=$($r401.status)"

# --- G2 注册并生成 v1
$reg = Invoke-Api -Method POST -Path "/api/auth/register" -Body (@{ username = $uname; password = "Test123456" } | ConvertTo-Json)
if ($reg.status -eq 200 -and $reg.json.data.token) { $token = $reg.json.data.token }
Add-Result "G2" "注册测试账号 $uname" ($null -ne $token) "status=$($reg.status)"

$gen = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{
    goal = "减脂"; experience = "初级"; weeklyDays = 3; sessionMinutes = 45
    equipments = "徒手,哑铃"; injuries = ""
} | ConvertTo-Json)
$genOk = $gen.status -eq 200 -and $gen.json.data.version -eq 1
Add-Result "G3" "生成计划 v1（离线降级 TEMPLATE 合法）" $genOk "status=$($gen.status) v=$($gen.json.data.version) src=$($gen.json.data.sourceLink)"

# --- G4 局部替换 → v2 + Diff
$plan1 = $gen.json.data
$trainDay = $plan1.days | Where-Object { -not $_.restDay } | Select-Object -First 1
$target = $trainDay.items[0].exerciseName
$alt = $trainDay.items[1].exerciseName
if (-not $alt) { $alt = "俯卧撑" }
$msg1 = "把$($trainDay.dayLabel)的${target}换成${alt}"

$rev1 = Invoke-Api -Method POST -Path "/api/plan/revise" -Token $token -Body (@{ message = $msg1 } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString("N") }
$rb1 = $rev1.json.data
$revOk = $rev1.status -eq 200 -and $rb1.action -eq "REVISE" -and $rb1.plan.version -eq 2 -and $rb1.plan.sourceLink -eq "REVISED" -and $rb1.diff.Count -ge 1
Add-Result "G4" "局部替换：v1→v2 sourceLink=REVISED + Diff" $revOk "action=$($rb1.action) v=$($rb1.plan.version) parent=$($rb1.plan.parentId) diff=$($rb1.diff.Count)"
if ($rb1.diff) {
    foreach ($d in $rb1.diff) {
        Write-Host ("        diff: {0} {1} {2} :: {3}" -f $d.type, $d.dayLabel, $d.exerciseName, $d.detail) -ForegroundColor DarkGray
    }
}
$sessionKey = $rb1.sessionKey

# --- G5 同会话移除 → v3
$msg2 = "去掉${alt}"
$rev2 = Invoke-Api -Method POST -Path "/api/plan/revise" -Token $token -Body (@{ message = $msg2; sessionKey = "$sessionKey" } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString("N") }
$rb2 = $rev2.json.data
$rev2Ok = $rev2.status -eq 200 -and $rb2.action -eq "REVISE" -and $rb2.plan.version -eq 3
Add-Result "G5" "同会话第二轮移除：v2→v3" $rev2Ok "action=$($rb2.action) v=$($rb2.plan.version) parent=$($rb2.plan.parentId)"

# --- G6 幂等回放：同 key 同 body 两次 → 200 + Idempotent-Replay: true
$idemKey = "p3-verify-$stamp"
$rv1 = Invoke-Api -Method POST -Path "/api/plan/revise" -Token $token -Body (@{ message = $msg2; sessionKey = "$sessionKey" } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = $idemKey }
$rv2 = Invoke-Api -Method POST -Path "/api/plan/revise" -Token $token -Body (@{ message = $msg2; sessionKey = "$sessionKey" } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = $idemKey }
$replayHdr = $rv2.headers["Idempotent-Replay"]
$replayOk = ($rv1.status -eq 200) -and ($rv2.status -eq 200) -and ($replayHdr -eq "true") -and ($rv1.body -eq $rv2.body)
Add-Result "G6" "幂等回放：同 key 同 body 回放" $replayOk "first=$($rv1.status) second=$($rv2.status) hdr=$replayHdr body一致=$($rv1.body -eq $rv2.body)"

# --- G7 幂等冲突：同 key 不同 body → 409
$rv3 = Invoke-Api -Method POST -Path "/api/plan/revise" -Token $token -Body (@{ message = "把周一的快走换成慢走" } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = $idemKey }
Add-Result "G7" "幂等冲突：同 key 异 body 409" ($rv3.status -eq 409) "status=$($rv3.status) msg=$($rv3.json.message)"

# --- G8 回滚 activate
$act = Invoke-Api -Method POST -Path "/api/plan/$($rb1.plan.parentId)/activate" -Token $token -Body '{}'
Add-Result "G8" "回滚到 v1（activate）" ($act.status -eq 200) "status=$($act.status) msg=$($act.json.message)"

# --- G9 消息留痕：chat_message ≥ 4 行
$msgRows = 0
if ($MYSQL) {
    $env:MYSQL_PWD = $DbPass
    $msgRows = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "-e" "SELECT COUNT(*) FROM $DbName.chat_message m JOIN $DbName.chat_session s ON m.session_id=s.id WHERE s.user_id=(SELECT id FROM $DbName.users WHERE username='$uname');") 2>$null
    $msgRows = ($msgRows | Select-Object -First 1) -as [int]
    $env:MYSQL_PWD = $null
}
Add-Result "G9" "会话消息服务端留痕（≥4 行成对）" ($msgRows -ge 4) "chat_message rows=$msgRows（user=$uname）"

# --- G10 无 ACTIVE 计划时的引导回复（REVISE 不可用）
$reg2 = Invoke-Api -Method POST -Path "/api/auth/register" -Body (@{ username = "$uname-b"; password = "Test123456" } | ConvertTo-Json)
$token2 = $reg2.json.data.token
$noPlan = Invoke-Api -Method POST -Path "/api/plan/revise" -Token $token2 -Body '{"message":"把周三的深蹲换成臀桥"}' -Headers @{ "Idempotency-Key" = [guid]::NewGuid().ToString("N") }
$npOk = $noPlan.status -eq 200 -and $noPlan.json.data.action -ne "REVISE"
Add-Result "G10" "无 ACTIVE 计划时修订请求不落库（引导生成）" $npOk "status=$($noPlan.status) action=$($noPlan.json.data.action)"

# ================================================================ 汇总
Write-Host ""
$fail = @($results | Where-Object { $_.Result -eq "FAIL" })
$pass = @($results | Where-Object { $_.Result -eq "PASS" })
Write-Host ("  合计：{0} 项通过，{1} 项失败" -f $pass.Count, $fail.Count) -ForegroundColor $(if ($fail.Count) { "Red" } else { "Green" })
if ($fail.Count) {
    $fail | ForEach-Object { Write-Host ("    ✗ {0} {1} {2}" -f $_.Id, $_.Name, $_.Detail) -ForegroundColor Red }
    exit 1
}
exit 0
