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

// ---------- Config (env vars) ----------
data class Config(
    val clientId: String = env("YT_CLIENT_ID"),
    val clientSecret: String = env("YT_CLIENT_SECRET"),
    val refreshToken: String = env("YT_REFRESH_TOKEN"),
    val downloadDir: File = File(System.getenv("DOWNLOAD_DIR") ?: "./vods"),
    val stateFile: File = File(System.getenv("STATE_FILE") ?: "./processed.txt"),
    val pollSeconds: Long = System.getenv("POLL_SECONDS")?.toLong() ?: 300,
    // Only grab streams that ended within this window (avoids downloading your whole history on first run)
    val lookbackHours: Long = System.getenv("LOOKBACK_HOURS")?.toLong() ?: 24,
    // Optional: Netscape cookies file, needed only if your broadcasts are PRIVATE (unlisted works without)
    val cookiesFile: String? = System.getenv("YT_COOKIES_FILE"),
    // Optional: command run after a successful download, file path appended as last arg (e.g. upload to S3)
    val postDownloadCmd: String? = System.getenv("POST_DOWNLOAD_CMD"),
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

fun log(msg: String) = println("[${Instant.now()}] $msg")

// ---------- Main loop ----------
fun main() {
    val cfg = Config()
    val yt = YouTube(cfg)
    val processed = cfg.stateFile.takeIf { it.exists() }?.readLines()?.toMutableSet() ?: mutableSetOf()
    log("Started. Polling every ${cfg.pollSeconds}s, saving to ${cfg.downloadDir.absolutePath}")

    while (true) {
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
        Thread.sleep(cfg.pollSeconds * 1000)
    }
}
