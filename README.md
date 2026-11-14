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

## 4. Raspberry Pi setup (one-time)
Use Raspberry Pi OS **64-bit** and save videos to a USB SSD/HDD rather than the SD card.

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless ffmpeg pipx
pipx install yt-dlp && pipx inject yt-dlp yt-dlp-ejs
curl -fsSL https://deno.land/install.sh | sh      # yt-dlp needs a JS runtime for YouTube

# secrets
cp deploy/vod.env.example ~/vod.env && nano ~/vod.env

# service
sudo cp deploy/vod-fetcher.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable vod-fetcher

# let the deploy job restart the service without a password
echo "$USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart vod-fetcher" | sudo tee /etc/sudoers.d/vod-fetcher
```
If your username isn't `pi`, update the paths in `vod-fetcher.service`.

## 5. Auto-deploy with GitHub Actions
Every push to `main` triggers `.github/workflows/deploy.yml`:
1. **build** runs on GitHub's servers and runs `./gradlew installDist`.
2. **deploy** runs **on your Pi** through a self-hosted runner. It downloads the build into `~/ps5-vod-fetcher` and restarts the service.

The runner on the Pi connects *out* to GitHub, so you don't need port forwarding or SSH exposed to the internet.

**Install the runner on the Pi:** in your repo, go to **Settings → Actions → Runners → New self-hosted runner → Linux / ARM64** and run the commands shown there. Then install it as a service:
```bash
cd ~/actions-runner
sudo ./svc.sh install $USER
sudo ./svc.sh start
```

⚠️ **Keep the repo private.** On a public repo, anyone could open a PR that runs code on your Pi.

## Notes
- **Quota:** each poll costs about 1 unit out of the default 10,000/day, so polling every 5 minutes uses about 288/day.
- **Retries:** YouTube needs some time to process a VOD after the stream ends. Failed downloads are retried on the next poll and only marked as done in `processed.txt` once they succeed.
- **Quality:** the VOD is limited to what the PS5 broadcast at (Settings → Captures and Broadcasts → Broadcasts → Video quality).
- yt-dlp breaks now and then when YouTube changes something. Keep it updated with a weekly cron job running `pipx upgrade yt-dlp`.
- Logs: `journalctl -u vod-fetcher -f`
