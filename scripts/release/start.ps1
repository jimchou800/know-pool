param(
    [string]$DataDirectory = (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'KnowPool/data'),
    [ValidateRange(1, 65535)][int]$Port = 8080
)

$ErrorActionPreference = 'Stop'
$javaPath = Join-Path $PSScriptRoot 'runtime/bin/java.exe'
$jarPath = Join-Path $PSScriptRoot 'application.jar'
if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf) -or -not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    throw '发行包不完整，请重新解压整个压缩包。'
}
$dataPath = [IO.Path]::GetFullPath($DataDirectory)
if ($dataPath.StartsWith('\\')) { throw '数据目录必须位于本机磁盘。' }
New-Item -ItemType Directory -Path $dataPath -Force | Out-Null
$logPath = Join-Path $dataPath 'logs/application.log'
Write-Output ('正在启动知池：http://127.0.0.1:' + $Port + '/')
Write-Output ('数据目录：' + $dataPath)
Write-Output '请保留当前窗口，按 Ctrl+C 关闭。升级前请先备份数据。'
& $javaPath -jar $jarPath '--server.address=127.0.0.1' "--server.port=$Port" "--know-pool.data-directory=$dataPath" "--logging.file.name=$logPath"
exit $LASTEXITCODE
