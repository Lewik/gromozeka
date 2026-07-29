param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("server", "worker")]
    [string]$Component,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$PackageName = "gromozeka-$Component-windows-x64"
$StagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
$PackageRoot = Join-Path $StagingRoot $PackageName
$RuntimeModules = "java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs"

if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows
) -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne "X64") {
    throw "The Windows x64 $Component package must be built on Windows x64"
}

try {
    New-Item -ItemType Directory -Force -Path `
        (Join-Path $PackageRoot "app"), `
        (Join-Path $PackageRoot "bin"), `
        (Join-Path $PackageRoot "config"), `
        $OutputDirectory | Out-Null

    & (Join-Path $env:JAVA_HOME "bin/jlink.exe") `
        --add-modules $RuntimeModules `
        --bind-services `
        --strip-debug `
        --no-header-files `
        --no-man-pages `
        --compress=zip-6 `
        --output (Join-Path $PackageRoot "runtime")
    if ($LASTEXITCODE -ne 0) {
        throw "jlink failed with exit code $LASTEXITCODE"
    }

    Copy-Item `
        (Join-Path $RepositoryRoot "$Component/build/libs/gromozeka-$Component.jar") `
        (Join-Path $PackageRoot "app")
    Copy-Item (Join-Path $RepositoryRoot "LICENSE") $PackageRoot

    if ($Component -eq "server") {
        $WebSource = Join-Path $RepositoryRoot "presentation/build/dist/wasmJs/productionExecutable"
        if (-not (Test-Path (Join-Path $WebSource "index.html"))) {
            throw "Production Web client was not built: $WebSource"
        }

        Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/server.yaml.example") (Join-Path $PackageRoot "config")
        Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/SERVER_README.md") (Join-Path $PackageRoot "README.md")
        Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/gromozeka-server.cmd") (Join-Path $PackageRoot "bin")
        $WebDestination = Join-Path $PackageRoot "web"
        New-Item -ItemType Directory -Force -Path $WebDestination | Out-Null
        Get-ChildItem $WebSource | Copy-Item -Destination $WebDestination -Recurse

        Get-ChildItem $WebDestination -Recurse -File |
            Where-Object { $_.Extension -eq ".wasm" -or $_.Name -eq "gromozeka.js" } |
            ForEach-Object {
                $InputStream = [System.IO.File]::OpenRead($_.FullName)
                $OutputStream = [System.IO.File]::Create("$($_.FullName).gz")
                $GzipStream = [System.IO.Compression.GZipStream]::new(
                    $OutputStream,
                    [System.IO.Compression.CompressionLevel]::SmallestSize
                )
                try {
                    $InputStream.CopyTo($GzipStream)
                }
                finally {
                    $GzipStream.Dispose()
                    $InputStream.Dispose()
                }
            }
    }
    else {
        Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/worker.yaml.example") (Join-Path $PackageRoot "config")
        Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/WORKER_README.md") (Join-Path $PackageRoot "README.md")
        Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/gromozeka-worker.cmd") (Join-Path $PackageRoot "bin")
    }

    Compress-Archive `
        -Path $PackageRoot `
        -DestinationPath (Join-Path $OutputDirectory "$PackageName.zip") `
        -CompressionLevel Optimal
}
finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $StagingRoot
}
