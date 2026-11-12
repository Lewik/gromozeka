$ErrorActionPreference = 'Stop'
$Source = Join-Path $PSScriptRoot '../application-updates/src/jvmMain/resources/updates/install.ps1'
$Root = Join-Path $env:TEMP ('gromozeka-update-recovery-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $Root | Out-Null
$Registrations = @()
try {
    $Executable = Join-Path $Root 'fixture.exe'
    Add-Type -TypeDefinition 'using System; public class RecoveryFixture { public static void Main() {} }' -OutputAssembly $Executable -OutputType ConsoleApplication
    foreach ($Boundary in @('old-moved', 'new-moved', 'installed', 'rollback-moved')) {
        $Case = Join-Path $Root $Boundary
        $Installation = Join-Path $Case 'Gromozeka'
        $PackageRoot = Join-Path $Case 'package'
        New-Item -ItemType Directory -Path $Installation, (Join-Path $PackageRoot 'runtime') -Force | Out-Null
        Copy-Item $Executable (Join-Path $Installation 'Gromozeka.exe')
        Copy-Item $Executable (Join-Path $PackageRoot 'Gromozeka.exe')
        Set-Content (Join-Path $Installation 'old.txt') 'old'
        Set-Content (Join-Path $PackageRoot 'new.txt') 'new'
        Set-Content (Join-Path $PackageRoot 'runtime/fixture.txt') 'runtime'
        $Archive = Join-Path $Case 'client.zip'
        Compress-Archive -Path (Join-Path $PackageRoot '*') -DestinationPath $Archive
        $Id = 'recovery-' + [Guid]::NewGuid().ToString('N')
        $Registrations += $Id
        $Script = Join-Path $Case 'install.ps1'
        $Text = [IO.File]::ReadAllText($Source)
        $Kill = 'if (-not (Test-Path (Join-Path $Cache "killed"))) { Set-Content (Join-Path $Cache "killed") "killed"; Stop-Process -Id $PID -Force }'
        $Anchor = switch ($Boundary) {
            'old-moved' { 'Move-Item -LiteralPath $Installation -Destination $Previous' }
            'new-moved' { 'Move-Item -LiteralPath $Staging -Destination $Installation' }
            'installed' { "Write-Phase 'installed'" }
            'rollback-moved' { 'Move-Item -LiteralPath $Previous -Destination $Installation' }
        }
        if ($Boundary -eq 'rollback-moved') {
            $Text = $Text.Replace('function Start-Installed {', 'function Start-Installed { if (Test-Path (Join-Path $Installation "new.txt")) { throw "Fixture launch failure" }')
        }
        if (-not $Text.Contains($Anchor)) { throw "Missing crash injection boundary: $Boundary" }
        [IO.File]::WriteAllText($Script, $Text.Replace($Anchor, "$Anchor`n$Kill"))
        $Old = Start-Process "$env:SystemRoot\System32\cmd.exe" -ArgumentList '/c exit 0' -PassThru -Wait
        $Result = Join-Path $Case 'result.json'
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $Script $Archive $Installation CLIENT $Id $Old.Id $Result
        if ($LASTEXITCODE -eq 0 -or -not (Test-Path (Join-Path $Case 'killed'))) { throw "Updater was not killed at $Boundary" }
        $StartupLink = Join-Path ([Environment]::GetFolderPath('Startup')) "Gromozeka update recovery $Id.lnk"
        if (-not (Test-Path $StartupLink)) { throw 'Login recovery shortcut is missing' }
        $Startup = (New-Object -ComObject WScript.Shell).CreateShortcut($StartupLink)
        if (-not $Startup.Arguments.Contains($Script)) { throw 'Login recovery does not point outside installation' }
        if ($Startup.Arguments.Length -le 260) { throw 'Fixture must exercise recovery commands exceeding the Run registry limit' }
        $Link = Join-Path ([Environment]::GetFolderPath('Programs')) "Gromozeka update recovery $Id.lnk"
        if (-not (Test-Path $Link)) { throw 'Manual recovery shortcut is missing' }
        if ($Boundary -eq 'old-moved' -and (Test-Path $Installation)) { throw 'Fixture did not expose the missing installation gap' }
        $Shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($Link)
        $Recovered = Start-Process -FilePath $Shortcut.TargetPath -ArgumentList $Shortcut.Arguments -PassThru -Wait
        if ($Recovered.ExitCode -ne 0) { throw "Recovery failed at $Boundary" }
        $RolledBack = $Boundary -notin @('new-moved', 'installed')
        if ((Test-Path (Join-Path $Installation 'old.txt')) -ne $RolledBack) { throw "Wrong recovered version at $Boundary" }
        if ((Test-Path (Join-Path $Installation 'new.txt')) -eq $RolledBack) { throw "Mixed installation at $Boundary" }
        if ($RolledBack -and ((Get-Content $Result -Raw | ConvertFrom-Json).phase -ne 'FAILED')) { throw 'Rollback was not reported' }
        if (Test-Path $StartupLink) { throw 'Login recovery was not removed' }
        if (Test-Path $Link) { throw 'Recovery shortcut was not removed' }
        Write-Output "Recovered abrupt updater termination at $Boundary"
    }
} finally {
    foreach ($Id in $Registrations) {
        Remove-Item -LiteralPath (Join-Path ([Environment]::GetFolderPath('Startup')) "Gromozeka update recovery $Id.lnk") -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath (Join-Path ([Environment]::GetFolderPath('Programs')) "Gromozeka update recovery $Id.lnk") -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $Root -Recurse -Force
}
