@echo off
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0gromozeka-browser-mcp.ps1" %*
exit /b %ERRORLEVEL%
