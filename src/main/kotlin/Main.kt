import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// ---------- Config (env vars) ----------
data class Config(
    val clientId: String = env("YT_CLIENT_ID"),
    val clientSecret: String = env("YT_CLIENT_SECRET"),
    val refreshToken: String = env("YT_REFRESH_TOKEN"),
    val downloadDir: File = File(System.getenv("DOWNLOAD_DIR") ?: "./vods"),
    val stateFile: File = File(System.getenv("STATE_FILE") ?: "./processed.txt"),
    val pollSeconds: Long = System.getenv("POLL_SECONDS")?.toLong() ?: 300,
    // Run the whole cycle (fetch → download → clip → post) once a day at this local time.
    // RUN_AT=off (or empty) falls back to polling every POLL_SECONDS.
    val runAt: LocalTime? = (System.getenv("RUN_AT") ?: "04:00").trim()
        .takeUnless { it.isEmpty() || it.equals("off", ignoreCase = true) }?.let(LocalTime::parse),
    val runZone: ZoneId = ZoneId.of(System.getenv("RUN_TZ")?.takeIf { it.isNotBlank() } ?: "Europe/Warsaw"),
    // Only grab streams that ended within this window (avoids downloading your whole history on first run).
    // Daily mode defaults to 48 h so a VOD that wasn't ready yet still gets picked up the next day.
    val lookbackHours: Long = System.getenv("LOOKBACK_HOURS")?.toLong() ?: if (runAt != null) 48 else 24,
    // Optional: Netscape cookies file, needed only if your broadcasts are PRIVATE (unlisted works without)
    val cookiesFile: String? = System.getenv("YT_COOKIES_FILE"),
    // Optional: command run after a successful download, file path appended as last arg (e.g. upload to S3)
    val postDownloadCmd: String? = System.getenv("POST_DOWNLOAD_CMD"),
    // Delete downloaded VODs this many days after download (0 = keep forever). Clips are kept.
    val vodRetentionDays: Long = System.getenv("VOD_RETENTION_DAYS")?.takeIf { it.isNotBlank() }?.toLong() ?: 7,
)

fun env(name: String) = System.getenv(name) ?: error("Missing env var $name")

// ---------- API models ----------
private val json = Json { ignoreUnknownKeys = true }

@Serializable data class TokenResponse(val access_token: String, val expires_in: Long)
@Serializable data class BroadcastList(val items: List<Broadcast> = emptyList())
@Serializable data class Broadcast(val id: String, val snippet: Snippet)
@Serializable data class Snippet(val title: String, val actualEndTime: String? = null)

// ---------- YouTube client ----------
class YouTube(private val cfg: Config) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
    private var token: String? = null
    private var tokenExpiry: Instant = Instant.EPOCH

    private fun accessToken(): String {
        if (token != null && Instant.now().isBefore(tokenExpiry)) return token!!
        val form = mapOf(
            "client_id" to cfg.clientId,
            "client_secret" to cfg.clientSecret,
            "refresh_token" to cfg.refreshToken,
            "grant_type" to "refresh_token",
        ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }

        val res = http.send(
            HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "Token refresh failed: ${res.body()}" }
        val t = json.decodeFromString<TokenResponse>(res.body())
        token = t.access_token
        tokenExpiry = Instant.now().plusSeconds(t.expires_in - 60)
        return t.access_token
    }

    /** Your own broadcasts that have ended. broadcastType=all catches both event + persistent (PS5) streams. */
    fun completedBroadcasts(): List<Broadcast> {
        val url = "https://www.googleapis.com/youtube/v3/liveBroadcasts" +
            "?part=snippet&broadcastStatus=completed&broadcastType=all&maxResults=25"
        val res = http.send(
            HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer ${accessToken()}").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(res.statusCode() == 200) { "liveBroadcasts.list failed (${res.statusCode()}): ${res.body()}" }
        return json.decodeFromString<BroadcastList>(res.body()).items
    }
}

// ---------- Download ----------
fun download(cfg: Config, b: Broadcast): File? {
    cfg.downloadDir.mkdirs()
    val cmd = mutableListOf(
        "yt-dlp",
        "-f", "bv*+ba/b",
        "--merge-output-format", "mp4",
        "--no-progress",
        "--no-mtime", // file date = download date, used by clipPending and deleteOldVods
        "--print", "after_move:filepath",
        "-o", "${cfg.downloadDir.absolutePath}/%(upload_date)s_%(title).80B_%(id)s.%(ext)s",
    )
    cfg.cookiesFile?.let { cmd += listOf("--cookies", it) }
    cmd += "https://www.youtube.com/watch?v=${b.id}"

    val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = p.inputStream.bufferedReader().readText().trim()
    return if (p.waitFor() == 0 && out.isNotEmpty()) File(out.lines().last()) else null
}

fun runPostCmd(cfg: Config, file: File) {
    val base = cfg.postDownloadCmd ?: return
    val p = ProcessBuilder(listOf("sh", "-c", "$base \"\$1\"", "sh", file.absolutePath)).inheritIO().start()
    if (p.waitFor() != 0) log("Post-download command failed for ${file.name}")
}

/** Deletes VODs (and leftover partial downloads) older than VOD_RETENTION_DAYS. processed.txt keeps them from being re-downloaded. */
fun deleteOldVods(cfg: Config) {
    if (cfg.vodRetentionDays <= 0) return
    val cutoff = System.currentTimeMillis() - cfg.vodRetentionDays * 86_400_000
    val videoExt = setOf("mp4", "mkv", "webm", "part", "ytdl")
    cfg.downloadDir.listFiles { f -> f.isFile && f.extension in videoExt && f.lastModified() < cutoff }?.forEach { f ->
        if (f.delete()) log("Deleted ${f.name} (older than ${cfg.vodRetentionDays} days)")
        else log("Could not delete ${f.name}")
    }
}

fun log(msg: String) = println("[${Instant.now()}] $msg")

// ---------- Clipping ----------
/** Clip every recent VOD that has no clips.json yet — new downloads plus earlier failures (retried each poll). */
fun clipPending(cfg: Config, clipper: Clipper?) {
    val cutoff = System.currentTimeMillis() - cfg.lookbackHours * 3_600_000
    val vods = cfg.downloadDir.listFiles { f -> f.extension == "mp4" && f.lastModified() > cutoff } ?: return
    for (vod in vods.sortedBy { it.lastModified() }) {
        if (clipper == null) { // no Claude CLI: just tell the user once per VOD
            if (!clipFolder(vod).exists()) writeManualNote(
                vod, "AI clipping is off (the Claude Code CLI is not installed, or CLIP_ENABLED=false).",
                "Install Claude Code (https://claude.com/claude-code), log in (or set CLAUDE_CODE_OAUTH_TOKEN) and restart",
            )
            continue
        }
        try {
            clipper.process(vod)
        } catch (e: Exception) {
            log("Clipper failed for ${vod.name}, will retry next poll: ${e.message}")
        }
    }
}

// ---------- Main loop ----------
fun main(args: Array<String>) {
    if (args.isEmpty()) return service()
    // Manual commands: print a clean error instead of a stack trace
    try {
        command(args)
    } catch (e: Exception) {
        log("Error: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}

/** Pulls `--max N` out of [rest] (if present) into the clip config. */
fun clipConfigWithMax(rest: MutableList<String>): ClipConfig {
    var cfg = ClipConfig.fromEnv() ?: error("The Claude Code CLI (claude) is not installed, or CLIP_ENABLED=false")
    val i = rest.indexOf("--max")
    if (i >= 0) {
        val n = rest.getOrNull(i + 1)?.toIntOrNull()?.takeIf { it > 0 } ?: error("--max needs a number, e.g. --max 5")
        cfg = cfg.copy(maxClips = n)
        rest.subList(i, i + 2).clear()
    }
    return cfg
}

/** A VOD already downloaded to DOWNLOAD_DIR (file names end in _<video id>.mp4). */
/** The YouTube video id in [s]: a bare id, or a watch / youtu.be / live / shorts link. Null if [s] isn't one. */
fun videoId(s: String): String? {
    val id = "[A-Za-z0-9_-]{11}"
    Regex("^$id$").find(s)?.let { return it.value }
    return Regex("(?:[?&]v=|youtu\\.be/|/live/|/shorts/|/embed/)($id)").find(s)?.groupValues?.get(1)
}

/**
 * Resolves [which] (number from `list`, video id or YouTube link) and returns the downloaded VOD,
 * downloading it if needed. [force] deletes an existing copy and downloads it again.
 * The id goes into processed.txt, so the service won't download it a second time.
 */
fun fetchVod(cfg: Config, which: String, force: Boolean): File {
    val b = which.toIntOrNull()?.let { n ->
        val vods = YouTube(cfg).completedBroadcasts()
        vods.getOrNull(n - 1)?.also { log("VOD $n: '${it.snippet.title}' (${it.id})") }
            ?: error("No VOD number $n (there are ${vods.size}; see `list`)")
    } ?: videoId(which)?.let { Broadcast(it, Snippet(title = it)).also { b -> log("VOD ${b.id}") } }
        ?: error("'$which' is not a number from `list`, a video id or a YouTube link")

    downloadedVod(cfg, b)?.let { existing ->
        if (!force) return existing.also { log("Already downloaded: ${it.name} (add --force to download it again)") }
        log("--force: deleting ${existing.name} and downloading again")
        check(existing.delete()) { "Could not delete ${existing.absolutePath}" }
    }
    log("Downloading…")
    val file = download(cfg, b)
        ?: error("Download failed for ${b.id} (if the stream just ended, YouTube may still be processing it)")
    val processed = cfg.stateFile.takeIf { it.exists() }?.readLines()?.map { it.trim() }.orEmpty()
    if (b.id !in processed) cfg.stateFile.apply { absoluteFile.parentFile?.mkdirs() }.appendText("${b.id}\n")
    return file
}

fun downloadedVod(cfg: Config, b: Broadcast): File? =
    cfg.downloadDir.listFiles { f -> f.extension == "mp4" && f.nameWithoutExtension.endsWith("_${b.id}") }?.firstOrNull()

fun command(args: Array<String>) {
    // Manual mode: ps5-vod-fetcher clip [--max N] <video.mp4> [more.mp4 ...]
    if (args.firstOrNull() == "clip") {
        val rest = args.drop(1).toMutableList()
        val clipper = Clipper(clipConfigWithMax(rest))
        if (rest.isEmpty()) error("Usage: clip [--max N] <video.mp4> [more.mp4 ...]")
        rest.forEach { clipper.process(File(it), newRun = true) }
        return
    }
    // Numbered list of your finished streams (newest first): ps5-vod-fetcher list
    if (args.firstOrNull() == "list") {
        val cfg = Config()
        val vods = YouTube(cfg).completedBroadcasts()
        if (vods.isEmpty()) return println("No finished streams found on your channel.")
        vods.forEachIndexed { n, b ->
            val ended = b.snippet.actualEndTime?.take(16)?.replace('T', ' ') ?: "?"
            val mark = if (downloadedVod(cfg, b) != null) "  [downloaded]" else ""
            println("%3d. %s  %s  (%s)%s".format(n + 1, ended, b.snippet.title, b.id, mark))
        }
        return
    }
    // Download one VOD: ps5-vod-fetcher download <number from list | video id | YouTube link> [--force]
    if (args.firstOrNull() == "download") {
        val rest = args.drop(1).toMutableList()
        val force = rest.remove("--force")
        val which = rest.singleOrNull() ?: error("Usage: download <number from list | video id | YouTube link> [--force]")
        val cfg = Config()
        val file = fetchVod(cfg, which, force)
        log("VOD file: ${file.absolutePath} (${file.length() / 1_048_576} MB)")
        return
    }
    // Download (if needed) and clip one VOD:
    // ps5-vod-fetcher clip-vod <number from list | video id | YouTube link> [--max N] [--force]
    // --force downloads it again even if it's already there.
    if (args.firstOrNull() == "clip-vod") {
        val rest = args.drop(1).toMutableList()
        val force = rest.remove("--force")
        val clipCfg = clipConfigWithMax(rest)
        val which = rest.singleOrNull()
            ?: error("Usage: clip-vod <number from list | video id | YouTube link> [--max N] [--force]")
        val cfg = Config()
        val file = fetchVod(cfg, which, force)
        val out = Clipper(clipCfg).process(file, newRun = true)
        log(if (out != null) "Done → ${out.absolutePath}" else "No clips made, see the note above")
        return
    }
    // Post now: ps5-vod-fetcher post <clips folder> [more folders ...] [--all]
    // Posts each folder's next clip (best first), or every remaining clip with --all (5 minutes apart).
    if (args.firstOrNull() == "post") {
        val all = "--all" in args
        val dirs = args.drop(1).filter { it != "--all" }
        if (dirs.isEmpty()) error("Usage: post <clips folder> [more folders ...] [--all]")
        val ig = Instagram.fromEnv() ?: error("Set IG_ACCESS_TOKEN to post to Instagram")
        val (_, username) = ig.account()
        log("Instagram: posting as @$username")
        dirs.forEach { ig.postFolder(File(it), all) }
        return
    }
    // What gets posted when: ps5-vod-fetcher queue
    // Drop everything waiting, post only new VODs from now on: ps5-vod-fetcher queue clear
    if (args.firstOrNull() == "queue") {
        return when (args.getOrNull(1)) {
            null -> printQueue(PostConfig.fromEnv())
            "clear" -> clearQueue(PostConfig.fromEnv())
            else -> error("Usage: queue | queue clear")
        }
    }
    // Check the Instagram token: ps5-vod-fetcher ig-check
    if (args.firstOrNull() == "ig-check") {
        val ig = Instagram.fromEnv() ?: error("Set IG_ACCESS_TOKEN first")
        val (id, username) = ig.account()
        log("Instagram token OK: @$username (account id $id)")
        return
    }
    error("Unknown command '${args.first()}'. Use: list | download <n|id|link> [--force] | clip-vod <n|id|link> [--max N] [--force] | clip [--max N] <video...> | post <clips folder...> [--all] | queue [clear] | ig-check")
}

// ---------- Service: fetch + clip (daily or polling), post one Reel per posting time ----------
fun service() {
    val cfg = Config()
    val yt = YouTube(cfg)
    val clipper = ClipConfig.fromEnv()?.let { Clipper(it) }
    val processed = cfg.stateFile.takeIf { it.exists() }?.readLines()?.toMutableSet() ?: mutableSetOf()
    val pc = PostConfig.fromEnv()
    log(if (cfg.runAt != null) "Started. Fetching daily at ${cfg.runAt} ${cfg.runZone}, saving to ${cfg.downloadDir.absolutePath}"
        else "Started. Polling every ${cfg.pollSeconds}s, saving to ${cfg.downloadDir.absolutePath}")
    log(if (cfg.vodRetentionDays > 0) "VODs are deleted ${cfg.vodRetentionDays} days after download (VOD_RETENTION_DAYS=0 keeps them)"
        else "VODs are kept forever (VOD_RETENTION_DAYS=0)")
    log(if (clipper != null) "Auto-clipping ON (shorts → ${ClipConfig.fromEnv()!!.outDir.absolutePath})"
        else "Auto-clipping OFF (Claude Code CLI not installed, or CLIP_ENABLED=false)")
    val ig = Instagram.fromEnv()
    log(when {
        ig == null -> "Instagram posting OFF (set IG_ACCESS_TOKEN to enable)"
        pc.times.isEmpty() -> "Instagram: scheduled posting OFF (POST_TIMES=off), post by hand with `post <folder>`"
        else -> "Instagram: one Reel at ${pc.times.joinToString(", ")} ${pc.zone}, best clips first, oldest VOD first" +
            (if (pc.autoPost) "" else " (only folders with an $APPROVED_MARKER file)") + ". See the plan with `queue`."
    })
    val posting = ig != null && pc.times.isNotEmpty()
    logQueue(pc)

    var nextFetch = if (cfg.runAt != null) nextAt(cfg.runAt, cfg.runZone) else ZonedDateTime.now(cfg.runZone)
    var lastSlotCheck = ZonedDateTime.now(pc.zone)
    while (true) {
        val nextSlot = if (posting) pc.nextSlotAfter(lastSlotCheck) else null
        val wake = listOfNotNull(nextFetch, nextSlot).minOrNull()!!
        val wait = Duration.between(ZonedDateTime.now(), wake)
        if (wait.toMinutes() >= 1) log("Next: " + listOfNotNull(
            "fetch ${nextFetch.withZoneSameInstant(cfg.runZone).toLocalDateTime()}",
            nextSlot?.let { "post ${it.toLocalDateTime()}" },
        ).joinToString(", "))
        if (!wait.isNegative) Thread.sleep(wait.toMillis())

        if (!ZonedDateTime.now().isBefore(nextFetch)) {
            fetchAndClip(cfg, yt, clipper, processed)
            nextFetch = if (cfg.runAt != null) nextAt(cfg.runAt, cfg.runZone)
                else ZonedDateTime.now(cfg.runZone).plusSeconds(cfg.pollSeconds)
        }
        if (posting) {
            // A posting time passed (maybe while clipping ran): post one Reel. Missed times don't pile up.
            val now = ZonedDateTime.now(pc.zone)
            val due = pc.nextSlotAfter(lastSlotCheck)?.let { !it.isAfter(now) } == true
            lastSlotCheck = now
            if (due) try { postNext(ig!!, pc) } catch (e: Exception) { log("Instagram: error: ${e.message}") }
        }
    }
}

/** Check YouTube for finished streams, download new ones, clip pending VODs, delete old VODs. */
fun fetchAndClip(cfg: Config, yt: YouTube, clipper: Clipper?, processed: MutableSet<String>) {
    if (cfg.runAt != null) log("Daily run starting")
    try {
        val cutoff = Instant.now().minus(Duration.ofHours(cfg.lookbackHours))
        val fresh = yt.completedBroadcasts().filter { b ->
            b.id !in processed &&
                b.snippet.actualEndTime?.let { Instant.parse(it).isAfter(cutoff) } == true
        }
        for (b in fresh) {
            log("New finished stream: '${b.snippet.title}' (${b.id}) — downloading…")
            val file = download(cfg, b)
            if (file != null) {
                log("Saved ${file.name}")
                runPostCmd(cfg, file)
                processed += b.id
                cfg.stateFile.appendText("${b.id}\n")
            } else {
                // VOD often needs a few minutes of processing after the stream ends — retry next poll
                log("Download not ready/failed for ${b.id}, will retry")
            }
        }
    } catch (e: Exception) {
        log("Error: ${e.message}")
    }
    clipPending(cfg, clipper)
    try { deleteOldVods(cfg) } catch (e: Exception) { log("Cleanup error: ${e.message}") }
}

/** The next [time] in [zone] after now (DST-aware). */
fun nextAt(time: LocalTime, zone: ZoneId): ZonedDateTime {
    val now = ZonedDateTime.now(zone)
    val today = now.with(time).withSecond(0).withNano(0)
    return if (today.isAfter(now)) today else now.plusDays(1).with(time).withSecond(0).withNano(0)
}
