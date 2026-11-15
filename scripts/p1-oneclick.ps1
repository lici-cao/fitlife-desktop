<#
.SYNOPSIS
    FitLife P1 一键改造执行脚本（迁移 → 构建 → 启动 → 自检）。

.DESCRIPTION
    对应开题/报告的 P1 阶段五项改造：
      1. 唯一约束与索引      2. 幂等键
      3. RBAC 与归属切面      4. 密钥外置
      5. 统一响应体

    脚本做四件事：
      - 数据库：执行 sql/migration/V1__p1_hardening.sql（幂等，可重复执行）
      - 构建：  mvn -DskipTests package（生成可执行 jar）
      - 启动：  用 .env / 参数注入 JWT_SECRET、DB 口令，后台启动 jar 并等待 /api/health
      - 自检：  调用 scripts/verify-p1.ps1（可用 -SkipVerify 跳过）

    幂等：重复执行不会重复加列/重复建索引；已存在的 .env 不会被覆盖。

.PARAMETER DbUser / DbPass / DbHost / DbPort / DbName
    数据库连接信息。命令行参数优先级高于 .env。

.PARAMETER MysqlPath
    mysql.exe 的完整路径（PATH 里找不到时用）。

.PARAMETER SkipDb
    跳过数据库迁移（只构建+启动）。

.PARAMETER SkipBuild
    跳过构建，直接启动已有 jar。

.PARAMETER SkipVerify
    启动后不跑自检。

.PARAMETER NoStart
    只做迁移+构建+自检准备，不启动服务（CI 环境用）。

.EXAMPLE
    pwsh -File scripts\p1-oneclick.ps1
    pwsh -File scripts\p1-oneclick.ps1 -DbPass 123456 -MysqlPath "C:\phpstudy_pro\Extensions\MySQL5.7.26\bin\mysql.exe"

.NOTES
    需要：JDK 17+、Maven 3.6+、MySQL 5.7/8、PowerShell 5.1 或 7+。
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
    [switch]$NoStart
)

# 原生程序（java -version、mvn、mysql）会把版本号/告警写到 stderr，
# 在 Stop 模式下会被 PowerShell 当成异常直接中断脚本；这里统一用 Continue，
# 失败判定一律依赖显式的 $LASTEXITCODE 检查与 Fail。
$ErrorActionPreference = "Continue"
$ROOT = Split-Path -Parent $PSScriptRoot
$JAVA_DIR = Join-Path $ROOT "FitLife-java"
$SQL_MAIN = Join-Path $ROOT "fitlife-mysql.sql"
$SQL_MIGRATION = Join-Path $ROOT "sql\migration\V1__p1_hardening.sql"
$ENV_FILE = Join-Path $ROOT ".env"
$LOG_DIR = Join-Path $ROOT "logs"
$VERIFY_SCRIPT = Join-Path $PSScriptRoot "verify-p1.ps1"

function Write-Step($msg) { Write-Host ""; Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    [OK]   $msg" -ForegroundColor Green }
function Write-Info($msg) { Write-Host "    [INFO] $msg" -ForegroundColor Gray }
function Write-Warn2($msg){ Write-Host "    [WARN] $msg" -ForegroundColor Yellow }
function Fail($msg) {
    Write-Host "    [FAIL] $msg" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- 工具定位
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

# ---------------------------------------------------------------- 0. 环境
Write-Host ""
Write-Host "  FitLife P1 一键改造" -ForegroundColor White
Write-Host "  源码根目录：$ROOT" -ForegroundColor DarkGray

Write-Step "0/5 检查运行环境"

if (-not (Test-Path $JAVA_DIR)) { Fail "找不到后端目录：$JAVA_DIR" }
if (-not (Test-Path $SQL_MIGRATION)) { Fail "找不到迁移脚本：$SQL_MIGRATION" }

$JAVA = Find-Exe "java" @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" }),
    "C:\Program Files\Java\jdk-17\bin\java.exe"
)
if (-not $JAVA) { Fail "未找到 java。请安装 JDK 17+ 并配置 JAVA_HOME/PATH。" }
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVer = ((& $JAVA -version 2>&1) | Select-Object -First 1)
$ErrorActionPreference = $prevEap
$javaVer = "$javaVer"
Write-Ok "Java: $JAVA"
Write-Info $javaVer

$MVN = Find-Exe "mvn" @()
if (-not $MVN) {
    $mvnWrapper = Join-Path $JAVA_DIR "mvnw.cmd"
    if (Test-Path $mvnWrapper) { $MVN = $mvnWrapper } else { Fail "未找到 mvn。请安装 Maven 3.6+ 或提供 mvnw。" }
}
Write-Ok "Maven: $MVN"

$MYSQL = $null
if (-not $SkipDb) {
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
    if (-not $MYSQL) { Fail "未找到 mysql.exe。请用 -MysqlPath 指定，或加 -SkipDb 只构建。" }
    Write-Ok "MySQL: $MYSQL"
}

# ---------------------------------------------------------------- 1. 密钥
Write-Step "1/5 准备环境变量（密钥外置）"

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
        "# FitLife 本地环境变量（由 p1-oneclick.ps1 生成，请勿提交到版本库）",
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

# 命令行参数覆盖 .env
if ($PSBoundParameters.ContainsKey("DbUser")) { $env:DB_USER = $DbUser }
if ($PSBoundParameters.ContainsKey("DbPass")) { $env:DB_PASS = $DbPass }
if (-not $env:DB_USER) { $env:DB_USER = $DbUser }
if (-not $env:DB_PASS) { $env:DB_PASS = $DbPass }
$env:DB_URL = "jdbc:mysql://${DbHost}:${DbPort}/${DbName}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"

# ---------------------------------------------------------------- 2. 数据库
if ($SkipDb) {
    Write-Step "2/5 数据库迁移（已跳过）"
} else {
    Write-Step "2/5 执行数据库迁移（幂等）"
    $env:MYSQL_PWD = $env:DB_PASS
    $dbArgs = @("-h", $DbHost, "-P", "$DbPort", "-u", $env:DB_USER, "--default-character-set=utf8mb4")

    # 库不存在时先建库并导入主脚本，避免"迁移脚本先于建库"
    $dbExists = (& $MYSQL @dbArgs -N -B -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DbName';") 2>$null
    if ("$dbExists".Trim() -ne "1") {
        Write-Info "数据库 $DbName 不存在，先导入主脚本（建库+建表+种子数据）"
        if (-not (Test-Path $SQL_MAIN)) { Fail "找不到主脚本：$SQL_MAIN" }
        cmd /c "`"$MYSQL`" -h $DbHost -P $DbPort -u $env:DB_USER --default-character-set=utf8mb4 < `"$SQL_MAIN`""
        if ($LASTEXITCODE -ne 0) { Fail "主脚本导入失败（exit=$LASTEXITCODE）" }
    }

    Write-Info "执行 $SQL_MIGRATION"
    cmd /c "`"$MYSQL`" -h $DbHost -P $DbPort -u $env:DB_USER --default-character-set=utf8mb4 $DbName < `"$SQL_MIGRATION`""
    if ($LASTEXITCODE -ne 0) { Fail "迁移脚本执行失败（exit=$LASTEXITCODE）" }

    $cols = (& $MYSQL @dbArgs -N -B -e "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='users' AND column_name IN ('role','status','last_login_at');") 2>$null
    $tbls = (& $MYSQL @dbArgs -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name IN ('idempotency_key','audit_log');") 2>$null
    Write-Ok "users 新增列 $("$cols".Trim())/3，新表 $("$tbls".Trim())/2"
    $env:MYSQL_PWD = $null
}

# ---------------------------------------------------------------- 3. 构建
if ($SkipBuild) {
    Write-Step "3/5 构建（已跳过）"
    $jar = Get-ChildItem (Join-Path $JAVA_DIR "target\*.jar") -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
} else {
    Write-Step "3/5 构建后端（mvn -DskipTests package）"
    Push-Location $JAVA_DIR
    try {
        & $MVN -B -q -DskipTests package
        if ($LASTEXITCODE -ne 0) { Fail "Maven 构建失败（exit=$LASTEXITCODE）" }
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

# ---------------------------------------------------------------- 5. 自检
if ($SkipVerify) {
    Write-Step "5/5 自检（已跳过）"
} else {
    Write-Step "5/5 运行 P1 验收自检"
    if (-not (Test-Path $VERIFY_SCRIPT)) { Write-Warn2 "找不到 $VERIFY_SCRIPT，跳过" }
    else {
        & $VERIFY_SCRIPT -BaseUrl "http://localhost:$BackendPort" -DbUser $env:DB_USER -DbPass $env:DB_PASS `
            -DbHost $DbHost -DbPort $DbPort -DbName $DbName -MysqlPath $MYSQL
        if ($LASTEXITCODE -ne 0) { Fail "P1 自检存在失败项，见上方报告" }
    }
}

Write-Host ""
Write-Host "  P1 改造执行完成。" -ForegroundColor Green
Write-Host "  演示账号：dandan / 123456（管理员）" -ForegroundColor Gray
Write-Host "  后台接口：/api/admin/users、/api/admin/audit-logs" -ForegroundColor Gray
Write-Host ""