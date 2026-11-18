$ErrorActionPreference = "Stop"
$Root = Split-Path $PSScriptRoot -Parent
$Installer = Join-Path $Root "deploy/distribution/gromozeka-worker-service.ps1"
$Tokens = $null
$Errors = $null
$Ast = [System.Management.Automation.Language.Parser]::ParseFile($Installer, [ref]$Tokens, [ref]$Errors)
if ($Errors.Count) { throw ($Errors | Out-String) }
$Definition = $Ast.Find({ param($Node)
    $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq "Confirm-WorkerLicenses"
}, $true)
if ($null -eq $Definition) { throw "License setup function is missing" }
# Load only the real acceptance function, never the service installer's mutating top-level code.
. ([scriptblock]::Create($Definition.Extent.Text))

function Assert-True([bool]$Value, [string]$Message) {
    if (-not $Value) { throw $Message }
}

$Fixture = Join-Path ([IO.Path]::GetTempPath()) ("grz-license-setup-" + [Guid]::NewGuid().ToString("N"))
$OriginalHome = $env:GROMOZEKA_HOME
$OriginalInstallation = $env:GROMOZEKA_INSTALLATION_HOME
try {
    New-Item -ItemType Directory -Path $Fixture | Out-Null
    $UserHome = Join-Path $Fixture "user home & operator"
    $ServiceHome = Join-Path $Fixture "service home"
    $env:GROMOZEKA_HOME = $UserHome
    $CallerInstallation = Join-Path $Fixture "old Worker"
    $env:GROMOZEKA_INSTALLATION_HOME = $CallerInstallation
    $App = Join-Path $Fixture "new Worker & runtime"
    $AcceptLicenses = $false
    $script:FixtureExit = 2
    $script:ObservedArgs = @()
    $script:ObservedHome = $null
    function Invoke-LicenseFixture {
        Assert-True ($args[0] -eq "-jar" -and $args[1] -eq (Join-Path $App "app/gromozeka-worker.jar")) "License check must call the bundled Worker JAR directly"
        Assert-True ($env:GROMOZEKA_INSTALLATION_HOME -eq $App) "License check must inspect the selected installation, not the previous one"
        $script:ObservedArgs = @($args | Select-Object -Skip 2)
        $script:ObservedHome = $env:GROMOZEKA_HOME
        $global:LASTEXITCODE = $script:FixtureExit
    }
    function Join-Path([string]$Path, [string]$ChildPath) {
        if ($ChildPath -eq "runtime/java/bin/java.exe") { return "Invoke-LicenseFixture" }
        Microsoft.PowerShell.Management\Join-Path $Path $ChildPath
    }

    $Continued = $false
    $Rejected = $false
    try {
        Confirm-WorkerLicenses $App
        $Continued = $true
    } catch {
        $Rejected = $true
        Assert-True ($_.Exception.Message.Contains("service was not stopped or replaced")) "Missing refusal diagnostic"
    }
    Assert-True ($Rejected -and -not $Continued) "Refusal must stop installer continuation"
    Assert-True ($script:ObservedHome -eq $ServiceHome) "Acceptance must target the service home"
    Assert-True ($env:GROMOZEKA_HOME -eq $UserHome) "Caller environment was not restored on failure"
    Assert-True ($env:GROMOZEKA_INSTALLATION_HOME -eq $CallerInstallation) "Installation environment was not restored on failure"
    Assert-True (($script:ObservedArgs -join "|") -eq "accept-licenses") "Unattended setup must not implicitly accept"

    $UserAcceptance = Join-Path $UserHome "licenses/windows-python"
    New-Item -ItemType Directory -Force $UserAcceptance | Out-Null
    $script:FixtureExit = 0
    Confirm-WorkerLicenses $App
    Assert-True (($script:ObservedArgs -join "|") -eq "accept-licenses|--from|$UserAcceptance") "Existing agreement must be validated by the same Worker code"
    Assert-True ($env:GROMOZEKA_HOME -eq $UserHome) "Caller environment was not restored on success"
    Assert-True ($env:GROMOZEKA_INSTALLATION_HOME -eq $CallerInstallation) "Installation environment was not restored on success"

    $AcceptLicenses = $true
    Confirm-WorkerLicenses $App
    Assert-True (($script:ObservedArgs -join "|") -eq "accept-licenses|--yes|--from|$UserAcceptance") "Explicit installer acceptance was not forwarded"

    $AcceptLicenses = $false
    $env:GROMOZEKA_HOME = $ServiceHome
    New-Item -ItemType Directory -Force (Join-Path $ServiceHome "licenses/windows-python") | Out-Null
    Confirm-WorkerLicenses $App
    Assert-True (($script:ObservedArgs -join "|") -eq "accept-licenses") "Service updates must not import their own directory or imply acceptance"

    $Source = [IO.File]::ReadAllText($Installer)
    $Confirmation = $Source.IndexOf('Confirm-WorkerLicenses $SourceHome')
    $CopyConfig = $Source.IndexOf('Copy-Item -LiteralPath $SourceConfig -Destination $ServiceConfig')
    $Stop = $Source.IndexOf('    Stop-InstalledWorker', $Confirmation)
    Assert-True ($Confirmation -ge 0 -and $Confirmation -lt $CopyConfig -and $Confirmation -lt $Stop) "Agreement must precede configuration import and stopping the old service"

    Remove-Item Function:\Join-Path
    $Package = Join-Path $Fixture "wrapper"
    $Bin = Join-Path $Package "bin"
    New-Item -ItemType Directory -Force $Bin | Out-Null
    Copy-Item (Join-Path $Root "deploy/distribution/gromozeka-worker.ps1") $Bin
    $Stub = Join-Path $Bin "gromozeka-worker-service.ps1"
    [IO.File]::WriteAllText($Stub, @'
param([string]$Command, [switch]$AcceptLicenses)
if ($Command -ne "install-service" -or -not $AcceptLicenses) { throw "Wrong service arguments" }
Write-Output "Wrapper license flag routed"
exit 0
'@)
    & (Join-Path $Bin "gromozeka-worker.ps1") install-service --accept-licenses
    Assert-True ($LASTEXITCODE -eq 0) "Worker wrapper did not route explicit acceptance"
    Write-Output "License setup contracts passed: refusal, explicit acceptance, migration, environment restoration, ordering and wrapper routing. No real licenses accepted or services changed."
} finally {
    $env:GROMOZEKA_HOME = $OriginalHome
    $env:GROMOZEKA_INSTALLATION_HOME = $OriginalInstallation
    if (Test-Path $Fixture) { Remove-Item -LiteralPath $Fixture -Recurse -Force }
}
