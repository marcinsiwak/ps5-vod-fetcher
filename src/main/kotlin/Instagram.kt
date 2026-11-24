import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

// Two token kinds:
//  IGAA… = Instagram API with Instagram Login (graph.instagram.com, no Facebook Page)
//  EAA…  = Instagram API with Facebook Login (graph.facebook.com, Instagram linked to a Facebook Page)
private const val IG_API = "https://graph.instagram.com/v27.0"
private const val FB_API = "https://graph.facebook.com/v27.0"
private const val MAX_ATTEMPTS = 3
const val APPROVED_MARKER = "APPROVED"

private val igJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** Persisted token: [seed] is the IG_ACCESS_TOKEN it came from, so pasting a new token in vod.env takes over. */
@Serializable data class IgToken(val seed: String, val token: String, val refreshedAt: Long)

/** One entry per clip file in posted.json */
@Serializable data class PostRecord(
    val mediaId: String? = null,
    val permalink: String? = null,
    val postedAt: String? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
)

class Instagram(private val envToken: String, private val tokenFile: File) {
    companion object {
        fun fromEnv(): Instagram? = System.getenv("IG_ACCESS_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Instagram(it, File(System.getenv("IG_TOKEN_FILE") ?: "./ig_token.json"))
        }
    }

    private val fb = !envToken.startsWith("IG") // IGAA… = Instagram Login; EAA…/EAB… = Facebook Login
    private val api = if (fb) FB_API else IG_API
    // Facebook tokens can only be renewed with the app's id + secret (optional; else extend it by hand every 60 days)
    private val fbAppId = System.getenv("FB_APP_ID")?.trim()?.takeIf { it.isNotEmpty() }
    private val fbAppSecret = System.getenv("FB_APP_SECRET")?.trim()?.takeIf { it.isNotEmpty() }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    private var account: Pair<String, String>? = null // user_id, username

    // ---------- token (long-lived = 60 days; refreshed weekly so it never lapses) ----------
    private fun token(): String {
        val stored = tokenFile.takeIf { it.exists() }
            ?.let { runCatching { igJson.decodeFromString<IgToken>(it.readText()) }.getOrNull() }
        var t = if (stored == null || stored.seed != envToken) {
            IgToken(envToken, envToken, System.currentTimeMillis()).also(::save)
        } else stored

        val canRefresh = !fb || (fbAppId != null && fbAppSecret != null)
        if (canRefresh && System.currentTimeMillis() - t.refreshedAt > Duration.ofDays(7).toMillis()) {
            try {
                val r = if (fb) getJson("$FB_API/oauth/access_token?grant_type=fb_exchange_token&client_id=${enc(fbAppId!!)}" +
                    "&client_secret=${enc(fbAppSecret!!)}&fb_exchange_token=${enc(t.token)}")
                else getJson("https://graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token&access_token=${enc(t.token)}")
                t = t.copy(token = r.str("access_token"), refreshedAt = System.currentTimeMillis())
                save(t)
                log("Instagram: access token refreshed (valid another 60 days)")
            } catch (e: Exception) {
                log("Instagram: token refresh failed, will retry next time: ${e.message}")
            }
        }
        return t.token
    }

    private fun save(t: IgToken) {
        tokenFile.absoluteFile.parentFile?.mkdirs()
        tokenFile.writeText(igJson.encodeToString(t))
    }

    /** The professional account the token belongs to. Also a cheap "is the token OK?" check. */
    fun account(): Pair<String, String> = account ?: (if (fb) fbAccount() else
        getJson("$IG_API/me?fields=user_id,username&access_token=${enc(token())}").let { it.str("user_id") to it.str("username") })
        .also { account = it }

    /**
     * Facebook Login: either a system-user token (Facebook Login for Business, "Instagram API (Unified Login)")
     * that has the Instagram account assigned directly, or a user token that reaches it through a linked Page.
     */
    private fun fbAccount(): Pair<String, String> {
        val tok = enc(token())
        val assigned = getJson("$FB_API/me/assigned_instagram_accounts?access_token=$tok")["data"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.str("id")
        if (assigned != null) return assigned to getJson("$FB_API/$assigned?fields=username&access_token=$tok").str("username")

        val pages = getJson("$FB_API/me/accounts?fields=name,instagram_business_account%7Bid,username%7D&access_token=$tok")["data"]
            ?.jsonArray ?: JsonArray(emptyList())
        val ig = pages.firstNotNullOfOrNull { it.jsonObject["instagram_business_account"]?.jsonObject }
            ?: error("This Facebook token has no Instagram account assigned and sees ${pages.size} Page(s) with none linked. " +
                "Use a Facebook Login for Business token with your Instagram account selected (README → Instagram)")
        return ig.str("id") to ig.str("username")
    }

    // ---------- publish one Reel from a local file ----------
    /** Returns media id + permalink. Upload goes straight from disk (resumable upload) — no public URL needed. */
    fun postReel(video: File, caption: String): Pair<String, String?> {
        val tok = token()
        val (igId, _) = account()

        // 1. container
        val container = postForm(
            "$api/$igId/media",
            mapOf(
                "media_type" to "REELS",
                "upload_type" to "resumable",
                "caption" to caption,
                "share_to_feed" to "true",
                "access_token" to tok,
            ),
        )
        val containerId = container.str("id")
        val uploadUri = container["uri"]?.jsonPrimitive?.content
            ?: "https://rupload.facebook.com/ig-api-upload/v27.0/$containerId"

        // 2. upload the bytes
        val up = http.send(
            HttpRequest.newBuilder(URI(uploadUri))
                .timeout(Duration.ofMinutes(10))
                .header("Authorization", "OAuth $tok")
                .header("offset", "0")
                .header("file_size", video.length().toString())
                .POST(HttpRequest.BodyPublishers.ofFile(video.toPath()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(up.statusCode() == 200) { "upload failed (${up.statusCode()}): ${up.body()}" }

        // 3. wait for Instagram to process it
        val deadline = Instant.now().plus(Duration.ofMinutes(10))
        while (true) {
            val s = getJson("$api/$containerId?fields=status_code,status&access_token=${enc(tok)}")
            when (val code = s["status_code"]?.jsonPrimitive?.content) {
                "FINISHED" -> break
                "ERROR", "EXPIRED" -> error("Instagram rejected the video ($code): ${s["status"]?.jsonPrimitive?.content}")
                else -> check(Instant.now().isBefore(deadline)) { "processing timed out (last status: $code)" }
            }
            Thread.sleep(15_000)
        }

        // 4. publish
        val mediaId = postForm("$api/$igId/media_publish", mapOf("creation_id" to containerId, "access_token" to tok)).str("id")
        val permalink = runCatching {
            getJson("$api/$mediaId?fields=permalink&access_token=${enc(tok)}")["permalink"]?.jsonPrimitive?.content
        }.getOrNull()
        return mediaId to permalink
    }

    /**
     * Post every clip in a clips folder that still exists (deleted = rejected in review) and isn't posted yet.
     * Progress lives in posted.json, so re-running never double-posts. Returns true when nothing is left to retry.
     */
    fun postFolder(dir: File): Boolean {
        account() // token problems throw here, before any clip burns an attempt
        val clips = igJson.decodeFromString<List<ClipOut>>(File(dir, "clips.json").readText())
        val postedFile = File(dir, "posted.json")
        val posted = postedFile.takeIf { it.exists() }
            ?.let { igJson.decodeFromString<Map<String, PostRecord>>(it.readText()) }?.toMutableMap() ?: mutableMapOf()

        var pending = false
        for (c in clips) {
            val rec = posted[c.file] ?: PostRecord()
            val file = File(dir, c.file)
            if (rec.mediaId != null || rec.attempts >= MAX_ATTEMPTS || !file.exists()) continue

            log("Instagram: posting ${c.file}…")
            try {
                val (mediaId, link) = postReel(file, caption(c))
                posted[c.file] = rec.copy(mediaId = mediaId, permalink = link, postedAt = Instant.now().toString(),
                    attempts = rec.attempts + 1, lastError = null)
                log("Instagram: posted ${c.file} → ${link ?: mediaId}")
            } catch (e: Exception) {
                val r = rec.copy(attempts = rec.attempts + 1, lastError = e.message)
                posted[c.file] = r
                log("Instagram: failed ${c.file} (attempt ${r.attempts}/$MAX_ATTEMPTS): ${e.message}")
                if (r.attempts < MAX_ATTEMPTS) pending = true
            }
            postedFile.writeText(igJson.encodeToString(posted.toMap()))
        }
        return !pending
    }

    // ---------- http helpers ----------
    private fun getJson(url: String): JsonObject = parse(
        http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(1)).GET().build(), HttpResponse.BodyHandlers.ofString()),
    )

    private fun postForm(url: String, form: Map<String, String>): JsonObject = parse(
        http.send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ),
    )

    private fun parse(res: HttpResponse<String>): JsonObject {
        val body = runCatching { igJson.parseToJsonElement(res.body()).jsonObject }.getOrNull()
        check(res.statusCode() == 200 && body != null) { "Instagram API ${res.statusCode()}: ${res.body().take(500)}" }
        return body!!
    }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: error("Instagram API: no '$key' in $this")
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/**
 * Reel caption: hook line, keyword-rich text with a call to action, then max 5 hashtags at the end
 * (Instagram's limit). Edit title / caption / hashtags in clips.json before approving.
 */
fun caption(c: ClipOut) = listOf(c.title, c.caption, cleanHashtags(c.hashtags).joinToString(" ") { "#$it" })
    .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n").take(2200)

/**
 * Post clip folders that aren't finished yet (no POSTED.txt):
 *  - every folder whose clips.json was written after [autoSince] (auto-posting; null = off), and
 *  - any folder with an APPROVED file (manual approval, e.g. for older folders).
 * Delete a clip's .mp4 before the run to keep it from being posted. Finished folders get POSTED.txt.
 */
fun postPending(ig: Instagram, autoSince: Instant?) {
    val dirs = clipsDir().listFiles { f ->
        val clips = File(f, "clips.json")
        f.isDirectory && clips.exists() && !File(f, "POSTED.txt").exists() &&
            (File(f, APPROVED_MARKER).exists() || (autoSince != null && clips.lastModified() > autoSince.toEpochMilli()))
    } ?: return
    for (dir in dirs.sortedBy { it.name }) {
        try {
            if (ig.postFolder(dir)) {
                File(dir, APPROVED_MARKER).delete()
                File(dir, "POSTED.txt").writeText("Posting finished ${Instant.now()} — see posted.json for links/errors.\n")
                log("Instagram: done with ${dir.name}")
            }
        } catch (e: Exception) {
            log("Instagram: error in ${dir.name}, will retry next run: ${e.message}")
        }
    }
}
