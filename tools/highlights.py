#!/usr/bin/env python3
"""Find highlight moments in a VOD and cut them into clips.

Runs inside the ps5-vod-fetcher Docker image (needs yt-dlp + ffmpeg); use ../highlights.ps1.

Signals (each turned into a per-second robust z-score, then weighted & summed):
  loud    - momentary loudness (EBU R128)
  rise    - sudden loudness jump vs. the previous ~30 s (reactions, explosions)
  motion  - frame-to-frame luma difference (fast camera / action)
  cuts    - density of scene changes (spectating, deaths, menus flipping)
  flash   - brightness jumps (explosions, muzzle flash, hit effects)
  replay  - YouTube "Most replayed" heatmap       (if YouTube has one)
  chat    - live-chat messages per second          (if the stream had chat)
  comment - timestamps viewers wrote in comments   (if any)
"""
import argparse, json, math, os, re, shutil, statistics, subprocess, sys
from pathlib import Path

WEIGHTS = {"loud": 1.0, "rise": 1.2, "motion": 0.8, "cuts": 0.5, "flash": 0.5,
           "replay": 2.0, "chat": 1.5, "comment": 1.5}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def run(cmd, **kw):
    log("  $", " ".join(str(c) for c in cmd)[:200])
    return subprocess.run(cmd, check=True, **kw)


def hms(t):
    t = int(round(t))
    return f"{t // 3600}:{t // 60 % 60:02d}:{t % 60:02d}"


def parse_ts(s):
    secs = 0.0
    for part in s.strip().split(":"):
        secs = secs * 60 + float(part)
    return secs


# ---------------------------------------------------------------- your channel (YouTube Data API)
def yt_api(path, token, **params):
    import urllib.parse, urllib.request
    url = "https://www.googleapis.com/youtube/v3/" + path + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def yt_token():
    import urllib.parse, urllib.request
    try:
        data = urllib.parse.urlencode({k.lower(): os.environ[v] for k, v in (
            ("client_id", "YT_CLIENT_ID"), ("client_secret", "YT_CLIENT_SECRET"),
            ("refresh_token", "YT_REFRESH_TOKEN"))} | {"grant_type": "refresh_token"}).encode()
    except KeyError as e:
        sys.exit(f"Missing {e.args[0]} - put the YouTube credentials in vod.env")
    with urllib.request.urlopen("https://oauth2.googleapis.com/token", data, timeout=30) as r:
        return json.load(r)["access_token"]


def iso_duration(d):
    m = re.fullmatch(r"P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", d or "")
    if not m:
        return 0
    dd, h, mi, sec = (int(x or 0) for x in m.groups())
    return dd * 86400 + h * 3600 + mi * 60 + sec


def channel_videos(limit=25):
    token = yt_token()
    ch = yt_api("channels", token, part="snippet,contentDetails", mine="true")["items"]
    if not ch:
        sys.exit("This YouTube login has no channel.")
    log(f"Channel: {ch[0]['snippet']['title']} ({ch[0]['id']})")
    uploads = ch[0]["contentDetails"]["relatedPlaylists"]["uploads"]
    items = yt_api("playlistItems", token, part="contentDetails", playlistId=uploads,
                   maxResults=min(limit, 50)).get("items", [])
    ids = [i["contentDetails"]["videoId"] for i in items]
    if not ids:
        return []
    vids = yt_api("videos", token, part="snippet,contentDetails,liveStreamingDetails,status",
                  id=",".join(ids))["items"]
    by_id = {v["id"]: v for v in vids}
    out = []
    for vid in ids:  # keep upload order (newest first)
        v = by_id.get(vid)
        if not v:
            continue
        out.append({"id": vid, "title": v["snippet"]["title"],
                    "published": v["snippet"]["publishedAt"][:10],
                    "duration": iso_duration(v["contentDetails"].get("duration")),
                    "stream": "liveStreamingDetails" in v,
                    "privacy": v["status"]["privacyStatus"]})
    return out


def print_channel(videos):
    print(f"\n{'#':>2}  {'date':10}  {'length':>8}  {'type':6}  {'privacy':8}  title")
    for n, v in enumerate(videos, 1):
        kind = "stream" if v["stream"] else "upload"
        print(f"{n:>2}  {v['published']:10}  {hms(v['duration']):>8}  {kind:6}  {v['privacy']:8}  {v['title']}")
    print("\nClip one with:  ./highlights.ps1 --channel <#>   (e.g. --channel 1 = newest)")


# ---------------------------------------------------------------- inputs
def is_url(s):
    return s.startswith(("http://", "https://")) or re.fullmatch(r"[\w-]{11}", s) is not None


def fetch_youtube_meta(url, work):
    """info JSON (+heatmap, comments) and live-chat replay, no video."""
    info_path = work / "info.json"
    if not info_path.exists():
        out = run(["yt-dlp", "-J", "--no-warnings", "--write-comments",
                   "--extractor-args", "youtube:max_comments=500", url],
                  capture_output=True, text=True).stdout
        info_path.write_text(out, encoding="utf-8")
    info = json.loads(info_path.read_text(encoding="utf-8"))
    chat = None
    if info.get("was_live") or info.get("live_status") == "was_live":
        chat_file = work / "chat.live_chat.json"
        if not chat_file.exists():
            try:
                run(["yt-dlp", "--skip-download", "--no-warnings", "--write-subs",
                     "--sub-langs", "live_chat", "-o", str(work / "chat"), url],
                    capture_output=True, text=True)
            except subprocess.CalledProcessError:
                log("  (no live-chat replay)")
        if chat_file.exists():
            chat = chat_file
    return info, chat


def download_analysis_copy(url, work):
    """Low-res copy: analysis doesn't need 1080p and it's ~10x less to download."""
    existing = list(work.glob("analysis.*"))
    if existing:
        return existing[0]
    out = run(["yt-dlp", "-f", "bv*+ba/b", "-S", "res:360", "--merge-output-format", "mkv",
               "--no-progress", "--print", "after_move:filepath",
               "-o", str(work / "analysis.%(ext)s"), url],
              capture_output=True, text=True).stdout.strip()
    return Path(out.splitlines()[-1])


# ---------------------------------------------------------------- ffmpeg analysis
def read_metadata_file(path):
    """Parse ffmpeg metadata=print output -> list of (pts_time, {key: float})."""
    frames, cur = [], None
    for line in Path(path).read_text(errors="replace").splitlines():
        if line.startswith("frame:"):
            m = re.search(r"pts_time:(\S+)", line)
            cur = (float(m.group(1)) if m else 0.0, {})
            frames.append(cur)
        elif cur is not None and "=" in line:
            k, v = line.split("=", 1)
            try:
                cur[1][k.strip()] = float(v)
            except ValueError:
                pass
    return frames


def has_audio(media):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries",
                          "stream=index", "-of", "csv=p=0", str(media)],
                         capture_output=True, text=True).stdout
    return bool(out.strip())


def media_duration(media):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                          "-of", "csv=p=0", str(media)], capture_output=True, text=True).stdout
    return float(out.strip())


def analyze_media(media, work):
    vfile, afile = work / "video.txt", work / "audio.txt"
    graph = (f"[0:v:0]fps=4,scale=256:-2,signalstats,scdet=threshold=12,"
             f"metadata=mode=print:file={vfile}[v]")
    maps = ["-map", "[v]", "-f", "null", "-"]
    audio = has_audio(media)
    if audio:
        graph += (f";[0:a:0]ebur128=metadata=1,"
                  f"ametadata=mode=print:key=lavfi.r128.M:file={afile}[a]")
        maps += ["-map", "[a]", "-f", "null", "-"]
    log("Analyzing audio + video (this decodes the whole VOD)...")
    run(["ffmpeg", "-hide_banner", "-nostats", "-loglevel", "error", "-i", str(media),
         "-filter_complex", graph, *maps])

    n = int(math.ceil(media_duration(media)))
    sig = {k: [0.0] * n for k in ("loud", "motion", "cuts", "flash")}
    cnt = [0] * n
    cut_times = []
    prev_yavg = None
    for t, kv in read_metadata_file(vfile):
        i = min(int(t), n - 1)
        sig["motion"][i] += kv.get("lavfi.signalstats.YDIF", 0.0)
        cnt[i] += 1
        yavg = kv.get("lavfi.signalstats.YAVG")
        if yavg is not None and prev_yavg is not None:
            sig["flash"][i] = max(sig["flash"][i], abs(yavg - prev_yavg))
        prev_yavg = yavg
        if "lavfi.scd.time" in kv:
            sig["cuts"][i] += 1
            cut_times.append(round(t, 2))
    sig["motion"] = [m / c if c else 0.0 for m, c in zip(sig["motion"], cnt)]

    sig["loud"] = [-70.0] * n
    if audio:
        for t, kv in read_metadata_file(afile):
            i = min(int(t), n - 1)
            m = kv.get("lavfi.r128.M")
            if m is not None and m > sig["loud"][i]:
                sig["loud"][i] = max(m, -70.0)
    return n, sig, cut_times


# ---------------------------------------------------------------- external signals
def heatmap_signal(info, n):
    s = [0.0] * n
    for seg in info.get("heatmap") or []:
        for i in range(int(seg["start_time"]), min(n, int(math.ceil(seg["end_time"])))):
            s[i] = seg["value"]
    return s


def chat_signal(chat_file, n):
    s = [0.0] * n
    if not chat_file:
        return s
    for line in Path(chat_file).read_text(encoding="utf-8", errors="replace").splitlines():
        try:
            off = int(json.loads(line)["replayChatItemAction"]["videoOffsetTimeMsec"]) / 1000
        except (KeyError, ValueError, TypeError):
            continue
        if 0 <= off < n:
            s[int(off)] += 1
    return s


TS_RE = re.compile(r"(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?![\d:])")


def comment_signal(info, n):
    s = [0.0] * n
    for c in info.get("comments") or []:
        weight = 1 + math.log1p(c.get("like_count") or 0)
        for h, m, sec in TS_RE.findall(c.get("text") or ""):
            t = int(h or 0) * 3600 + int(m) * 60 + int(sec)
            # people type the timestamp just after the moment -> bump the 10 s before it
            for i in range(max(0, t - 10), min(n, t + 3)):
                s[i] += weight
    return s


# ---------------------------------------------------------------- scoring
def smooth(xs, w):
    if w <= 1:
        return list(xs)
    half, out, acc = w // 2, [], 0.0
    pref = [0.0]
    for x in xs:
        acc += x
        pref.append(acc)
    for i in range(len(xs)):
        a, b = max(0, i - half), min(len(xs), i + half + 1)
        out.append((pref[b] - pref[a]) / (b - a))
    return out


def robust_z(xs):
    if not xs:
        return xs
    med = statistics.median(xs)
    mad = statistics.median(abs(x - med) for x in xs) * 1.4826
    if mad < 1e-9:
        sd = statistics.pstdev(xs)
        if sd < 1e-9:
            return None  # flat signal -> no information
        mad = sd
    return [max(-3.0, min(6.0, (x - med) / mad)) for x in xs]


def loudness_rise(loud):
    short = smooth(loud, 3)
    out = []
    for i in range(len(loud)):
        base = loud[max(0, i - 30):max(1, i - 3)]
        out.append(short[i] - statistics.median(base) if base else 0.0)
    return out


def score(sig, n, length):
    raw = dict(sig)
    raw["rise"] = loudness_rise(sig["loud"])
    raw["cuts"] = smooth(sig["cuts"], 5)
    zs = {}
    for k, xs in raw.items():
        z = robust_z(smooth(xs, 3))
        if z is not None:
            zs[k] = z
    total = [0.0] * n
    for k, z in zs.items():
        w = WEIGHTS.get(k, 0.0)
        for i in range(n):
            total[i] += w * z[i]
    return smooth(total, max(3, length // 3)), zs


def pick(total, zs, n, top, length, skip_start, skip_end, cut_times, min_score):
    order = sorted(range(n), key=lambda i: total[i], reverse=True)
    picks = []
    for i in order:
        if len(picks) >= top or total[i] < min_score:
            break
        if i < skip_start or i > n - skip_end:
            continue
        if any(abs(i - p) < length for p in picks):
            continue
        picks.append(i)
    clips = []
    for rank, peak in enumerate(sorted(picks, key=lambda i: -total[i]), 1):
        start = max(0.0, peak - 0.6 * length)   # action usually builds up before the peak
        near = [c for c in cut_times if abs(c - start) <= 2.0]
        if near:                                  # start on a scene change if one is close
            start = min(near, key=lambda c: abs(c - start))
        end = min(float(n), start + length)
        reasons = sorted((k for k, z in zs.items() if z[peak] >= 1.5), key=lambda k: -zs[k][peak])
        clips.append({"rank": rank, "peak": peak, "start": round(start, 2), "end": round(end, 2),
                      "score": round(total[peak], 2), "reasons": reasons})
    return clips


# ---------------------------------------------------------------- cutting
def cut_youtube(url, clips, outdir):
    """Download only the clip sections, in full quality."""
    cmd = ["yt-dlp", "-f", "bv*+ba/b", "--merge-output-format", "mp4", "--force-keyframes-at-cuts",
           "--no-progress", "--print", "after_move:filepath",
           "-o", str(outdir / "sec_%(section_start)d.%(ext)s")]
    for c in clips:
        cmd += ["--download-sections", f"*{c['start']}-{c['end']}"]
    out = run(cmd + [url], capture_output=True, text=True).stdout
    by_start = {}
    for p in out.strip().splitlines():
        m = re.search(r"sec_(\d+)\.", p)
        if m:
            by_start[int(m.group(1))] = Path(p)
    for c in clips:
        src = by_start.get(int(c["start"]))
        if src and src.exists():
            dst = outdir / clip_name(c)
            src.replace(dst)
            c["file"] = dst.name


def cut_local(media, clips, outdir):
    for c in clips:
        dst = outdir / clip_name(c)
        run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-ss", str(c["start"]),
             "-to", str(c["end"]), "-i", str(media), "-c:v", "libx264", "-preset", "veryfast",
             "-crf", "18", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(dst)])
        c["file"] = dst.name


def clip_name(c):
    tag = "manual" if c.get("manual") else "+".join(c["reasons"][:3]) or "score"
    return f"{c['rank']:02d}_{hms(c['start']).replace(':', '-')}_{tag}.mp4"


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("source", nargs="?", help="YouTube URL / video id, or a local video file")
    ap.add_argument("--channel", nargs="?", type=int, const=0, metavar="N",
                    help="use your channel (vod.env login): alone = list recent videos, N = clip the Nth newest")
    ap.add_argument("--top", type=int, default=8, help="number of auto clips (default 8)")
    ap.add_argument("--length", type=int, default=30, help="clip length in seconds (default 30)")
    ap.add_argument("--manual", default="", help='extra clips, e.g. "1:23:10-1:23:45,2:01:00-2:01:20"')
    ap.add_argument("--list", action="store_true", help="only print candidates, don't cut")
    ap.add_argument("--pick", default="", help='cut only these ranks, e.g. "1,3,4"')
    ap.add_argument("--min-score", type=float, default=3.0,
                    help="drop moments scoring below this (default 3; lower it to get more clips)")
    ap.add_argument("--skip-start", type=int, default=30, help="ignore the first N seconds (menus)")
    ap.add_argument("--skip-end", type=int, default=15, help="ignore the last N seconds")
    ap.add_argument("--cache", default="/cache")
    ap.add_argument("--out", default="/clips")
    a = ap.parse_args()

    if a.channel is not None:
        videos = channel_videos()
        if a.channel == 0:
            print_channel(videos)
            return
        if not 1 <= a.channel <= len(videos):
            sys.exit(f"--channel {a.channel}: the channel has {len(videos)} recent videos")
        v = videos[a.channel - 1]
        if v["privacy"] == "private":
            log("Note: private video - yt-dlp needs YT_COOKIES_FILE for it to download.")
        a.source = v["id"]
    if not a.source:
        ap.error("give a source, or use --channel")

    url = None
    if is_url(a.source):
        url = a.source if a.source.startswith("http") else f"https://www.youtube.com/watch?v={a.source}"
        log("Fetching metadata...")
        probe = run(["yt-dlp", "--no-warnings", "--print", "%(id)s", url], capture_output=True, text=True)
        key = probe.stdout.strip().splitlines()[-1]
    else:
        key = re.sub(r"[^\w-]+", "_", Path(a.source).stem)[:60]
    work = Path(a.cache) / key
    work.mkdir(parents=True, exist_ok=True)

    info, chat = ({}, None)
    if url:
        info, chat = fetch_youtube_meta(url, work)
        log(f"'{info.get('title')}' ({hms(info.get('duration') or 0)})")

    sig_file = work / "signals.json"
    if sig_file.exists():
        cached = json.loads(sig_file.read_text())
        n, sig, cut_times = cached["n"], cached["sig"], cached["cut_times"]
        log("Using cached analysis.")
    else:
        media = download_analysis_copy(url, work) if url else Path(a.source)
        n, sig, cut_times = analyze_media(media, work)
        sig_file.write_text(json.dumps({"n": n, "sig": sig, "cut_times": cut_times}))
        if url:
            media.unlink(missing_ok=True)   # the low-res copy isn't needed any more
        for f in ("video.txt", "audio.txt"):
            (work / f).unlink(missing_ok=True)

    sig = dict(sig)
    if url:
        sig["replay"] = heatmap_signal(info, n)
        sig["chat"] = chat_signal(chat, n)
        sig["comment"] = comment_signal(info, n)

    total, zs = score(sig, n, a.length)
    clips = pick(total, zs, n, a.top, a.length, a.skip_start, a.skip_end, cut_times, a.min_score)
    log("Signals used: " + ", ".join(sorted(zs)) + "  (not available: "
        + (", ".join(sorted(set(WEIGHTS) - set(zs))) or "none") + ")")

    for r in filter(None, a.manual.split(",")):
        s, e = r.split("-")
        clips.append({"rank": len(clips) + 1, "start": parse_ts(s), "end": parse_ts(e),
                      "score": None, "reasons": ["manual"], "manual": True})

    print(f"\n{'#':>2}  {'start':>8}  {'end':>8}  {'score':>6}  why")
    for c in clips:
        sc = f"{c['score']:6.2f}" if c["score"] is not None else "     -"
        print(f"{c['rank']:>2}  {hms(c['start']):>8}  {hms(c['end']):>8}  {sc}  {', '.join(c['reasons']) or '-'}")

    outdir = Path(a.out) / key
    outdir.mkdir(parents=True, exist_ok=True)
    report = {"source": a.source, "title": info.get("title"), "signals": sorted(zs), "clips": clips}

    if not a.list:
        chosen = clips
        if a.pick:
            want = {int(x) for x in a.pick.split(",")}
            chosen = [c for c in clips if c["rank"] in want]
        log(f"\nCutting {len(chosen)} clip(s)...")
        if url:
            cut_youtube(url, chosen, outdir)
        else:
            cut_local(Path(a.source), chosen, outdir)
        for c in chosen:
            print(f"  -> {c.get('file', 'FAILED')}")

    (outdir / "highlights.json").write_text(json.dumps(report, indent=2))
    print(f"\nReport: {outdir / 'highlights.json'}")


if __name__ == "__main__":
    main()
