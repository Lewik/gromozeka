$ErrorActionPreference = "Stop"

$RuntimeManifestPath = if ($env:GROMOZEKA_RUNTIME_MANIFEST) {
    $env:GROMOZEKA_RUNTIME_MANIFEST
} else {
    Join-Path $PSScriptRoot "runtime-versions.properties"
}

if (-not (Test-Path $RuntimeManifestPath -PathType Leaf)) {
    throw "Gromozeka runtime manifest was not found: $RuntimeManifestPath"
}

$GromozekaRuntimeValues = @{}
Get-Content $RuntimeManifestPath | ForEach-Object {
    $Line = $_.Trim()
    if ($Line -and -not $Line.StartsWith("#")) {
        $Parts = $Line.Split("=", 2)
        if ($Parts.Count -ne 2) {
            throw "Invalid Gromozeka runtime manifest line: $Line"
        }
        $GromozekaRuntimeValues[$Parts[0].Trim()] = $Parts[1].Trim()
    }
}

function Get-GromozekaRuntimeCache {
    if ($env:GROMOZEKA_RUNTIME_CACHE) {
        return $env:GROMOZEKA_RUNTIME_CACHE
    }
    $HomeDirectory = if ($env:GROMOZEKA_HOME) {
        $env:GROMOZEKA_HOME
    } else {
        Join-Path $HOME ".gromozeka"
    }
    return Join-Path $HomeDirectory "runtimes"
}

function Test-GromozekaJava {
    param([string]$Candidate)
    if (-not $Candidate -or -not (Test-Path $Candidate -PathType Leaf)) {
        return $false
    }
    try {
        $VersionOutput = (& $Candidate -version 2>&1) -join "`n"
        return $VersionOutput -match 'version "([0-9]+)' -and [int]$Matches[1] -ge 21
    } catch {
        return $false
    }
}

function Test-GromozekaNode {
    param([string]$Candidate)
    if (-not $Candidate -or -not (Test-Path $Candidate -PathType Leaf)) {
        return $false
    }
    try {
        $Version = (& $Candidate --version 2>$null).Trim().TrimStart("v")
        return $Version -match '^([0-9]+)\.' -and [int]$Matches[1] -ge 20
    } catch {
        return $false
    }
}

function Install-GromozekaZipRuntime {
    param(
        [string]$Kind,
        [string]$Version,
        [string]$Url,
        [string]$Sha256,
        [string]$Executable
    )

    if (-not [Environment]::Is64BitOperatingSystem) {
        throw "Gromozeka standalone packages require Windows x64"
    }

    $Target = Join-Path (Get-GromozekaRuntimeCache) "$Kind/$Version/windows_x64"
    $TargetExecutable = Join-Path $Target $Executable
    $CompleteMarker = Join-Path $Target ".complete"
    if ((Test-Path $TargetExecutable -PathType Leaf) -and (Test-Path $CompleteMarker -PathType Leaf)) {
        return $TargetExecutable
    }

    $MutexNameBytes = [Text.Encoding]::UTF8.GetBytes("$Kind|$Version|$Target")
    $Sha256Algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $MutexNameHash = (($Sha256Algorithm.ComputeHash($MutexNameBytes) | ForEach-Object {
            $_.ToString("x2")
        }) -join "").Substring(0, 24)
    } finally {
        $Sha256Algorithm.Dispose()
    }
    $Mutex = [Threading.Mutex]::new($false, "Local\GromozekaRuntime$MutexNameHash")
    try {
        if (-not $Mutex.WaitOne([TimeSpan]::FromMinutes(2))) {
            throw "Timed out waiting for managed runtime installation: $Target"
        }
        if ((Test-Path $TargetExecutable -PathType Leaf) -and (Test-Path $CompleteMarker -PathType Leaf)) {
            return $TargetExecutable
        }

        $Parent = Split-Path $Target -Parent
        New-Item -ItemType Directory -Force $Parent | Out-Null
        $Temporary = Join-Path $Parent ".$Kind-download-$([Guid]::NewGuid())"
        $Archive = Join-Path $Temporary "runtime.zip"
        $Extracted = Join-Path $Temporary "extracted"
        try {
            New-Item -ItemType Directory -Force $Extracted | Out-Null
            Write-Host "Downloading $Kind runtime $Version for windows_x64..."
            Invoke-WebRequest -Uri $Url -OutFile $Archive -UseBasicParsing
            $ActualSha256 = (Get-FileHash $Archive -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($ActualSha256 -ne $Sha256.ToLowerInvariant()) {
                throw "Runtime archive checksum mismatch: expected $Sha256, got $ActualSha256"
            }
            Expand-Archive -Path $Archive -DestinationPath $Extracted
            $Roots = @(Get-ChildItem $Extracted -Directory)
            if ($Roots.Count -ne 1) {
                throw "Downloaded $Kind runtime has an unexpected archive layout"
            }
            $ExtractedExecutable = Join-Path $Roots[0].FullName $Executable
            if (-not (Test-Path $ExtractedExecutable -PathType Leaf)) {
                throw "Downloaded $Kind runtime does not contain $Executable"
            }
            New-Item -ItemType File -Force (Join-Path $Roots[0].FullName ".complete") | Out-Null
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Target
            Move-Item $Roots[0].FullName $Target
        } finally {
            Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $Temporary
        }
        return $TargetExecutable
    } finally {
        try {
            $Mutex.ReleaseMutex()
        } catch [ApplicationException] {
        }
        $Mutex.Dispose()
    }
}

function Resolve-GromozekaJava {
    if ($env:GROMOZEKA_JAVA_EXECUTABLE) {
        if (-not (Test-GromozekaJava $env:GROMOZEKA_JAVA_EXECUTABLE)) {
            throw "GROMOZEKA_JAVA_EXECUTABLE must point to Java 21 or newer"
        }
        return $env:GROMOZEKA_JAVA_EXECUTABLE
    }

    $Candidates = @()
    if ($env:GROMOZEKA_JAVA_HOME) {
        $Candidates += Join-Path $env:GROMOZEKA_JAVA_HOME "bin/java.exe"
    }
    if ($env:JAVA_HOME) {
        $Candidates += Join-Path $env:JAVA_HOME "bin/java.exe"
    }
    $SystemJava = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($SystemJava) {
        $Candidates += $SystemJava.Source
    }
    foreach ($Candidate in $Candidates) {
        if (Test-GromozekaJava $Candidate) {
            return $Candidate
        }
    }

    return Install-GromozekaZipRuntime `
        -Kind "java" `
        -Version $GromozekaRuntimeValues["GROMOZEKA_JAVA_CACHE_VERSION"] `
        -Url $GromozekaRuntimeValues["GROMOZEKA_JAVA_WINDOWS_X64_URL"] `
        -Sha256 $GromozekaRuntimeValues["GROMOZEKA_JAVA_WINDOWS_X64_SHA256"] `
        -Executable "bin/java.exe"
}

function Resolve-GromozekaNode {
    if ($env:GROMOZEKA_NODE_EXECUTABLE) {
        if (-not (Test-GromozekaNode $env:GROMOZEKA_NODE_EXECUTABLE)) {
            throw "GROMOZEKA_NODE_EXECUTABLE must point to Node.js 20 or newer"
        }
        return $env:GROMOZEKA_NODE_EXECUTABLE
    }

    $Candidates = @()
    if ($env:GROMOZEKA_NODE_HOME) {
        $Candidates += Join-Path $env:GROMOZEKA_NODE_HOME "node.exe"
    }
    $SystemNode = Get-Command node.exe -ErrorAction SilentlyContinue
    if ($SystemNode) {
        $Candidates += $SystemNode.Source
    }
    foreach ($Candidate in $Candidates) {
        if (Test-GromozekaNode $Candidate) {
            return $Candidate
        }
    }

    return Install-GromozekaZipRuntime `
        -Kind "node" `
        -Version $GromozekaRuntimeValues["GROMOZEKA_NODE_CACHE_VERSION"] `
        -Url $GromozekaRuntimeValues["GROMOZEKA_NODE_WINDOWS_X64_URL"] `
        -Sha256 $GromozekaRuntimeValues["GROMOZEKA_NODE_WINDOWS_X64_SHA256"] `
        -Executable "node.exe"
}
