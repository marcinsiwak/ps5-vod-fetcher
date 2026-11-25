# PS5 → YouTube → VOD fetcher

Once a day at **4:00 Polish time** (`RUN_AT=04:00`, `RUN_TZ=Europe/Warsaw`) this service checks your YouTube channel for live streams that have finished, downloads each new VOD with `yt-dlp`, clips it and publishes the clips as Instagram Reels. Set `RUN_AT=off` to poll every `POLL_SECONDS` instead.

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
export RUN_AT=04:00                   # optional: daily run time (default 04:00), or "off" to poll
export RUN_TZ=Europe/Warsaw           # optional: time zone for RUN_AT (default Europe/Warsaw)
export POLL_SECONDS=300               # optional: only used with RUN_AT=off
export LOOKBACK_HOURS=48              # optional: ignore older streams (default 48 daily / 24 polling)
export VOD_RETENTION_DAYS=7           # optional: delete VODs this many days after download (default 7, 0 = keep)
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
The deploy job copies `deploy/docker-compose.yml` into `~/vod-fetcher` and starts the container. Videos go to `/mnt/ssd/vods` by default; to change that, add `VODS_DIR=/your/path` to a `.env` file next to the compose file. State is kept in `~/vod-fetcher/state`: `processed.txt` (downloaded streams) and `clipped.txt` (clipped VODs), so a recreated container never downloads or clips the same VOD twice. Downloaded VODs are deleted after `VOD_RETENTION_DAYS` (default 7); clips are kept.

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
Using the **Claude Code CLI on your Claude subscription** (no API key), every downloaded VOD is cut into vertical 1080×1920 clips of the best moments: gameplay highlights, stunts, funny moments and unexpected events.

How it works (`src/main/kotlin/Clipper.kt`):
1. **Audio scan:** ffmpeg measures loudness every second, and spikes (explosions, crashes, shouting) are passed to Claude as hints.
2. **Contact sheets:** one keyframe every `CLIP_FRAME_INTERVAL` seconds (default 4), tiled 3×3, so each image covers 36 s of footage. Only keyframes are decoded, so this is fast on a Pi.
3. **Claude**, run as `claude -p` (`CLIP_MODEL=haiku` by default, or `sonnet`/`opus` for sharper picks), reads about 12 minutes of sheets per run with its Read tool and returns clip candidates as structured JSON: start/end, category, score from 1 to 10, game, title, caption and hashtags. Cutscenes, cinematics, replays and photo mode are never clipped (Claude flags clips that include one and they are dropped).
4. The best non-overlapping clips scoring 5 or more (up to `MAX_CLIPS`) are rendered with ffmpeg. With `CLIP_STYLE=auto` (default) Claude decides per clip: **vertical** (center crop to full-screen 9:16) when the action stays in the middle, or **horizontal** (the whole 16:9 frame on a blurred background) when it spreads across the screen or the HUD matters. `CLIP_STYLE=crop` or `blur` forces one style for every clip. The choice is saved as `framing` in `clips.json`.

Output goes to `CLIPS_DIR/<vod name>/` (on the Pi: `/mnt/ssd/clips`, which you can change with `CLIPS_DIR=` in `.env`):
```
01_stunt_insane-barrel-roll-landing.mp4
02_funny-ragdoll-off-the-cliff.mp4
clips.json      # timestamps, scores, captions, hashtags
captions.md     # ready to copy-paste when posting
worth_checking.md  # more moments rated worth a short that didn't fit in MAX_CLIPS: time ranges to check/cut yourself
```
`MAX_CLIPS` (default 8) is how many clips get rendered. The `clip` command also takes `--max N`.
If Claude has a temporary problem (usage limit reached, outage), nothing is written and the VOD is retried on the next poll. This applies to VODs downloaded within the last `LOOKBACK_HOURS`.

**CLI not logged in or not installed:** a tiny test request runs before any heavy work. If it fails for one of these reasons, the folder gets a `CUT_MANUALLY.txt` that says why and how to fix it, and the log shows a `⚠ NO SHORTS` line. That VOD isn't retried automatically, so you can cut it yourself (CapCut, DaVinci Resolve, or YouTube Studio → "Create Short"), or fix the problem and then run the `clip` command below, or delete the note.

**Your own video file** (Windows, local Docker):
```powershell
./clip.ps1 "vods\my gameplay.mp4"                    # → clips\my gameplay\
./clip.ps1 "vods\my gameplay.mp4" -Style crop        # → clips\my gameplay_2\  (earlier clips are kept)
```
Options: `-Style blur|crop`, `-MaxClips N`, `-Model opus`, `-FrameInterval N`, `-Out <folder>`, `-Build` (rebuild the image after code changes).
Running it again on the same video never overwrites anything. Each run saves to the next free folder (`_2`, `_3`, …), so you can compare settings.

**On your PC without Docker** (`local.ps1`): lists your finished streams, asks which one to clip and how many clips to make, downloads it to `vods\` if needed, and clips it to `clips\`. It reads `vod.env` and uses your `claude` login. On the first run it offers to install the missing tools with winget (JDK 17, ffmpeg, yt-dlp, deno).
```powershell
./local.ps1                      # pick from the list
./local.ps1 -Vod 2 -Max 5        # VOD number 2 from the list, at most 5 clips
./local.ps1 -Vod 1 -Model opus   # sharper picks
./local.ps1 -List                # just show the list
./local.ps1 -Vod 2 -DownloadOnly # just download it to vods\ (test the YouTube side)
```
The same commands work in the app itself: `list`, `download <n>` and `clip-vod <n> [--max N]`.

**Run it by hand** on the Pi or in Docker:
```bash
docker run --rm --env-file vod.env -v "$PWD/vods:/data/vods" -v "$PWD/clips:/data/clips" \
  ps5-vod-fetcher clip --max 5 /data/vods/<file>.mp4
```

**Login (no API key):** the clipper calls the `claude` CLI that is built into the Docker image, and `ANTHROPIC_API_KEY` is never passed to it, so it always uses your Claude Pro/Max subscription. Run `claude setup-token` once on any PC, then put the token in `CLAUDE_CODE_OAUTH_TOKEN=` in `vod.env` (on the Pi too). If you run the app outside Docker, logging in with `claude` once is enough. Set `CLIP_ENABLED=false` to turn clipping off.
**Usage:** each 12-minute section is one `claude -p` run with about 20 images, so it counts against your plan's usage limits. Haiku uses the least.
**Pi speed:** rendering a clip takes about real time to 2× real time on a Pi 5 (`libx264 veryfast`), so 8 clips take a few minutes.

## 7. Highlight clips (no AI)
`highlights.ps1` finds the best moments in a VOD and cuts them into 16:9 clips. It runs yt-dlp + ffmpeg inside the Docker image, so you don't need them installed locally.
```powershell
./highlights.ps1 https://youtu.be/VIDEO_ID --list          # show candidate moments only
./highlights.ps1 https://youtu.be/VIDEO_ID                 # cut the top 8 x 30 s clips
./highlights.ps1 https://youtu.be/VIDEO_ID --pick 1,3      # cut only those (analysis is cached)
./highlights.ps1 https://youtu.be/VIDEO_ID --manual "1:23:10-1:23:45" --top 5 --length 45 --min-score 2
./highlights.ps1 .\vods\stream.mp4                   # local files work too
```
Each second of the VOD gets a score from several signals combined:
- **Audio:** loudness, and sudden loudness jumps relative to the previous ~30 s
- **Video:** motion, scene-cut density, and brightness flashes
- **YouTube (when available):** the *Most replayed* heatmap, live-chat activity from the stream replay, and timestamps viewers wrote in comments

For YouTube VODs, analysis uses a 360p copy that is deleted afterwards. Clips are then downloaded in full quality, fetching only their sections.

Output goes to `clips/<video-id>/`: the clips, plus `highlights.json` listing why each moment was picked.

## 8. Instagram Reels posting (automatic)
New clips go into a **posting queue**, and **one Reel is published at each posting time**: `POST_TIMES=13:00,19:00` by default (in `RUN_TZ`), so 2 Reels a day. Posting runs on its own schedule in both daily and polling mode. Instagram's API can't save drafts, so there is no draft step.

- **Order:** oldest VOD first; within a VOD, the highest-scoring clip first. A newer VOD's clips wait until the previous VOD is done.
- **Expiry:** clips scoring under 7/10 that weren't posted within `POST_MAX_AGE_DAYS` (default 7) of the stream are skipped. Clips scoring 7+ never expire. `POST_MAX_AGE_DAYS=0` turns expiry off.
- **Failures:** a failed upload uses up that posting time; the clip is retried at the next one, up to 3 times, then skipped.
- **Spacing:** there are always at least 5 minutes between two Reels, including manual posts.
- **See the plan:** `docker exec vod-fetcher /app/bin/ps5-vod-fetcher queue` lists every queued clip with its posting time and expiry.
- **Skipping a clip:** delete its `.mp4`. To change the text, edit `caption` / `hashtags` in `clips.json` before its turn.
- **Caption:** one short English reaction line that relates to the clip without describing it, then 3-5 hashtags (Instagram allows at most 5) with the game's name. `title` is only used for file names and `captions.md`.
- **Post right away:** `docker exec vod-fetcher /app/bin/ps5-vod-fetcher post /data/clips/<vod>` posts that folder's next clip (best first); add `--all` for all of them, 5 minutes apart. Manual posts don't use up a scheduled posting time. On the PC, `./post.ps1 "clips\<vod>"` posts all (asks first; `-Yes` skips the question).
- **Review first instead:** set `AUTO_POST=false` in `vod.env`. Then a folder joins the queue only once it has an `APPROVED` file (`touch /mnt/ssd/clips/<vod>/APPROVED`). Its place is still by stream date, and expiry still counts from the stream date.
- **Turn scheduled posting off:** `POST_TIMES=off` (post by hand only).

When a folder is done, it gets `POSTED.txt` with what happened to each clip (posted link, deleted, expired, or failed). `posted.json` in each folder records the Reel links and attempts, so a clip is never posted twice. Uploads go straight from disk, so no public URL or port forwarding is needed.

**One-time setup** (Instagram professional account; no Facebook Page needed):
1. In the Instagram app, go to **Settings → Account type and tools** and switch to a **Creator** or **Business** account if you haven't already.
2. At developers.facebook.com, choose **My Apps → Create app**. Pick the use case for managing messaging and content on Instagram (the "Instagram API" product).
3. In the app, go to **Instagram → API setup with Instagram login**:
   - **Generate access tokens → Add account**, log in with your Instagram account and allow it. If an invite shows up instead, accept it in the Instagram app under **Settings → Apps and websites → Tester invites**, then try again.
   - Copy the generated **access token** into `IG_ACCESS_TOKEN=` in `vod.env`. It lasts 60 days, and the service refreshes it weekly so it doesn't expire. The current token is kept in `state/ig_token.json`.
4. Check it: `./post.ps1 -Check` should print `Instagram token OK: @yourname`.

While the app is in development mode, it can only post to accounts added to the app, which is all you need for your own account. No App Review is required for that, though Meta's docs aren't fully consistent on this.

**Alternative: a Facebook system-user token (starts with `EAA`/`EAB`, never expires).** This is the setup that works without a Page:
1. In your app, open **Facebook Login for Business → Configurations** and create one with the variation **"Instagram API (Unified Login)"**. Set the access token to **System-user access token**, add your **Instagram account** as a business asset, and include the permissions `instagram_basic` and `instagram_content_publish`.
2. Generate the token for that configuration and paste it into `IG_ACCESS_TOKEN=`.
3. Run `./post.ps1 -Check`. The app finds the account through `me/assigned_instagram_accounts`; a user token whose Instagram is linked to a Page also works. System-user tokens don't expire, so `FB_APP_ID` and `FB_APP_SECRET` aren't needed.

## Notes
- **Quota:** each check costs about 1 unit out of the default 10,000/day (daily mode: 1/day; polling every 5 minutes: about 288/day).
- **Retries:** YouTube needs some time to process a VOD after the stream ends. Failed downloads are retried on the next run and only marked as done in `processed.txt` once they succeed.
- **Quality:** the VOD is limited to what the PS5 broadcast at (Settings → Captures and Broadcasts → Broadcasts → Video quality).
- yt-dlp breaks now and then when YouTube changes something. Each `./push.ps1` installs the latest version into the image, so re-run it (with `-Deploy`) when downloads start failing.
- Logs: `docker logs -f vod-fetcher`
