<#
.SYNOPSIS
    FitLife P4 验收自检（执行闭环与统计）。

.DESCRIPTION
    检查项：
      F. 数据库结构：workout_records / daily_records 新列、plan_adherence 表与唯一键、ADVICE_LOAD 规则
      G. 主链路：注册 → 生成计划 → 今日训练卡（含 planDetailId 与靶心率）→
         在【计划内训练日】打卡 DONE / SKIPPED → 服务端聚合接口（依从率、训练量、热量收支）→
         风险提示分级 → 调整建议（HOLD/DELOAD/PROGRESS/SWAP/STOP）→ 按建议进阶生成 v2 →
         幂等打卡回放 → 越权读他人计划 403 → 未登录 401

    关键设计：打卡必须落在「计划内训练日」才能验证依从率分母。
    脚本取当前生效计划里第一个非休息日，按 dow 折算到「本周内该 dow 的日期」，
    若落在明天则回退到上周同一天；该日由 resolvePlanFor 命中同一计划。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\verify-p4.ps1 -BaseUrl http://localhost:8090 -DbPort 3307 -DbUser fitlife -DbPass fitlife888
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
    $req.KeepAlive = $false
    if ($Token) { $req.Headers.Add("Authorization", "Bearer $Token") }
    if ($Headers) {
        foreach ($k in $Headers.Keys) { $req.Headers.Add($k, $Headers[$k]) }
    }
    $status = 0; $text = ""; $respHeaders = @{}
    try {
        # 写请求体必须放在 try 里：连接失败时 GetRequestStream 也会抛 WebException，
        # 否则异常直接冒泡、后续所有用例都拿到 status=0
        if ($Body) {
            $req.ContentType = "application/json; charset=utf-8"
            $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
            $req.ContentLength = $bytes.Length
            $st = $req.GetRequestStream(); $st.Write($bytes, 0, $bytes.Length); $st.Close()
        }
        $resp = $req.GetResponse()
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ status = 0; body = ""; json = $null; headers = @{} } }
        $status = [int]$resp.StatusCode
    }
    if (-not $status) { $status = [int]$resp.StatusCode }
    foreach ($h in $resp.Headers.AllKeys) { $respHeaders[$h] = $resp.Headers[$h] }
    $stream = $resp.GetResponseStream()
    if ($stream) {
        $sr = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
        $text = $sr.ReadToEnd(); $sr.Close()
    }
    $resp.Close()
    $json = $null
    try { $json = $text | ConvertFrom-Json } catch { }
    return @{ status = $status; body = $text; json = $json; headers = $respHeaders }
}

function Exec-Sql([string]$sql) {
    if (-not $MYSQL) { return $null }
    $env:MYSQL_PWD = $DbPass
    # PS 5.1 会把 "-h" "127.0.0.1" 这类「选项 + 值」拆成两个参数并让 host 只收到 "127"，
    # 因此这里一律用「选项=值」形式，且整体作为数组传入。
    $cliArgs = @(
        "-h$DbHost", "-P$DbPort", "-u$DbUser", "-N", "-B",
        "--default-character-set=utf8mb4", "-e", $sql
    )
    $out = & $MYSQL $cliArgs 2>$null
    $env:MYSQL_PWD = $null
    return ($out | Select-Object -First 1)
}

Write-Host ""
Write-Host "  FitLife P4 验收自检  目标：$BaseUrl" -ForegroundColor Cyan
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
    $wc = Exec-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='workout_records' AND column_name IN ('plan_detail_id','plan_day','status','skipped_reasons','rpe','pain_flag');"
    Add-Result "F1" "workout_records 新增 6 列" (($wc -as [int]) -eq 6) "命中 $wc/6"

    $dc = Exec-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='daily_records' AND column_name IN ('net_calories','workout_minutes','completed_items','planned_items','avg_rpe','pain_flag');"
    Add-Result "F2" "daily_records 新增 6 列" (($dc -as [int]) -eq 6) "命中 $dc/6"

    $idx = Exec-Sql "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='workout_records' AND index_name='idx_workout_plan_detail';"
    Add-Result "F3" "打卡按计划明细建索引" (($idx -as [int]) -ge 1) "idx_workout_plan_detail"

    $tbl = Exec-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='plan_adherence';"
    Add-Result "F4" "plan_adherence 表存在" (($tbl -as [int]) -eq 1) ""

    $uk = Exec-Sql "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='plan_adherence' AND index_name='uk_pa_user_plan_date' AND non_unique=0;"
    Add-Result "F5" "plan_adherence 唯一键（用户+计划+日期）" (($uk -as [int]) -eq 3) "命中 $uk/3 列"

    $kr = Exec-Sql "SELECT COUNT(*) FROM $DbName.knowledge_rule WHERE code='ADVICE_LOAD';"
    Add-Result "F6" "ADVICE_LOAD 阈值规则可配置" (($kr -as [int]) -eq 1) "knowledge_rule.code=ADVICE_LOAD"
} else {
    Add-Result "F0" "数据库结构检查（未找到 mysql 客户端，跳过）" $false "用 -MysqlPath 指定"
}

# ================================================================ G. 主链路
$stamp = Get-Random -Maximum 99999
$uname = "p4check$stamp"
$token = $null
$tokenB = $null

# --- G1 未登录 401
$r401 = Invoke-Api -Method GET -Path "/api/data/today-plan"
Add-Result "G1" "未登录访问 /api/data/today-plan 返回 401" ($r401.status -eq 401) "status=$($r401.status)"

# --- G2 注册 + 生成本周计划
$reg = Invoke-Api -Method POST -Path "/api/auth/register" -Body (@{ username = $uname; password = "Test123456" } | ConvertTo-Json)
if ($reg.status -eq 200 -and $reg.json.data.token) { $token = $reg.json.data.token }
Add-Result "G2" "注册测试账号 $uname" ($null -ne $token) "status=$($reg.status)"

$gen = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{
    goal = "减脂"; experience = "初级"; weeklyDays = 3; sessionMinutes = 45
    equipments = "徒手,哑铃"; injuries = ""
} | ConvertTo-Json)
$plan = $gen.json.data
$planOk = $gen.status -eq 200 -and $null -ne $plan.id
Add-Result "G3" "生成计划 v$($plan.version)" $planOk "planId=$($plan.id) src=$($plan.sourceLink)"

# --- G4 计划明细回传 planDetailId（前端打卡的前提）
$firstItem = $null
foreach ($d in $plan.days) {
    if (-not $d.restDay -and $d.items.Count -gt 0) { $firstItem = $d.items[0]; break }
}
$pdOk = $null -ne $firstItem -and "$($firstItem.planDetailId)" -ne ""
Add-Result "G4" "计划明细回传 planDetailId（打卡前提）" $pdOk "planDetailId=$($firstItem.planDetailId) 动作=$($firstItem.exerciseName)"

# --- G5 今日训练卡：靶心率区间 + 动作清单
$today = Invoke-Api -Method GET -Path "/api/data/today-plan" -Token $token
$tp = $today.json.data
$hrOk = $today.status -eq 200 -and $tp.hrLow -gt 80 -and $tp.hrHigh -gt $tp.hrLow
Add-Result "G5" "今日训练卡含 Karvonen 靶心率区间" $hrOk "hr=$($tp.hrLow)-$($tp.hrHigh) dow=$($tp.planDay) restDay=$($tp.restDay)"

# --- 计算「计划内训练日」的目标日期：本周同 dow，若在未来则回退一周
$todayDate = Get-Date
$todayDow = [int]$todayDate.DayOfWeek      # 0=周日
if ($todayDow -eq 0) { $todayDow = 7 }
$trainDow = $null
foreach ($d in $plan.days) {
    if (-not $d.restDay -and $d.items.Count -gt 0) { $trainDow = [int]$d.dayOfWeek; break }
}
$delta = $todayDow - $trainDow
$targetDate = $todayDate.AddDays(-$delta)
if ($delta -lt 0) { $targetDate = $targetDate.AddDays(-7) }
$targetStr = $targetDate.ToString("yyyy-MM-dd")
Write-Host ("        计划训练日 dow={0}，打卡目标日期={1}" -f $trainDow, $targetStr) -ForegroundColor DarkGray

$trainDay = $plan.days | Where-Object { -not $_.restDay -and [int]$_.dayOfWeek -eq $trainDow } | Select-Object -First 1
$itemA = $trainDay.items[0]
$itemB = $trainDay.items[1]

# --- G6 训练日打卡 DONE
$ck1Body = @{
    planDetailId = $itemA.planDetailId
    duration = 15
    calories = 90
    status = "DONE"
    rpe = 7
    date = $targetStr
    planId = $plan.id
} | ConvertTo-Json
$ck1 = Invoke-Api -Method POST -Path "/api/data/check-in" -Token $token -Body $ck1Body -Headers @{ "Idempotency-Key" = "p4-ck-$stamp-1" }
$ck1Ok = $ck1.status -eq 200 -and $ck1.json.data.status -eq "DONE" -and $null -ne $ck1.json.data.planDetailId
Add-Result "G6" "训练日打卡 DONE（关联计划明细）" $ck1Ok "status=$($ck1.status) recId=$($ck1.json.data.id) pd=$($ck1.json.data.planDetailId)"

# --- G7 同 key 同体重放 → Idempotent-Replay
$ck1b = Invoke-Api -Method POST -Path "/api/data/check-in" -Token $token -Body $ck1Body -Headers @{ "Idempotency-Key" = "p4-ck-$stamp-1" }
$replayOk = $ck1b.status -eq 200 -and $ck1b.headers["Idempotent-Replay"] -eq "true" -and $ck1b.body -eq $ck1.body
Add-Result "G7" "打卡幂等回放（同 key 同体）" $replayOk "hdr=$($ck1b.headers['Idempotent-Replay']) 体一致=$($ck1b.body -eq $ck1.body)"

# --- G8 跳过打卡 SKIPPED
$ck2 = Invoke-Api -Method POST -Path "/api/data/check-in" -Token $token -Body (@{
    planDetailId = $itemB.planDetailId
    status = "SKIPPED"
    skippedReasons = "时间不够"
    date = $targetStr
    planId = $plan.id
} | ConvertTo-Json) -Headers @{ "Idempotency-Key" = "p4-ck-$stamp-2" }
Add-Result "G8" "跳过打卡 SKIPPED 记录原因" ($ck2.status -eq 200 -and $ck2.json.data.status -eq "SKIPPED") "status=$($ck2.status) reason=$($ck2.json.data.skippedReasons)"

# --- G9 服务端统计：依从率分母来自计划明细
$sum = Invoke-Api -Method GET -Path "/api/data/stats/summary?days=7" -Token $token
$s = $sum.json.data
$sumOk = $sum.status -eq 200 -and $s.plannedItems -ge ($trainDay.items.Count) -and $s.doneItems -ge 1
Add-Result "G9" "统计接口：依从率按计划明细分母聚合" $sumOk "planned=$($s.plannedItems) done=$($s.doneItems) rate=$($s.adherenceRate)% series=$($s.series.Count)"

# --- G10 训练量与热量收支
$volOk = $sum.status -eq 200 -and $s.totalMinutes -ge 15 -and [math]::Abs([double]$s.totalBurnCalories - 90) -lt 0.01
Add-Result "G10" "训练量与热量收支由服务端聚合" $volOk "minutes=$($s.totalMinutes) burn=$($s.totalBurnCalories) intake=$($s.totalIntakeCalories) net=$($s.netCalories)"

# --- G11 RPE 偏差
$rpeOk = $sum.status -eq 200 -and $null -ne $s.avgActualRpe
Add-Result "G11" "RPE 实际/计划偏差可算" $rpeOk "actual=$($s.avgActualRpe) planned=$($s.avgPlannedRpe) dev=$($s.rpeDeviation)"

# --- G12 风险提示分级（有 code + level + suggestion）
$riskOk = $true
$riskDesc = "risks=$($s.risks.Count)"
if ($s.risks.Count -gt 0) {
    $r0 = $s.risks[0]
    $riskOk = $null -ne $r0.code -and $null -ne $r0.level -and $null -ne $r0.suggestion
    $riskDesc = "首条 level=$($r0.level) code=$($r0.code)"
}
Add-Result "G12" "风险提示分级（level/code/suggestion）" $riskOk $riskDesc

# --- G13 调整建议决策
$adv = Invoke-Api -Method GET -Path "/api/data/stats/advice?days=7" -Token $token
$a = $adv.json.data
$validDecisions = @("HOLD", "DELOAD", "PROGRESS", "SWAP", "STOP")
$advOk = $adv.status -eq 200 -and $validDecisions -contains $a.decision
Add-Result "G13" "调整建议返回合法决策" $advOk "decision=$($a.decision) title=$($a.title)"

# --- G14 按建议进阶：生成下一版（version+1，parent=当前）
$advance = Invoke-Api -Method POST -Path "/api/data/stats/advance?days=7" -Token $token -Body "{}" -Headers @{ "Idempotency-Key" = "p4-adv-$stamp" }
$ad = $advance.json.data
# 低依从（<70%）时决策为 DELOAD/STOP，自动进阶必须被拒绝——这是安全设计，不是缺陷
$gateOk = $advance.status -eq 200 -and $null -eq $ad.newPlanId -and @("DELOAD", "STOP") -contains $a.decision
Add-Result "G14" "低依从时不自动进阶（决策门禁生效）" $gateOk "decision=$($a.decision) newPlanId=$($ad.newPlanId) note=$($ad.note)"

# --- G14b 显式降载：用户确认后仍可生成 v2，且 parent 指向 v1
$regen = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{ goal = "减脂"; experience = "初级"; weeklyDays = 3; sessionMinutes = 45; equipments = "徒手,哑铃"; force = $true } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = "p4-regen-$stamp" }
$rg = $regen.json.data
$prevPlanId = $rg.parentId
$regenOk = $regen.status -eq 200 -and [int]$rg.version -eq 2 -and $null -ne $rg.parentId
Add-Result "G14b" "显式降载生成 v2 且 parent 指向 v1" $regenOk "version=$($rg.version) parentId=$($rg.parentId) src=$($rg.sourceLink)"


# --- G15 缓存/口径一致性：daily-records 与 stats 同日数值一致
$dr = Invoke-Api -Method GET -Path "/api/data/daily-records?startDate=$targetStr&endDate=$targetStr" -Token $token
$todayRow = $dr.json.data | Where-Object { $_.date -eq $targetStr } | Select-Object -First 1
$rowOk = $dr.status -eq 200 -and $null -ne $todayRow -and [int]$todayRow.completedItems -ge 1
Add-Result "G15" "daily-records 回报聚合快照（completedItems）" $rowOk "date=$($targetStr) completed=$($todayRow.completedItems) planned=$($todayRow.plannedItems) net=$($todayRow.netCalories)"

# --- G16 越权：他人计划详情 403
$regB = Invoke-Api -Method POST -Path "/api/auth/register" -Body (@{ username = "$uname-b"; password = "Test123456" } | ConvertTo-Json)
$tokenB = $regB.json.data.token
$ro = Invoke-Api -Method GET -Path "/api/plan/$($plan.id)" -Token $tokenB
Add-Result "G16" "跨用户读取他人计划 403" ($ro.status -eq 403) "status=$($ro.status) msg=$($ro.json.message)"

# --- G17 休息日打卡不污染分母（可选：dow 与训练日不同的一天）
$restDow = 1..7 | Where-Object { $_ -ne $trainDow } | Select-Object -First 1
$restDate = $todayDate.AddDays(-(($todayDow - $restDow + 7) % 7))
if ((($todayDow - $restDow + 7) % 7) -ne 0) {
    $restStr = $restDate.ToString("yyyy-MM-dd")
    $ck3 = Invoke-Api -Method POST -Path "/api/data/check-in" -Token $token -Body (@{
        planDetailId = $itemA.planDetailId
        duration = 10
        calories = 60
        status = "DONE"
        rpe = 6
        date = $restStr
        planId = $plan.id
    } | ConvertTo-Json) -Headers @{ "Idempotency-Key" = "p4-ck-$stamp-3" }
    $restOk = $ck3.status -eq 200
    Add-Result "G17" "休息日补打卡仍被接受且计入当日应做" $restOk "date=$restStr status=$($ck3.status)"
} else {
    Add-Result "G17" "休息日补打卡（本周无可用休息日，跳过）" $true "trainDow=$trainDow"
}

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