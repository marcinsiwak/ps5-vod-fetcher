import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
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
    val outDir: File = clipsDir(),
    // Claude Code CLI model alias or full name: haiku (default, lightest on your plan's usage limits), sonnet, opus
    val model: String = System.getenv("CLIP_MODEL") ?: "haiku",
    val maxClips: Int = System.getenv("MAX_CLIPS")?.toInt() ?: 8,
    // "blur" = whole 16:9 frame centred on a blurred fill (keeps HUD/context), "crop" = centre crop to 9:16
    val style: String = System.getenv("CLIP_STYLE") ?: "blur",
    // seconds between sampled frames; lower = finer detection, more usage
    val frameInterval: Int = System.getenv("CLIP_FRAME_INTERVAL")?.toInt() ?: 4,
    // the Claude Code CLI binary
    val claudeBin: String = System.getenv("CLAUDE_BIN") ?: "claude",
) {
    companion object {
        /** On when the `claude` CLI is installed (and CLIP_ENABLED isn't false). Login is checked per VOD (preflight). */
        fun fromEnv(): ClipConfig? {
            if (System.getenv("CLIP_ENABLED")?.lowercase() == "false") return null
            return ClipConfig().takeIf { claudeInstalled(it.claudeBin) }
        }

        private fun claudeInstalled(bin: String) = try {
            ProcessBuilder(bin, "--version").redirectErrorStream(true).start().run {
                inputStream.readAllBytes(); waitFor(30, TimeUnit.SECONDS) && exitValue() == 0
            }
        } catch (e: Exception) { false }
    }
}

// ---------- Structured output schema (Claude fills this in) ----------
@Serializable data class ClipIdea(
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
@Serializable data class ClipIdeas(val clips: List<ClipIdea>)

/** JSON Schema for `claude --json-schema`; the CLI returns a matching object in `structured_output`. */
private val CLIP_SCHEMA = """
{"type":"object","additionalProperties":false,"required":["clips"],"properties":{"clips":{"type":"array","items":{
"type":"object","additionalProperties":false,
"required":["startSec","endSec","category","score","title","caption","hashtags","reason"],
"properties":{
"startSec":{"type":"integer"},"endSec":{"type":"integer"},
"category":{"type":"string","enum":["gameplay","stunt","funny","unexpected"]},
"score":{"type":"integer","minimum":1,"maximum":10},
"title":{"type":"string"},"caption":{"type":"string"},
"hashtags":{"type":"array","items":{"type":"string"}},"reason":{"type":"string"}}}}}}
""".trimIndent().replace("\n", "")

// What we write next to the rendered clips
@Serializable data class ClipOut(
    val file: String, val startSec: Long, val endSec: Long, val category: String,
    val score: Long, val title: String, val caption: String, val hashtags: List<String>, val reason: String,
)

private const val TILE = 3                 // 3x3 contact sheet
private const val SHEETS_PER_REQUEST = 20  // 20 sheets x 9 frames x 4s = 12 min of video per Claude CLI run
private const val MIN_LEN = 10L
private const val MAX_LEN = 90L

private val SYSTEM_PROMPT = """
You are a short-form video editor cutting a PlayStation 5 gameplay stream (a YouTube VOD) into
TikTok / Instagram Reels / YouTube Shorts clips.

You get contact sheets: JPG files in the current directory, each a 3x3 grid of frames sampled at a
fixed interval, read left-to-right, top-to-bottom; the prompt lists each file and its cells' timestamps.
Open every listed sheet with the Read tool before answering (read several at once). You also get
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
    private val json = Json { ignoreUnknownKeys = true }
    private val prettyJson = Json { prettyPrint = true }

    /**
     * Analyse [video] and render the best moments as vertical clips. Returns the output folder.
     * If Claude can't be used (CLI not logged in) a CUT_MANUALLY.txt note is left instead and the VOD
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
            val (picked, extra) = pick(ideas, duration)
            log("Clipper: ${ideas.size} candidates → rendering ${picked.size}, ${extra.size} more worth checking")

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
            writeWorthChecking(File(outDir, WORTH_CHECKING), video, extra)
            log("Clipper: saved ${out.size} clips to ${outDir.path}" +
                if (extra.isNotEmpty()) " (+${extra.size} more moments listed in $WORTH_CHECKING)" else "")
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

    // ---------- 3. ask Claude (Claude Code CLI on your subscription, no API key) ----------
    private fun askClaude(sheets: List<File>, firstSheet: Int, loud: DoubleArray, duration: Double): List<ClipIdea> {
        val perSheet = (TILE * TILE * cfg.frameInterval).toLong()
        val from = firstSheet * perSheet
        val to = min(duration.toLong(), (firstSheet + sheets.size) * perSheet)

        val peakText = peaks(loud, from, to).joinToString("\n") { (s, d) -> "- ${s}s (${fmt(s)}): +%.0f dB".format(d) }
        val prompt = buildString {
            append("Section ${fmt(from)}–${fmt(to)} of a ${fmt(duration.toLong())} video (${from}s–${to}s).\n")
            append("Loudest audio moments (dB above the stream's median):\n${peakText.ifEmpty { "- none stand out" }}\n\n")
            append("Contact sheets (read every one):\n")
            sheets.forEachIndexed { i, sheet ->
                val base = (firstSheet + i) * perSheet
                val times = (0 until TILE * TILE).map { base + it * cfg.frameInterval }
                append("- ${sheet.name} (sheet ${firstSheet + i + 1}), cells at: ${times.joinToString(", ") { "${it}s" }}\n")
            }
            append("\nList the clip-worthy moments in this section.")
        }

        // Other errors propagate: process() then writes nothing and the VOD is retried on the next poll
        val res = callClaude(
            "section ${fmt(from)}–${fmt(to)}", sheets.first().parentFile, prompt,
            "--tools", "Read", "--allowedTools", "Read", "--json-schema", CLIP_SCHEMA,
            "--system-prompt-file", File(sheets.first().parentFile, "system-prompt.txt").apply { writeText(SYSTEM_PROMPT) }.path,
        )
        val structured = res["structured_output"] as? JsonObject
        if (structured == null) {
            log("Clipper: no structured answer for section ${fmt(from)}–${fmt(to)}, skipping " +
                "(${res["result"]?.jsonPrimitive?.contentOrNull?.take(200)})")
            return emptyList()
        }
        val ideas = json.decodeFromJsonElement<ClipIdeas>(structured).clips
        log("Clipper: section ${fmt(from)}–${fmt(to)} → ${ideas.size} candidates")
        return ideas
    }

    // ---------- 4. pick best non-overlapping ----------
    /**
     * Best non-overlapping clips scoring 5+: the top [ClipConfig.maxClips] get rendered, the rest
     * (worth posting but over the limit) are returned second, for the "worth checking" list.
     */
    private fun pick(ideas: List<ClipIdea>, duration: Double): Pair<List<ClipIdea>, List<ClipIdea>> {
        val good = mutableListOf<ClipIdea>()
        for (raw in ideas.sortedByDescending { it.score }) {
            if (raw.score < 5) break
            val start = raw.startSec.coerceIn(0, max(0, duration.toLong() - MIN_LEN))
            val end = raw.endSec.coerceIn(start + MIN_LEN, min(start + MAX_LEN, duration.toLong()))
            val c = raw.copy(startSec = start, endSec = end)
            if (good.none { it.startSec < c.endSec && c.startSec < it.endSec }) good += c
        }
        return good.take(cfg.maxClips).sortedBy { it.startSec } to good.drop(cfg.maxClips).sortedBy { it.startSec }
    }

    /** Moments Claude rated worth posting that didn't make the MAX_CLIPS cut, with time ranges to cut by hand. */
    private fun writeWorthChecking(file: File, video: File, extra: List<ClipIdea>) {
        if (extra.isEmpty()) { file.delete(); return }
        file.writeText(buildString {
            append("# More moments worth checking — ${video.name}\n\n")
            append("Claude rated these worth a short too, but they didn't fit in MAX_CLIPS=${cfg.maxClips}.\n")
            append("Cut them yourself, or re-run with a higher limit: `clip --max N <video>`.\n\n")
            append("| Time range | Length | Score | Category | Title | What happens |\n|---|---|---|---|---|---|\n")
            extra.forEach {
                append("| ${fmt(it.startSec)}–${fmt(it.endSec)} | ${it.endSec - it.startSec}s | ${it.score}/10 | ${it.category} " +
                    "| ${it.title.replace("|", "/")} | ${it.reason.replace("|", "/")} |\n")
            }
        })
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

    // ---------- Claude Code CLI ----------
    /** Tiny request: tells us the CLI isn't logged in before minutes of ffmpeg work. */
    private fun preflight() {
        callClaude("preflight", cfg.outDir.apply { mkdirs() }, "Reply with just: ok", "--tools", "", "--model", "haiku")
    }

    /**
     * Runs `claude -p` headless in [dir] (prompt on stdin) and returns the CLI's JSON result object.
     * ANTHROPIC_API_KEY is removed from its environment so the CLI uses your Claude subscription:
     * your `claude` login on a PC, or CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`) in Docker / on the Pi.
     */
    private fun callClaude(what: String, dir: File, prompt: String, vararg extra: String): JsonObject {
        // a --model in [extra] overrides this one (last flag wins)
        val cmd = (listOf(cfg.claudeBin, "-p", "--output-format", "json", "--model", cfg.model, "--no-session-persistence") + extra)
            .map(::windowsArg)
        val pb = ProcessBuilder(cmd).directory(dir).redirectError(ProcessBuilder.Redirect.INHERIT)
        pb.environment().remove("ANTHROPIC_API_KEY")
        val p = try { pb.start() } catch (e: Exception) {
            throw ClipBlocked(
                "The Claude Code CLI ('${cfg.claudeBin}') could not be started.",
                "Install Claude Code (https://claude.com/claude-code) or set CLAUDE_BIN to its path",
            )
        }
        p.outputStream.bufferedWriter().use { it.write(prompt) }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.MINUTES)) { p.destroyForcibly(); error("claude timed out during $what") }

        val res = out.trim().lines().lastOrNull()?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val isError = res?.get("is_error")?.jsonPrimitive?.booleanOrNull ?: true
        if (res != null && !isError && p.exitValue() == 0) return res

        val msg = res?.get("result")?.jsonPrimitive?.contentOrNull ?: out.take(500).ifBlank { "exit code ${p.exitValue()}" }
        if (Regex("not logged in|/login|invalid api key|authenticat|oauth|\\b401\\b", RegexOption.IGNORE_CASE).containsMatchIn(msg)) {
            throw ClipBlocked(
                "The Claude Code CLI is not logged in ($msg).",
                "Run `claude setup-token` on any PC, put the token in CLAUDE_CODE_OAUTH_TOKEN in vod.env and restart " +
                    "(or just run `claude` once and log in, when clipping on your own PC)",
            )
        }
        // usage limit reached / outage: transient, the VOD is retried on the next poll
        throw IllegalStateException("Claude CLI error during $what: $msg")
    }

    // ---------- helpers ----------
    private fun probeDuration(video: File): Double =
        runCapture("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", video.path)
            .firstOrNull()?.trim()?.toDoubleOrNull() ?: error("ffprobe could not read ${video.name}")
}

/**
 * On Windows, Java's ProcessBuilder doesn't escape double quotes inside arguments, so claude.exe would get
 * the JSON schema with its quotes stripped ("--json-schema is not valid JSON"). Quote + escape such args.
 */
private fun windowsArg(arg: String): String {
    if (!System.getProperty("os.name").startsWith("Windows") || arg.isEmpty()) return arg
    if (arg.none { it == '"' || it == ' ' || it == '\t' }) return arg
    val sb = StringBuilder("\"")
    var backslashes = 0
    for (ch in arg) {
        when (ch) {
            '\\' -> backslashes++
            '"' -> { sb.append("\\".repeat(backslashes * 2 + 1)).append('"'); backslashes = 0 }
            else -> { sb.append("\\".repeat(backslashes)).append(ch); backslashes = 0 }
        }
    }
    return sb.append("\\".repeat(backslashes * 2)).append('"').toString()
}

// ---------- "cut it yourself" fallback ----------
const val MANUAL_NOTE = "CUT_MANUALLY.txt"
const val WORTH_CHECKING = "worth_checking.md"

/** The AI can't run until the user fixes something (CLI missing / not logged in) — not worth retrying every poll. */
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
