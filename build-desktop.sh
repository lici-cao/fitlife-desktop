#!/usr/bin/env bash
# ============================================================
# FitLife 桌面版一键构建脚本（Linux / macOS）
#
# 用法：
#   ./build-desktop.sh                     # 构建当前平台的 app-image
#   ./build-desktop.sh -v 1.2.0            # 指定版本号
#   ./build-desktop.sh --portable-win      # 只产出 Windows 免安装便携包（交叉组装，无需 Windows）
#   ./build-desktop.sh --skip-frontend     # 跳过前端构建（dist 已就绪时用）
#
# 前置条件（任选其一）：
#   * 本机已装 JDK 17（JAVA_HOME 指向 JDK，不是 JRE）+ Maven 3.9+ + Node.js 22
#   * 或者直接用 GitHub Actions：.github/workflows/build-desktop.yml
#
# 说明：jpackage 不能跨平台打包。在 Linux 上 --type app-image 得到的是 Linux 产物；
#       想要 Windows 的 exe，请用 --portable-win（JRE + jar + bat，Windows 解压即可运行），
#       或在 Windows 机器 / windows-latest runner 上执行 build-desktop.ps1。
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="$ROOT/FitLife-java"
WEB_DIR="$ROOT/FitLife-web"
BUILD_DIR="$ROOT/build"
APP_NAME="FitLife"
VERSION="1.0.0"
SKIP_FRONTEND=0
PORTABLE_WIN=0

while [ $# -gt 0 ]; do
  case "$1" in
    -v|--version) VERSION="$2"; shift 2 ;;
    --skip-frontend) SKIP_FRONTEND=1; shift ;;
    --portable-win) PORTABLE_WIN=1; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 1 ;;
  esac
done

step() { printf '\n==== %s ====\n' "$1"; }
fail() { printf '\n[失败] %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------
# 0. 环境检查
# ---------------------------------------------------------------
step "检查构建环境"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ]; then
  :
else
  JAVA_BIN="$(command -v java || true)"
  [ -n "$JAVA_BIN" ] || fail "未找到 java。请安装 JDK 17 并设置 JAVA_HOME"
  CAND="$(dirname "$(dirname "$(readlink -f "$JAVA_BIN")")")"
  [ -x "$CAND/bin/jpackage" ] || fail "JAVA_HOME 指向的不是 JDK17（缺 jpackage）。请安装 Temurin 17 JDK"
  export JAVA_HOME="$CAND"
fi
echo "JAVA_HOME = $JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -n 1

MVN="mvn"
if ! command -v mvn >/dev/null 2>&1; then
  if [ -x "$JAVA_DIR/mvnw" ]; then
    MVN="$JAVA_DIR/mvnw"
  else
    fail "未找到 Maven。请安装 Maven 3.9+，或改用云端构建 workflow"
  fi
fi
echo "Maven     = $MVN"

if [ "$SKIP_FRONTEND" -eq 0 ]; then
  command -v npm >/dev/null 2>&1 || fail "未找到 npm，请安装 Node.js 22（或用 --skip-frontend）"
  echo "npm       = $(npm --version)"
fi

mkdir -p "$BUILD_DIR"

# ---------------------------------------------------------------
# 1. 前端构建
# ---------------------------------------------------------------
if [ "$SKIP_FRONTEND" -eq 0 ]; then
  step "1/6 构建前端（--mode desktop → hash 路由）"
  cd "$WEB_DIR"
  if [ ! -d node_modules ]; then
    echo "安装前端依赖（首次约 1-3 分钟）..."
    npm ci --no-audit --no-fund || npm install --no-audit --no-fund
  fi
  if ! npm run build:desktop; then
    echo "[警告] vue-tsc 类型检查未通过，回退为纯 vite 构建"
    npx vite build --mode desktop
  fi
  [ -f dist/index.html ] || fail "前端产物缺失：$WEB_DIR/dist/index.html"
  cd "$ROOT"
else
  step "1/6 跳过前端构建"
  [ -f "$WEB_DIR/dist/index.html" ] || fail "--skip-frontend 但 dist 不存在"
fi

# ---------------------------------------------------------------
# 2. 内嵌到后端 static
# ---------------------------------------------------------------
step "2/6 拷贝前端产物到 src/main/resources/static"
STATIC_DIR="$JAVA_DIR/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -R "$WEB_DIR/dist/." "$STATIC_DIR/"
echo "已内嵌 $(find "$STATIC_DIR" -type f | wc -l | tr -d ' ') 个静态文件"

# ---------------------------------------------------------------
# 3. 后端打包
# ---------------------------------------------------------------
step "3/6 打包 Spring Boot（mvn package）"
cd "$JAVA_DIR"
"$MVN" -B -ntp -DskipTests package
cd "$ROOT"

JAR="$(ls "$JAVA_DIR"/target/fitlife-backend-*.jar 2>/dev/null | grep -v sources | head -n 1 || true)"
[ -n "$JAR" ] || fail "未找到 target/fitlife-backend-*.jar"
echo "jar = $(basename "$JAR")  ($(du -h "$JAR" | cut -f1))"

APP_INPUT="$BUILD_DIR/app"
rm -rf "$APP_INPUT"; mkdir -p "$APP_INPUT"
cp "$JAR" "$APP_INPUT/"

# ---------------------------------------------------------------
# 4. jlink 裁剪 JRE
# ---------------------------------------------------------------
step "4/6 jlink 裁剪运行时"
RUNTIME_DIR="$BUILD_DIR/runtime"
rm -rf "$RUNTIME_DIR"

# jdk.crypto.ec 必须显式加入，否则 HTTPS 握手失败（AI 对话连不上）
MODULES="java.base,java.logging,java.sql,java.naming,java.desktop,java.management,java.instrument,java.net.http,java.security.jgss,java.transaction.xa,java.xml,java.compiler,java.scripting,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.jfr"

"$JAVA_HOME/bin/jlink" \
  --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages \
  --compress=2 \
  --output "$RUNTIME_DIR"
[ -x "$RUNTIME_DIR/bin/java" ] || fail "jlink 失败"
echo "运行时大小 = $(du -sh "$RUNTIME_DIR" | cut -f1)（完整 JDK 约 300MB）"

# ---------------------------------------------------------------
# 5. 生成产物
# ---------------------------------------------------------------
rm -rf "$BUILD_DIR/portable" "$BUILD_DIR/out"
mkdir -p "$BUILD_DIR/out"

if [ "$PORTABLE_WIN" -eq 1 ]; then
  step "5/6 交叉组装 Windows 免安装便携包"

  JRE_ZIP="$BUILD_DIR/jre-win-x64.zip"
  if [ ! -f "$JRE_ZIP" ]; then
    echo "下载 Windows x64 JRE 17（约 45MB）..."
    curl -fL --retry 3 -o "$JRE_ZIP" \
      "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
  fi
  rm -rf "$BUILD_DIR/jre-win-extract"; mkdir -p "$BUILD_DIR/jre-win-extract"
  unzip -q "$JRE_ZIP" -d "$BUILD_DIR/jre-win-extract"
  JRE_DIR="$(find "$BUILD_DIR/jre-win-extract" -maxdepth 1 -mindepth 1 -type d | head -n 1)"

  PKG="$BUILD_DIR/portable/$APP_NAME"
  mkdir -p "$PKG"
  cp -R "$JRE_DIR" "$PKG/runtime"
  cp -R "$APP_INPUT" "$PKG/app"

  JAR_NAME="$(basename "$JAR")"
  cat > "$PKG/FitLife.bat" <<BAT
@echo off
setlocal
cd /d "%~dp0"
start "" "%~dp0runtime\\bin\\javaw.exe" -Dfile.encoding=UTF-8 -Xmx768m -jar "%~dp0app\\$JAR_NAME"
BAT
  cat > "$PKG/FitLife-debug.bat" <<BAT
@echo off
setlocal
cd /d "%~dp0"
echo 正在启动 FitLife（关闭此窗口即退出程序）...
"%~dp0runtime\\bin\\java.exe" -Dfile.encoding=UTF-8 -Xmx768m -jar "%~dp0app\\$JAR_NAME"
pause
BAT
  # bat 必须是 CRLF，否则 Windows 下可能解析异常
  for f in "$PKG"/*.bat; do sed -i 's/$/\r/' "$f"; done

  ZIP_OUT="$BUILD_DIR/out/FitLife-windows-portable-v$VERSION.zip"
  rm -f "$ZIP_OUT"
  ( cd "$BUILD_DIR/portable" && zip -qr "$ZIP_OUT" "$APP_NAME" )
else
  step "5/6 jpackage 生成当前平台 app-image"

  # 启动主类自适应：Spring Boot 3.2+ 在 loader.launch 包下，3.2 之前是 loader 包
  MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"
  if ! "$JAVA_HOME/bin/jar" tf "$JAR" 2>/dev/null | grep -q "org/springframework/boot/loader/launch/JarLauncher.class"; then
    MAIN_CLASS="org.springframework.boot.loader.JarLauncher"
  fi
  echo "启动主类 = $MAIN_CLASS"

  "$JAVA_HOME/bin/jpackage" \
    --type app-image \
    --name "$APP_NAME" \
    --app-version "$VERSION" \
    --vendor "FitLife" \
    --description "FitLife 健康管理 · AI 健身计划" \
    --input "$APP_INPUT" \
    --main-jar "$(basename "$JAR")" \
    --main-class "$MAIN_CLASS" \
    --runtime-image "$RUNTIME_DIR" \
    --java-options "-Dfile.encoding=UTF-8" \
    --java-options "-Xmx768m" \
    --dest "$BUILD_DIR/out"

  OS_TAG="$(uname -s | tr '[:upper:]' '[:lower:]')"
  ZIP_OUT="$BUILD_DIR/out/FitLife-$OS_TAG-v$VERSION.zip"
  rm -f "$ZIP_OUT"
  ( cd "$BUILD_DIR/out" && zip -qr "$ZIP_OUT" "$APP_NAME" )
fi

# ---------------------------------------------------------------
# 6. 收尾
# ---------------------------------------------------------------
step "6/6 完成"
[ -f "$ZIP_OUT" ] || fail "压缩包未生成"
echo "构建成功：$ZIP_OUT  ($(du -h "$ZIP_OUT" | cut -f1))"
echo
echo "提示："
echo "  1) 数据在 ~/.fitlife/data/，卸载重装不丢；"
echo "  2) AI Key 写在 ~/.fitlife/config/ai-key.yml（首次启动自动生成模板）；"
echo "  3) 想要 Windows exe，请在 Windows 上跑 build-desktop.ps1 或用 GitHub Actions。"
