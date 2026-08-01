param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$PackageName = "gromozeka-worker-windows-x64"
$StagingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
$PackageRoot = Join-Path $StagingRoot $PackageName
$RuntimeModules = "java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs"

if (-not [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
    [System.Runtime.InteropServices.OSPlatform]::Windows
) -or [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne "X64") {
    throw "The Windows x64 Worker package must be built on Windows x64"
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

    Copy-Item (Join-Path $RepositoryRoot "worker/build/libs/gromozeka-worker.jar") (Join-Path $PackageRoot "app")
    Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/worker.yaml.example") (Join-Path $PackageRoot "config")
    Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/WORKER_README.md") (Join-Path $PackageRoot "README.md")
    Copy-Item (Join-Path $RepositoryRoot "deploy/distribution/gromozeka-worker.cmd") (Join-Path $PackageRoot "bin")
    Copy-Item (Join-Path $RepositoryRoot "LICENSE") $PackageRoot

    Compress-Archive `
        -Path $PackageRoot `
        -DestinationPath (Join-Path $OutputDirectory "$PackageName.zip") `
        -CompressionLevel Optimal
}
finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $StagingRoot
}
