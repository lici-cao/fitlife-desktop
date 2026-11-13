#requires -Version 5.1
# ================================================================
#  FitLife 一键启动脚本 v4
#
#  v4（P1 改造）新增：
#   5. 首次运行自动生成 .env 并写入随机 JWT_SECRET；后端从环境变量读取密钥，
#      仓库里不再有可用口令（v3 把 jwt.secret 与 GLM Key 明文写在 application.yml）
#   6. 导入主 SQL 后自动执行 sql\migration\V1__p1_hardening.sql（唯一约束/索引/幂等表/审计表）、
#      V2__p2_ai_pipeline.sql（知识库/规则/Prompt 模板/调用日志/计划版本列）与
#      V3__p3_chat_revision.sql（会话表/消息表），三者均幂等
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
$SQL_MIGRATION = Join-Path $ROOT "sql\migration\V1__p1_hardening.sql"
$SQL_MIGRATION_P2 = Join-Path $ROOT "sql\migration\V2__p2_ai_pipeline.sql"
$SQL_MIGRATION_P3 = Join-Path $ROOT "sql\migration\V3__p3_chat_revision.sql"
$SQL_MIGRATION_P4 = Join-Path $ROOT "sql\migration\V4__p4_execution_loop.sql"
$ENV_FILE  = Join-Path $ROOT ".env"

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
# 0.5 环境变量：.env → 进程环境（JWT_SECRET / DB 凭据 / 模型 Key）
#
# 后端 application.yml 里 jwt.secret、数据库口令、模型 Key 全部改成
# ${JWT_SECRET:} 这类占位，缺失时启动即失败（JwtService 会校验长度），
# 因此这里必须先把 .env 读进进程环境，子进程 powershell 会继承。
# ------------------------------------------------------------
function Read-EnvFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in (Get-Content -LiteralPath $Path -Encoding UTF8)) {
        if ($null -eq $line) { continue }
        $t = $line.Trim()
        if ($t.Length -eq 0 -or $t.StartsWith("#")) { continue }
        $i = $t.IndexOf("=")
        if ($i -le 0) { continue }
        $k = $t.Substring(0, $i).Trim()
        $v = $t.Substring($i + 1).Trim()
        if ($v.Length -ge 2 -and $v.StartsWith('"') -and $v.EndsWith('"')) {
            $v = $v.Substring(1, $v.Length - 2)
        }
        if (-not [Environment]::GetEnvironmentVariable($k, "Process")) {
            [Environment]::SetEnvironmentVariable($k, $v, "Process")
        }
    }
}

function New-RandomSecret {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [Convert]::ToBase64String($bytes)
}

Write-Step "加载环境变量"
if (-not (Test-Path -LiteralPath $ENV_FILE)) {
    $envLines = @(
        "# FitLife 环境变量（首次运行自动生成，请勿提交到仓库）",
        "# 修改后重启本脚本生效；也可以直接在系统里设置同名环境变量。",
        "DB_USER=$DbUser",
        "DB_PASS=$DbPass",
        ("JWT_SECRET=" + (New-RandomSecret)),
        "# 可选：填入自己的模型 Key，留空则对应厂商在界面里置灰",
        "DEEPSEEK_API_KEY=",
        "ARK_API_KEY=",
        "ZHIPU_API_KEY="
    )
    Set-Content -LiteralPath $ENV_FILE -Value $envLines -Encoding UTF8
    Write-Ok "已生成 .env（含随机 JWT_SECRET）"
}
Read-EnvFile $ENV_FILE

# 命令行显式传入的凭据优先级最高；否则沿用 .env 里的值
if ($PSBoundParameters.ContainsKey("DbUser")) { $env:DB_USER = $DbUser }
elseif ($env:DB_USER) { $DbUser = $env:DB_USER }
if ($PSBoundParameters.ContainsKey("DbPass")) { $env:DB_PASS = $DbPass }
elseif ($env:DB_PASS) { $DbPass = $env:DB_PASS }

if (-not $env:JWT_SECRET) {
    $env:JWT_SECRET = New-RandomSecret
    Add-Content -LiteralPath $ENV_FILE -Value ("JWT_SECRET=" + $env:JWT_SECRET) -Encoding UTF8
    Write-Warn "原 .env 缺少 JWT_SECRET，已生成并追加"
}
Write-Ok ("JWT_SECRET 就绪（{0} 字节）" -f $env:JWT_SECRET.Length)

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

            # P1 硬化迁移：唯一约束/索引/幂等表/审计表。与主 SQL 内容有重叠，
            # 但单独执行一遍可以兜住「只导了旧 SQL、没跑主脚本」的机器。
            if (Test-Path -LiteralPath $SQL_MIGRATION) {
                $migText = Get-Content -LiteralPath $SQL_MIGRATION -Raw -Encoding UTF8
                $migOut = $migText | & $MYSQL_EXE @userArgs 2>&1 | Out-String
                if ($LASTEXITCODE -ne 0) {
                    Write-Warn "P1 迁移脚本返回非零状态，输出如下："
                    $migOut.Split("`n") | Select-Object -First 12 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkYellow }
                } else {
                    Write-Ok "P1 硬化迁移完成（唯一约束 / 索引 / 幂等表 / 审计表）"
                }
            } else {
                Write-Warn "未找到 $SQL_MIGRATION，跳过 P1 硬化迁移"
            }

            # P2 AI 主链路迁移：知识条目/规则/模板/调用日志 + plans 版本列。
            # 同样幂等，可重复执行。
            if (Test-Path -LiteralPath $SQL_MIGRATION_P2) {
                $mig2Text = Get-Content -LiteralPath $SQL_MIGRATION_P2 -Raw -Encoding UTF8
                $mig2Out = $mig2Text | & $MYSQL_EXE @userArgs 2>&1 | Out-String
                if ($LASTEXITCODE -ne 0) {
                    Write-Warn "P2 迁移脚本返回非零状态，输出如下："
                    $mig2Out.Split("`n") | Select-Object -First 12 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkYellow }
                } else {
                    Write-Ok "P2 主链路迁移完成（知识库 / 规则 / 模板 / 调用日志 / 计划版本）"
                }
            } else {
                Write-Warn "未找到 $SQL_MIGRATION_P2，跳过 P2 迁移"
            }

            # P3 对话与修订迁移：会话表 / 消息表。幂等，可重复执行。
            if (Test-Path -LiteralPath $SQL_MIGRATION_P3) {
                $mig3Text = Get-Content -LiteralPath $SQL_MIGRATION_P3 -Raw -Encoding UTF8
                $mig3Out = $mig3Text | & $MYSQL_EXE @userArgs 2>&1 | Out-String
                if ($LASTEXITCODE -ne 0) {
                    Write-Warn "P3 迁移脚本返回非零状态，输出如下："
                    $mig3Out.Split("`n") | Select-Object -First 12 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkYellow }
                } else {
                    Write-Ok "P3 对话迁移完成（会话表 / 消息表）"
                }
            } else {
                Write-Warn "未找到 $SQL_MIGRATION_P3，跳过 P3 迁移"
            }
            if (Test-Path -LiteralPath $SQL_MIGRATION_P4) {
                $mig4Text = Get-Content -LiteralPath $SQL_MIGRATION_P4 -Raw -Encoding UTF8
                $mig4Text | & $MYSQL_EXE @userArgs 2>$null | Out-Null
                if ($LASTEXITCODE -ne 0) { Write-Warn "P4 迁移脚本返回非零（可重复执行，忽略）" } else { Write-Ok "P4 执行闭环迁移已应用" }
            } else {
                Write-Warn "未找到 $SQL_MIGRATION_P4，跳过 P4 迁移"
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
Write-Host "  演示账号  ->  dandan / 123456（管理员，可访问 /api/admin）" -ForegroundColor Gray
Write-Host "  新功能    ->  动作库（100 个动作）、训练计划、AI 搭子里的计划生成与运动搭配" -ForegroundColor Gray
Write-Host "  P1 硬化   ->  唯一约束/索引、幂等键、RBAC 与归属校验、密钥外置、统一响应体" -ForegroundColor Gray
Write-Host "  提示      ->  关闭弹出的两个 PowerShell 窗口即可停止服务" -ForegroundColor Gray
Write-Host ""

if (-not $NoBrowser) { Start-Process "http://localhost:$FrontendPort" }
Read-Host "按回车关闭本窗口（服务继续运行）"
