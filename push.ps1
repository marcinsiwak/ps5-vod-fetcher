# Build the multi-arch image (PC + Raspberry Pi) and push it to Docker Hub.
#   ./push.ps1 -User <dockerhub-user>            # or set $env:DOCKERHUB_USERNAME
#   ./push.ps1 -User <dockerhub-user> -Deploy    # also tell the Pi to pull & restart (needs gh CLI)
param(
    [string]$User = $env:DOCKERHUB_USERNAME,
    [string]$Tag = "latest",
    [switch]$Deploy
)
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true

if (-not $User) { throw "Pass -User <dockerhub-user> or set `$env:DOCKERHUB_USERNAME" }
$image = "$User/ps5-vod-fetcher"
$sha = (git rev-parse --short HEAD).Trim()
if (git status --porcelain) { $sha = "$sha-dirty" }

Write-Host "Building $image`:$Tag and $image`:$sha for linux/amd64 + linux/arm64..."
docker buildx build `
    --platform linux/amd64,linux/arm64 `
    --pull `
    -t "$image`:$Tag" `
    -t "$image`:$sha" `
    --push .

Write-Host "Pushed $image`:$Tag and $image`:$sha"

if ($Deploy) {
    gh workflow run deploy.yml -f image=$image
    Write-Host "Triggered Pi deploy (gh run watch to follow it)"
}
