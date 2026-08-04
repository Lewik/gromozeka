$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BrowserMcpHome = if ($env:GROMOZEKA_BROWSER_MCP_HOME) {
    $env:GROMOZEKA_BROWSER_MCP_HOME
} elseif (Test-Path (Join-Path $AppHome "app/browser-mcp") -PathType Container) {
    Join-Path $AppHome "app/browser-mcp"
} else {
    Join-Path (Resolve-Path (Join-Path $PSScriptRoot "../..")) "browser-mcp"
}
$RuntimeBootstrap = if ($env:GROMOZEKA_RUNTIME_BOOTSTRAP) {
    $env:GROMOZEKA_RUNTIME_BOOTSTRAP
} else {
    Join-Path $PSScriptRoot "runtime-bootstrap.ps1"
}

. $RuntimeBootstrap
$NodeExecutable = Resolve-GromozekaNode
$McpCli = Join-Path $BrowserMcpHome "node_modules/@playwright/mcp/cli.js"
if (-not (Test-Path $McpCli -PathType Leaf)) {
    throw "Bundled Browser MCP is missing: $McpCli"
}

& $NodeExecutable $McpCli @args
exit $LASTEXITCODE
