@echo off
setlocal
title MobileClaw Trace
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-trace-desktop.ps1" %*
set "TRACE_EXIT=%ERRORLEVEL%"
if not "%TRACE_EXIT%"=="0" pause
exit /b %TRACE_EXIT%
