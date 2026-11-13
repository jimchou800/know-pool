param(
    [Parameter(Mandatory = $true)][string]$JdkDirectory,
    [string]$ApplicationJar,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $ApplicationJar) { $ApplicationJar = Join-Path $projectRoot 'server/target/server-0.1.0-SNAPSHOT.jar' }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'target/releases' }
$jdkPath = (Resolve-Path -LiteralPath $JdkDirectory).Path
$jarPath = (Resolve-Path -LiteralPath $ApplicationJar).Path
$javaPath = Join-Path $jdkPath 'bin/java.exe'
$linkPath = Join-Path $jdkPath 'bin/jlink.exe'
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf) -or -not (Test-Path -LiteralPath $linkPath -PathType Leaf)) {
    throw '请选择包含 java.exe 和 jlink.exe 的 Windows x64 Java 21 开发工具目录。'
}
$runtimeInfo = Get-Content -LiteralPath (Join-Path $jdkPath 'release') -Raw
if ($runtimeInfo -notmatch '(?m)^JAVA_VERSION="21(?:\.|\+|")' -or $runtimeInfo -notmatch '(?m)^OS_ARCH="(?:amd64|x86_64)"' -or $runtimeInfo -notmatch '(?m)^OS_NAME="Windows"') {
    throw '发行运行时必须为 Windows x64 Java 21。'
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$releaseRoot = (Resolve-Path -LiteralPath $OutputDirectory).Path
$releaseId = 'know-pool-windows-x64-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$stagePath = Join-Path $releaseRoot $releaseId
New-Item -ItemType Directory -Path $stagePath | Out-Null
$runtimePath = Join-Path $stagePath 'runtime'
& $linkPath --module-path (Join-Path $jdkPath 'jmods') --add-modules java.se,jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.zipfs,jdk.management,jdk.localedata --strip-debug --no-header-files --no-man-pages --output $runtimePath
if ($LASTEXITCODE -ne 0) { throw '装配 Java 运行时失败，保留本次目录供检查。' }
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $stagePath 'application.jar')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start.ps1') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start.cmd') -Destination $stagePath
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/release.md') -Destination (Join-Path $stagePath '使用说明.md')
if (Test-Path -LiteralPath (Join-Path $jdkPath 'legal')) {
    Copy-Item -LiteralPath (Join-Path $jdkPath 'legal') -Destination (Join-Path $stagePath 'runtime-licenses') -Recurse
}
$manifest = Get-ChildItem -LiteralPath $stagePath -File -Recurse | ForEach-Object {
    [ordered]@{ path = $_.FullName.Substring($stagePath.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
ConvertTo-Json -InputObject @($manifest) -Depth 3 | Set-Content -LiteralPath (Join-Path $stagePath 'manifest.json') -Encoding UTF8
$archivePath = Join-Path $releaseRoot ($releaseId + '.zip')
Compress-Archive -LiteralPath $stagePath -DestinationPath $archivePath -CompressionLevel Optimal
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath ($archivePath + '.sha256') -Value ($archiveHash + '  ' + [IO.Path]::GetFileName($archivePath)) -Encoding ASCII
Write-Output ('已装配发行包：' + $archivePath)
Write-Output '装配不代表已通过完整契约或目标环境验收。'
