$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ServiceName = "GromozekaWorkerSmoke"
$TestUser = "GrzWorkerSmoke"
$SmokeRoot = Join-Path $env:SystemDrive "grz-worker-smoke"
$Java = (Get-Command java.exe).Source
$Jar = (Get-Command jar.exe).Source
$Sc = Join-Path $env:SystemRoot "System32\sc.exe"
$Result = Join-Path $SmokeRoot "result.txt"

if ((Get-Service $ServiceName -ErrorAction SilentlyContinue) -or (Test-Path $SmokeRoot)) {
    throw "Smoke test resources already exist; refusing to reuse them"
}
New-Item -ItemType Directory -Path $SmokeRoot | Out-Null
$ClassPath = "$SmokeRoot\BOOT-INF\classes;$SmokeRoot\BOOT-INF\lib\*;$(Join-Path $ProjectRoot 'worker\build\classes\kotlin\test')"
$Entry = "com.gromozeka.worker.WindowsWorkerServiceSmoke"
try {
    $UpdateRoot = Join-Path $SmokeRoot 'updates'
    New-Item -ItemType Directory -Path $UpdateRoot | Out-Null
    $UpdateScript = Join-Path $ProjectRoot 'application-updates\src\jvmMain\resources\updates\install.ps1'
    $Tokens = $null
    $Errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($UpdateScript, [ref]$Tokens, [ref]$Errors) | Out-Null
    if ($Errors.Count) { throw ($Errors | Out-String) }
    $Client = Join-Path $UpdateRoot 'client'
    $ClientSource = Join-Path $UpdateRoot 'client-source'
    New-Item -ItemType Directory -Path $Client, (Join-Path $ClientSource 'runtime') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $Client 'old.txt') -Value 'previous installation'
    $env:GRZ_UPDATE_SMOKE_RESULT = Join-Path $UpdateRoot 'client-started.txt'
    Add-Type -TypeDefinition 'using System; using System.IO; public class UpdateFixture { public static void Main() { File.WriteAllText(Environment.GetEnvironmentVariable("GRZ_UPDATE_SMOKE_RESULT"), "started"); } }' -OutputAssembly (Join-Path $ClientSource 'Gromozeka.exe') -OutputType ConsoleApplication
    Set-Content -LiteralPath (Join-Path $ClientSource 'runtime\fixture.txt') -Value 'new runtime'
    $ClientZip = Join-Path $UpdateRoot 'client.zip'
    Compress-Archive -Path (Join-Path $ClientSource '*') -DestinationPath $ClientZip
    $Old = Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList '/c exit 0' -PassThru -Wait
    $UpdateResult = Join-Path $UpdateRoot 'result.json'
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $UpdateScript $ClientZip $Client CLIENT client-smoke $Old.Id $UpdateResult
    if ($LASTEXITCODE -ne 0 -or (Test-Path $UpdateResult) -or (Test-Path (Join-Path $Client 'old.txt'))) { throw 'Portable client replacement failed' }
    if (-not (Test-Path (Join-Path "$Client.previous-client-smoke" 'old.txt'))) { throw 'Previous client was removed before version confirmation' }
    $Deadline = (Get-Date).AddSeconds(10)
    while (-not (Test-Path $env:GRZ_UPDATE_SMOKE_RESULT) -and (Get-Date) -lt $Deadline) { Start-Sleep -Milliseconds 100 }
    if (-not (Test-Path $env:GRZ_UPDATE_SMOKE_RESULT)) { throw 'Updated client did not start' }

    $WorkerSource = Join-Path $UpdateRoot 'gromozeka-worker-windows-x64'
    New-Item -ItemType Directory -Path (Join-Path $WorkerSource 'bin') | Out-Null
    $env:GRZ_UPDATE_SERVICE_RESULT = Join-Path $UpdateRoot 'service-updated.txt'
    Set-Content -LiteralPath (Join-Path $WorkerSource 'bin\gromozeka-worker-service.ps1') -Value 'if ($args[0] -ne "update-service") { exit 2 }; Set-Content -LiteralPath $env:GRZ_UPDATE_SERVICE_RESULT -Value "updated"' -Encoding UTF8
    $WorkerZip = Join-Path $UpdateRoot 'worker.zip'
    Compress-Archive -Path $WorkerSource -DestinationPath $WorkerZip
    & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $UpdateScript $WorkerZip (Join-Path $UpdateRoot 'worker') WORKER worker-smoke $Old.Id $UpdateResult
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $env:GRZ_UPDATE_SERVICE_RESULT)) { throw 'Independent Worker updater did not invoke the existing service installer' }
    Write-Output 'Independent update helpers verified portable client replacement and Worker installer handoff'

    Push-Location $SmokeRoot
    try {
        & $Jar -xf (Join-Path $ProjectRoot "worker\build\libs\gromozeka-worker.jar")
        if ($LASTEXITCODE -ne 0) { throw "Cannot unpack Worker" }
    } finally { Pop-Location }
    foreach ($Path in @("gromozeka-worker.ps1", "gromozeka-worker-service.ps1")) {
        $Tokens = $null
        $Errors = $null
        [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $ProjectRoot "deploy\distribution\$Path"), [ref]$Tokens, [ref]$Errors) | Out-Null
        if ($Errors.Count) { throw ($Errors | Out-String) }
    }
    $BinaryPath = '"' + $Java + '" -cp "' + $ClassPath + '" ' + $Entry + ' service ' + $ServiceName + ' "' + $Result + '"'
    New-Service -Name $ServiceName -BinaryPathName $BinaryPath -StartupType Manual | Out-Null
    & $Sc failure $ServiceName reset= 60 actions= restart/1000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Cannot configure service recovery" }
    Start-Service $ServiceName
    (Get-Service $ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(60))
    $Deadline = (Get-Date).AddSeconds(100)
    while (-not (Test-Path $Result)) {
        if ((Get-Date) -gt $Deadline -or (Get-Service $ServiceName).Status -eq "Stopped") {
            Get-Content (Join-Path $SmokeRoot "service-error.log") -ErrorAction SilentlyContinue
            Get-WinEvent -FilterHashtable @{LogName='System'; ProviderName='Service Control Manager'; StartTime=(Get-Date).AddMinutes(-5)} -ErrorAction SilentlyContinue | Format-List Message
            throw "Windows Worker service smoke did not complete"
        }
        Start-Sleep -Milliseconds 200
    }
    $Outcome = Get-Content $Result -Raw
    Write-Output $Outcome
    if ($Outcome -notmatch '(?m)^screenshot=fixture-verified\r?$') { throw "Interactive screenshot was not verified" }
    $WorkerPid = [int]([regex]::Match($Outcome, 'pid=(\d+)').Groups[1].Value)
    $Password = "Grz!$([Guid]::NewGuid().ToString('N'))a9"
    New-LocalUser -Name $TestUser -Password (ConvertTo-SecureString $Password -AsPlainText -Force) -AccountNeverExpires | Out-Null
    if (-not (Get-LocalGroupMember -SID "S-1-5-32-545" | Where-Object { $_.Name -like "*\$TestUser" })) {
        Add-LocalGroupMember -SID "S-1-5-32-545" -Member $TestUser
    }
    $PasswordFile = Join-Path $SmokeRoot "temporary-password.txt"
    [IO.File]::WriteAllText($PasswordFile, $Password)
    & $Java -cp $ClassPath $Entry deny $ServiceName $WorkerPid $TestUser $PasswordFile
    if ($LASTEXITCODE -ne 0) { throw "Standard-user access verification failed" }
    Remove-Item $PasswordFile
    $Helpers = @(Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $WorkerPid -and $_.Name -eq "javaw.exe" } | Select-Object -ExpandProperty ProcessId)
    Remove-Item $Result
    Stop-Process -Id $WorkerPid -Force
    Start-Sleep -Seconds 2
    foreach ($HelperPid in $Helpers) {
        if (Get-Process -Id $HelperPid -ErrorAction SilentlyContinue) { throw "Desktop helper survived a service process crash" }
    }
    $Deadline = (Get-Date).AddSeconds(100)
    while (-not (Test-Path $Result)) {
        if ((Get-Date) -gt $Deadline) {
            Get-Content (Join-Path $SmokeRoot "service-error.log") -ErrorAction SilentlyContinue
            throw "Windows service did not recover after a process crash"
        }
        Start-Sleep -Milliseconds 200
    }
    $Recovered = Get-Content $Result -Raw
    if ($Recovered -notmatch '(?m)^screenshot=fixture-verified\r?$') { throw "Screenshot capture did not recover after service restart" }
    $RecoveredPid = [int]([regex]::Match($Recovered, 'pid=(\d+)').Groups[1].Value)
    if ($RecoveredPid -eq $WorkerPid) { throw "Service did not start a new process" }
    $Helpers = @(Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $RecoveredPid -and $_.Name -eq "javaw.exe" } | Select-Object -ExpandProperty ProcessId)
    Stop-Service $ServiceName
    (Get-Service $ServiceName).WaitForStatus("Stopped", [TimeSpan]::FromSeconds(60))
    foreach ($HelperPid in $Helpers) {
        if (Get-Process -Id $HelperPid -ErrorAction SilentlyContinue) { throw "Desktop helper survived service stop" }
    }
    Write-Output "Windows service startup, crash recovery, interactive screenshot contents, desktop-helper lifecycle, and standard-user permissions verified"
} finally {
    if (Get-Service $ServiceName -ErrorAction SilentlyContinue) {
        Stop-Service $ServiceName -ErrorAction SilentlyContinue
        & $Sc delete $ServiceName | Out-Null
    }
    if (Get-LocalUser $TestUser -ErrorAction SilentlyContinue) { Remove-LocalUser $TestUser }
    if (Test-Path $SmokeRoot) { Remove-Item $SmokeRoot -Recurse -Force }
}
