@echo off
setlocal
chcp 65001 >nul
title KGPT 最终修正版 (7897 优先模式)

:: ==========================================
:: 核心修正：强制锁定“当下目录”
:: ==========================================
cd /d "%~dp0"
echo [1/4] 已锁定当前工作目录：
echo %cd%

:: 再次检查防呆
if not exist "gradlew" (
    echo.
    echo [错误] 脚本没放对位置！
    echo 请务必把此脚本放在和 gradlew, app 同一级的文件夹里！
    pause
    exit
)

:: ==========================================
:: 步骤 2：生成一定会成功的编译配置
:: ==========================================
echo.
echo [2/4] 正在生成“通吃型”编译配置...

if not exist ".github\workflows" mkdir ".github\workflows"

:: 写入配置：搜索所有 APK，不再指定文件名
(
echo name: Android Build
echo.
echo on:
echo   push:
echo     branches: [ "main" ]
echo   workflow_dispatch:
echo.
echo jobs:
echo   build:
echo     runs-on: ubuntu-latest
echo.
echo     steps:
echo     - uses: actions/checkout@v4
echo.    
echo     - name: Set up JDK 17
echo       uses: actions/setup-java@v4
echo       with:
echo         java-version: '17'
echo         distribution: 'temurin'
echo         cache: gradle
echo.
echo     - name: Grant execute permission for gradlew
echo       run: chmod +x gradlew
echo.
echo     - name: Build with Gradle
echo       run: ./gradlew assembleDebug
echo.
echo     - name: Upload APK
echo       uses: actions/upload-artifact@v4
echo       with:
echo         name: KGPT-Final-APK
echo         path: "**/*.apk"
) > ".github\workflows\android_build.yml"

echo 配置已修复。

:: ==========================================
:: 步骤 3：Git 提交 (扫描当下目录所有文件)
:: ==========================================
echo.
echo [3/4] 正在扫描并提交当下目录所有文件...

if not exist .git git init
git remote remove origin 2>nul
git remote add origin https://github.com/3030606794/KGPT.git

:: 先清除所有代理设置，防止之前的残留
git config --global --unset http.proxy 2>nul
git config --global --unset https.proxy 2>nul
git config --unset http.proxy 2>nul
git config --unset https.proxy 2>nul

git add .
git commit -m "Final Fix: Direct Port 7897" 2>nul
git branch -M main

:: ==========================================
:: 步骤 4：上传 (优先使用 Clash Verge 7897)
:: ==========================================
echo.
echo [4/4] 正在推送到 GitHub...

:: 第一次尝试：直接强制指定 Clash Verge 端口 7897
echo [尝试 1] 正在通过代理端口 7897 上传...
git config http.proxy http://127.0.0.1:7897
git config https.proxy http://127.0.0.1:7897
git push -u origin main --force
if not errorlevel 1 goto success

:: 第二次尝试：如果 7897 失败，尝试旧版端口 7890
echo.
echo [警告] 端口 7897 失败，尝试旧版端口 7890...
git config http.proxy http://127.0.0.1:7890
git config https.proxy http://127.0.0.1:7890
git push -u origin main --force
if not errorlevel 1 goto success

:: 第三次尝试：最后尝试直连（作为保底）
echo.
echo [警告] 代理均失败，尝试取消代理直连...
git config --unset http.proxy
git config --unset https.proxy
git push -u origin main --force
if not errorlevel 1 goto success

echo.
echo [严重错误] 所有通道均无法连接 GitHub。
echo 请检查你的 Clash 是否开启，且端口确实是 7897。
pause
exit

:success
echo.
echo ==========================================
echo  🎉 成功！搞定了！
echo ==========================================
echo 1. 去 GitHub 点击 "Actions"
echo 2. 等那个转圈的任务变成绿色
echo 3. 点进去下载 "KGPT-Final-APK"
echo ==========================================
pause