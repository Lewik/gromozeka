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
    Stop-Service $ServiceName
    (Get-Service $ServiceName).WaitForStatus("Stopped", [TimeSpan]::FromSeconds(60))
    foreach ($HelperPid in $Helpers) {
        if (Get-Process -Id $HelperPid -ErrorAction SilentlyContinue) { throw "Desktop helper survived service stop" }
    }
    Write-Output "Windows service startup, desktop-helper lifecycle, and standard-user permissions verified"
} finally {
    if (Get-Service $ServiceName -ErrorAction SilentlyContinue) {
        Stop-Service $ServiceName -ErrorAction SilentlyContinue
        & $Sc delete $ServiceName | Out-Null
    }
    if (Get-LocalUser $TestUser -ErrorAction SilentlyContinue) { Remove-LocalUser $TestUser }
    if (Test-Path $SmokeRoot) { Remove-Item $SmokeRoot -Recurse -Force }
}
