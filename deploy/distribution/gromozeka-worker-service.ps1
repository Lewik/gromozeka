param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet("install-service", "update-service", "uninstall-service", "start-service", "stop-service", "service-status")]
    [string]$Command
)

$ErrorActionPreference = "Stop"
$ServiceName = "GromozekaWorker"
$InstallHome = Join-Path $env:ProgramFiles "Gromozeka Worker"
$ServiceHome = Join-Path $env:ProgramData "Gromozeka\Worker"
$ServiceConfig = Join-Path $ServiceHome "worker.yaml"
$SourceHome = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Marker = ".gromozeka-worker-installation"
$Sc = Join-Path $env:SystemRoot "System32\sc.exe"

function Invoke-ServiceControl([string[]]$Arguments) {
    & $Sc @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Service control failed with exit code $LASTEXITCODE" }
}

function Set-DirectoryPermissions([string]$Directory, [bool]$ReadableByUsers) {
    $Acl = New-Object System.Security.AccessControl.DirectorySecurity
    $Acl.SetAccessRuleProtection($true, $false)
    $Administrators = New-Object System.Security.Principal.SecurityIdentifier("S-1-5-32-544")
    $Acl.SetOwner($Administrators)
    foreach ($Sid in @("S-1-5-18", "S-1-5-32-544")) {
        $Identity = New-Object System.Security.Principal.SecurityIdentifier($Sid)
        $Rule = New-Object System.Security.AccessControl.FileSystemAccessRule($Identity, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")
        $Acl.AddAccessRule($Rule)
    }
    if ($ReadableByUsers) {
        $Identity = New-Object System.Security.Principal.SecurityIdentifier("S-1-5-32-545")
        $Rule = New-Object System.Security.AccessControl.FileSystemAccessRule($Identity, "ReadAndExecute", "ContainerInherit,ObjectInherit", "None", "Allow")
        $Acl.AddAccessRule($Rule)
    }
    Set-Acl -LiteralPath $Directory -AclObject $Acl
}

function Stop-InstalledWorker {
    $Service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    $ServiceDetails = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    $ServiceProcess = if ($ServiceDetails -and $ServiceDetails.ProcessId -gt 0) {
        Get-Process -Id $ServiceDetails.ProcessId -ErrorAction SilentlyContinue
    } else { $null }
    if ($Service -and $Service.Status -ne "Stopped") {
        Stop-Service -Name $ServiceName
        $Service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(120))
    }
    if ($ServiceProcess -and -not $ServiceProcess.WaitForExit(120000)) {
        throw "Worker service process did not exit; refusing to replace running program files."
    }
}

if ($Command -eq "service-status") {
    $Service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($Service) {
        $Service | Format-Table Name, Status, StartType -AutoSize
        Write-Output "Installation: $InstallHome"
        Write-Output "Configuration: $ServiceConfig"
    } else { Write-Output "Gromozeka Worker service is not installed." }
    exit 0
}

$Principal = New-Object System.Security.Principal.WindowsPrincipal([System.Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $Principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Open PowerShell as administrator to manage the Gromozeka Worker service."
}

$ExistingService = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
if ($ExistingService -and -not $ExistingService.PathName.StartsWith('"' + (Join-Path $InstallHome "runtime\java\bin\java.exe") + '" ')) {
    throw "An unrelated service uses the name $ServiceName; refusing to change it."
}

switch ($Command) {
    "start-service" {
        Start-Service -Name $ServiceName
        (Get-Service $ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(120))
        exit 0
    }
    "stop-service" { Stop-InstalledWorker; exit 0 }
    "uninstall-service" {
        Stop-InstalledWorker
        if ($ExistingService) { Invoke-ServiceControl @("delete", $ServiceName) }
        Write-Output "Service removed. Installation and configuration were preserved at $InstallHome and $ServiceHome."
        exit 0
    }
}

if ($Command -eq "update-service" -and -not $ExistingService) {
    throw "Gromozeka Worker service is not installed. Use install-service first."
}
if ((Test-Path $InstallHome) -and -not (Test-Path (Join-Path $InstallHome $Marker))) {
    throw "Installation directory is not managed by Gromozeka: $InstallHome"
}
foreach ($RelativePath in @("app\gromozeka-worker.jar", "runtime\java\bin\java.exe", "runtime\java\bin\javaw.exe", "runtime\node\node.exe")) {
    if (-not (Test-Path (Join-Path $SourceHome $RelativePath) -PathType Leaf)) {
        throw "Incomplete Worker distribution: missing $RelativePath"
    }
}

New-Item -ItemType Directory -Path $ServiceHome -Force | Out-Null
Set-DirectoryPermissions $ServiceHome $false
if (-not (Test-Path $ServiceConfig)) {
    $SourceConfig = $env:GROMOZEKA_WORKER_CONFIG
    if (-not $SourceConfig) {
        $UserHome = $env:GROMOZEKA_HOME
        if (-not $UserHome) { $UserHome = Join-Path $HOME ".gromozeka" }
        $SourceConfig = Join-Path $UserHome "worker.yaml"
    }
    if (-not (Test-Path $SourceConfig -PathType Leaf)) {
        throw "Connect the Worker first with 'gromozeka-worker.cmd connect --server <url> --worker-id <name>', then install-service."
    }
    Copy-Item -LiteralPath $SourceConfig -Destination $ServiceConfig
}

$Staging = "$InstallHome.staging-$([Guid]::NewGuid().ToString('N'))"
$Previous = "$InstallHome.previous-$([Guid]::NewGuid().ToString('N'))"
$Swapped = $false
$MovedPrevious = $false
try {
    New-Item -ItemType Directory -Path $Staging | Out-Null
    Set-DirectoryPermissions $Staging $true
    foreach ($Entry in @("app", "bin", "runtime", "config", "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md")) {
        Copy-Item -LiteralPath (Join-Path $SourceHome $Entry) -Destination $Staging -Recurse
    }
    Set-Content -LiteralPath (Join-Path $Staging $Marker) -Value "GromozekaWorker" -Encoding ASCII
    & (Join-Path $Staging "runtime\java\bin\java.exe") -version
    if ($LASTEXITCODE -ne 0) { throw "Bundled Java verification failed" }

    Stop-InstalledWorker
    if (Test-Path $InstallHome) {
        Move-Item -LiteralPath $InstallHome -Destination $Previous
        $MovedPrevious = $true
    }
    Move-Item -LiteralPath $Staging -Destination $InstallHome
    $Swapped = $true

    $Java = Join-Path $InstallHome "runtime\java\bin\java.exe"
    $Jar = Join-Path $InstallHome "app\gromozeka-worker.jar"
    $ConfigUri = ([Uri]$ServiceConfig).AbsoluteUri
    $BinaryPath = '"' + $Java + '" -jar "' + $Jar + '" windows-service "--spring.config.additional-location=' + $ConfigUri + '"'
    if ($ExistingService) {
        $Change = Invoke-CimMethod -InputObject $ExistingService -MethodName Change -Arguments @{
            PathName = $BinaryPath; StartMode = "Automatic"; StartName = "LocalSystem"
        }
        if ($Change.ReturnValue -ne 0) { throw "Service configuration failed with code $($Change.ReturnValue)" }
    } else {
        New-Service -Name $ServiceName -BinaryPathName $BinaryPath -StartupType Automatic -DisplayName "Gromozeka Worker" | Out-Null
    }
    Invoke-ServiceControl @("description", $ServiceName, "Gromozeka trusted Worker with an interactive desktop helper.")
    Invoke-ServiceControl @("sdset", $ServiceName, "D:(A;;CCLCSWRPWPDTLOCRRC;;;SY)(A;;CCDCLCSWRPWPDTLOCRSDRCWDWO;;;BA)(A;;CCLCSWLOCRRC;;;AU)")
    Invoke-ServiceControl @("failure", $ServiceName, "reset=", "86400", "actions=", "restart/5000/restart/15000/restart/60000")
    Invoke-ServiceControl @("failureflag", $ServiceName, "1")
    $Environment = @(
        "GROMOZEKA_MODE=prod",
        "GROMOZEKA_HOME=$ServiceHome",
        "GROMOZEKA_WORKER_CONFIG=$ServiceConfig",
        "GROMOZEKA_LOG_DIR=$(Join-Path $ServiceHome 'logs')",
        "GROMOZEKA_BROWSER_MCP_LAUNCHER=$(Join-Path $InstallHome 'bin\gromozeka-browser-mcp.cmd')",
        "GROMOZEKA_BROWSER_MCP_HOME=$(Join-Path $InstallHome 'app\browser-mcp')",
        "GROMOZEKA_NODE_EXECUTABLE=$(Join-Path $InstallHome 'runtime\node\node.exe')"
    )
    New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName" -Name Environment -PropertyType MultiString -Value $Environment -Force | Out-Null
    Start-Service -Name $ServiceName
    (Get-Service $ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(120))
    if (Test-Path $Previous) { Remove-Item -LiteralPath $Previous -Recurse -Force }
    Write-Output "Gromozeka Worker service is running. Configuration: $ServiceConfig"
} catch {
    $InstallError = $_
    if ($MovedPrevious -and (Test-Path $Previous)) {
        Stop-InstalledWorker
        if ($Swapped) { Remove-Item -LiteralPath $InstallHome -Recurse -Force }
        Move-Item -LiteralPath $Previous -Destination $InstallHome
        Write-Warning "Previous installation restored. Inspect the error before restarting the service."
    }
    throw $InstallError
} finally {
    if (Test-Path $Staging) { Remove-Item -LiteralPath $Staging -Recurse -Force }
}
