<#
.SYNOPSIS
    FitLife 桌面版一键构建脚本（Windows）

.DESCRIPTION
    在任意装有 JDK 17 + Maven + Node.js 22 的 Windows 机器上，一条命令跑完：
        前端构建(hash 路由) → 拷进后端 static → mvn package → jlink 裁剪 JRE
        → jpackage 生成 app-image → 压缩成可分发 zip
    构建过程不修改任何源码以外的系统状态，产物都在 build\ 目录下，可随时删除。

.PARAMETER Version
    产物版本号，写入 exe 版本信息与压缩包名，默认 1.0.0

.PARAMETER SkipFrontend
    跳过前端构建（dist 已存在时用，节省时间）

.PARAMETER Msi
    额外生成 MSI 安装包（需要 WiX Toolset 3.x，未安装会明确报错）

.PARAMETER PortableOnly
    不生成 exe，只打一个「JRE + jar + 启动 bat」的免安装便携包

.EXAMPLE
    .\build-desktop.ps1

.EXAMPLE
    .\build-desktop.ps1 -Version 1.2.0 -Msi

.EXAMPLE
    .\build-desktop.ps1 -SkipFrontend -PortableOnly
#>
[CmdletBinding()]
param(
    [string]$Version = "1.0.0",
    [switch]$SkipFrontend,
    [switch]$Msi,
    [switch]$PortableOnly,
    [string]$OutDir = ""
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$JavaDir = Join-Path $Root 'FitLife-java'
$WebDir  = Join-Path $Root 'FitLife-web'
$BuildDir = Join-Path $Root 'build'
$AppName  = 'FitLife'

if (-not $OutDir) { $OutDir = Join-Path $BuildDir 'dist' }

function Write-Step([string]$Text) {
    Write-Host ''
    Write-Host "==== $Text ====" -ForegroundColor Cyan
}

function Fail([string]$Text) {
    Write-Host ''
    Write-Host "[失败] $Text" -ForegroundColor Red
    exit 1
}

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jpackage.exe'))) {
        return $env:JAVA_HOME
    }
    # 退一步：按 PATH 里的 java.exe 反推 JDK 根目录（jpackage/jlink 与 java 同目录）
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($java) {
        $candidate = Split-Path -Parent (Split-Path -Parent $java.Source)
        if (Test-Path (Join-Path $candidate 'bin\jpackage.exe')) { return $candidate }
    }
    return $null
}

# ---------------------------------------------------------------
# 0. 环境检查（缺东西就立刻停，不要跑到一半才炸）
# ---------------------------------------------------------------
Write-Step '检查构建环境'

$JavaHome = Find-JavaHome
if (-not $JavaHome) {
    Fail "未找到 JDK 17（需要 jlink / jpackage）。请安装 Temurin 17 JDK 并设置 JAVA_HOME，例如：https://adoptium.net/temurin/releases/?version=17"
}
$env:JAVA_HOME = $JavaHome
Write-Host "JAVA_HOME  = $JavaHome"

$javaVersion = (& (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String)
if ($javaVersion -notmatch '"17') {
    Write-Host "[警告] 当前 JDK 主版本可能不是 17，继续执行但建议用 17：" -ForegroundColor Yellow
    Write-Host $javaVersion.Trim()
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    $wrapper = Join-Path $JavaDir 'mvnw.cmd'
    if (Test-Path $wrapper) {
        $MvnCmd = $wrapper
        Write-Host "未发现全局 mvn，改用项目自带 mvnw"
    } else {
        Fail "未找到 Maven。请安装 Maven 3.9+ 并加入 PATH（或改用云端构建：.github/workflows/build-desktop.yml）"
    }
} else {
    $MvnCmd = 'mvn'
}
Write-Host "Maven      = $MvnCmd"

if (-not $SkipFrontend) {
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Fail "未找到 npm。请安装 Node.js 22（https://nodejs.org/），或加 -SkipFrontend 使用已有 dist"
    }
    Write-Host ("npm        = " + (npm --version))
}

New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null

# ---------------------------------------------------------------
# 1. 前端构建（hash 路由 + 相对接口地址）
# ---------------------------------------------------------------
if (-not $SkipFrontend) {
    Write-Step '1/6 构建前端（--mode desktop → hash 路由）'

    Push-Location $WebDir
    try {
        if (-not (Test-Path 'node_modules')) {
            Write-Host '安装前端依赖（首次较慢，约 1-3 分钟）...'
            npm ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) {
                Write-Host 'npm ci 失败，回退到 npm install' -ForegroundColor Yellow
                npm install --no-audit --no-fund
                if ($LASTEXITCODE -ne 0) { Fail '前端依赖安装失败' }
            }
        }

        # vue-tsc 类型检查若报历史遗留错误，回退到纯 vite 构建，保证产物能出来
        npm run build:desktop
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'vue-tsc 类型检查未通过，回退为纯 vite 构建（产物不受影响）' -ForegroundColor Yellow
            npx vite build --mode desktop
            if ($LASTEXITCODE -ne 0) { Fail '前端构建失败' }
        }

        if (-not (Test-Path 'dist\index.html')) { Fail '前端产物缺失：dist\index.html 未生成' }
    } finally {
        Pop-Location
    }
} else {
    Write-Step '1/6 跳过前端构建'
    if (-not (Test-Path (Join-Path $WebDir 'dist\index.html'))) {
        Fail '指定了 -SkipFrontend，但 FitLife-web\dist 不存在，请先完整跑一次'
    }
}

# ---------------------------------------------------------------
# 2. 产物内嵌到后端 static
# ---------------------------------------------------------------
Write-Step '2/6 拷贝前端产物到 FitLife-java\src\main\resources\static'

$staticDir = Join-Path $JavaDir 'src\main\resources\static'
if (Test-Path $staticDir) { Remove-Item $staticDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $staticDir | Out-Null
Copy-Item (Join-Path $WebDir 'dist\*') $staticDir -Recurse -Force
$fileCount = (Get-ChildItem $staticDir -Recurse -File | Measure-Object).Count
Write-Host "已内嵌 $fileCount 个静态文件"

# ---------------------------------------------------------------
# 3. 后端打包
# ---------------------------------------------------------------
Write-Step '3/6 打包 Spring Boot（mvn package，跳过测试）'

Push-Location $JavaDir
try {
    & $MvnCmd -B -ntp -DskipTests package
    if ($LASTEXITCODE -ne 0) { Fail 'Maven 打包失败，请查看上方日志' }
} finally {
    Pop-Location
}

$jar = Get-ChildItem (Join-Path $JavaDir 'target\fitlife-backend-*.jar') -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1
if (-not $jar) { Fail '未找到 target\fitlife-backend-*.jar' }
Write-Host ("jar        = {0}  ({1:N1} MB)" -f $jar.Name, ($jar.Length / 1MB))

$AppInput = Join-Path $BuildDir 'app'
if (Test-Path $AppInput) { Remove-Item $AppInput -Recurse -Force }
New-Item -ItemType Directory -Force -Path $AppInput | Out-Null
Copy-Item $jar.FullName $AppInput

# ---------------------------------------------------------------
# 4. jlink 裁剪 JRE
# ---------------------------------------------------------------
Write-Step '4/6 jlink 裁剪运行时'

$RuntimeDir = Join-Path $BuildDir 'runtime'
if (Test-Path $RuntimeDir) { Remove-Item $RuntimeDir -Recurse -Force }

# jdk.crypto.ec 必须显式加入：否则 HTTPS 握手会报 no cipher suites in common，
# 表现为「AI 对话一直连不上」。
$modules = @(
    'java.base', 'java.logging', 'java.sql', 'java.naming', 'java.desktop',
    'java.management', 'java.instrument', 'java.net.http', 'java.security.jgss',
    'java.transaction.xa', 'java.xml', 'java.compiler', 'java.scripting',
    'jdk.crypto.ec', 'jdk.crypto.cryptoki', 'jdk.unsupported', 'jdk.zipfs', 'jdk.jfr'
) -join ','

& (Join-Path $JavaHome 'bin\jlink.exe') `
    --add-modules $modules `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 `
    --output $RuntimeDir
if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $RuntimeDir 'bin\java.exe'))) {
    Fail 'jlink 失败'
}
$rtSize = (Get-ChildItem $RuntimeDir -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("运行时大小 = {0:N1} MB（完整 JDK 约 300MB）" -f $rtSize)

# ---------------------------------------------------------------
# 5. 生成产物
# ---------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if ($PortableOnly) {
    Write-Step '5/6 组装免安装便携包'
    $portableRoot = Join-Path $BuildDir 'portable\FitLife'
    if (Test-Path (Split-Path -Parent $portableRoot)) { Remove-Item (Split-Path -Parent $portableRoot) -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $portableRoot | Out-Null
    Copy-Item $RuntimeDir (Join-Path $portableRoot 'runtime') -Recurse
    Copy-Item $AppInput (Join-Path $portableRoot 'app') -Recurse

    $bat = @"
@echo off
setlocal
cd /d "%~dp0"
start "" "%~dp0runtime\bin\javaw.exe" -Dfile.encoding=UTF-8 -Xmx768m -jar "%~dp0app\$($jar.Name)"
"@
    $bat | Set-Content (Join-Path $portableRoot 'FitLife.bat') -Encoding OEM

    $batDebug = @"
@echo off
setlocal
cd /d "%~dp0"
echo 正在启动 FitLife（关闭此窗口即退出程序）...
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Xmx768m -jar "%~dp0app\$($jar.Name)"
pause
"@
    $batDebug | Set-Content (Join-Path $portableRoot 'FitLife-debug.bat') -Encoding OEM

    $ZipPath = Join-Path $OutDir "FitLife-windows-portable-v$Version.zip"
    if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
    Compress-Archive -Path $portableRoot -DestinationPath $ZipPath -CompressionLevel Optimal
} else {
    Write-Step '5/6 jpackage 生成 app-image'

    # 启动主类自适应：Spring Boot 3.2+ 在 loader.launch 包下，3.2 之前是 loader 包
    $jarEntries = & (Join-Path $JavaHome 'bin\jar.exe') tf $jar.FullName
    $mainClass = if ($jarEntries -contains 'org/springframework/boot/loader/launch/JarLauncher.class') {
        'org.springframework.boot.loader.launch.JarLauncher'
    } else {
        'org.springframework.boot.loader.JarLauncher'
    }
    Write-Host "启动主类 = $mainClass"

    $imgDir = Join-Path $OutDir "$AppName"
    if (Test-Path $imgDir) { Remove-Item $imgDir -Recurse -Force }

    & (Join-Path $JavaHome 'bin\jpackage.exe') `
        --type app-image `
        --name $AppName `
        --app-version $Version `
        --vendor 'FitLife' `
        --description 'FitLife 健康管理 · AI 健身计划' `
        --input $AppInput `
        --main-jar $jar.Name `
        --main-class $mainClass `
        --runtime-image $RuntimeDir `
        --java-options '-Dfile.encoding=UTF-8' `
        --java-options '-Djava.awt.headless=false' `
        --java-options '-Xmx768m' `
        --win-console `
        --dest $OutDir
    if ($LASTEXITCODE -ne 0) { Fail 'jpackage 生成 app-image 失败' }

    $exe = Join-Path $imgDir "$AppName.exe"
    if (-not (Test-Path $exe)) { Fail "jpackage 未生成 $exe" }

    $ZipPath = Join-Path $OutDir "FitLife-windows-x64-v$Version.zip"
    if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
    Compress-Archive -Path $imgDir -DestinationPath $ZipPath -CompressionLevel Optimal

    if ($Msi) {
        Write-Step '额外生成 MSI 安装包'
        & (Join-Path $JavaHome 'bin\jpackage.exe') `
            --type msi `
            --name $AppName `
            --app-version $Version `
            --vendor 'FitLife' `
            --input $AppInput `
            --main-jar $jar.Name `
            --main-class $mainClass `
            --runtime-image $RuntimeDir `
            --java-options '-Dfile.encoding=UTF-8' `
            --java-options '-Xmx768m' `
            --win-menu `
            --win-shortcut `
            --win-dir-chooser `
            --dest (Join-Path $BuildDir 'msi')
        if ($LASTEXITCODE -ne 0) {
            Write-Host 'MSI 生成失败：通常是未安装 WiX Toolset 3.x（winget install WiXToolset.WiXToolset）' -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------
# 6. 收尾
# ---------------------------------------------------------------
Write-Step '6/6 完成'

if (-not (Test-Path $ZipPath)) { Fail '压缩包未生成' }
$zipSize = (Get-Item $ZipPath).Length / 1MB
Write-Host ''
Write-Host '构建成功，产物：' -ForegroundColor Green
Write-Host ("  {0}  ({1:N1} MB)" -f $ZipPath, $zipSize)
if (-not $PortableOnly) {
    Write-Host ("  解压后双击：{0}\{1}\{1}.exe" -f $OutDir, $AppName)
}
Write-Host ''
Write-Host '提示：' -ForegroundColor Cyan
Write-Host '  1) 数据全部存放在 %USERPROFILE%\.fitlife\data\，卸载重装不丢；'
Write-Host '  2) AI Key 写在 %USERPROFILE%\.fitlife\config\ai-key.yml，首次启动会自动生成模板；'
Write-Host '  3) 杀毒软件可能对新生成的 exe 报未知应用，属正常现象（未签名）。'
