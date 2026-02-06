@echo off
setlocal EnableDelayedExpansion
chcp 65001 >nul
title GitHub 万能发布工具 (防闪退最终版)
color 0A

:: ========================================================
:: 0. 自动排除脚本自身
:: ========================================================
cd /d "%~dp0"
if not exist .gitignore type nul > .gitignore
findstr /C:"万能发布工具.bat" .gitignore >nul
if errorlevel 1 echo 万能发布工具.bat>> .gitignore

:: ========================================================
:: 1. 仓库选择菜单 (您之前的要求)
:: ========================================================
:repo_menu
cls
echo ========================================================
echo               第一步：选择目标仓库
echo ========================================================
echo.
echo  [1] PasteBar (电脑版)
echo      地址: https://github.com/3030606794/-.git
echo.
echo  [2] KGPT (安卓版)
echo      地址: https://github.com/3030606794/KGPT.git
echo.
echo  [3] 毒蛇
echo      地址: https://github.com/3030606794/毒蛇.git
echo.
echo  [4] DDCToolbox-Build
echo      地址: https://github.com/3030606794/DDCToolbox-Build.git
echo.
echo  [5] 手动粘贴新仓库地址...
echo.
echo ========================================================
set /p repo_choice="请输入数字 (1-5): "

if "%repo_choice%"=="1" set "repo_url=https://github.com/3030606794/-.git" && goto mode_menu
if "%repo_choice%"=="2" set "repo_url=https://github.com/3030606794/KGPT.git" && goto mode_menu
if "%repo_choice%"=="3" set "repo_url=https://github.com/3030606794/毒蛇.git" && goto mode_menu
if "%repo_choice%"=="4" set "repo_url=https://github.com/3030606794/DDCToolbox-Build.git" && goto mode_menu
if "%repo_choice%"=="5" goto manual_repo

echo 输入错误，请重试。
goto repo_menu

:manual_repo
echo.
set /p repo_url="请粘贴仓库地址 (右键粘贴): "
if "%repo_url%"=="" goto manual_repo
goto mode_menu

:: ========================================================
:: 2. 项目类型 (生成配置 - 改为单行写入防闪退)
:: ========================================================
:mode_menu
cls
echo ========================================================
echo               第二步：选择项目类型
echo ========================================================
echo.
echo  [1] 电脑软件 (PC Windows)
echo      - 目标: .exe / .msi
echo.
echo  [2] 安卓软件 (Android)
echo      - 目标: .apk
echo.
echo ========================================================
set /p mode="请输入数字 (1 或 2): "

if "%mode%"=="1" goto pc_config
if "%mode%"=="2" goto android_config
goto mode_menu

:: --- 电脑版配置 (PC) ---
:pc_config
echo.
echo [1/3] 正在生成 Windows 配置 (防闪退模式)...
if not exist ".github\workflows" mkdir ".github\workflows"
del ".github\workflows\*.yml" 2>nul

:: 单行写入，绝对安全
echo name: Windows Build > ".github\workflows\windows_build.yml"
echo on: >> ".github\workflows\windows_build.yml"
echo   push: >> ".github\workflows\windows_build.yml"
echo     branches: [ "main" ] >> ".github\workflows\windows_build.yml"
echo jobs: >> ".github\workflows\windows_build.yml"
echo   build-windows: >> ".github\workflows\windows_build.yml"
echo     runs-on: windows-latest >> ".github\workflows\windows_build.yml"
echo     steps: >> ".github\workflows\windows_build.yml"
echo     - uses: actions/checkout@v4 >> ".github\workflows\windows_build.yml"
echo     - name: Setup Node.js >> ".github\workflows\windows_build.yml"
echo       uses: actions/setup-node@v4 >> ".github\workflows\windows_build.yml"
echo       with: >> ".github\workflows\windows_build.yml"
echo         node-version: 'lts/*' >> ".github\workflows\windows_build.yml"
echo     - name: Install Rust >> ".github\workflows\windows_build.yml"
echo       uses: dtolnay/rust-toolchain@stable >> ".github\workflows\windows_build.yml"
echo     - name: Install dependencies >> ".github\workflows\windows_build.yml"
echo       run: npm install >> ".github\workflows\windows_build.yml"
echo     - name: Build App >> ".github\workflows\windows_build.yml"
echo       run: npm run tauri build >> ".github\workflows\windows_build.yml"
echo     - name: Upload Installer >> ".github\workflows\windows_build.yml"
echo       uses: actions/upload-artifact@v4 >> ".github\workflows\windows_build.yml"
echo       with: >> ".github\workflows\windows_build.yml"
echo         name: PC-Windows-Installer >> ".github\workflows\windows_build.yml"
echo         path: src-tauri/target/release/bundle/*/*.{exe,msi} >> ".github\workflows\windows_build.yml"

goto upload_start

:: --- 安卓版配置 (Android) ---
:android_config
echo.
echo [1/3] 正在生成 Android 配置 (防闪退模式)...
if not exist ".github\workflows" mkdir ".github\workflows"
del ".github\workflows\*.yml" 2>nul

echo name: Android Build > ".github\workflows\android_build.yml"
echo on: >> ".github\workflows\android_build.yml"
echo   push: >> ".github\workflows\android_build.yml"
echo     branches: [ "main" ] >> ".github\workflows\android_build.yml"
echo jobs: >> ".github\workflows\android_build.yml"
echo   build-android: >> ".github\workflows\android_build.yml"
echo     runs-on: ubuntu-latest >> ".github\workflows\android_build.yml"
echo     steps: >> ".github\workflows\android_build.yml"
echo     - uses: actions/checkout@v4 >> ".github\workflows\android_build.yml"
echo     - name: Set up JDK 17 >> ".github\workflows\android_build.yml"
echo       uses: actions/setup-java@v4 >> ".github\workflows\android_build.yml"
echo       with: >> ".github\workflows\android_build.yml"
echo         java-version: '17' >> ".github\workflows\android_build.yml"
echo         distribution: 'temurin' >> ".github\workflows\android_build.yml"
echo     - name: Grant execute permission for gradlew >> ".github\workflows\android_build.yml"
echo       run: chmod +x gradlew >> ".github\workflows\android_build.yml"
echo     - name: Build with Gradle >> ".github\workflows\android_build.yml"
echo       run: ./gradlew assembleDebug >> ".github\workflows\android_build.yml"
echo     - name: Upload APK >> ".github\workflows\android_build.yml"
echo       uses: actions/upload-artifact@v4 >> ".github\workflows\android_build.yml"
echo       with: >> ".github\workflows\android_build.yml"
echo         name: Android-APK-Installer >> ".github\workflows\android_build.yml"
echo         path: "**/*.apk" >> ".github\workflows\android_build.yml"

goto upload_start

:: ========================================================
:: 3. 核心上传逻辑
:: ========================================================
:upload_start
echo.
echo [2/3] 正在打包所有文件 (包括子文件夹)...
if not exist .git git init
git remote remove origin 2>nul
git remote add origin %repo_url%

git config --global --unset http.proxy 2>nul
git config --global --unset https.proxy 2>nul

:: 暴力添加所有内容
git add --all
git commit -m "Auto Upload Source Code" 2>nul
git branch -M main

echo.
echo [3/3] 正在推送到 GitHub...
echo 目标: %repo_url%

:: 端口轮询
echo [尝试] 端口 7897...
git config http.proxy http://127.0.0.1:7897
git config https.proxy http://127.0.0.1:7897
git push -u origin main --force
if not errorlevel 1 goto success

echo [尝试] 端口 7890...
git config http.proxy http://127.0.0.1:7890
git config https.proxy http://127.0.0.1:7890
git push -u origin main --force
if not errorlevel 1 goto success

echo [尝试] 直连...
git config --unset http.proxy
git config --unset https.proxy
git push -u origin main --force
if not errorlevel 1 goto success

color 0C
echo.
echo [失败] 无法上传。请检查网络。
pause
exit

:: ========================================================
:: 4. 成功倒计时
:: ========================================================
:success
color 0A
cls
echo ========================================================
echo               🎉 任务圆满完成！
echo ========================================================
echo.
echo  1. 已上传至: %repo_url%
echo  2. 编译已开始，稍后请去 GitHub 下载。
echo.
echo  窗口将在 10 秒后自动关闭...
echo ========================================================
timeout /t 10
exit