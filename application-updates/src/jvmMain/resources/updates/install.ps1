param([string]$Package, [string]$Installation, [string]$Component, [string]$RequestId, [int]$OldProcessId, [string]$Result)
$ErrorActionPreference = 'Stop'
Set-Content -LiteralPath (Join-Path (Split-Path -Parent $Result) "installer-$RequestId.pid") -Value $PID -Encoding ASCII
$Staging = "$Installation.update-$RequestId"
$Previous = "$Installation.previous-$RequestId"
$MovedPrevious = $false
try {
    if ($RequestId -notmatch '^[a-zA-Z0-9-]{1,80}$' -or -not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw 'Invalid update package' }
    if ((Test-Path -LiteralPath $Staging) -or (Test-Path -LiteralPath $Previous)) { throw 'An update directory already exists' }
    Expand-Archive -LiteralPath $Package -DestinationPath $Staging
    if ($Component -eq 'WORKER') {
        $Source = Join-Path $Staging 'gromozeka-worker-windows-x64'
        $Installer = Join-Path $Source 'bin\gromozeka-worker-service.ps1'
        if (-not (Test-Path -LiteralPath $Installer -PathType Leaf)) { throw 'Worker service installer is missing' }
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $Installer update-service
        if ($LASTEXITCODE -ne 0) { throw 'Worker service update failed' }
    } elseif ($Component -eq 'CLIENT') {
        if (-not (Test-Path -LiteralPath (Join-Path $Staging 'Gromozeka.exe') -PathType Leaf)) { throw 'Client launcher is missing' }
        if (-not (Test-Path -LiteralPath (Join-Path $Staging 'runtime') -PathType Container)) { throw 'Client runtime is missing' }
        $Process = Get-Process -Id $OldProcessId -ErrorAction SilentlyContinue
        if ($Process -and -not $Process.WaitForExit(120000)) { throw 'Client did not exit' }
        Move-Item -LiteralPath $Installation -Destination $Previous
        $MovedPrevious = $true
        Move-Item -LiteralPath $Staging -Destination $Installation
        Start-Process -FilePath (Join-Path $Installation 'Gromozeka.exe')
    } else { throw 'Unsupported component' }
} catch {
    if ($MovedPrevious -and (Test-Path -LiteralPath $Previous)) {
        if (Test-Path -LiteralPath $Installation) { Remove-Item -LiteralPath $Installation -Recurse -Force }
        Move-Item -LiteralPath $Previous -Destination $Installation
        Start-Process -FilePath (Join-Path $Installation 'Gromozeka.exe')
    }
    @{requestId=$RequestId; phase='FAILED'; message='Installation failed; see installer.log'} | ConvertTo-Json -Compress | Set-Content -LiteralPath "$Result.new" -Encoding UTF8
    Move-Item -LiteralPath "$Result.new" -Destination $Result -Force
    if ($Component -eq 'WORKER') { Start-Service GromozekaWorker -ErrorAction SilentlyContinue }
    throw
} finally {
    if (Test-Path -LiteralPath $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
}
