import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ---------- Posting queue ----------
// One Reel per posting time (POST_TIMES, default 13:00 and 19:00). VODs are posted oldest first, and a VOD's
// clips best score first; a newer VOD waits until the older one is done. Clips scoring below
// NEVER_EXPIRE_SCORE that weren't posted within POST_MAX_AGE_DAYS of the stream are skipped.

const val NEVER_EXPIRE_SCORE = 7
val POST_GAP: Duration = Duration.ofMinutes(5)

data class PostConfig(
    val times: List<LocalTime>,
    val zone: ZoneId,
    val maxAgeDays: Long,
    /** false = only folders with an APPROVED file join the queue */
    val autoPost: Boolean,
) {
    /** The first posting time strictly after [t], or null when POST_TIMES=off. */
    fun nextSlotAfter(t: ZonedDateTime): ZonedDateTime? {
        val local = t.withZoneSameInstant(zone)
        return (0L..1L).flatMap { d -> times.map { ZonedDateTime.of(local.toLocalDate().plusDays(d), it, zone) } }
            .firstOrNull { it.isAfter(local) }
    }

    companion object {
        fun fromEnv() = PostConfig(
            times = parseTimes(System.getenv("POST_TIMES")?.takeIf { it.isNotBlank() } ?: "13:00,19:00"),
            zone = ZoneId.of(System.getenv("RUN_TZ")?.takeIf { it.isNotBlank() } ?: "Europe/Warsaw"),
            maxAgeDays = System.getenv("POST_MAX_AGE_DAYS")?.takeIf { it.isNotBlank() }?.toLong() ?: 7,
            autoPost = System.getenv("AUTO_POST")?.lowercase() != "false",
        )

        /** "13:00,19:00" (also "9:30") → sorted times; "off" → none. */
        private fun parseTimes(s: String): List<LocalTime> {
            if (s.trim().equals("off", ignoreCase = true)) return emptyList()
            return s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map {
                val (h, m) = (it.split(":") + "0").map { p -> p.trim().toIntOrNull() ?: error("POST_TIMES: bad time '$it'") }
                LocalTime.of(h, m)
            }.distinct().sorted()
        }
    }
}

data class QueueItem(val dir: File, val clip: ClipOut, val streamDate: LocalDate) {
    /** When this clip stops being posted automatically, or null if it never expires. */
    fun expiresAt(pc: PostConfig): ZonedDateTime? =
        if (clip.score >= NEVER_EXPIRE_SCORE || pc.maxAgeDays <= 0) null
        else streamDate.plusDays(pc.maxAgeDays + 1).atStartOfDay(pc.zone)
}

/** Stream date from the folder name (yt-dlp names start with the upload date, yyyyMMdd), else when it was clipped. */
fun streamDate(dir: File, zone: ZoneId): LocalDate =
    Regex("^(\\d{8})_").find(dir.name)?.groupValues?.get(1)
        ?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }
        ?: Instant.ofEpochMilli(File(dir, "clips.json").lastModified()).atZone(zone).toLocalDate()

/**
 * Everything waiting to be posted, in posting order. With [finish], folders with nothing left
 * (all posted, given up, expired or deleted) get POSTED.txt with a summary and leave the queue for good.
 */
fun postQueue(pc: PostConfig, now: ZonedDateTime = ZonedDateTime.now(pc.zone), finish: Boolean = false): List<QueueItem> {
    val dirs = clipsDir().listFiles { f ->
        f.isDirectory && File(f, "clips.json").exists() && !File(f, "POSTED.txt").exists() &&
            (pc.autoPost || File(f, APPROVED_MARKER).exists())
    } ?: return emptyList()

    val queue = mutableListOf<QueueItem>()
    for (dir in dirs.sortedWith(compareBy<File>({ streamDate(it, pc.zone) }, { it.name }))) {
        try {
            val date = streamDate(dir, pc.zone)
            val items = unposted(dir).filter { !gaveUp(dir, it) }.map { QueueItem(dir, it, date) }
                .filter { it.expiresAt(pc)?.isAfter(now) ?: true }
            if (items.isEmpty() && finish) finishFolder(dir, pc, now) else queue += items
        } catch (e: Exception) {
            log("Queue: skipping ${dir.name}: ${e.message}")
        }
    }
    return queue
}

/** Writes POSTED.txt: what happened to each clip. */
private fun finishFolder(dir: File, pc: PostConfig, now: ZonedDateTime, cleared: Boolean = false) {
    val posted = readPosted(dir)
    val date = streamDate(dir, pc.zone)
    val lines = readClips(dir).sortedByDescending { it.score }.map { c ->
        val rec = posted[c.file]
        val status = when {
            rec?.mediaId != null -> "posted ${rec.permalink ?: rec.mediaId}"
            !File(dir, c.file).exists() -> "deleted, not posted"
            gaveUp(dir, c) -> "failed $MAX_ATTEMPTS times: ${rec?.lastError}"
            QueueItem(dir, c, date).expiresAt(pc)?.isAfter(now) == false -> "expired (score ${c.score} < $NEVER_EXPIRE_SCORE, older than ${pc.maxAgeDays} days)"
            cleared -> "cleared from the queue, not posted"
            else -> "not posted"
        }
        "${c.file} (${c.score}/10): $status"
    }
    File(dir, "POSTED.txt").writeText("Posting finished ${Instant.now()}\n\n" + lines.joinToString("\n") + "\n")
    File(dir, APPROVED_MARKER).delete()
    log("Instagram: done with ${dir.name}")
}

/**
 * `queue clear`: drops everything waiting now, so only VODs clipped from now on get posted.
 * Each folder gets POSTED.txt (delete it to put the folder back in the queue); `post <folder>` still works.
 */
fun clearQueue(pc: PostConfig) {
    val now = ZonedDateTime.now(pc.zone)
    val byFolder = postQueue(pc, now).groupBy { it.dir }
    if (byFolder.isEmpty()) return println("The queue is already empty.")
    for ((dir, items) in byFolder) {
        finishFolder(dir, pc, now, cleared = true)
        println("Cleared ${items.size} clip(s) from ${dir.name}")
    }
    println("\nThe queue is empty: only VODs clipped from now on will be posted.")
    println("To put a folder back: delete its POSTED.txt. To post one clip anyway: post <folder>.")
}

/** Posts the first clip in the queue (one per posting time). */
fun postNext(ig: Instagram, pc: PostConfig) {
    val next = postQueue(pc, finish = true).firstOrNull() ?: return log("Instagram: posting time, but the queue is empty")
    try {
        ig.account() // a bad token is not the clip's fault: don't burn one of its attempts
    } catch (e: Exception) {
        return log("Instagram: can't post, token problem (fix IG_ACCESS_TOKEN): ${e.message}")
    }
    ig.postClip(next.dir, next.clip)
    postQueue(pc, finish = true) // close the folder right away if that was its last clip
}

/** `queue` command: what will be posted when. Assumes every post succeeds. */
fun printQueue(pc: PostConfig) {
    val now = ZonedDateTime.now(pc.zone)
    val q = postQueue(pc, now)
    val fmt = DateTimeFormatter.ofPattern("EEE dd.MM HH:mm", java.util.Locale.ENGLISH)
    println(if (pc.times.isEmpty()) "Scheduled posting is OFF (POST_TIMES=off). Post by hand with: post <folder>"
        else "Posting at ${pc.times.joinToString(", ")} (${pc.zone}), one Reel each. " +
            "Clips under $NEVER_EXPIRE_SCORE/10 expire ${pc.maxAgeDays} days after the stream" +
            if (pc.maxAgeDays <= 0) " (never: POST_MAX_AGE_DAYS=0)" else ".")
    if (!pc.autoPost) println("AUTO_POST=false: only folders with an $APPROVED_MARKER file are queued.")
    if (q.isEmpty()) return println("\nThe queue is empty.")
    println()
    var from = now
    for (item in q) {
        val c = item.clip
        val slot = pc.nextSlotAfter(from)
        val exp = item.expiresAt(pc)
        val name = "${item.dir.name}/${c.file}"
        when {
            slot == null -> println("  (manual)        ${c.score}/10  $name")
            exp != null && !exp.isAfter(slot) -> println("  (expires ${exp.format(fmt)} before its turn)  ${c.score}/10  $name")
            else -> {
                println("  ${slot.format(fmt)}  ${c.score}/10  $name" + (exp?.let { "  [expires ${it.format(fmt)}]" } ?: ""))
                println("                   ${c.caption.lines().first().take(80)}")
                from = slot
            }
        }
    }
}

/**
 * At least [POST_GAP] between two Reels, also between the service and a manual `post` run
 * (separate processes), via a timestamp file in the clips folder.
 */
object PostGap {
    private val file get() = File(clipsDir(), ".last_post")

    fun waitTurn() {
        val last = runCatching { Instant.parse(file.readText().trim()) }.getOrNull() ?: return
        val wait = Duration.between(Instant.now(), last.plus(POST_GAP))
        if (wait.isNegative || wait.isZero) return
        log("Instagram: waiting ${wait.toSeconds()}s (${POST_GAP.toMinutes()} minutes between Reels)…")
        Thread.sleep(wait.toMillis())
    }

    fun markPosted() {
        runCatching { clipsDir().mkdirs(); file.writeText(Instant.now().toString()) }
    }
}
