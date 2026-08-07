@echo off
REM ==========================================================================
REM  LAN FPS - Windows 10 server launcher
REM ==========================================================================
setlocal
title LAN FPS Server

cd /d "%~dp0"

REM --- locate a Java runtime ------------------------------------------------
set "JAVA_BIN=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"

"%JAVA_BIN%" -version >nul 2>&1
if errorlevel 1 (
  echo.
  echo   [ERROR] Java was not found on this PC.
  echo.
   echo   Install a Java 17 or newer runtime, for example Adoptium Temurin:
  echo       https://adoptium.net/temurin/releases/?version=17
  echo.
  echo   Then run this file again.
  echo.
  pause
  exit /b 1
)

echo.
echo  Starting LAN FPS server...
echo  Config: server.properties   ^(edit it to change mode / bots^)
echo  Stop the server with Ctrl+C.
echo.

REM --- show this PC IPv4 addresses so you know what to type on the phones ---
echo  This PC IPv4 addresses:
for /f "tokens=2 delims=:" %%a in (
'ipconfig ^| findstr /c:"IPv4 Address"'
) do echo    %%a
echo.

REM -Xmx256m is plenty: the server holds a few dozen entities.
"%JAVA_BIN%" -Xms64m -Xmx256m -XX:+UseSerialGC -jar server.jar %*

set "EXITCODE=%ERRORLEVEL%"
echo.
if not "%EXITCODE%"=="0" (
  echo  Server exited with code %EXITCODE%.
  echo  If the port is in use, try: run-server.bat --udpPort=7778
  echo.
  pause
)
endlocal
