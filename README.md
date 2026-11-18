# PS5 → YouTube → VOD fetcher

Every few minutes this service checks your YouTube channel for live streams that have finished, then downloads each new VOD with `yt-dlp`.

```
PS5 (Create → Broadcast → YouTube, Unlisted)
        │  stream ends
        ▼
YouTube saves VOD ──► this service polls liveBroadcasts.list(completed)
                              │ new one?
                              ▼
                       yt-dlp → ./vods/*.mp4 → (optional) POST_DOWNLOAD_CMD
```

## 1. PS5 + YouTube
1. Turn on live streaming for your YouTube channel (studio.youtube.com). The first time, it can take up to 24h to activate.
2. On the PS5, go to **Settings → Users and Accounts → Link with Other Services → YouTube**.
3. To play and record, press **Create → Broadcast → YouTube** and set the visibility to **Unlisted**. Unlisted VODs can be downloaded without cookies. If you choose **Private**, you'll need to set `YT_COOKIES_FILE`.

## 2. Google API credentials (one-time)
1. In console.cloud.google.com, create a project and enable **YouTube Data API v3**.
2. Configure the **OAuth consent screen** as External and add yourself as a test user.
   ⚠️ After that, set the publishing status to **In production**. An unverified app is fine for personal use. While the app stays in "Testing", refresh tokens **expire after 7 days**.
3. Go to **Credentials → Create OAuth client ID** and choose type **Web application**. Add `https://developers.google.com/oauthplayground` as an authorized redirect URI.
4. Get a refresh token from the OAuth Playground (developers.google.com/oauthplayground):
   - Click the ⚙️ icon, tick "Use your own OAuth credentials", and paste your client ID and secret.
   - Choose the scope `https://www.googleapis.com/auth/youtube.readonly`, then **Authorize** and **Exchange** the code for tokens.
   - Copy the **refresh token**.

## 3. Run locally (optional)
Requirements: JDK 17+, `yt-dlp` with Deno, and `ffmpeg`.

```bash
export YT_CLIENT_ID=...
export YT_CLIENT_SECRET=...
export YT_REFRESH_TOKEN=...
export DOWNLOAD_DIR=/data/vods        # optional, default ./vods
export POLL_SECONDS=300               # optional
export LOOKBACK_HOURS=24              # optional: ignore older streams on first run
# export YT_COOKIES_FILE=cookies.txt  # only for Private broadcasts
# export POST_DOWNLOAD_CMD="rclone copy --"   # optional: file path is appended as the last arg

./gradlew run
```

### …or with Docker
```bash
docker build -t ps5-vod-fetcher .
docker run --rm --env-file vod.env -v "$PWD/vods:/data/vods" -v "$PWD/state:/data" ps5-vod-fetcher
```
The image bundles the JRE, `yt-dlp` (+ `yt-dlp-ejs`), Deno and `ffmpeg`. Inside the container, videos go to `/data/vods` and the state file to `/data/processed.txt`.

## 4. Raspberry Pi setup (one-time)
Use Raspberry Pi OS **64-bit** and save videos to a USB SSD/HDD rather than the SD card.

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER        # log out & back in afterwards

mkdir -p ~/vod-fetcher && cd ~/vod-fetcher
# copy deploy/vod.env.example here as vod.env and fill in the secrets
nano vod.env
```
The deploy job copies `deploy/docker-compose.yml` into `~/vod-fetcher` and starts the container. Videos go to `/mnt/ssd/vods` by default; to change that, add `VODS_DIR=/your/path` to a `.env` file next to the compose file. State (`processed.txt`) is kept in `~/vod-fetcher/state`.

## 5. Push to Docker Hub & deploy
Build the image on your PC and push it to Docker Hub as `<user>/ps5-vod-fetcher:latest` and `:<commit-sha>`. The image is multi-arch (`linux/amd64` + `linux/arm64`), so it runs on both your PC and the Pi.
```powershell
docker login
./push.ps1 -User <dockerhub-user>            # or set $env:DOCKERHUB_USERNAME
./push.ps1 -User <dockerhub-user> -Deploy    # also make the Pi pull & restart (needs the gh CLI)
```
`-Deploy` triggers `.github/workflows/deploy.yml`, which runs **on your Pi** through a self-hosted runner and does `docker compose pull && docker compose up -d`. You can also start it from the Actions tab (Run workflow), entering the image name. Without the runner, run the same two compose commands on the Pi yourself, with `DOCKER_IMAGE=<user>/ps5-vod-fetcher` in `~/vod-fetcher/.env`.

If you want the image private, create the `ps5-vod-fetcher` repo on Docker Hub as private first, then run `docker login` on the Pi.

The runner on the Pi connects *out* to GitHub, so you don't need port forwarding or SSH exposed to the internet.

**Install the runner on the Pi:** in your repo, go to **Settings → Actions → Runners → New self-hosted runner → Linux / ARM64** and run the commands shown there. Then install it as a service (the runner user must be in the `docker` group):
```bash
cd ~/actions-runner
sudo ./svc.sh install $USER
sudo ./svc.sh start
```

⚠️ **Keep the repo private.** On a public repo, anyone could open a PR that runs code on your Pi.

## 6. AI shorts (TikTok / Reels / YouTube Shorts)
When `ANTHROPIC_API_KEY` is set, every downloaded VOD is cut into vertical 1080×1920 clips of the best moments: gameplay highlights, stunts, funny moments and unexpected events.

How it works (`src/main/kotlin/Clipper.kt`):
1. **Audio scan:** ffmpeg measures loudness every second, and spikes (explosions, crashes, shouting) are passed to Claude as hints.
2. **Contact sheets:** one keyframe every `CLIP_FRAME_INTERVAL` seconds (default 4), tiled 3×3, so each image covers 36 s of footage. Only keyframes are decoded, so this is fast on a Pi.
3. **Claude** (`claude-haiku-5-5` by default, or `CLIP_MODEL=claude-opus-5-5` for sharper picks) reviews about 12 minutes of sheets per request and returns clip candidates as structured JSON: start/end, category, score from 1 to 10, title, caption and hashtags.
4. The best non-overlapping clips scoring 5 or more (up to `MAX_CLIPS`) are rendered with ffmpeg. `CLIP_STYLE=blur` keeps the whole 16:9 frame on a blurred background; `crop` does a center crop.

Output goes to `CLIPS_DIR/<vod name>/` (on the Pi: `/mnt/ssd/clips`, which you can change with `CLIPS_DIR=` in `.env`):
```
01_stunt_insane-barrel-roll-landing.mp4
02_funny-ragdoll-off-the-cliff.mp4
clips.json      # timestamps, scores, captions, hashtags
captions.md     # ready to copy-paste when posting
```
If the API has a temporary problem (rate limit, outage), nothing is written and the VOD is retried on the next poll. This applies to VODs downloaded within the last `LOOKBACK_HOURS`.

**No credit, a bad key, or no key at all:** a tiny test request (about 10 tokens) runs before any heavy work. If it fails for one of these reasons, the folder gets a `CUT_MANUALLY.txt` that says why and how to fix it, and the log shows a `⚠ NO SHORTS` line. That VOD isn't retried automatically, so you can cut it yourself (CapCut, DaVinci Resolve, or YouTube Studio → "Create Short"), or fix the problem and then run the `clip` command below, or delete the note.

**Run it by hand** on any video:
```bash
docker run --rm --env-file vod.env -v "$PWD/vods:/data/vods" -v "$PWD/clips:/data/clips" \
  ps5-vod-fetcher clip /data/vods/<file>.mp4
```

**API key:** go to platform.claude.com, then **Settings → API keys → Create key**, and add credits under **Billing**.
**Cost:** each sheet is about 1,500 input tokens, so a 1-hour VOD (100 sheets, 5 requests) is roughly 160K tokens: about **$0.02 per hour of stream** on Haiku 5.5, or about $0.65 on Opus 5.5. A lower `CLIP_FRAME_INTERVAL` gives finer detection but costs proportionally more.
**Pi speed:** rendering a clip takes about real time to 2× real time on a Pi 5 (`libx264 veryfast`), so 8 clips take a few minutes.

## Notes
- **Quota:** each poll costs about 1 unit out of the default 10,000/day, so polling every 5 minutes uses about 288/day.
- **Retries:** YouTube needs some time to process a VOD after the stream ends. Failed downloads are retried on the next poll and only marked as done in `processed.txt` once they succeed.
- **Quality:** the VOD is limited to what the PS5 broadcast at (Settings → Captures and Broadcasts → Broadcasts → Video quality).
- yt-dlp breaks now and then when YouTube changes something. Each `./push.ps1` installs the latest version into the image, so re-run it (with `-Deploy`) when downloads start failing.
- Logs: `docker logs -f vod-fetcher`
