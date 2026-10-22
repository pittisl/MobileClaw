@echo off
setlocal
rem This policy applies only to this PowerShell process, never to the user's settings.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-trace.ps1" %*
set "TRACE_EXIT=%ERRORLEVEL%"
if not "%TRACE_EXIT%"=="0" pause
exit /b %TRACE_EXIT%
