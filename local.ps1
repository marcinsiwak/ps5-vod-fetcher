# Make shorts from one of your YouTube VODs, running directly on this PC (no Docker).
#   ./local.ps1                      # list your finished streams, ask which one to clip
#   ./local.ps1 -Vod 2               # download (if needed) + clip VOD number 2 from the list
#   ./local.ps1 -Vod 1 -Max 5        # render at most 5 clips (the rest go to worth_checking.md)
#   ./local.ps1 -Vod 1 -Model opus   # haiku (default) | sonnet | opus
#   ./local.ps1 -List                # only show the list
#   ./local.ps1 -Vod 2 -DownloadOnly # just download VOD number 2 to .ods (no clipping)
#   ./local.ps1 -Build               # force a rebuild of the app
# Reads vod.env (YouTube login, clip settings). Claude uses your `claude` CLI login, no API key.
# VODs go to .\vods, clips to .\clips\<vod name>\
param(
    [int]$Vod,
    [int]$Max,
    [string]$Model,
    [switch]$List,
    [switch]$DownloadOnly,
    [switch]$Build
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Update-SessionPath {
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
}

function Find-Jdk17 {
    $dirs = @("C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Microsoft", "$env:USERPROFILE\.jdks") |
        Where-Object { Test-Path $_ } | ForEach-Object { Get-ChildItem $_ -Directory -Filter "jdk-17*" } |
        Where-Object { Test-Path (Join-Path $_.FullName "bin\java.exe") }
    $dirs | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
}

# ---------- prerequisites ----------
$missing = @()
if (-not (Find-Jdk17)) { $missing += [pscustomobject]@{ Name = "Java 17 (JDK)"; Id = "EclipseAdoptium.Temurin.17.JDK" } }
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { $missing += [pscustomobject]@{ Name = "ffmpeg"; Id = "Gyan.FFmpeg" } }
if (-not (Get-Command yt-dlp -ErrorAction SilentlyContinue)) { $missing += [pscustomobject]@{ Name = "yt-dlp"; Id = "yt-dlp.yt-dlp" } }
if (-not (Get-Command deno -ErrorAction SilentlyContinue)) { $missing += [pscustomobject]@{ Name = "deno (yt-dlp needs it for YouTube)"; Id = "DenoLand.Deno" } }
if (-not (Get-Command claude -ErrorAction SilentlyContinue)) {
    throw "The Claude Code CLI (claude) is not installed. Install it from https://claude.com/claude-code and run 'claude' once to log in."
}
if ($missing) {
    Write-Host "Missing: $(($missing.Name) -join ', ')"
    $answer = Read-Host "Install with winget now? [Y/n]"
    if ($answer -match '^(n|no)$') { throw "Install them first: winget install $(($missing.Id) -join ' ')" }
    foreach ($m in $missing) {
        Write-Host "Installing $($m.Name)…"
        winget install --id $m.Id -e --accept-package-agreements --accept-source-agreements
        # -1978335189 = already installed, no upgrade; -1978335135 = already installed (e.g. pulled in as a dependency)
        if ($LASTEXITCODE -notin 0, -1978335189, -1978335135) { throw "winget could not install $($m.Id) (exit code $LASTEXITCODE)" }
        Update-SessionPath
    }
    foreach ($tool in "ffmpeg", "yt-dlp", "deno") {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "$tool is installed but not on PATH yet. Open a new terminal and run the script again."
        }
    }
}
# YouTube changes often; the nightly yt-dlp keeps up (stable can fail with "n challenge solving failed")
yt-dlp --update-to nightly *> $null

$env:JAVA_HOME = Find-Jdk17
if (-not $env:JAVA_HOME) { throw "Java 17 not found after install. Open a new terminal and run the script again." }
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# ---------- settings from vod.env (Docker paths are replaced by local folders) ----------
$envFile = Join-Path $root "vod.env"
if (-not (Test-Path $envFile)) { throw "vod.env not found (copy deploy\vod.env.example and fill in the YouTube login)" }
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        $name, $value = $Matches[1], $Matches[2].Trim()
        if ($name -in "DOWNLOAD_DIR", "CLIPS_DIR", "STATE_FILE", "IG_TOKEN_FILE", "YT_COOKIES_FILE", "POST_DOWNLOAD_CMD") { continue }
        Set-Item "env:$name" $value
    }
}
# UTF-8 console output (– → … in the log instead of �)
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_OPTS = "-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

$env:DOWNLOAD_DIR = Join-Path $root "vods"
$env:CLIPS_DIR = Join-Path $root "clips"
if ($Model) { $env:CLIP_MODEL = $Model }

# ---------- build (first run, after code changes, or with -Build) ----------
$app = Join-Path $root "build\install\ps5-vod-fetcher\bin\ps5-vod-fetcher.bat"
$newestSource = Get-ChildItem (Join-Path $root "src"), (Join-Path $root "build.gradle.kts") -Recurse -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($Build -or -not (Test-Path $app) -or $newestSource.LastWriteTime -gt (Get-Item $app).LastWriteTime) {
    Write-Host "Building the app…"
    & (Join-Path $root "gradlew.bat") -p $root --no-daemon -q installDist
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

# ---------- list / clip ----------
if ($List -or -not $Vod) {
    & $app list
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($List) { exit 0 }
    $pick = Read-Host "`nWhich VOD number? (Enter to cancel)"
    if (-not $pick) { exit 0 }
    $Vod = [int]$pick
    if (-not $Max -and -not $DownloadOnly) {
        $maxAnswer = Read-Host "How many clips at most? (Enter = $(if ($env:MAX_CLIPS) { $env:MAX_CLIPS } else { 8 }))"
        if ($maxAnswer) { $Max = [int]$maxAnswer }
    }
}

if ($DownloadOnly) { & $app download "$Vod"; exit $LASTEXITCODE }
$appArgs = @("clip-vod", "$Vod")
if ($Max) { $appArgs += @("--max", "$Max") }
& $app @appArgs
exit $LASTEXITCODE
