param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("server", "worker")]
    [string]$Component,

    [Parameter(Mandatory = $true)]
    [ValidateSet("windows")]
    [string]$Platform,

    [Parameter(Mandatory = $true)]
    [ValidateSet("x64")]
    [string]$Architecture,

    [Parameter(Mandatory = $true)]
    [string]$Destination
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$Manifest = Join-Path $RepositoryRoot "deploy/distribution/bundled-runtime-versions.properties"
$CacheRoot = if ($env:GROMOZEKA_BUILD_RUNTIME_CACHE) {
    $env:GROMOZEKA_BUILD_RUNTIME_CACHE
} else {
    Join-Path $RepositoryRoot "build/bundled-runtime-cache"
}
$Properties = @{}

foreach ($Line in [System.IO.File]::ReadAllLines($Manifest)) {
    if ([string]::IsNullOrWhiteSpace($Line) -or $Line.TrimStart().StartsWith("#")) {
        continue
    }
    $Separator = $Line.IndexOf("=")
    if ($Separator -lt 1) {
        throw "Invalid bundled runtime manifest line: $Line"
    }
    $Properties[$Line.Substring(0, $Separator)] = $Line.Substring($Separator + 1)
}

function Get-RuntimeProperty {
    param([string]$Name)

    if (-not $Properties.ContainsKey($Name)) {
        throw "Bundled runtime manifest property is missing: $Name"
    }
    return $Properties[$Name]
}

function Get-Sha256 {
    param([string]$Path)

    $Stream = [System.IO.File]::OpenRead($Path)
    $Sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($Sha256.ComputeHash($Stream))).Replace("-", "").ToLowerInvariant()
    } finally {
        $Sha256.Dispose()
        $Stream.Dispose()
    }
}

function Assert-RuntimeContents {
    param(
        [string]$Kind,
        [string]$Root
    )

    if ($Kind -eq "java") {
        if (-not (Test-Path (Join-Path $Root "bin/java.exe") -PathType Leaf)) {
            throw "Bundled Java runtime does not contain bin/java.exe"
        }
        if (-not (Test-Path (Join-Path $Root "legal") -PathType Container)) {
            throw "Bundled Java runtime does not contain its legal notices"
        }
        return
    }
    if (-not (Test-Path (Join-Path $Root "node.exe") -PathType Leaf)) {
        throw "Bundled Node.js runtime does not contain node.exe"
    }
    if (-not (Test-Path (Join-Path $Root "LICENSE") -PathType Leaf)) {
        throw "Bundled Node.js runtime does not contain LICENSE"
    }
}

function Prepare-Runtime {
    param([string]$Kind)

    $UppercaseKind = $Kind.ToUpperInvariant()
    $Version = Get-RuntimeProperty "GROMOZEKA_${UppercaseKind}_VERSION"
    $Url = Get-RuntimeProperty "GROMOZEKA_${UppercaseKind}_WINDOWS_X64_URL"
    $ExpectedSha256 = Get-RuntimeProperty "GROMOZEKA_${UppercaseKind}_WINDOWS_X64_SHA256"
    $SafeVersion = $Version -replace "[^A-Za-z0-9._-]", "_"
    $Cache = Join-Path $CacheRoot "v2/$Kind/windows-x64/$SafeVersion-$($ExpectedSha256.Substring(0, 16))"
    $Complete = Join-Path $Cache ".complete"

    if (-not (Test-Path $Complete -PathType Leaf)) {
        New-Item -ItemType Directory -Force $CacheRoot | Out-Null
        $Temporary = Join-Path $CacheRoot ".$Kind-$([Guid]::NewGuid().ToString('N'))"
        $Archive = Join-Path $Temporary "runtime.zip"
        $Unpacked = Join-Path $Temporary "unpacked"
        $Extracted = Join-Path $Temporary "extracted"
        try {
            New-Item -ItemType Directory -Force $Unpacked, $Extracted | Out-Null
            Write-Host "Bundling $Kind $Version for windows-x64..."
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $Archive
            $ActualSha256 = Get-Sha256 $Archive
            if ($ActualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
                throw "Bundled runtime checksum mismatch: expected $ExpectedSha256, got $ActualSha256"
            }
            Expand-Archive -Path $Archive -DestinationPath $Unpacked
            $SourceRoot = Get-ChildItem $Unpacked -Directory | Select-Object -First 1
            if ($null -eq $SourceRoot) {
                throw "Bundled runtime archive has no root directory: $Url"
            }
            Copy-Item (Join-Path $SourceRoot.FullName "*") $Extracted -Recurse -Force
            Assert-RuntimeContents $Kind $Extracted
            if ($Kind -eq "node") {
                $Minimal = Join-Path $Temporary "minimal"
                New-Item -ItemType Directory -Force $Minimal | Out-Null
                Copy-Item (Join-Path $Extracted "node.exe") $Minimal
                Copy-Item (Join-Path $Extracted "LICENSE") $Minimal
                Remove-Item $Extracted -Recurse -Force
                Move-Item $Minimal $Extracted
            }
            New-Item -ItemType Directory -Force (Split-Path $Cache -Parent) | Out-Null
            if (Test-Path $Cache) {
                Remove-Item $Cache -Recurse -Force
            }
            New-Item -ItemType File (Join-Path $Extracted ".complete") | Out-Null
            Move-Item $Extracted $Cache
        } finally {
            if (Test-Path $Temporary) {
                Remove-Item $Temporary -Recurse -Force
            }
        }
    }

    Assert-RuntimeContents $Kind $Cache
    $RuntimeDestination = Join-Path $Destination $Kind
    New-Item -ItemType Directory -Force $RuntimeDestination | Out-Null
    Copy-Item (Join-Path $Cache "*") $RuntimeDestination -Recurse -Force
    $DestinationComplete = Join-Path $RuntimeDestination ".complete"
    if (Test-Path $DestinationComplete) {
        Remove-Item $DestinationComplete -Force
    }
}

if (Test-Path $Destination) {
    Remove-Item $Destination -Recurse -Force
}
New-Item -ItemType Directory -Force $Destination | Out-Null

$RuntimeKinds = if ($Component -eq "server") { @("java") } else { @("java", "node") }
foreach ($RuntimeKind in $RuntimeKinds) {
    Prepare-Runtime $RuntimeKind
}
