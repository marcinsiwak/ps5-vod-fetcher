# Find highlights in a VOD and cut them into 16:9 clips (runs yt-dlp + ffmpeg inside the Docker image).
#   ./highlights.ps1 --channel                                 # list recent videos on your channel (vod.env login)
#   ./highlights.ps1 --channel 1 --list                        # candidates for the newest one
#   ./highlights.ps1 --channel 1                               # cut them
#   ./highlights.ps1 https://youtu.be/VIDEO_ID --pick 1,3,4    # any URL; cut just those (analysis is cached)
#   ./highlights.ps1 https://youtu.be/VIDEO_ID --manual "1:23:10-1:23:45" --top 5 --length 45
#   ./highlights.ps1 .\vods\my-stream.mp4                      # local file works too
# Clips land in .\clips\<video-id>\
$ErrorActionPreference = "Stop"
$image = if ($env:CLIP_IMAGE) { $env:CLIP_IMAGE } else { "ps5-vod-fetcher:local" }

docker image inspect $image *> $null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Building $image..."
    docker build -t $image $PSScriptRoot
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$clips = Join-Path $PSScriptRoot "clips"
$cache = Join-Path $PSScriptRoot ".clipcache"
New-Item -ItemType Directory -Force $clips, $cache | Out-Null

$opts = @("-v", "${PSScriptRoot}\tools:/tools:ro", "-v", "${clips}:/clips", "-v", "${cache}:/cache")
$envFile = Join-Path $PSScriptRoot "vod.env"
if (Test-Path $envFile) { $opts += @("--env-file", $envFile) }

# a local video file among the args -> mount its folder and pass the in-container path
$pyArgs = @(foreach ($a in $args) {
    if ($a -notlike "-*" -and (Test-Path -LiteralPath $a -PathType Leaf)) {
        $file = Get-Item -LiteralPath $a
        $opts += @("-v", "$($file.DirectoryName):/input:ro")
        "/input/$($file.Name)"
    } else { "$a" }
})

docker run --rm @opts --entrypoint python3 $image /tools/highlights.py @pyArgs
exit $LASTEXITCODE
