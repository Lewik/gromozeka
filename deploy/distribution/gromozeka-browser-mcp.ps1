$ErrorActionPreference = "Stop"
$AppHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$BrowserMcpHome = if ($env:GROMOZEKA_BROWSER_MCP_HOME) {
    $env:GROMOZEKA_BROWSER_MCP_HOME
} elseif (Test-Path (Join-Path $AppHome "app/browser-mcp") -PathType Container) {
    Join-Path $AppHome "app/browser-mcp"
} else {
    Join-Path (Resolve-Path (Join-Path $PSScriptRoot "../..")) "browser-mcp"
}
$NodeExecutable = if ($env:GROMOZEKA_NODE_EXECUTABLE) {
    $NodeCommand = Get-Command $env:GROMOZEKA_NODE_EXECUTABLE -ErrorAction SilentlyContinue
    if ($null -eq $NodeCommand) {
        throw "Configured Node.js executable was not found: $env:GROMOZEKA_NODE_EXECUTABLE"
    }
    $NodeCommand.Source
} else {
    Join-Path $AppHome "runtime/node/node.exe"
}
if (-not (Test-Path $NodeExecutable -PathType Leaf)) {
    throw "Bundled Node.js runtime is missing: $(Join-Path $AppHome 'runtime/node')"
}
$McpCli = Join-Path $BrowserMcpHome "node_modules/@playwright/mcp/cli.js"
if (-not (Test-Path $McpCli -PathType Leaf)) {
    throw "Bundled Browser MCP is missing: $McpCli"
}

& $NodeExecutable $McpCli @args
exit $LASTEXITCODE
