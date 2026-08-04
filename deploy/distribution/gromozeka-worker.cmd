@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0gromozeka-worker.ps1" %*
exit /b %ERRORLEVEL%
