<#
.SYNOPSIS
    P4 改造一键执行与验证：执行闭环与统计（打卡关联 / 聚合接口 / 前端改接 / 风险分级 / 进阶闭环）。

.DESCRIPTION
    步骤：0 环境检查 → 1 读取/生成 .env → 2 执行迁移（V1~V4，幂等）
          → 3 构建（默认真跑单测）→ 4 启动后端 → 5 调 verify-p4 → 5b 回归 verify-p1/p2/p3

.PARAMETER DbHost      MySQL/MariaDB 主机，默认 127.0.0.1
.PARAMETER DbPort      MySQL/MariaDB 端口，默认 3306
.PARAMETER DbUser      数据库账号，默认 root
.PARAMETER DbPass      数据库口令
.PARAMETER DbName      数据库名，默认 fitlife
.PARAMETER MysqlPath   mysql.exe 路径（默认自动探测）
.PARAMETER BackendPort 后端端口，默认 8080
.PARAMETER SkipDb      跳过迁移
.PARAMETER SkipBuild   跳过构建（复用已有 jar）
.PARAMETER SkipTests   构建时跳过单测（不建议，P4 含规则校验器用例）
.PARAMETER SkipVerify  跳过验证
.PARAMETER NoStart     只构建不启动

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\p4-oneclick.ps1 -DbPort 3307 -DbUser fitlife -DbPass fitlife888 -BackendPort 8090
#>
param(
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$DbUser = 'root',
    [string]$DbPass = '',
    [string]$DbName = 'fitlife',
    [string]$MysqlPath = '',
    [int]$BackendPort = 8080,
    [switch]$SkipDb,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$SkipVerify,
    [switch]$NoStart
)

# 原生程序（java -version、mysql）会向 stderr 写正常输出，Stop 模式会误判为异常，统一用 Continue + $LASTEXITCODE 判定
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$ROOT = Split-Path -Parent $PSScriptRoot
$JAVA_DIR = Join-Path $ROOT 'FitLife-java'
$ENV_FILE = Join-Path $ROOT '.env'
$LOG_DIR = Join-Path $ROOT 'logs'
if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR | Out-Null }

$script:Failures = 0

function Write-Step($msg) {
    Write-Host ''
    Write-Host "==> $msg" -ForegroundColor Cyan
}

function Write-Ok($msg) {
    Write-Host "    [OK] $msg" -ForegroundColor Green
}

function Write-Warn2($msg) {
    Write-Host "    [WARN] $msg" -ForegroundColor Yellow
}

function Fail($msg) {
    Write-Host "    [FAIL] $msg" -ForegroundColor Red
    $script:Failures++
}

function Find-Exe($name, $explicit) {
    if ($explicit -and (Test-Path $explicit)) { return $explicit }
    $candidates = @(
        "C:\fitlife_tools\mdb\mariadb-11.4.13-winx64\bin\$name.exe",
        "C:\phpstudy_pro\Extensions\MySQL5.7.26\bin\$name.exe",
        "C:\phpstudy_pro\Extensions\MySQL8.0.12\bin\$name.exe",
        "C:\xampp\mysql\bin\$name.exe",
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\$name.exe",
        "C:\Program Files\MariaDB 11.4\bin\$name.exe"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function New-RandomSecret([int]$bytes = 48) {
    $buf = New-Object byte[] $bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($buf)
    return [Convert]::ToBase64String($buf)
}

function Read-EnvFile($path) {
    $map = @{}
    if (-not (Test-Path $path)) { return $map }
    foreach ($line in Get-Content $path -Encoding UTF8) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $i = $t.IndexOf('=')
        if ($i -gt 0) {
            $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
        }
    }
    return $map
}

# ---------- 0 环境 ----------
Write-Step '0/5 环境检查'
$MYSQL = Find-Exe 'mysql' $MysqlPath
if (-not $MYSQL) {
    $MYSQL = Find-Exe 'mariadb' $MysqlPath
}
if (-not $MYSQL) {
    if (-not $SkipDb) { Fail '未找到 mysql.exe，请用 -MysqlPath 指定，或使用 -SkipDb' }
    else { Write-Warn2 '未找到 mysql.exe（本次 -SkipDb，验证的 DB 结构项会跳过）' }
} else {
    Write-Ok "mysql: $MYSQL"
}

$JAVA_HOME_ = $env:JAVA_HOME
if (-not $JAVA_HOME_ -or -not (Test-Path (Join-Path $JAVA_HOME_ 'bin\java.exe'))) {
    foreach ($c in @('C:\fitlife_tools\jdk\jdk-17.0.20.1+1', 'C:\Program Files\Java\jdk-17')) {
        if (Test-Path (Join-Path $c 'bin\java.exe')) { $JAVA_HOME_ = $c; break }
    }
}
if (-not $JAVA_HOME_) {
    Fail '未找到 JDK 17，请设置 JAVA_HOME'
} else {
    $env:JAVA_HOME = $JAVA_HOME_
    $env:Path = (Join-Path $JAVA_HOME_ 'bin') + ';' + $env:Path
    Write-Ok "JDK: $JAVA_HOME_"
}

$MVN = $null
foreach ($c in @('C:\fitlife_tools\mvn\apache-maven-3.9.16\bin\mvn.cmd')) {
    if (Test-Path $c) { $MVN = $c; break }
}
if (-not $MVN) {
    $cmd = Get-Command 'mvn' -ErrorAction SilentlyContinue
    if ($cmd) { $MVN = $cmd.Source }
}
if (-not $MVN) { Fail '未找到 Maven' } else { Write-Ok "maven: $MVN" }

$MVN_SETTINGS = $null
if (Test-Path 'C:\fitlife_tools\settings.xml') { $MVN_SETTINGS = 'C:\fitlife_tools\settings.xml' }

if ($script:Failures -gt 0) {
    Write-Host ''
    Fail '环境检查未通过，终止'
    exit 1
}

# ---------- 1 .env ----------
Write-Step '1/5 装载环境变量（.env）'
$envMap = Read-EnvFile $ENV_FILE
if ($envMap.Count -eq 0) {
    Write-Warn2 '未找到 .env，生成随机 JWT_SECRET 并写入（仅本机，勿提交）'
    $envMap['JWT_SECRET'] = New-RandomSecret 48
    $lines = @(
        '# 由 p4-oneclick.ps1 生成：本机密钥，请勿提交到仓库',
        "JWT_SECRET=$($envMap['JWT_SECRET'])",
        "DB_USER=$DbUser",
        "DB_PASS=$DbPass"
    )
    $lines | Set-Content -Path $ENV_FILE -Encoding UTF8
} else {
    Write-Ok '.env 已存在，复用其中的密钥'
}
if (-not $envMap['JWT_SECRET'] -or $envMap['JWT_SECRET'].Length -lt 32) {
    $envMap['JWT_SECRET'] = New-RandomSecret 48
    Add-Content -Path $ENV_FILE -Value "JWT_SECRET=$($envMap['JWT_SECRET'])" -Encoding UTF8
}
$env:JWT_SECRET = $envMap['JWT_SECRET']
# 口令优先级：命令行 -DbUser/-DbPass > .env（.env 可能是旧口令，且 PS 无法区分“未传参=默认值”）
$script:DbUserEff = if ($DbUser -and $DbUser -ne 'root') { $DbUser } elseif ($envMap['DB_USER']) { $envMap['DB_USER'] } else { $DbUser }
$script:DbPassEff = if ($DbPass -and $DbPass -ne '888') { $DbPass } elseif ($envMap['DB_PASS']) { $envMap['DB_PASS'] } else { $DbPass }
if ($envMap['DB_USER'] -and ($script:DbUserEff -ne $envMap['DB_USER'] -or $script:DbPassEff -ne $envMap['DB_PASS'])) {
    Write-Warn2 '命令行账号/口令与 .env 不一致，按命令行覆盖并回写 .env（仅本机）'
    $envMap['DB_USER'] = $script:DbUserEff
    $envMap['DB_PASS'] = $script:DbPassEff
    ($envMap.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) | Set-Content -LiteralPath $ENV_FILE -Encoding UTF8
}
$env:DB_USER = $script:DbUserEff
$env:DB_PASS = $script:DbPassEff
$env:DB_URL = "jdbc:mysql://${DbHost}:${DbPort}/${DbName}?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$env:SPRING_PROFILES_ACTIVE = ''
Write-Ok "DB_URL: $($env:DB_URL)"

# ---------- 2 迁移 ----------
Write-Step '2/5 数据库迁移（V1~V4，幂等）'
if ($SkipDb) {
    Write-Warn2 '按 -SkipDb 跳过'
} else {
    function Invoke-SqlFile($file) {
        if (-not (Test-Path $file)) { Fail "缺少 SQL: $file"; return }
        $cmdLine = '"' + $MYSQL + '" -h' + $DbHost + ' -P' + $DbPort + ' -u' + $script:DbUserEff + ' --default-character-set=utf8mb4 ' + $DbName + ' < "' + $file + '" 1>nul'
        $oldPwd = $env:MYSQL_PWD
        $env:MYSQL_PWD = $script:DbPassEff
        cmd /c $cmdLine | Out-Null
        $code = $LASTEXITCODE
        $env:MYSQL_PWD = $oldPwd
        if ($code -ne 0) { Fail ("执行失败（exit $code）：" + (Split-Path -Leaf $file)) }
        else { Write-Ok (Split-Path -Leaf $file) }
    }

    # 库/表不存在时先导主脚本
    $probe = Join-Path $env:TEMP 'p4_probe.sql'
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='exercises';" | Set-Content -Path $probe -Encoding ASCII
    $oldPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $script:DbPassEff
    $existing = cmd /c ('"' + $MYSQL + '" -h' + $DbHost + ' -P' + $DbPort + ' -u' + $script:DbUserEff + ' -N -B < "' + $probe + '" 2>nul')
    $env:MYSQL_PWD = $oldPwd
    if ("$existing".Trim() -ne '1') {
        Write-Warn2 '基础表缺失，先导入 fitlife-mysql.sql'
        Invoke-SqlFile (Join-Path $ROOT 'fitlife-mysql.sql')
    } else {
        Write-Ok '基础表已存在'
    }

    foreach ($m in @('V1__p1_hardening.sql', 'V2__p2_ai_pipeline.sql', 'V3__p3_chat_revision.sql', 'V4__p4_execution_loop.sql')) {
        Invoke-SqlFile (Join-Path $ROOT "sql\migration\$m")
    }

    # 结构校验
    $check = Join-Path $env:TEMP 'p4_check.sql'
    @"
SELECT CONCAT('wc=', COUNT(*)) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='workout_records' AND column_name IN ('plan_detail_id','plan_day','status','skipped_reasons','rpe','pain_flag');
SELECT CONCAT('dc=', COUNT(*)) FROM information_schema.columns WHERE table_schema='$DbName' AND table_name='daily_records' AND column_name IN ('net_calories','workout_minutes','completed_items','planned_items','avg_rpe','pain_flag');
SELECT CONCAT('idx=', COUNT(DISTINCT index_name)) FROM information_schema.statistics WHERE table_schema='$DbName' AND table_name='workout_records' AND index_name='idx_workout_plan_detail';
SELECT CONCAT('pa=', COUNT(*)) FROM information_schema.tables WHERE table_schema='$DbName' AND table_name='plan_adherence';
SELECT CONCAT('kr=', COUNT(*)) FROM knowledge_rule WHERE code='ADVICE_LOAD';
"@ | Set-Content -Path $check -Encoding UTF8
    $oldPwd = $env:MYSQL_PWD
    $env:MYSQL_PWD = $script:DbPassEff
    $res = cmd /c ('"' + $MYSQL + '" -h' + $DbHost + ' -P' + $DbPort + ' -u' + $script:DbUserEff + ' -N -B ' + $DbName + ' < "' + $check + '" 2>nul')
    $env:MYSQL_PWD = $oldPwd
    $joined = ($res -join ';')
    Write-Host "    结构核对: $joined"
    foreach ($expect in @('wc=6', 'dc=6', 'idx=1', 'pa=1', 'kr=1')) {
        if ($joined -notmatch [regex]::Escape($expect)) { Fail "结构缺失：$expect" } else { Write-Ok $expect }
    }
}

# ---------- 3 构建 ----------
Write-Step '3/5 构建后端'
$jar = Join-Path $JAVA_DIR 'target\fitlife-backend-1.0.0.jar'
if ($SkipBuild) {
    Write-Warn2 '按 -SkipBuild 跳过'
} else {
    Push-Location $JAVA_DIR
    $args = @('-B', '-Duser.language=en', '-Duser.country=US', 'clean', 'package')
    if ($MVN_SETTINGS) { $args = @('-B', '-s', $MVN_SETTINGS, '-Duser.language=en', '-Duser.country=US', 'clean', 'package') }
    if ($SkipTests) { $args += '-DskipTests' }
    & $MVN @args 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR 'p4-build.log') | Select-String -Pattern 'Tests run:|BUILD|ERROR' | Select-Object -Last 12
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Fail "构建失败（exit $code），详见 logs\p4-build.log"; exit 1 }
    Write-Ok 'BUILD SUCCESS'
}
if (-not (Test-Path $jar)) { Fail "缺少 jar: $jar"; exit 1 }

# ---------- 4 启动 ----------
Write-Step '4/5 启动后端'
$baseUrl = "http://127.0.0.1:$BackendPort"
if ($NoStart) {
    Write-Warn2 '按 -NoStart 跳过'
} else {
    Get-Process java -ErrorAction SilentlyContinue | Where-Object {
        $_.Path -and $_.Path -like '*java.exe*'
    } | ForEach-Object {
        try {
            $conns = Get-NetTCPConnection -OwningProcess $_.Id -ErrorAction SilentlyContinue |
                Where-Object { $_.LocalPort -eq $BackendPort }
            if ($conns) { Stop-Process -Id $_.Id -Force; Write-Warn2 "已停止占用 $BackendPort 的旧进程 PID=$($_.Id)" }
        } catch {}
    }
    Start-Sleep -Seconds 1
    $outLog = Join-Path $LOG_DIR 'p4-app.out.log'
    $errLog = Join-Path $LOG_DIR 'p4-app.err.log'
    # 用 cmd 承载输出重定向：若直接给 java.exe 加 -RedirectStandardOutput，
    # 启动本脚本的宿主进程退出后文件句柄失效，java 写日志即抛异常退出（实测会静默掉线）。
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    # 用 ProcessStartInfo 直接组装命令行（含 1> 2> 重定向），交给 cmd.exe 执行。
    # 不用 Start-Process -ArgumentList 传含引号/重定向的整串：PS 5.1 会给参数补引号，
    # 结果是 java 拿不到 -jar、日志文件也不生成（实测静默失败）。
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'cmd.exe'
    $psi.Arguments = '/c ""' + $java + '" -jar "' + $jar + '" --server.port=' + $BackendPort +
                     ' --spring.jpa.hibernate.ddl-auto=none 1>"' + $outLog + '" 2>"' + $errLog + '""'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    [System.Diagnostics.Process]::Start($psi) | Out-Null
    $ok = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Seconds 2
        try {
            $r = Invoke-WebRequest -Uri "$baseUrl/api/health" -TimeoutSec 3 -UseBasicParsing
            if ($r.StatusCode -eq 200) { $ok = $true; break }
        } catch {}
    }
    if ($ok) { Write-Ok "后端已就绪：$baseUrl" }
    else { Fail "后端启动超时，见 logs\p4-app.err.log"; exit 1 }
}

# ---------- 5 验证 ----------
Write-Step '5/5 验证'
if ($SkipVerify -or $NoStart) {
    Write-Warn2 '跳过验证'
} else {
    $common = @('-DbUser', $env:DB_USER, '-DbPass', $env:DB_PASS, '-DbHost', $DbHost, '-DbPort', $DbPort, '-DbName', $DbName)
    if ($MYSQL) { $common += @('-MysqlPath', $MYSQL) }

    Write-Host ''
    # 说明：不能把子脚本的输出接给 Select-String / Select-Object 再读 $LASTEXITCODE——
    # 管道缓冲会让长任务看起来"卡住"，且子进程的退出码在管道里不可靠。
    # 这里直接调用、让输出原样流到控制台，只靠 $LASTEXITCODE 判定。
    $verifyFailed = 0

    Write-Host '--- verify-p4（P4 新增能力）---' -ForegroundColor Cyan
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify-p4.ps1') -BaseUrl $baseUrl @common
    if ($LASTEXITCODE -ne 0) { Fail 'verify-p4 未全部通过'; $verifyFailed++ }

    Write-Host ''
    Write-Host '--- 回归 verify-p3 / p2 / p1 ---' -ForegroundColor Cyan
    foreach ($v in @('verify-p3.ps1', 'verify-p2.ps1', 'verify-p1.ps1')) {
        $p = Join-Path $PSScriptRoot $v
        if (-not (Test-Path $p)) { Write-Warn2 "缺少 $v，跳过"; continue }
        & powershell -ExecutionPolicy Bypass -File $p -BaseUrl $baseUrl @common
        if ($LASTEXITCODE -ne 0) { Fail "$v 回归失败"; $verifyFailed++ } else { Write-Ok "$v 回归通过" }
    }
}

Write-Host ''
if ($script:Failures -eq 0) {
    Write-Host 'P4 一键流程全部通过。' -ForegroundColor Green
    exit 0
} else {
    Write-Host "存在 $script:Failures 项失败，见上方 [FAIL]。" -ForegroundColor Red
    exit 1
}