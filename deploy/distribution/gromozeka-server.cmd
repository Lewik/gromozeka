@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0gromozeka-server.ps1" %*
exit /b %ERRORLEVEL%
