param([string]$Package, [string]$Installation, [string]$Component, [string]$RequestId, [int]$OldProcessId, [string]$Result)
$ErrorActionPreference = 'Stop'
if ($RequestId -notmatch '^[a-zA-Z0-9-]{1,80}$') { throw 'Invalid update request' }
$Cache = Split-Path -Parent $Result
try {
    $Lease = [IO.File]::Open((Join-Path $Cache "installer-$RequestId.lock"), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
} catch [IO.IOException] {
    if (($_.Exception.HResult -band 0xFFFF) -eq 32) { exit 0 }
    throw
}
$StartedAt = ([DateTimeOffset](Get-Process -Id $PID).StartTime).ToUnixTimeSeconds()
Set-Content -LiteralPath (Join-Path $Cache "installer-$RequestId.process.new") -Value "$PID $StartedAt" -Encoding ASCII
Move-Item -LiteralPath (Join-Path $Cache "installer-$RequestId.process.new") -Destination (Join-Path $Cache "installer-$RequestId.process") -Force
$Staging = "$Installation.update-$RequestId"
$Previous = "$Installation.previous-$RequestId"
$Transaction = Join-Path $Cache "transaction-$RequestId"
$RecoveryName = "GromozekaUpdate-$RequestId"
$RecoveryKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$RecoveryLink = Join-Path ([Environment]::GetFolderPath('Programs')) "Gromozeka update recovery $RequestId.lnk"
$Finished = $false

function Write-Phase([string]$Phase) {
    $Bytes = [Text.Encoding]::UTF8.GetBytes($Phase)
    $Stream = [IO.File]::Open("$Transaction.new", [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $Stream.Write($Bytes, 0, $Bytes.Length); $Stream.Flush($true) } finally { $Stream.Dispose() }
    Move-Item -LiteralPath "$Transaction.new" -Destination $Transaction -Force
}

function Start-Installed {
    Start-Process -FilePath (Join-Path $Installation 'Gromozeka.exe')
}

function Restore-Previous {
    Write-Phase 'rolling-back'
    if (Test-Path -LiteralPath $Previous -PathType Container) {
        if (Test-Path -LiteralPath $Installation) { Remove-Item -LiteralPath $Installation -Recurse -Force }
        Move-Item -LiteralPath $Previous -Destination $Installation
    }
    if (-not (Test-Path -LiteralPath $Installation -PathType Container)) { throw 'Installation recovery is incomplete' }
    @{requestId=$RequestId; phase='FAILED'; message='Interrupted or failed installation restored; see installer.log'} | ConvertTo-Json -Compress | Set-Content -LiteralPath "$Result.new" -Encoding UTF8
    Move-Item -LiteralPath "$Result.new" -Destination $Result -Force
    Write-Phase 'failed'
    Start-Installed
}

try {
    if ($Component -eq 'CLIENT') {
        $Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File ' + ((@($PSCommandPath, $Package, $Installation, $Component, $RequestId, $OldProcessId.ToString(), $Result) | ForEach-Object { '"' + $_ + '"' }) -join ' ')
        if (-not (Test-Path $RecoveryKey)) { New-Item -Path $RecoveryKey -Force | Out-Null }
        New-ItemProperty -Path $RecoveryKey -Name $RecoveryName -Value ('powershell.exe ' + $Arguments) -PropertyType String -Force | Out-Null
        $Shell = New-Object -ComObject WScript.Shell
        $Shortcut = $Shell.CreateShortcut($RecoveryLink)
        $Shortcut.TargetPath = Join-Path $PSHOME 'powershell.exe'
        $Shortcut.Arguments = $Arguments
        $Shortcut.Save()
        if (Test-Path -LiteralPath $Transaction) {
            $Phase = [IO.File]::ReadAllText($Transaction).Trim()
            switch ($Phase) {
                'installed' { Start-Installed; Write-Phase 'complete' }
                'complete' { }
                { $_ -in @('prepared', 'replacing', 'rolling-back', 'failed') } { Restore-Previous }
                default { throw 'Unknown installation transaction state' }
            }
            $Finished = $true
            return
        }
    }
    if (-not (Test-Path -LiteralPath $Package -PathType Leaf)) { throw 'Invalid update package' }
    if ((Test-Path -LiteralPath $Staging) -or (Test-Path -LiteralPath $Previous)) { throw 'An update directory already exists' }
    if ($Component -eq 'CLIENT') { Write-Phase 'prepared' }
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
        Write-Phase 'replacing'
        Move-Item -LiteralPath $Installation -Destination $Previous
        Move-Item -LiteralPath $Staging -Destination $Installation
        Write-Phase 'installed'
        Start-Installed
        Write-Phase 'complete'
    } else { throw 'Unsupported component' }
    $Finished = $true
} catch {
    $Failure = $_
    if ($Component -eq 'CLIENT') {
        Restore-Previous
    } else {
        @{requestId=$RequestId; phase='FAILED'; message='Installation failed; see installer.log'} | ConvertTo-Json -Compress | Set-Content -LiteralPath "$Result.new" -Encoding UTF8
        Move-Item -LiteralPath "$Result.new" -Destination $Result -Force
        if ($Component -eq 'WORKER') { Start-Service GromozekaWorker -ErrorAction SilentlyContinue }
    }
    $Finished = $true
    throw $Failure
} finally {
    try {
        if ($Finished) {
            if (Test-Path -LiteralPath $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
            if ($Component -eq 'CLIENT') {
                Remove-ItemProperty -Path $RecoveryKey -Name $RecoveryName -ErrorAction SilentlyContinue
                Remove-Item -LiteralPath $RecoveryLink -ErrorAction SilentlyContinue
            }
        }
    } finally { $Lease.Dispose() }
}
