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
$JavaExecutable = Join-Path $AppHome "runtime/java/bin/java.exe"
if (-not (Test-Path $JavaExecutable -PathType Leaf)) {
    throw "Bundled Java runtime is missing: $(Join-Path $AppHome 'runtime/java')"
}
$WorkerJar = Join-Path $AppHome "app/gromozeka-worker.jar"

$ConfigurationCommands = @("enroll", "connect", "configure")
if ($args.Count -gt 0 -and $ConfigurationCommands -contains $args[0]) {
    & $JavaExecutable -jar $WorkerJar @args
} else {
    & $JavaExecutable `
        -jar $WorkerJar `
        "--spring.config.additional-location=optional:file:$env:GROMOZEKA_WORKER_CONFIG" `
        @args
}
exit $LASTEXITCODE
