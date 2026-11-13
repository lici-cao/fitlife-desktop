<#
.SYNOPSIS
    FitLife P3 一键改造执行脚本（迁移 → 测试构建 → 启动 → 自检 + P1/P2 回归）。

.DESCRIPTION
    对应报告 P3 阶段（对话与修订）五项改造：
      1. 会话与消息持久化（chat_session / chat_message，跨设备留痕）
      2. 上下文装配（档案 + 当前计划 + 校验报告 + 最近 6 轮 → Prompt）
      3. 意图路由（LLM 结构化优先，无 Key/失败退关键词规则）
      4. 局部修订（定位目标动作 → 候选替代 → 重算 → 校验 → v+1 落库 → 结构化 Diff）
      5. 幂等与回滚（revise 端点幂等回放 + activate 版本回滚）

    脚本做五件事：
      - 数据库：依次执行 V1 / V2 / V3 迁移（均幂等）
      - 构建：  mvn package（含 38 项单元测试，测试失败即中断）
      - 启动：  注入 .env 环境变量后后台启动 jar，等待 /api/health
      - 自检：  scripts/verify-p3.ps1（P3 专项）
      - 回归：  scripts/verify-p2.ps1 + verify-p1.ps1（确认既有改造未被破坏）

.PARAMETER DbUser / DbPass / DbHost / DbPort / DbName
    数据库连接信息。命令行参数优先级高于 .env。

.PARAMETER MysqlPath
    mysql.exe / mariadb.exe 的完整路径（PATH 里找不到时用）。

.PARAMETER SkipDb / SkipBuild / SkipVerify / NoStart
    分别跳过：迁移 / 构建 / 自检 / 启动。

.PARAMETER SkipTests
    构建时 -DskipTests（默认会真跑单元测试）。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\p3-oneclick.ps1
    powershell -ExecutionPolicy Bypass -File scripts\p3-oneclick.ps1 -DbPort 3307 -DbUser fitlife -DbPass 888 -BackendPort 8090

.NOTES
    需要：JDK 17+、Maven 3.6+、MySQL 5.7/8 或 MariaDB 10.4+、PowerShell 5.1 或 7+。
    未配置任何模型 API Key 时，意图路由走关键词规则层——修订链路全部在服务端
    规则层完成，验收不依赖大模型（intentByLlm=false 为合法路径）。
#>
[CmdletBinding()]
param(
    [string]$DbUser = "root",
    [string]$DbPass = "888",
    [string]$DbHost = "localhost",
    [int]$DbPort = 3306,
    [string]$DbName = "fitlife",
    [string]$MysqlPath = "",
    [int]$BackendPort = 8080,
    [switch]$SkipDb,
    [switch]$SkipBuild,
    [switch]$SkipVerify,
    [switch]$SkipTests,
    [switch]$NoStart
)

# 原生程序把版本信息写 stderr，Stop 模式会误判为异常；统一 Continue，
# 失败判定只依赖 $LASTEXITCODE 与 Fail。
$ErrorActionPreference = "Continue"
$ROOT = Split-Path -Parent $PSScriptRoot
$JAVA_DIR = Join-Path $ROOT "FitLife-java"
$SQL_MAIN = Join-Path $ROOT "fitlife-mysql.sql"
$SQL_MIG1 = Join-Path $ROOT "sql\migration\V1__p1_hardening.sql"
$SQL_MIG2 = Join-Path $ROOT "sql\migration\V2__p2_ai_pipeline.sql"
$SQL_MIG3 = Join-Path $ROOT "sql\migration\V3__p3_chat_revision.sql"
$ENV_FILE = Join-Path $ROOT ".env"
$LOG_DIR = Join-Path $ROOT "logs"
$VERIFY_P3 = Join-Path $PSScriptRoot "verify-p3.ps1"
$VERIFY_P2 = Join-Path $PSScriptRoot "verify-p2.ps1"
$VERIFY_P1 = Join-Path $PSScriptRoot "verify-p1.ps1"

function Write-Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    [OK]   $msg" -ForegroundColor Green }
function Write-Info($msg) { Write-Host "    [INFO] $msg" -ForegroundColor Gray }
function Write-Warn2($msg){ Write-Host "    [WARN] $msg" -ForegroundColor Yellow }
function Fail($msg) {
    Write-Host "    [FAIL] $msg" -ForegroundColor Red
    exit 1
}

function Find-Exe([string]$name, [string[]]$candidates) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

function Read-EnvFile([string]$path) {
    if (-not (Test-Path $path)) { return }
    Get-Content $path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $idx = $line.IndexOf("=")
        if ($idx -lt 1) { return }
        $k = $line.Substring(0, $idx).Trim()
        $v = $line.Substring($idx + 1).Trim()
        if ($v.StartsWith('"') -and $v.EndsWith('"') -and $v.Length -ge 2) { $v = $v.Substring(1, $v.Length - 2) }
        if (-not [Environment]::GetEnvironmentVariable($k, "Process")) {
            [Environment]::SetEnvironmentVariable($k, $v, "Process")
        }
    }
}

function New-RandomSecret([int]$bytes = 48) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return [Convert]::ToBase64String($buffer)
}

Write-Host ""
Write-Host "  FitLife P3 一键改造（多轮对话与局部修订）" -ForegroundColor White
Write-Host "  源码根目录：$ROOT" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 0. 环境
Write-Step "0/5 检查运行环境"

if (-not (Test-Path $JAVA_DIR))  { Fail "找不到后端目录：$JAVA_DIR" }
if (-not (Test-Path $SQL_MIG3))  { Fail "找不到迁移脚本：$SQL_MIG3" }

$JAVA = Find-Exe "java" @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" }),
    "C:\Program Files\Java\jdk-17\bin\java.exe"
)
if (-not $JAVA) { Fail "未找到 java。请安装 JDK 17+ 并配置 JAVA_HOME/PATH。" }
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVer = "$(((& $JAVA -version 2>&1) | Select-Object -First 1))"
$ErrorActionPreference = $prevEap
Write-Ok "Java: $JAVA"
Write-Info $javaVer

$MVN = Find-Exe "mvn" @()
if (-not $MVN) {
    $mvnWrapper = Join-Path $JAVA_DIR "mvnw.cmd"
    if (Test-Path $mvnWrapper) { $MVN = $mvnWrapper } else { Fail "未找到 mvn。请安装 Maven 3.6+ 或提供 mvnw。" }
}
Write-Ok "Maven: $MVN"

$MYSQL = $null
$mysqlCandidates = @()
if ($MysqlPath) {
    if (Test-Path $MysqlPath -PathType Container) { $mysqlCandidates += (Join-Path $MysqlPath "mysql.exe") }
    else { $mysqlCandidates += $MysqlPath }
}
$mysqlCandidates += @(
    "C:\phpstudy_pro\Extensions\MySQL5.7.26\bin\mysql.exe",
    "C:\xampp\mysql\bin\mysql.exe",
    "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
)
$MYSQL = Find-Exe "mysql" $mysqlCandidates
if (-not $MYSQL) {
    $MARIA = Find-Exe "mariadb" @()
    if ($MARIA) { $MYSQL = $MARIA }
}
if (-not $MYSQL -and -not $SkipDb) {
    Fail "未找到 mysql.exe / mariadb.exe。请用 -MysqlPath 指定，或加 -SkipDb 只构建。"
}
if ($MYSQL) { Write-Ok "数据库客户端: $MYSQL" }

# ---------------------------------------------------------------- 1. 密钥
Write-Step "1/5 准备环境变量（沿用 P1/P2 的 .env 机制）"

if (Test-Path $ENV_FILE) {
    Read-EnvFile $ENV_FILE
    Write-Ok "已加载 $ENV_FILE"
} else {
    $env:JWT_SECRET = New-RandomSecret 48
    $env:DB_USER = $DbUser
    $env:DB_PASS = $DbPass
    $env:DEEPSEEK_API_KEY = ""
    $env:ARK_API_KEY = ""
    $env:ZHIPU_API_KEY = ""
    $lines = @(
        "# FitLife 本地环境变量（由 p3-oneclick.ps1 生成，请勿提交到版本库）",
        "JWT_SECRET=$($env:JWT_SECRET)",
        "DB_USER=$DbUser",
        "DB_PASS=$DbPass",
        "DEEPSEEK_API_KEY=",
        "ARK_API_KEY=",
        "ZHIPU_API_KEY="
    )
    Set-Content -Path $ENV_FILE -Value $lines -Encoding UTF8
    Write-Ok "已生成 $ENV_FILE（含随机 JWT_SECRET）"
}

if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = New-RandomSecret 48
    Add-Content -Path $ENV_FILE -Value "JWT_SECRET=$($env:JWT_SECRET)" -Encoding UTF8
    Write-Warn2 ".env 缺少 JWT_SECRET，已补生成"
}
if ($env:JWT_SECRET.Length -lt 32) { Fail "JWT_SECRET 长度不足 32 字节（当前 $($env:JWT_SECRET.Length)）" }
Write-Ok "JWT_SECRET 就绪（$($env:JWT_SECRET.Length) 字符）"

if ($PSBoundParameters.ContainsKey("DbUser")) { $env:DB_USER = $DbUser }
if ($PSBoundParameters.ContainsKey("DbPass")) { $env:DB_PASS = $DbPass }
if (-not $env:DB_USER) { $env:DB_USER = $DbUser }
if (-not $env:DB_PASS) { $env:DB_PASS = $DbPass }
$env:DB_URL = "jdbc:mysql://${DbHost}:${DbPort}/${DbName}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"

# ---------------------------------------------------------------- 2. 数据库
if ($SkipDb) {
    Write-Step "2/5 数据库迁移（已跳过）"
} else {
    Write-Step "2/5 执行数据库迁移 V1 + V2 + V3（幂等）"
    $env:MYSQL_PWD = $env:DB_PASS
    $dbArgs = @("-h", $DbHost, "-P", "$DbPort", "-u", $env:DB_USER, "--default-character-set=utf8mb4")

    $dbExists = (& $MYSQL @dbArgs -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DbName';") 2>$null
    if ("$dbExists".Trim() -ne "1") {
        Write-Info "数据库 $DbName 不存在，先导入主脚本（建库+建表+种子数据）"
        if (-not (Test-Path $SQL_MAIN)) { Fail "找不到主脚本：$SQL_MAIN" }
        cmd /c "`"$MYSQL`" -h $DbHost -P $DbPort -u $env:DB_USER --default-character-set=utf8mb4 < `"$SQL_MAIN`""
        if ($LASTEXITCODE -ne 0) { Fail "主脚本导入失败（exit=$LASTEXITCODE）" }
    }

    foreach ($mig in @($SQL_MIG1, $SQL_MIG2, $SQL_MIG3)) {
        if (-not (Test-Path $mig)) { Write-Warn2 "找不到 $mig，跳过"; continue }
        Write-Info "执行 $(Split-Path -Leaf $mig)"
        cmd /c "`"$MYSQL`" -h $DbHost -P $DbPort -u $env:DB_USER --default-character-set=utf8mb4 $DbName < `"$mig`""
        if ($LASTEXITCODE -ne 0) { Fail "迁移脚本执行失败：$mig（exit=$LASTEXITCODE）" }
    }

    $ki = (& $MYSQL @dbArgs -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name IN ('knowledge_item','prompt_template','chat_session','chat_message');") 2>$null
    $pv = (& $MYSQL @dbArgs -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name IN ('idempotency_key','audit_log');") 2>$null
    Write-Ok "P2+P3 新表合计 $("$ki".Trim())/4，P1 新表 $("$pv".Trim())/2"
    $env:MYSQL_PWD = $null
}

# ---------------------------------------------------------------- 3. 构建
if ($SkipBuild) {
    Write-Step "3/5 构建（已跳过）"
    $jar = Get-ChildItem (Join-Path $JAVA_DIR "target\*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else {
    if ($SkipTests) {
        Write-Step "3/5 构建后端（mvn -DskipTests package）"
    } else {
        Write-Step "3/5 构建后端并运行单元测试（mvn package，含 38 项测试）"
    }
    Push-Location $JAVA_DIR
    try {
        if ($SkipTests) { & $MVN -B -q -DskipTests package } else { & $MVN -B -q package }
        if ($LASTEXITCODE -ne 0) { Fail "Maven 构建/测试失败（exit=$LASTEXITCODE）。测试失败会在这里暴露。" }
    } finally {
        Pop-Location
    }
    $jar = Get-ChildItem (Join-Path $JAVA_DIR "target\*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { Fail "构建完成但未找到可执行 jar" }
    Write-Ok "产物：$($jar.FullName)"
}

# ---------------------------------------------------------------- 4. 启动
if ($NoStart) {
    Write-Step "4/5 启动（-NoStart，已跳过）"
} else {
    Write-Step "4/5 启动服务"
    if (-not $jar) { Fail "没有可启动的 jar，请先构建" }
    New-Item -ItemType Directory -Force -Path $LOG_DIR | Out-Null
    $outLog = Join-Path $LOG_DIR "backend.out.log"
    $errLog = Join-Path $LOG_DIR "backend.err.log"
    $env:SERVER_PORT = "$BackendPort"

    $proc = Start-Process -FilePath $JAVA -ArgumentList @("-jar", "`"$($jar.FullName)`"", "--server.port=$BackendPort") `
        -WorkingDirectory $JAVA_DIR -RedirectStandardOutput $outLog -RedirectStandardError $errLog -PassThru -WindowStyle Hidden
    Write-Info "PID=$($proc.Id)  日志：$outLog"

    $health = "http://localhost:$BackendPort/api/health"
    $ready = $false
    for ($i = 1; $i -le 60; $i++) {
        Start-Sleep -Seconds 2
        try {
            $r = Invoke-WebRequest -Uri $health -UseBasicParsing -TimeoutSec 3
            if ($r.StatusCode -eq 200) { $ready = $true; break }
        } catch { }
        if ($proc.HasExited) { break }
    }
    if (-not $ready) {
        Write-Warn2 "健康检查未通过，最后 20 行日志："
        if (Test-Path $errLog) { Get-Content $errLog -Tail 20 | ForEach-Object { Write-Host "      $_" } }
        Fail "服务启动失败，请查看 logs\backend.err.log"
    }
    Write-Ok "服务已就绪：http://localhost:$BackendPort"
}

# ---------------------------------------------------------------- 5. 自检 + 回归
if ($SkipVerify) {
    Write-Step "5/5 自检（已跳过）"
} else {
    Write-Step "5/5 运行 P3 验收自检"
    if (-not (Test-Path $VERIFY_P3)) { Write-Warn2 "找不到 $VERIFY_P3，跳过" }
    else {
        & $VERIFY_P3 -BaseUrl "http://localhost:$BackendPort" -DbUser $env:DB_USER -DbPass $env:DB_PASS `
            -DbHost $DbHost -DbPort $DbPort -DbName $DbName -MysqlPath $MYSQL
        if ($LASTEXITCODE -ne 0) { Fail "P3 自检存在失败项，见上方报告" }
    }
    Write-Step "5b 运行 P2 回归自检"
    if (-not (Test-Path $VERIFY_P2)) { Write-Warn2 "找不到 $VERIFY_P2，跳过" }
    else {
        & $VERIFY_P2 -BaseUrl "http://localhost:$BackendPort" -DbUser $env:DB_USER -DbPass $env:DB_PASS `
            -DbHost $DbHost -DbPort $DbPort -DbName $DbName -MysqlPath $MYSQL
        if ($LASTEXITCODE -ne 0) { Fail "P2 回归存在失败项，见上方报告" }
    }
    Write-Step "5c 运行 P1 回归自检"
    if (-not (Test-Path $VERIFY_P1)) { Write-Warn2 "找不到 $VERIFY_P1，跳过" }
    else {
        & $VERIFY_P1 -BaseUrl "http://localhost:$BackendPort" -DbUser $env:DB_USER -DbPass $env:DB_PASS `
            -DbHost $DbHost -DbPort $DbPort -DbName $DbName -MysqlPath $MYSQL
        if ($LASTEXITCODE -ne 0) { Fail "P1 回归存在失败项，见上方报告" }
    }
}

Write-Host ""
Write-Host "  P3 改造执行完成。" -ForegroundColor Green
Write-Host "  主链路：会话落库 → 上下文装配 → 意图路由 → 局部修订(Diff) → v+1 落库 → 可回滚" -ForegroundColor Gray
Write-Host "  无 API Key 时意图路由走关键词规则层，修订链路不依赖大模型" -ForegroundColor Gray
Write-Host "  新端点：POST /api/plan/revise（幂等 + Diff + 会话留痕）" -ForegroundColor Gray
Write-Host ""
