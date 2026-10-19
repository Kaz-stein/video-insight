@echo off
rem Entry point for cmd.exe / double-click. Always uses pwsh 7 with bypass policy.
setlocal
set "PSEXE=%ProgramFiles%\PowerShell\7\pwsh.exe"
if not exist "%PSEXE%" set "PSEXE=powershell.exe"
"%PSEXE%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-local.ps1" %*
exit /b %ERRORLEVEL%
