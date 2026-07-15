param(
    [string]$Target = "diamond@192.168.0.92",
    [string]$RemoteDir = "~/afk-farm-manager"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")

ssh $Target "mkdir -p $RemoteDir"
rsync -av --delete `
    --exclude node_modules `
    --exclude data `
    "$ProjectRoot/" `
    "${Target}:${RemoteDir}/"

Write-Host "Uploaded to ${Target}:${RemoteDir}"
