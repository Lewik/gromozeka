$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $env:GROMOZEKA_MODE) {
    $env:GROMOZEKA_MODE = "prod"
}
if (-not $env:GROMOZEKA_HOME) {
    $env:GROMOZEKA_HOME = Join-Path $HOME ".gromozeka"
}
if (-not $env:GROMOZEKA_WEB_STATIC_DIR) {
    $env:GROMOZEKA_WEB_STATIC_DIR = Join-Path $AppHome "web"
}
if (-not $env:GROMOZEKA_SERVER_CONFIG) {
    $env:GROMOZEKA_SERVER_CONFIG = Join-Path $env:GROMOZEKA_HOME "server.yaml"
}

. (Join-Path $AppHome "bin/runtime-bootstrap.ps1")
$JavaExecutable = Resolve-GromozekaJava
& $JavaExecutable `
    -XX:MaxRAMPercentage=75.0 `
    -jar (Join-Path $AppHome "app/gromozeka-server.jar") `
    "--spring.config.additional-location=optional:file:$env:GROMOZEKA_SERVER_CONFIG" `
    @args
exit $LASTEXITCODE
