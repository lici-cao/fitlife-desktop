<#
.SYNOPSIS
    FitLife P2 验收自检（AI 计划生成主链路）。

.DESCRIPTION
    检查项：
      F. 数据库结构：4 张新表 + plans 版本列 + uk_plans_user_version + hr_rest
      G. 主链路：生成计划 → 校验报告 + 版本链 + 回滚 + 禁忌过滤 + 计量落库 + 管理端点

    不修改任何业务数据以外的东西：会注册/使用 p2check+<随机> 测试账号。
    需要 PowerShell 5.1 或 7+。

.EXAMPLE
    pwsh -File scripts\verify-p2.ps1 -BaseUrl http://localhost:8090

.NOTES
    无 API Key 环境下 source_link=TEMPLATE 是合法降级路径，验收只断言
    “链路完整跑通 + 报告/版本/回滚可用”，不断言必须走大模型。
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
Write-Host "  FitLife P2 验收自检  目标：$BaseUrl" -ForegroundColor Cyan
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
    $tables = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name IN ('knowledge_item','knowledge_rule','prompt_template','ai_call_log');") 2>$null
    Add-Result "F1" "P2 四张新表存在" (($tables | Select-Object -First 1) -eq 4) "knowledge_item/knowledge_rule/prompt_template/ai_call_log（$($tables | Select-Object -First 1)/4）"

    $cols = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='plans' AND column_name IN ('version','parent_id','change_note','validation_report','knowledge_snapshot','source_link','generation_ms','ai_tokens');") 2>$null
    Add-Result "F2" "plans 版本链 8 列" (($cols | Select-Object -First 1) -eq 8) "（$($cols | Select-Object -First 1)/8）"

    $uk = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='plans' AND index_name='uk_plans_user_version' AND non_unique=0;") 2>$null
    Add-Result "F3" "uk_plans_user_version 唯一索引" ((($uk | Select-Object -First 1) -as [int]) -ge 1) ""

    $seed = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT (SELECT COUNT(*) FROM $DbName.knowledge_item), (SELECT COUNT(*) FROM $DbName.knowledge_rule), (SELECT COUNT(*) FROM $DbName.prompt_template WHERE code='PLAN_GEN' AND active=1);") 2>$null
    $seedLine = ($seed | Select-Object -First 1)
    Add-Result "F4" "知识种子（条目/规则/模板）" ($seedLine -match "^24\s+[6-9]\s+1") "实际：$seedLine（期望 24/6/1）"

    $hr = (& $MYSQL "-h$DbHost" "-P$($DbPort)" "-u$DbUser" "-N" "-B" "--default-character-set=utf8mb4" "-e" "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='user_profiles' AND column_name='hr_rest';") 2>$null
    Add-Result "F5" "user_profiles.hr_rest 列" ((($hr | Select-Object -First 1) -as [int]) -ge 1) ""
    $env:MYSQL_PWD = $null
} else {
    Add-Result "F0" "数据库结构检查（未找到 mysql 客户端，跳过）" $false "用 -MysqlPath 指定"
}

# ================================================================ G. 主链路
$stamp = Get-Random -Maximum 99999
$uname = "p2check$stamp"
$plan1 = $null; $plan2 = $null; $token = $null

$reg = Invoke-Api -Method POST -Path "/api/auth/register" -Body (@{ username = $uname; password = "Test123456" } | ConvertTo-Json)
Add-Result "G1" "注册测试账号" ($reg.status -eq 200 -and $reg.json.data.token) "user=$uname"
$token = $reg.json.data.token

if (-not $token) {
    Add-Result "G2" "主链路（无 token，跳过）" $false ""
} else {
    # 生成 v1
    $g1 = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{ goal = "fat_loss"; weeklyDays = 3; sessionMinutes = 45 } | ConvertTo-Json)
    $d1 = $g1.json.data
    $plan1 = $d1.id
    Add-Result "G2" "生成 v1（200）" ($g1.status -eq 200 -and $plan1) "source_link=$($d1.sourceLink) version=$($d1.version)"

    $hasReport = ($null -ne $d1.validationReport) -and ($d1.validationReport.checks.Count -ge 0) -and ($d1.validationReport.score -ne $null)
    Add-Result "G3" "校验报告随计划返回" $hasReport "score=$($d1.validationReport.score) passed=$($d1.validationReport.passed) checks=$($d1.validationReport.checks.Count) 项"

    $hasRisk = ($d1.riskNotes.Count -ge 1)
    Add-Result "G4" "风险提示（靶心率区间等）" $hasRisk ($d1.riskNotes | Select-Object -First 1)

    Add-Result "G5" "v1 版本号=1" (($d1.version -as [int]) -eq 1) ""

    # force → v2，带 parent 链
    $g2 = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{ goal = "fat_loss"; weeklyDays = 3; sessionMinutes = 45; force = $true } | ConvertTo-Json)
    $d2 = $g2.json.data
    $plan2 = $d2.id
    $chainOk = ($g2.status -eq 200) -and (($d2.version -as [int]) -eq 2) -and (($d2.parentId -as [long]) -eq $plan1) -and $d2.changeNote
    Add-Result "G6" "force 重生成 → v2 + parent_id + change_note" $chainOk "v$($d2.version) parent=$($d2.parentId) note=$($d2.changeNote)"

    $cur = Invoke-Api -Path "/api/plan/current" -Token $token
    $curOk = ($cur.json.data.id -eq $plan2) -and ($cur.json.data.version -as [int]) -eq 2
    Add-Result "G7" "current 指向 v2，旧版归档" $curOk ""

    # 回滚 v1
    $act = Invoke-Api -Method POST -Path "/api/plan/$plan1/activate" -Token $token
    $actOk = ($act.status -eq 200) -and ($act.json.message -match "v1")
    Add-Result "G8" "回滚 v1 生效" $actOk "$($act.json.message)"

    $cur2 = Invoke-Api -Path "/api/plan/current" -Token $token
    Add-Result "G9" "回滚后 current=v1" ($cur2.json.data.id -eq $plan1) ""

    # 列表带版本信息
    $list = Invoke-Api -Path "/api/plan/list" -Token $token
    $first = $list.json.data | Select-Object -First 1
    Add-Result "G10" "历史列表带 version/sourceLink" (($null -ne $first.version) -and $first.sourceLink) "共 $($list.json.data.Count) 版"

    # 禁忌过滤：给档案设伤病再生成（equipments/injuries 是逗号分隔字符串，不是数组）
    $prof = Invoke-Api -Method PUT -Path "/api/data/profile" -Token $token -Body (@{ gender = "male"; age = 30; height = 175; weight = 70; goal = "fat_loss"; activityLevel = "moderate"; experience = "初级"; weeklyDays = 3; sessionMinutes = 45; equipments = "徒手"; injuries = "膝关节" } | ConvertTo-Json)
    $profOk = ($prof.status -eq 200)
    if (-not $profOk) { Add-Result "G11a" "档案写入伤病失败" $false "HTTP $($prof.status) $($prof.json.message)" }
    $g3 = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{ goal = "fat_loss"; weeklyDays = 3; sessionMinutes = 45; force = $true } | ConvertTo-Json)
    $d3 = $g3.json.data
    $viol = 0
    foreach ($day in $d3.days) {
        foreach ($item in $day.items) {
            $ex = Invoke-Api -Path "/api/exercise/$($item.exerciseId)" -Token $token
            if ($ex.json.data.contraindication -and $ex.json.data.contraindication -match "膝") { $viol++ }
        }
    }
    Add-Result "G11" "膝伤用户计划不含膝关节禁忌动作" ($viol -eq 0) "违例动作 $viol 个（候选筛选+校验+修复三层过滤）"

    # 幂等回放仍工作（P1 能力回归在主链路上）
    $key = [guid]::NewGuid().ToString()
    $h = @{ "Idempotency-Key" = $key }
    $i1 = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{ weeklyDays = 3; sessionMinutes = 45; force = $false } | ConvertTo-Json) -Headers $h
    $i2 = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{ weeklyDays = 3; sessionMinutes = 45; force = $false } | ConvertTo-Json) -Headers $h
    $replay = ($i2.status -eq 200) -and ($i2.headers["Idempotent-Replay"] -eq "true")
    Add-Result "G12" "主链路幂等回放" $replay "两次均 $($i1.status)/$($i2.status)"

    # 管理端点鉴权
    $kn = Invoke-Api -Path "/api/admin/knowledge-items" -Token $token
    Add-Result "G13" "普通用户访问知识端点 403" ($kn.status -eq 403) "实际 $($kn.status)"

    $login = Invoke-Api -Method POST -Path "/api/auth/login" -Body (@{ username = "dandan"; password = "123456" } | ConvertTo-Json)
    $adminToken = $login.json.data.token
    if ($adminToken) {
        $ka = Invoke-Api -Path "/api/admin/knowledge-items" -Token $adminToken
        $total = $ka.json.data.total
        Add-Result "G14" "管理员读取知识条目" ($ka.status -eq 200 -and ($total -as [int]) -ge 24) "total=$total"
        $kr = Invoke-Api -Path "/api/admin/knowledge-rules" -Token $adminToken
        Add-Result "G15" "管理员读取规则表" ($kr.status -eq 200 -and $kr.json.data.Count -ge 6) "$($kr.json.data.Count) 条"
        $pt = Invoke-Api -Path "/api/admin/prompt-templates/PLAN_GEN" -Token $adminToken
        Add-Result "G16" "管理员读取 Prompt 模板" ($pt.status -eq 200 -and $pt.json.data[0].version -eq 1) "v$($pt.json.data[0].version)"
        $calls = Invoke-Api -Path "/api/admin/ai-calls?endpoint=PLAN_GENERATE" -Token $adminToken
        Add-Result "G17" "AI 调用计量已落库" ($calls.status -eq 200 -and ($calls.json.data.total -as [int]) -ge 1) "total=$($calls.json.data.total)（含 NO_PROVIDER 降级记录）"
    } else {
        Add-Result "G14" "管理员登录（dandan 不存在，跳过 G14-G17）" $false ""
    }

    # 校验报告持久化：detail 接口回读
    $det = Invoke-Api -Path "/api/plan/$plan2" -Token $token
    Add-Result "G18" "报告持久化（detail 回读一致）" ($det.json.data.validationReport.score -eq $d2.validationReport.score) "score=$($det.json.data.validationReport.score)"
}

# ================================================================ 汇总
Write-Host ""
Write-Host "  ------------------------------------------------" -ForegroundColor DarkGray
$fail = @($results | Where-Object { $_.Result -eq "FAIL" })
Write-Host ("  共 {0} 项，PASS {1}，FAIL {2}" -f $results.Count, ($results.Count - $fail.Count), $fail.Count) -ForegroundColor $(if ($fail.Count -eq 0) { "Green" } else { "Red" })
if ($fail.Count -gt 0) {
    Write-Host ""
    $fail | ForEach-Object { Write-Host ("    FAIL {0} {1}  {2}" -f $_.Id, $_.Name, $_.Detail) -ForegroundColor Red }
    exit 1
}
exit 0
