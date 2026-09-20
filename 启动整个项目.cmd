@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0RuoYi-Cloud\bin\Start-LocalPlatform.ps1" %*
exit /b %ERRORLEVEL%
