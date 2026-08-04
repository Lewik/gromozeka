$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if (-not $env:GROMOZEKA_HOME) {
    $env:GROMOZEKA_HOME = Join-Path $HOME ".gromozeka"
}
if (-not $env:GROMOZEKA_WORKER_CONFIG) {
    $env:GROMOZEKA_WORKER_CONFIG = Join-Path $env:GROMOZEKA_HOME "worker.yaml"
}
if (-not $env:GROMOZEKA_BROWSER_MCP_LAUNCHER) {
    $env:GROMOZEKA_BROWSER_MCP_LAUNCHER = Join-Path $AppHome "bin/gromozeka-browser-mcp.cmd"
}
if (-not $env:GROMOZEKA_BROWSER_MCP_HOME) {
    $env:GROMOZEKA_BROWSER_MCP_HOME = Join-Path $AppHome "app/browser-mcp"
}
if (-not $env:GROMOZEKA_RUNTIME_BOOTSTRAP) {
    $env:GROMOZEKA_RUNTIME_BOOTSTRAP = Join-Path $AppHome "bin/runtime-bootstrap.ps1"
}

. (Join-Path $AppHome "bin/runtime-bootstrap.ps1")
$JavaExecutable = Resolve-GromozekaJava
$WorkerJar = Join-Path $AppHome "app/gromozeka-worker.jar"

if ($args.Count -gt 0 -and $args[0] -eq "enroll") {
    & $JavaExecutable -jar $WorkerJar @args
} else {
    & $JavaExecutable `
        -jar $WorkerJar `
        "--spring.config.additional-location=optional:file:$env:GROMOZEKA_WORKER_CONFIG" `
        @args
}
exit $LASTEXITCODE
