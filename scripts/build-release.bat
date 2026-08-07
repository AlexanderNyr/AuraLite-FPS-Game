@echo off
REM ============================================================================
REM  LAN FPS - one-shot release build (Windows)
REM
REM  Produces:
REM     release\server.zip                    Windows server bundle
REM     release\lanfps-client-release.apk     signed Android APK (needs the SDK)
REM
REM  The APK step is skipped when no Android SDK is configured.
REM ============================================================================
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set "ROOT=%CD%"
set "OUT=%ROOT%\release"
if not exist "%OUT%" mkdir "%OUT%"

echo ==============================================================
echo  LAN FPS release build
echo  project: %ROOT%
echo ==============================================================

REM --- 1. signing key ---------------------------------------------------------
set "KEYSTORE=%ROOT%\keystore\lanfps.keystore"
if not exist "%KEYSTORE%" (
  echo [1/4] generating a local signing key ^(password: lanfps^)
  if not exist "%ROOT%\keystore" mkdir "%ROOT%\keystore"
  keytool -genkeypair -v -keystore "%KEYSTORE%" -alias lanfps -keyalg RSA ^
     -keysize 2048 -validity 10000 -storepass lanfps -keypass lanfps ^
     -dname "CN=LAN FPS, OU=LAN, O=LAN FPS, L=LAN, S=LAN, C=LT"
  if errorlevel 1 goto :fail
) else (
  echo [1/4] signing key already present
)

REM --- 2. tests ---------------------------------------------------------------
echo [2/4] running the shared + server test suites
call gradlew.bat :shared:test :server:test --console=plain
if errorlevel 1 goto :fail

REM --- 3. server bundle -------------------------------------------------------
echo [3/4] building the server bundle
call gradlew.bat :server:packageServer --console=plain
if errorlevel 1 goto :fail
echo       -^> %OUT%\server.zip

REM --- 4. Android APK ---------------------------------------------------------
set "SDK=%ANDROID_SDK_ROOT%"
if "%SDK%"=="" set "SDK=%ANDROID_HOME%"
if "%SDK%"=="" if exist "%ROOT%\local.properties" (
  for /f "tokens=1,* delims==" %%a in ('findstr /b "sdk.dir" "%ROOT%\local.properties"') do set "SDK=%%b"
)

if "%SDK%"=="" (
  echo [4/4] SKIPPED - no Android SDK found.
  echo.
  echo       The server bundle above is complete and ready to use.
  echo       To build the APK install Android Studio ^(or the command line
  echo       tools^), then set ANDROID_SDK_ROOT and run this file again.
  goto :done
)

echo [4/4] building the Android client with SDK at %SDK%
call gradlew.bat :client-android:assembleDebug :client-android:assembleRelease --console=plain
if errorlevel 1 goto :fail

if exist "%ROOT%\client-android\build\outputs\apk\debug\client-android-debug.apk" (
  copy /y "%ROOT%\client-android\build\outputs\apk\debug\client-android-debug.apk" "%OUT%\lanfps-client-debug.apk" >nul
)
if exist "%ROOT%\client-android\build\outputs\apk\release\client-android-release.apk" (
  copy /y "%ROOT%\client-android\build\outputs\apk\release\client-android-release.apk" "%OUT%\lanfps-client-release.apk" >nul
)

:done
echo.
echo ==============================================================
echo  done. artefacts in release\:
dir /b "%OUT%"
echo ==============================================================
endlocal
exit /b 0

:fail
echo.
echo  BUILD FAILED - see the messages above.
endlocal
exit /b 1
