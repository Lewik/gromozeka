@echo off
setlocal

set "APP_HOME=%~dp0.."
if not defined GROMOZEKA_HOME set "GROMOZEKA_HOME=%USERPROFILE%\.gromozeka"
if not defined GROMOZEKA_WORKER_CONFIG set "GROMOZEKA_WORKER_CONFIG=%GROMOZEKA_HOME%\worker.yaml"

if /I "%~1"=="enroll" (
  "%APP_HOME%\runtime\bin\java.exe" -jar "%APP_HOME%\app\gromozeka-worker.jar" %*
  exit /b %ERRORLEVEL%
)

"%APP_HOME%\runtime\bin\java.exe" -jar "%APP_HOME%\app\gromozeka-worker.jar" "--spring.config.additional-location=optional:file:%GROMOZEKA_WORKER_CONFIG%" %*
exit /b %ERRORLEVEL%
