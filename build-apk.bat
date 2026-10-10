@echo off
setlocal
cd /d "%~dp0"

rem JDK: use Android Studio's bundled one if JAVA_HOME is not set
if not defined JAVA_HOME (
  if exist "C:\Program Files\Android\Android Studio\jbr" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
)

rem Android SDK location
if not exist local.properties (
  if exist "%LOCALAPPDATA%\Android\Sdk" echo sdk.dir=%LOCALAPPDATA:\=/%/Android/Sdk> local.properties
)

rem AGP 8.5 needs Gradle 8.x, so fetch a private copy of 8.9 once
set "GV=8.9"
set "GD=%~dp0.gradle-dist\gradle-%GV%"
if not exist "%GD%\bin\gradle.bat" (
  echo Downloading Gradle %GV% ...
  powershell -NoProfile -Command "New-Item -ItemType Directory -Force '.gradle-dist' | Out-Null; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GV%-bin.zip' -OutFile '.gradle-dist\g.zip'; Expand-Archive -Force '.gradle-dist\g.zip' '.gradle-dist'"
)

rem QuickJS = the JavaScript runtime YouTube downloads need (the app starts it as libqjs.so)
if not exist app\src\main\jniLibs\arm64-v8a\libqjs.so (
  echo Downloading QuickJS ...
  powershell -NoProfile -Command "New-Item -ItemType Directory -Force 'app\src\main\jniLibs\arm64-v8a','app\src\main\jniLibs\armeabi-v7a' | Out-Null; $b='https://github.com/quickjs-ng/quickjs/releases/download/v0.17.0'; Invoke-WebRequest ($b + '/qjs-linux-aarch64') -OutFile 'app\src\main\jniLibs\arm64-v8a\libqjs.so'; Invoke-WebRequest ($b + '/qjs-linux-armv7') -OutFile 'app\src\main\jniLibs\armeabi-v7a\libqjs.so'"
)

call "%GD%\bin\gradle.bat" --no-daemon assembleDebug
if errorlevel 1 (
  echo.
  echo Build failed. Read the error above.
  pause
  exit /b 1
)

if not exist dist mkdir dist
copy /y app\build\outputs\apk\debug\app-debug.apk dist\HoneyBeat.apk >nul
echo.
echo Done: dist\HoneyBeat.apk
pause
