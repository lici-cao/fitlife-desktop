#requires -Version 5.1
# ================================================================
#  FitLife 一键启动脚本 v3（重新制作）
#
#  解决的问题：
#   1. 旧脚本用 mysql -e "source <Windows路径>" 导入，反斜杠被转义导致静默失败，
#      数据库里始终没有 exercises 动作库 → 改用 stdin 管道导入，纯字节流、无路径转义
#   2. 旧脚本每次 DROP DATABASE 重建，会清空用户数据 → 默认幂等迁移，只有 -ResetDb 才重建
#   3. 旧 ps1 是 UTF-8 无 BOM，PowerShell 5.1 按 GBK 读取导致中文乱码 → 本文件带 UTF-8 BOM
#   4. 导入后校验动作库条数；数据库凭据通过环境变量传给后端，前后端配置自动一致
#
#  用法：
#    双击 start-fitlife.bat
#    powershell -ExecutionPolicy Bypass -File start-fitlife.ps1
#    powershell -ExecutionPolicy Bypass -File start-fitlife.ps1 -SkipDb
#    powershell -ExecutionPolicy Bypass -File start-fitlife.ps1 -ResetDb -DbPass 你的密码
# ================================================================

param(
    [switch]$SkipDb,          # 跳过数据库导入（仅启动服务）
    [switch]$ResetDb,         # 危险：清空并重建 fitlife 数据库
    [switch]$NoBrowser,       # 不自动打开浏览器
    [string]$DbUser = "root",
    [string]$DbPass = "888",
    [string]$MysqlPath = "",  # 手动指定 mysql.exe 完整路径
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch {}
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

$ROOT      = $PSScriptRoot
$BACKEND   = Join-Path $ROOT "FitLife-java"
$FRONTEND  = Join-Path $ROOT "FitLife-web"
$SQL_MAIN  = Join-Path $ROOT "fitlife-mysql.sql"
$SQL_RESET = Join-Path $ROOT "sql\fitlife-reset.sql"

function Write-Step { param([string]$t) Write-Host ""; Write-Host "==> $t" -ForegroundColor Cyan }
function Write-Ok   { param([string]$t) Write-Host "[OK]   $t" -ForegroundColor Green }
function Write-Warn { param([string]$t) Write-Host "[WARN] $t" -ForegroundColor Yellow }
function Write-Err  { param([string]$t) Write-Host "[ERROR] $t" -ForegroundColor Red }
function Stop-Script { Write-Host ""; Read-Host "按回车退出"; exit 1 }
function PsQuote { param([string]$s) return "'" + $s.Replace("'", "''") + "'" }

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "            FitLife 智能健身系统  一键启动 v3" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "项目目录: $ROOT"

# ------------------------------------------------------------
# 0. 目录检查
# ------------------------------------------------------------
if (-not (Test-Path $BACKEND))  { Write-Err "未找到后端目录: $BACKEND";  Stop-Script }
if (-not (Test-Path $FRONTEND)) { Write-Err "未找到前端目录: $FRONTEND"; Stop-Script }

# ------------------------------------------------------------
# 1. 定位 JDK（返回 JAVA_HOME）
# ------------------------------------------------------------
Write-Step "检测运行环境"
function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { return $env:JAVA_HOME }
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) { return (Split-Path (Split-Path $j.Source -Parent) -Parent) }
    $bases = @(
        "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Amazon Corretto",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:USERPROFILE\.jdks", "C:\Java", "D:\Java",
        (Join-Path $ROOT "tools\jdk")
    )
    foreach ($b in $bases) {
        if (-not (Test-Path $b)) { continue }
        $hit = Get-ChildItem $b -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue |
               Where-Object { $_.FullName -match '\\bin\\java\.exe$' } |
               Select-Object -First 1
        if ($hit) { return (Split-Path (Split-Path $hit.FullName -Parent) -Parent) }
    }
    return $null
}
$JAVA_DIR = Find-JavaHome
if (-not $JAVA_DIR) {
    Write-Err "未检测到 JDK 17+，请先安装并配置 JAVA_HOME"
    Write-Host "       下载: https://adoptium.net/temurin/releases/?version=17" -ForegroundColor Yellow
    Stop-Script
}
Write-Ok "JDK: $JAVA_DIR"

# ------------------------------------------------------------
# 2. 定位 Maven
# ------------------------------------------------------------
function Find-Maven {
    $m = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($m) { return $m.Source }
    $m = Get-Command mvn -ErrorAction SilentlyContinue
    if ($m) { return $m.Source }
    $bases = @(
        "$env:MAVEN_HOME\bin\mvn.cmd", "$env:M2_HOME\bin\mvn.cmd",
        "C:\apache-maven", "C:\Program Files\apache-maven", "D:\apache-maven",
        (Join-Path $ROOT "tools\maven")
    )
    foreach ($b in $bases) {
        if (-not (Test-Path $b)) { continue }
        if ((Get-Item $b).PSIsContainer) {
            $hit = Get-ChildItem $b -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($hit) { return $hit.FullName }
        } else { return $b }
    }
    $mw = Join-Path $BACKEND "mvnw.cmd"
    if (Test-Path $mw) { return $mw }
    return $null
}
$MVN = Find-Maven
if (-not $MVN) {
    Write-Err "未检测到 Maven（mvn / mvnw.cmd），请安装 Maven 3.6+"
    Write-Host "       下载: https://maven.apache.org/download.cgi" -ForegroundColor Yellow
    Stop-Script
}
Write-Ok "Maven: $MVN"

# ------------------------------------------------------------
# 3. 定位 Node / npm
# ------------------------------------------------------------
function Find-Npm {
    $n = Get-Command npm.cmd -ErrorAction SilentlyContinue
    if ($n) { return $n.Source }
    $n = Get-Command npm -ErrorAction SilentlyContinue
    if ($n) { return $n.Source }
    $bases = @(
        "$env:ProgramFiles\nodejs\npm.cmd", "${env:ProgramFiles(x86)}\nodejs\npm.cmd",
        "$env:LOCALAPPDATA\Programs\nodejs\npm.cmd",
        "$env:ProgramFiles\nodejs", "C:\nodejs",
        (Join-Path $ROOT "tools\nodejs\npm.cmd")
    )
    foreach ($b in $bases) { if (Test-Path $b) { return $b } }
    return $null
}
$NPM = Find-Npm
if (-not $NPM) {
    Write-Err "未检测到 Node.js / npm，请安装 Node 18+"
    Write-Host "       下载: https://nodejs.org/zh-cn/download" -ForegroundColor Yellow
    Stop-Script
}
Write-Ok "npm: $NPM"

# ------------------------------------------------------------
# 4. 定位 mysql 客户端
# ------------------------------------------------------------
function Find-Mysql {
    if ($MysqlPath -and (Test-Path $MysqlPath)) { return (Resolve-Path $MysqlPath).Path }
    $m = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($m) { return $m.Source }
    $m = Get-Command mysql -ErrorAction SilentlyContinue
    if ($m) { return $m.Source }
    $bases = @(
        "$env:ProgramFiles\MySQL", "${env:ProgramFiles(x86)}\MySQL",
        "$env:ProgramData\MySQL", "C:\MySQL", "D:\MySQL",
        "C:\xampp\mysql\bin", "C:\wamp64\bin\mysql", "C:\phpstudy_pro\Extensions",
        "$env:LOCALAPPDATA\Programs\MySQL", (Join-Path $ROOT "tools\mysql")
    )
    foreach ($b in $bases) {
        if (-not (Test-Path $b)) { continue }
        $hit = Get-ChildItem $b -Recurse -Filter "mysql.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}
$MYSQL_EXE = Find-Mysql

# ------------------------------------------------------------
# 5. 数据库：幂等迁移（默认）+ 可选全量重建
# ------------------------------------------------------------
if (-not $SkipDb) {
    # PS5.1 在 ErrorActionPreference=Stop 时，原生命令写 stderr 会抛 NativeCommandError；此处临时降级
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    Write-Step "数据库初始化"

    # 后端会自动兜底灌入动作库，所以 mysql 客户端缺失不会导致功能不可用
    if (-not $MYSQL_EXE) {
        Write-Warn "未找到 mysql.exe，跳过 SQL 导入"
        Write-Host "       后端启动时会自动建表并灌入 100 个动作（ExerciseSeedInitializer）。" -ForegroundColor Yellow
        Write-Host "       如需手动导入: mysql -u $DbUser -p < `"$SQL_MAIN`"" -ForegroundColor Yellow
        Write-Host "       也可用 -MysqlPath 参数指定 mysql.exe 完整路径。" -ForegroundColor Yellow
    } elseif (-not (Test-Path $SQL_MAIN)) {
        Write-Warn "未找到 $SQL_MAIN，跳过 SQL 导入（后端会自动兜底）"
    } else {
        # 凭据走 MYSQL_PWD 环境变量，避免命令行明文密码与特殊字符转义问题
        $env:MYSQL_PWD = $DbPass
        $userArgs = @("--user=$DbUser", "--default-character-set=utf8mb4", "--batch")

        # 先测试连通性
        $probe = & $MYSQL_EXE @userArgs "-e" "SELECT 1;" 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "无法连接 MySQL（用户 $DbUser），请检查服务是否启动、密码是否正确"
            Write-Host "       可用 -DbUser / -DbPass 指定凭据；后端会自动兜底动作库。" -ForegroundColor Yellow
            Write-Host "       手动导入: mysql -u $DbUser -p --default-character-set=utf8mb4 < `"$SQL_MAIN`"" -ForegroundColor Yellow
        } else {
            if ($ResetDb) {
                if (Test-Path $SQL_RESET) {
                    Write-Warn "即将清空 fitlife 数据库（所有业务数据会丢失）"
                    $confirm = Read-Host "确认重建请输入 YES"
                    if ($confirm -ne "YES") {
                        Write-Warn "已取消重建，改为幂等迁移"
                    } else {
                        $resetText = Get-Content -LiteralPath $SQL_RESET -Raw -Encoding UTF8
                        $resetOut = $resetText | & $MYSQL_EXE @userArgs 2>&1 | Out-String
                        if ($LASTEXITCODE -ne 0) { Write-Warn "重建库失败: $resetOut" }
                        else { Write-Ok "已清空并重建数据库" }
                    }
                } else {
                    Write-Warn "未找到 $SQL_RESET，跳过重建"
                }
            }

            # 关键改动：用 stdin 管道导入，避免 Windows 反斜杠路径与 source 命令的坑
            $sqlText = Get-Content -LiteralPath $SQL_MAIN -Raw -Encoding UTF8
            $importOut = $sqlText | & $MYSQL_EXE @userArgs 2>&1 | Out-String
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "SQL 导入返回非零状态，输出如下："
                $importOut.Split("`n") | Select-Object -First 12 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkYellow }
                Write-Host "       后端仍会自动兜底灌入动作库。" -ForegroundColor Yellow
            } else {
                Write-Ok "数据库迁移完成（幂等，未清空已有业务数据）"
            }

            # 校验动作库是否真的建好
            $count = (& $MYSQL_EXE @userArgs "--skip-column-names" "-e" "SELECT COUNT(*) FROM fitlife.exercises;" 2>$null | Select-Object -First 1)
            if ($count -and "$count".Trim() -match '^\d+$') {
                $n = [int]"$count".Trim()
                if ($n -ge 100) { Write-Ok "动作库校验通过：exercises 表共 $n 条动作" }
                else { Write-Warn "动作库仅 $n 条，后端启动时会自动补齐到 100 条" }
            } else {
                Write-Warn "未能读取动作库条数（后端启动时会自动兜底）"
            }
        }
    }
    $ErrorActionPreference = $prevEap
} else {
    Write-Warn "按 -SkipDb 要求跳过数据库导入"
}

# ------------------------------------------------------------
# 6. 前端依赖
# ------------------------------------------------------------
$nodeModules = Join-Path $FRONTEND "node_modules"
if (-not (Test-Path $nodeModules)) {
    Write-Step "首次运行，安装前端依赖（使用国内镜像，约 1-3 分钟）"
    Push-Location $FRONTEND
    & $NPM install --registry=https://registry.npmmirror.com --no-audit --no-fund
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Err "npm install 失败，请检查网络"; Stop-Script }
    Write-Ok "前端依赖安装完成"
} else {
    Write-Ok "前端依赖已存在"
}

# ------------------------------------------------------------
# 7. 启动后端（子进程用单行命令，避免中文与换行解析问题）
# ------------------------------------------------------------
Write-Step "启动后端服务 (端口 $BackendPort)"

$javaBin = Join-Path $JAVA_DIR "bin"
$backendLine = '$env:JAVA_HOME=' + (PsQuote $JAVA_DIR) + '; ' +
               '$env:PATH=' + (PsQuote ($javaBin + ';')) + ' + $env:PATH; ' +
               '$env:DB_USER=' + (PsQuote $DbUser) + '; ' +
               '$env:DB_PASS=' + (PsQuote $DbPass) + '; ' +
               'Set-Location ' + (PsQuote $BACKEND) + '; ' +
               '& ' + (PsQuote $MVN) + ' spring-boot:run ' +
               (PsQuote "-Dspring-boot.run.arguments=--server.port=$BackendPort")
Start-Process powershell -ArgumentList "-NoExit", "-Command", $backendLine

Write-Host "       等待 /api/health 就绪（最多 120 秒）..." -ForegroundColor Gray
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$BackendPort/api/health" -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
}
if ($ready) { Write-Ok "后端已就绪: http://localhost:$BackendPort/api/health" }
else { Write-Warn "后端 120 秒内未响应，请查看后端窗口日志（常见原因：MySQL 未启动或密码不对）" }

# ------------------------------------------------------------
# 8. 启动前端
# ------------------------------------------------------------
Write-Step "启动前端服务 (端口 $FrontendPort)"
$frontendLine = 'Set-Location ' + (PsQuote $FRONTEND) + '; ' +
                '& ' + (PsQuote $NPM) + ' run dev -- --port ' + $FrontendPort
Start-Process powershell -ArgumentList "-NoExit", "-Command", $frontendLine
Start-Sleep -Seconds 6

# ------------------------------------------------------------
# 9. 完成
# ------------------------------------------------------------
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "                     启动完成" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ("  前端页面  ->  http://localhost:{0}" -f $FrontendPort) -ForegroundColor White
Write-Host ("  后端接口  ->  http://localhost:{0}/api/health" -f $BackendPort) -ForegroundColor White
Write-Host ""
Write-Host "  演示账号  ->  dandan / 123456" -ForegroundColor Gray
Write-Host "  新功能    ->  动作库（100 个动作）、训练计划、AI 搭子里的计划生成与运动搭配" -ForegroundColor Gray
Write-Host "  提示      ->  关闭弹出的两个 PowerShell 窗口即可停止服务" -ForegroundColor Gray
Write-Host ""

if (-not $NoBrowser) { Start-Process "http://localhost:$FrontendPort" }
Read-Host "按回车关闭本窗口（服务继续运行）"
