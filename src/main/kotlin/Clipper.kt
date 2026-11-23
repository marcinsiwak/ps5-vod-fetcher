import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import kotlin.math.max
import kotlin.math.min

// ---------- Config ----------
fun clipsDir() = File(System.getenv("CLIPS_DIR") ?: "./clips")
fun clipFolder(video: File) = File(clipsDir(), video.nameWithoutExtension.take(100))

/** [base] if it's unused, else base_2, base_3, … (a folder counts as used once it has clips or a note) */
fun nextFreeFolder(base: File): File = generateSequence(1) { it + 1 }
    .map { n -> if (n == 1) base else File(base.parentFile, "${base.name}_$n") }
    .first { !File(it, "clips.json").exists() && !File(it, MANUAL_NOTE).exists() }

data class ClipConfig(
    val apiKey: String,
    val outDir: File = clipsDir(),
    // Haiku: ~$0.02 per hour of stream. claude-opus-5-5 is sharper at "funny" moments but ~40x the price.
    val model: String = System.getenv("CLIP_MODEL") ?: "claude-haiku-5-5",
    val maxClips: Int = System.getenv("MAX_CLIPS")?.toInt() ?: 8,
    // "blur" = whole 16:9 frame centred on a blurred fill (keeps HUD/context), "crop" = centre crop to 9:16
    val style: String = System.getenv("CLIP_STYLE") ?: "blur",
    // seconds between sampled frames; lower = finer detection, more tokens
    val frameInterval: Int = System.getenv("CLIP_FRAME_INTERVAL")?.toInt() ?: 4,
) {
    companion object {
        fun fromEnv(): ClipConfig? = System.getenv("ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() }?.let { ClipConfig(it) }
    }
}

// ---------- Structured output schema (Claude fills this in) ----------
@JvmRecord data class ClipIdea(
    val startSec: Long,
    val endSec: Long,
    /** gameplay | stunt | funny | unexpected */
    val category: String,
    /** 1-10, how likely this does well as a short */
    val score: Long,
    val title: String,
    val caption: String,
    val hashtags: List<String>,
    val reason: String,
)
@JvmRecord data class ClipIdeas(val clips: List<ClipIdea>)

// What we write next to the rendered clips
@Serializable data class ClipOut(
    val file: String, val startSec: Long, val endSec: Long, val category: String,
    val score: Long, val title: String, val caption: String, val hashtags: List<String>, val reason: String,
)

private const val TILE = 3                 // 3x3 contact sheet
private const val SHEETS_PER_REQUEST = 20  // 20 sheets x 9 frames x 4s = 12 min of video per Claude call
private const val MIN_LEN = 10L
private const val MAX_LEN = 90L

private val SYSTEM_PROMPT = """
You are a short-form video editor cutting a PlayStation 5 gameplay stream (a YouTube VOD) into
TikTok / Instagram Reels / YouTube Shorts clips.

You get contact sheets: each image is a 3x3 grid of frames sampled at a fixed interval, read
left-to-right, top-to-bottom; the text before each image gives each cell's timestamp. You also get
the loudest audio moments (a spike usually means an explosion, crash, scream, laugh or hype moment).

Find the moments worth posting:
- gameplay: impressive skill, clutch play, big win, satisfying sequence
- stunt: jumps, crashes, near-misses, physics chaos, flashy movement
- funny: glitches, fails, ragdolls, absurd situations
- unexpected: surprises, jump scares, sudden twists, rare events

Rules:
- Each clip must be self-contained and $MIN_LEN-60 seconds long (up to $MAX_LEN only if the moment really needs it).
  Start 2-4 seconds before the action so viewers get context, end right after the payoff.
- Timestamps are in seconds from the start of the video. Frames are sparse, so infer the action
  between frames and use the audio spikes to place start/end precisely.
- Skip menus, loading screens, pause screens, idle walking/driving, and anything repetitive.
- Score honestly (1-10). Most footage is not clip-worthy; returning zero clips for a dull section is correct.
- title: short hook, max 60 characters. caption: 1-2 punchy sentences for the post. hashtags: 3-6, no '#'.
- reason: one sentence on what happens in the clip.
""".trimIndent()

class Clipper(private val cfg: ClipConfig) {
    private val claude: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(cfg.apiKey)
        .maxRetries(4)
        .build()
    private val prettyJson = Json { prettyPrint = true }

    /**
     * Analyse [video] and render the best moments as vertical clips. Returns the output folder.
     * If the API can't be used (no credit / bad key) a CUT_MANUALLY.txt note is left instead and the VOD
     * is not retried automatically.
     * [newRun] (the manual `clip` command) never skips and never overwrites: if the VOD was clipped before,
     * this run goes to the next free folder (name_2, name_3, …) so earlier clips are kept.
     */
    fun process(video: File, newRun: Boolean = false): File? {
        val base = clipFolder(video)
        val outDir = if (newRun) nextFreeFolder(base) else base
        if (!newRun) {
            if (File(outDir, "clips.json").exists()) {
                log("Clips already exist for ${video.name}, skipping")
                return outDir
            }
            if (File(outDir, MANUAL_NOTE).exists()) return null
        }

        try {
            preflight() // fail fast (and cheaply) before the heavy ffmpeg work
        } catch (e: ClipBlocked) {
            writeManualNote(video, e.message!!, e.fix, outDir)
            return null
        }

        val work = File(outDir, ".work").apply { deleteRecursively(); mkdirs() }
        try {
            val duration = probeDuration(video)
            log("Clipper: ${video.name} (${fmt(duration.toLong())}) — scanning audio + frames…")
            val loudness = loudnessPerSecond(video)
            val sheets = contactSheets(video, work)
            log("Clipper: ${sheets.size} contact sheets, asking ${cfg.model}…")

            val ideas = sheets.chunked(SHEETS_PER_REQUEST).withIndex().flatMap { (i, chunk) ->
                val firstSheet = i * SHEETS_PER_REQUEST
                askClaude(chunk, firstSheet, loudness, duration)
            }
            val picked = pick(ideas, duration)
            log("Clipper: ${ideas.size} candidates → rendering ${picked.size}")

            val out = picked.mapIndexedNotNull { n, c ->
                val name = "%02d_%s_%s.mp4".format(n + 1, c.category, slug(c.title))
                if (render(video, File(outDir, name), c)) {
                    ClipOut(name, c.startSec, c.endSec, c.category, c.score, c.title, c.caption, c.hashtags, c.reason)
                } else null
            }
            File(outDir, "clips.json").writeText(prettyJson.encodeToString(out))
            File(outDir, "captions.md").writeText(out.joinToString("\n\n") {
                "## ${it.file}\n**${it.title}** (${it.category}, ${it.score}/10, ${fmt(it.startSec)}–${fmt(it.endSec)})\n\n" +
                    "${it.caption}\n\n${it.hashtags.joinToString(" ") { h -> "#$h" }}"
            } + "\n")
            log("Clipper: saved ${out.size} clips to ${outDir.path}")
            return outDir
        } catch (e: ClipBlocked) { // e.g. credit ran out mid-VOD
            writeManualNote(video, e.message!!, e.fix, outDir)
            return null
        } finally {
            work.deleteRecursively()
        }
    }

    // ---------- 1. audio loudness (RMS dB per second) ----------
    private fun loudnessPerSecond(video: File): DoubleArray {
        val lines = runCapture(
            "ffmpeg", "-hide_banner", "-nostats", "-i", video.path, "-vn",
            "-af", "aresample=8000,asetnsamples=8000,astats=metadata=1:reset=1," +
                "ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-",
            "-f", "null", "-",
        )
        return lines.filter { it.startsWith("lavfi.astats.Overall.RMS_level=") }
            .map { it.substringAfter('=').toDoubleOrNull()?.takeIf { v -> v.isFinite() } ?: -90.0 }
            .toDoubleArray()
    }

    /** Loudest seconds in [from, to), well above the stream's median level, at least 5s apart. */
    private fun peaks(loud: DoubleArray, from: Long, to: Long): List<Pair<Long, Double>> {
        if (loud.isEmpty()) return emptyList()
        val median = loud.sorted()[loud.size / 2]
        val range = (from.coerceAtLeast(0)..<min(to, loud.size.toLong())).toList()
        val result = mutableListOf<Pair<Long, Double>>()
        for (s in range.sortedByDescending { loud[it.toInt()] }) {
            val db = loud[s.toInt()]
            if (db < median + 8 || result.size >= 15) break
            if (result.none { kotlin.math.abs(it.first - s) < 5 }) result += s to db - median
        }
        return result.sortedBy { it.first }
    }

    // ---------- 2. contact sheets (keyframes only → fast even on a Pi) ----------
    private fun contactSheets(video: File, work: File): List<File> {
        runChecked(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-skip_frame", "nokey", "-i", video.path, "-an",
            "-vf", "fps=1/${cfg.frameInterval},scale=480:270:force_original_aspect_ratio=decrease," +
                "pad=480:270:(ow-iw)/2:(oh-ih)/2,tile=${TILE}x$TILE",
            "-q:v", "5", File(work, "sheet_%04d.jpg").path,
        )
        return work.listFiles { f -> f.name.startsWith("sheet_") }!!.sortedBy { it.name }
    }

    // ---------- 3. ask Claude ----------
    private fun askClaude(sheets: List<File>, firstSheet: Int, loud: DoubleArray, duration: Double): List<ClipIdea> {
        val perSheet = (TILE * TILE * cfg.frameInterval).toLong()
        val from = firstSheet * perSheet
        val to = min(duration.toLong(), (firstSheet + sheets.size) * perSheet)

        val content = mutableListOf<ContentBlockParam>()
        val peakText = peaks(loud, from, to).joinToString("\n") { (s, d) -> "- ${s}s (${fmt(s)}): +%.0f dB".format(d) }
        content += text(
            "Section ${fmt(from)}–${fmt(to)} of a ${fmt(duration.toLong())} video (${from}s–${to}s).\n" +
                "Loudest audio moments (dB above the stream's median):\n" + peakText.ifEmpty { "- none stand out" },
        )
        sheets.forEachIndexed { i, sheet ->
            val base = (firstSheet + i) * perSheet
            val times = (0 until TILE * TILE).map { base + it * cfg.frameInterval }
            content += text("Sheet ${firstSheet + i + 1}, cells at: " + times.joinToString(", ") { "${it}s" })
            content += ContentBlockParam.ofImage(
                ImageBlockParam.builder().source(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(Base64.getEncoder().encodeToString(sheet.readBytes()))
                        .build(),
                ).build(),
            )
        }
        content += text("List the clip-worthy moments in this section.")

        val params = MessageCreateParams.builder()
            .model(cfg.model)
            .maxTokens(16000L)
            .system(SYSTEM_PROMPT)
            .outputConfig(ClipIdeas::class.java) // typed JSON; effort stays at the model default (medium)
            .addUserMessageOfContentBlockParams(content)
            .apply {
                // Opus/Sonnet/Fable: if a safety classifier declines, retry on a fallback model (Haiku has none)
                if (!cfg.model.startsWith("claude-haiku")) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        // Other API errors propagate: process() then writes nothing and the VOD is retried on the next poll
        val msg = callClaude("section ${fmt(from)}–${fmt(to)}") { claude.messages().create(params) }
        when (msg.stopReason().orElse(null)) {
            StopReason.REFUSAL -> { log("Clipper: Claude declined section ${fmt(from)}–${fmt(to)}, skipping"); return emptyList() }
            StopReason.MAX_TOKENS -> { log("Clipper: response cut off for section ${fmt(from)}–${fmt(to)}, skipping"); return emptyList() }
            else -> {}
        }
        val ideas: List<ClipIdea> = msg.content().flatMap { block ->
            block.text().map { t -> t.text().clips }.orElse(emptyList())
        }
        log("Clipper: section ${fmt(from)}–${fmt(to)} → ${ideas.size} candidates " +
            "(${msg.usage().inputTokens()} in / ${msg.usage().outputTokens()} out tokens)")
        return ideas
    }

    // ---------- 4. pick best non-overlapping ----------
    private fun pick(ideas: List<ClipIdea>, duration: Double): List<ClipIdea> {
        val chosen = mutableListOf<ClipIdea>()
        for (raw in ideas.sortedByDescending { it.score }) {
            if (chosen.size >= cfg.maxClips || raw.score < 5) break
            val start = raw.startSec.coerceIn(0, max(0, duration.toLong() - MIN_LEN))
            val end = raw.endSec.coerceIn(start + MIN_LEN, min(start + MAX_LEN, duration.toLong()))
            val c = raw.copy(startSec = start, endSec = end)
            if (chosen.none { it.startSec < c.endSec && c.startSec < it.endSec }) chosen += c
        }
        return chosen.sortedBy { it.startSec }
    }

    // ---------- 5. render vertical 1080x1920 ----------
    private fun render(video: File, out: File, c: ClipIdea): Boolean {
        val vf = when (cfg.style) {
            "crop" -> "scale=-2:1920,crop=1080:1920,setsar=1"
            else -> "split[a][b];" +
                "[a]scale=270:480:force_original_aspect_ratio=increase,crop=270:480,boxblur=8:2,scale=1080:1920[bg];" +
                "[b]scale=1080:-2[fg];[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1"
        }
        val ok = run(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-ss", c.startSec.toString(), "-i", video.path, "-t", (c.endSec - c.startSec).toString(),
            "-filter_complex", vf,
            // Instagram Reels limits: H.264 4:2:0, ≤25 Mbps video, AAC ≤48 kHz / 128 kbps, moov atom first
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "21", "-maxrate", "20M", "-bufsize", "40M",
            "-pix_fmt", "yuv420p", "-fpsmax", "60",
            "-c:a", "aac", "-b:a", "128k", "-ar", "48000", "-movflags", "+faststart", out.path,
        )
        if (!ok) log("Clipper: ffmpeg failed rendering ${out.name}")
        return ok
    }

    // ---------- API access checks ----------
    /** ~10-token request: tells us about missing credit / bad key before minutes of ffmpeg work. */
    private fun preflight() = callClaude("preflight") {
        claude.messages().create(
            MessageCreateParams.builder().model(cfg.model).maxTokens(1L).addUserMessage("ping").build(),
        )
    }

    private fun <T> callClaude(what: String, block: () -> T): T = try {
        block()
    } catch (e: AnthropicServiceException) {
        val msg = e.message.orEmpty()
        when {
            e.statusCode() == 402 || msg.contains("credit balance", ignoreCase = true) -> throw ClipBlocked(
                "Your Anthropic account has no API credit left.",
                "Add credit at https://platform.claude.com → Settings → Billing",
            )
            e.statusCode() == 401 || e.statusCode() == 403 -> throw ClipBlocked(
                "The Anthropic API key was rejected (${e.statusCode()}).",
                "Put a valid key in ANTHROPIC_API_KEY in vod.env (https://platform.claude.com → Settings → API keys) and restart",
            )
            else -> throw IllegalStateException("Claude API error (${e.statusCode()}) during $what: $msg", e)
        }
    }

    // ---------- helpers ----------
    private fun probeDuration(video: File): Double =
        runCapture("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.path)
            .firstOrNull()?.trim()?.toDoubleOrNull() ?: error("ffprobe could not read ${video.name}")

    private fun text(s: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(s).build())
}

// ---------- "cut it yourself" fallback ----------
const val MANUAL_NOTE = "CUT_MANUALLY.txt"

/** The AI can't run until the user fixes something (credit / key) — not worth retrying every poll. */
class ClipBlocked(reason: String, val fix: String) : Exception(reason)

/** Leave a note next to where the clips would be, and shout in the log. */
fun writeManualNote(video: File, reason: String, fix: String, folder: File = clipFolder(video)) {
    val dir = folder.apply { mkdirs() }
    File(dir, MANUAL_NOTE).writeText(
        """
        |No shorts were made automatically for this VOD — cut it yourself.
        |
        |VOD:    ${video.name}
        |Reason: $reason
        |
        |Cut it manually, e.g. in CapCut, DaVinci Resolve, or YouTube Studio → your video → "Create Short".
        |
        |To let the AI do it instead:
        |  1. $fix
        |  2. Run:  docker exec vod-fetcher /app/bin/ps5-vod-fetcher clip /data/vods/${video.name}
        |     (or delete this file — the next poll retries VODs from the last LOOKBACK_HOURS)
        |""".trimMargin(),
    )
    log("⚠ NO SHORTS for ${video.name}: $reason Cut it manually — details in ${File(dir, MANUAL_NOTE).path}")
}

private fun fmt(sec: Long) = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
else "%d:%02d".format(sec / 60, sec % 60)

private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "clip" }

private fun run(vararg cmd: String): Boolean =
    ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start().waitFor() == 0

private fun runChecked(vararg cmd: String) = check(run(*cmd)) { "${cmd[0]} failed" }

private fun runCapture(vararg cmd: String): List<String> {
    val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val lines = p.inputStream.bufferedReader().readLines()
    check(p.waitFor() == 0) { "${cmd[0]} failed" }
    return lines
}
