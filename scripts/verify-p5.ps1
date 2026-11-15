<#
.SYNOPSIS
    FitLife P5 验收自检（科学性可溯源、动作库寻源与投稿审核、饮食方案）。

.DESCRIPTION
    检查项：
      F. 数据库结构：exercises 12 个来源/审核列、knowledge_item 4 个来源列、plans 6 个饮食/引用列、
         food_item / diet_plan_item / exercise_submission 三张表、CONTENT_REVIEW 与 DIET_TARGET 两条规则、
         动作库寻源覆盖率
      G. 主链路：注册 → 生成计划（引用清单 + 科学性说明 + 每条动作来源链接 + 饮食方案含热量单位）→
         动作库寻源统计 → 食物库（每份热量）→ 投稿（合法 PENDING / 非法自动 REJECTED）→
         管理员审核通过（写入动作库并带来源）→ 驳回必须给原因 → 内容统计 → 普通用户 403 → 未登录 401

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\verify-p5.ps1 -BaseUrl http://localhost:8090 -DbPort 3307 -DbUser fitlife -DbPass fitlife888
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
    # PS 5.1 会把 "-h" "127.0.0.1" 这类「选项 + 值」拆成两个参数，一律用「选项 + 值紧贴」形式
    $cliArgs = @(
        "-h$DbHost", "-P$DbPort", "-u$DbUser", "-N", "-B",
        "--default-character-set=utf8mb4", "-e", $sql
    )
    $out = & $MYSQL $cliArgs 2>$null
    $env:MYSQL_PWD = $null
    return ($out | Select-Object -First 1)
}

Write-Host ""
Write-Host "  FitLife P5 验收自检  目标：$BaseUrl" -ForegroundColor Cyan
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
    $ex = Exec-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='exercises' AND column_name IN ('source_type','source_title','source_url','source_page','evidence_level','license_note','review_status','submitted_by','reviewed_by','reviewed_at','review_note','last_verified_at');"
    Add-Result "F1" "exercises 新增 12 个来源/审核列" (($ex -as [int]) -eq 12) "命中 $ex/12"

    $ki = Exec-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='knowledge_item' AND column_name IN ('publisher','source_title','source_url','evidence_level');"
    Add-Result "F2" "knowledge_item 新增 4 个来源列" (($ki -as [int]) -eq 4) "命中 $ki/4"

    $pl = Exec-Sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='plans' AND column_name IN ('diet_enabled','daily_kcal_target','protein_target','carb_target','fat_target','references_json');"
    Add-Result "F3" "plans 新增 6 个饮食/引用列" (($pl -as [int]) -eq 6) "命中 $pl/6"

    $tb = Exec-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name IN ('food_item','diet_plan_item','exercise_submission');"
    Add-Result "F4" "food_item / diet_plan_item / exercise_submission 三张表" (($tb -as [int]) -eq 3) "命中 $tb/3"

    $uk = Exec-Sql "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='food_item' AND index_name='uk_food_name' AND non_unique=0;"
    Add-Result "F5" "食物名唯一（防同一食物重复入库）" (($uk -as [int]) -ge 1) "uk_food_name"

    $kr = Exec-Sql "SELECT COUNT(*) FROM $DbName.knowledge_rule WHERE code IN ('CONTENT_REVIEW','DIET_TARGET');"
    Add-Result "F6" "CONTENT_REVIEW / DIET_TARGET 阈值规则可配置" (($kr -as [int]) -eq 2) "命中 $kr/2"

    $cov = Exec-Sql "SELECT CONCAT((SELECT COUNT(*) FROM $DbName.exercises WHERE source_url IS NOT NULL AND source_url <> ''), '/', (SELECT COUNT(*) FROM $DbName.exercises));"
    $covOk = $false
    if ($cov -match '^(\d+)/(\d+)$') { $covOk = ([int]$Matches[1] -eq [int]$Matches[2]) -and ([int]$Matches[2] -gt 0) }
    Add-Result "F7" "动作库全部条目标注来源链接" $covOk "覆盖 $cov"

    $fd = Exec-Sql "SELECT COUNT(*) FROM $DbName.food_item WHERE enabled=1;"
    Add-Result "F8" "食物库已装载（含单位与每份克数）" (([int]$fd) -ge 30) "enabled=$fd 条"
} else {
    Add-Result "F0" "数据库结构检查（未找到 mysql 客户端，跳过）" $false "用 -MysqlPath 指定"
}

# ================================================================ G. 主链路
$stamp = Get-Random -Maximum 99999
$uname = "p5check$stamp"
$token = $null

# --- G1 未登录投稿 401
$r401 = Invoke-Api -Method POST -Path "/api/exercise/submit" -Body (@{ name = "x" } | ConvertTo-Json)
Add-Result "G1" "未登录投稿返回 401" ($r401.status -eq 401) "status=$($r401.status)"

# --- G2 注册
$reg = Invoke-Api -Method POST -Path "/api/auth/register" -Body (@{ username = $uname; password = "Test123456" } | ConvertTo-Json)
if ($reg.status -eq 200 -and $reg.json.data.token) { $token = $reg.json.data.token }
Add-Result "G2" "注册测试账号 $uname" ($null -ne $token) "status=$($reg.status) role=$($reg.json.data.user.role)"

# --- G3 生成计划（含饮食与引用）
$gen = Invoke-Api -Method POST -Path "/api/plan/generate" -Token $token -Body (@{
    goal = "减脂"; experience = "初级"; weeklyDays = 4; sessionMinutes = 50
    equipments = "徒手,哑铃"; injuries = ""
} | ConvertTo-Json) -Headers @{ "Idempotency-Key" = "p5-gen-$stamp" }
$plan = $gen.json.data
Add-Result "G3" "生成计划 v$($plan.version)（来源标记 $($plan.sourceLink)）" ($gen.status -eq 200 -and $null -ne $plan.id) "planId=$($plan.id)"

# --- G4 规则校验报告
$vr = $plan.validationReport
$vrOk = $null -ne $vr -and $null -ne $vr.score -and $vr.checks -ne $null
$riskOk = $plan.riskNotes -ne $null -and $plan.riskNotes.Count -gt 0
Add-Result "G4" "计划附规则校验报告与风险提示" ($vrOk -and $riskOk) "score=$($vr.score) checks=$($vr.checks.Count) risks=$($plan.riskNotes.Count)"

# --- G5 科学性说明（引用证据基座与靶心率公式）
$note = "$($plan.scienceNote)"
$noteOk = $note.Length -gt 60 -and $note -match 'Karvonen' -and $note -match 'FITT' -and ($note -match 'kcal' -or $note -match '千卡')
Add-Result "G5" "科学性说明给出依据与公式（非模型自证）" $noteOk "长度=$($note.Length)"

# --- G6 每条动作带来源链接与证据等级
$firstItem = $null
foreach ($d in $plan.days) { if (-not $d.restDay -and $d.items.Count -gt 0) { $firstItem = $d.items[0]; break } }
$srcOk = $null -ne $firstItem -and "$($firstItem.sourceUrl)" -ne "" -and "$($firstItem.evidenceLevel)" -ne ""
Add-Result "G6" "计划动作明细带来源链接与证据等级" $srcOk "$($firstItem.exerciseName) → $($firstItem.sourceUrl) [$($firstItem.evidenceLevel)]"

# --- G7 饮食方案总体
$dietOk = $plan.dietEnabled -eq $true -and $plan.dietDays.Count -eq 7 -and $plan.dailyKcalTarget -gt 0
Add-Result "G7" "计划同时给出饮食方案（7 天 + 目标宏量）" $dietOk "kcal=$($plan.dailyKcalTarget) P=$($plan.proteinTarget) C=$($plan.carbTarget) F=$($plan.fatTarget)"

# --- G8 每条食物标注热量与单位
$d1 = $plan.dietDays[0]
$di = $d1.items[0]
$itemOk = $di.kcal -gt 0 -and $di.grams -gt 0 -and "$($di.unitLabel)" -ne "" -and "$($di.sourceUrl)" -ne "" -and "$($di.amountDesc)" -ne ""
Add-Result "G8" "食谱条目含 kcal / 克数 / 单位 / 数据来源" $itemOk "$($di.foodName) $($di.amountDesc) = $($di.kcal) kcal"
$near = [math]::Abs([double]$d1.kcalTotal - [double]$plan.dailyKcalTarget) -lt ([double]$plan.dailyKcalTarget * 0.35)
Add-Result "G9" "单日热量落在目标 ±35% 内" $near "day=$([math]::Round($d1.kcalTotal)) target=$([math]::Round($plan.dailyKcalTarget))"

# --- G10 引用清单
$refs = $plan.references
$kinds = (@($refs | ForEach-Object { $_.kind }) | Sort-Object -Unique) -join ","
$noUrl = @($refs | Where-Object { -not $_.url }).Count
$refOk = $refs.Count -ge 5 -and $kinds -match "GUIDELINE" -and $kinds -match "EXERCISE" -and $kinds -match "FOOD" -and $noUrl -eq 0
Add-Result "G10" "引用清单覆盖指南/动作/食物且全部可点开" $refOk "count=$($refs.Count) kinds=$kinds 缺链接=$noUrl"

# --- G11 动作库寻源统计（公开）
$ss = Invoke-Api -Method GET -Path "/api/exercise/sources/stats"
$sd = $ss.json.data
Add-Result "G11" "动作库寻源统计（公开可查）" ($ss.status -eq 200 -and $sd.withoutSource -eq 0) "total=$($sd.total) 未标注=$($sd.withoutSource) A=$($sd.evidenceA) 待审=$($sd.pendingSubmissions)"

# --- G12 食物库与每份热量
$fl = Invoke-Api -Method GET -Path "/api/food/list?category=%E8%9B%8B%E7%99%BD"
$fd0 = @($fl.json.data)[0]
$foodOk = $fl.status -eq 200 -and $null -ne $fd0 -and $fd0.kcalPerUnit -gt 0 -and "$($fd0.sourceUrl)" -ne ""
Add-Result "G12" "食物库给出每 100g 与每份热量" $foodOk "$($fd0.name) 100g=$($fd0.kcalPer100g) 一份($($fd0.householdMeasure))=$($fd0.kcalPerUnit) kcal"

# --- G13 合法投稿 → PENDING
$subName = "弹力带划船$stamp"
$sub = Invoke-Api -Method POST -Path "/api/exercise/submit" -Token $token -Body (@{
    name = $subName; category = "力量"; muscleGroup = "背"; equipment = "弹力带"
    difficulty = 2; metValue = 4.5; defaultSets = 3; defaultReps = "12 次"
    description = "弹力带固定于固点，坐姿后拉至腹部，肩胛后收，避免耸肩。"
    contraindication = "肩关节"
    sourceType = "GUIDELINE"; sourceTitle = "ACSM 运动测试与运动处方指南（第11版）"
    sourceUrl = "https://www.acsm.org/education-resources/books/guidelines-exercise-testing-prescription"
    sourcePage = "Chapter 7"; evidenceLevel = "A"
} | ConvertTo-Json)
$subD = $sub.json.data
$subId = $subD.id
Add-Result "G13" "带来源的投稿进入 PENDING（待人工审核）" ($sub.status -eq 200 -and $subD.status -eq "PENDING") "id=$subId"

# --- G14 非法投稿自动 REJECTED 并给出原因
$badName = "无来源动作$stamp"
$bad = Invoke-Api -Method POST -Path "/api/exercise/submit" -Token $token -Body (@{
    name = $badName; category = "力量"; muscleGroup = "背"; equipment = "弹力带"
    difficulty = 2; metValue = 99; defaultSets = 3; defaultReps = "10 次"
    description = "简短"; sourceType = "COMMUNITY"; sourceTitle = "网帖"
    sourceUrl = "not-a-url"; evidenceLevel = "C"
} | ConvertTo-Json)
$badD = $bad.json.data
$badOk = $badD.status -eq "REJECTED" -and "$($badD.reviewNote)" -match "MET"
Add-Result "G14" "缺来源/MET 异常的投稿被机器校验拦下" $badOk "note=$($badD.reviewNote)"

# --- G15 我的投稿
$my = Invoke-Api -Method GET -Path "/api/exercise/my-submissions" -Token $token
Add-Result "G15" "投稿人可查自己的投稿流水" ($my.status -eq 200 -and @($my.json.data).Count -ge 2) "count=$(@($my.json.data).Count)"

# --- G16 管理员登录
$adm = Invoke-Api -Method POST -Path "/api/auth/login" -Body (@{ username = "dandan"; password = "123456" } | ConvertTo-Json)
$atok = $adm.json.data.token
$role = $adm.json.data.user.role
Add-Result "G16" "管理员账号登录（role=ADMIN）" ($adm.status -eq 200 -and $role -eq "ADMIN") "role=$role"

# --- G17 待审列表与审核口径
$pend = Invoke-Api -Method GET -Path "/api/admin/exercise-submissions?status=PENDING" -Token $atok
$pd = $pend.json.data
$pendOk = $pend.status -eq 200 -and $null -ne $pd.reviewRubric -and $pd.reviewRubric.Count -ge 3
Add-Result "G17" "待审列表 + 审核口径（查什么）" $pendOk "pending=$($pd.pending) 口径=$($pd.reviewRubric.Count) 条"

# --- G18 审核通过后写入动作库并保留来源
$ap = Invoke-Api -Method POST -Path "/api/admin/exercise-submissions/$subId/approve" -Token $atok -Body (@{ note = "来源已核对" } | ConvertTo-Json)
$newExId = $ap.json.data.exerciseId
$lib = Invoke-Api -Method GET -Path "/api/exercise/$newExId"
$libD = $lib.json.data
$apOk = $ap.status -eq 200 -and $null -ne $newExId -and $libD.reviewStatus -eq "APPROVED" -and "$($libD.sourceUrl)" -ne ""
Add-Result "G18" "审核通过→动作入库且带回来源" $apOk "exerciseId=$newExId status=$($libD.reviewStatus) evidence=$($libD.evidenceLevel)"

# --- G19 驳回必须给原因
$sub2 = Invoke-Api -Method POST -Path "/api/exercise/submit" -Token $token -Body (@{
    name = "壶铃摇摆$stamp"; category = "力量"; muscleGroup = "全身"; equipment = "壶铃"
    difficulty = 3; metValue = 9.8; defaultSets = 4; defaultReps = "15 次"
    description = "髋铰链发力，壶铃摆至胸口高度，避免用手臂抬举。"
    contraindication = "腰椎"
    sourceType = "GUIDELINE"; sourceTitle = "NSCA 力量训练与体能训练指南（第4版）"
    sourceUrl = "https://www.nsca.com/education/books/"
    sourcePage = "Chapter 12"; evidenceLevel = "A"
} | ConvertTo-Json)
$sub2Id = $sub2.json.data.id
$rej0 = Invoke-Api -Method POST -Path "/api/admin/exercise-submissions/$sub2Id/reject" -Token $atok -Body (@{ reason = "" } | ConvertTo-Json)
$rej1 = Invoke-Api -Method POST -Path "/api/admin/exercise-submissions/$sub2Id/reject" -Token $atok -Body (@{ reason = "来源页面未能定位到该动作，请补充具体章节" } | ConvertTo-Json)
$rejOk = $rej0.status -eq 400 -and $rej1.status -eq 200
Add-Result "G19" "驳回必须填写原因" $rejOk "空原因=$($rej0.status) 有原因=$($rej1.status)"

# --- G20 内容统计
$cs = Invoke-Api -Method GET -Path "/api/admin/content-stats" -Token $atok
$csd = $cs.json.data
Add-Result "G20" "内容统计（覆盖率 / 证据分布 / 流水）" ($cs.status -eq 200) "coverage=$($csd.exercises.coverage)% food=$($csd.foodItems) 待审=$($csd.submissions.pending)"

# --- G21 普通用户访问审核接口 403
$forbid = Invoke-Api -Method GET -Path "/api/admin/content-stats" -Token $token
Add-Result "G21" "普通用户访问审核接口 403" ($forbid.status -eq 403) "status=$($forbid.status)"

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