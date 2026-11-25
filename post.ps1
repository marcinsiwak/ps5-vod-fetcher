# Post reviewed clips to Instagram as Reels.
# Review first: delete the .mp4s you don't want and edit title/caption/hashtags in clips.json if you like. Then:
#   ./post.ps1 "clips\my gameplay"
#   ./post.ps1 "clips\my gameplay" -Yes      # skip the confirmation
#   ./post.ps1 -Check                        # just verify the Instagram token
# Needs IG_ACCESS_TOKEN in vod.env. Already-posted clips (posted.json) are never posted twice.
param(
    [Parameter(Position = 0)][string]$Folder,
    [switch]$Yes,
    [switch]$Check,
    [switch]$Build
)
$ErrorActionPreference = "Stop"
$PSNativeCommandUseErrorActionPreference = $true
$image = "ps5-vod-fetcher"
$state = New-Item -ItemType Directory -Force -Path "$PSScriptRoot\state"   # keeps the auto-refreshed token

if ($Build) { docker build -t $image $PSScriptRoot }
$run = @("run", "--rm", "--env-file", "$PSScriptRoot\vod.env", "-v", "$($state.FullName):/data")

if ($Check) { docker @run $image ig-check; return }
if (-not $Folder) { throw "Usage: ./post.ps1 <clips folder>   (or -Check)" }

$dir = Get-Item -LiteralPath $Folder
$clips = Get-Content -Raw "$dir\clips.json" | ConvertFrom-Json
$posted = if (Test-Path "$dir\posted.json") { Get-Content -Raw "$dir\posted.json" | ConvertFrom-Json } else { $null }
$todo = $clips | Sort-Object -Property score -Descending | Where-Object {
    (Test-Path (Join-Path $dir $_.file)) -and -not ($posted -and $posted.($_.file).mediaId)
}
if (-not $todo) { Write-Host "Nothing to post in $($dir.Name) (all posted, or no .mp4 left)."; return }

Write-Host "`nWill post $(@($todo).Count) Reel(s) from '$($dir.Name)':`n"
foreach ($c in $todo) {
    Write-Host "  $($c.file)  [$($c.category), $($c.score)/10]" -ForegroundColor Cyan
    Write-Host "    $($c.caption)"
    Write-Host "    $(($c.hashtags | ForEach-Object { '#' + $_ }) -join ' ')`n"
}
if (-not $Yes) {
    $a = Read-Host "These go PUBLIC on Instagram. Post them? (y/N)"
    if ($a -notmatch '^(y|yes|t|tak)$') { Write-Host "Cancelled."; return }
}

docker @run -v "$($dir.FullName):/data/post/$($dir.Name)" $image post "/data/post/$($dir.Name)" --all
