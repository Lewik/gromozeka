@echo off
setlocal

set "APP_HOME=%~dp0.."
if not defined GROMOZEKA_MODE set "GROMOZEKA_MODE=prod"
if not defined GROMOZEKA_HOME set "GROMOZEKA_HOME=%USERPROFILE%\.gromozeka"
if not defined GROMOZEKA_WEB_STATIC_DIR set "GROMOZEKA_WEB_STATIC_DIR=%APP_HOME%\web"
if not defined GROMOZEKA_SERVER_CONFIG set "GROMOZEKA_SERVER_CONFIG=%GROMOZEKA_HOME%\server.yaml"

"%APP_HOME%\runtime\bin\java.exe" -XX:MaxRAMPercentage=75.0 -jar "%APP_HOME%\app\gromozeka-server.jar" "--spring.config.additional-location=optional:file:%GROMOZEKA_SERVER_CONFIG%" %*
exit /b %ERRORLEVEL%
