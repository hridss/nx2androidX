package com.nx.android.appstate

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the player is right now: the page, the thing on that page, and the facts that page carries.
 *
 * The App knows this; the assistant cannot see it. The App writes it down (see [PageStateStore]) and the
 * assistant reads the file whenever it needs it — no page state rides along in the player's words.
 *
 * A plain value, no Android: the JVM tests drive it.
 */
data class PageState(
    /** The page the player is on: "library", "builds", "game" ('' when there is none). */
    val page: String = "",
    /** The title ID of the thing on that page (the selected card, the build target, the running game). */
    val titleId: String = "",
    /** The name of that thing, as the player reads it. */
    val name: String = "",
    /** What that page carries: the build stage, the last build, the run options. */
    val facts: Map<String, String> = emptyMap(),
    /**
     * How much room is left on the phone ("2.2G free of 450G (99% used)").
     *
     * A global fact, not a page fact: the App asks the filesystem for it (a syscall, no
     * directory walk), while the assistant would have to measure the tree to learn the same
     * thing - hundreds of gigabytes, minutes.
     */
    val storage: String = "",
)

/** The fact keys the snapshot carries. The assistant reads them by name. */
object PageFacts {
    /** The stage a running build is at ("3/8"). */
    const val BUILD_STAGE = "build stage"

    /** The last build: which game, did it work, when, where its log is. */
    const val LAST_BUILD = "last build"

    /** The language the running game was started with. */
    const val LANGUAGE = "language"

    /** The Vulkan driver the running game was started with. */
    const val DRIVER = "driver"

    /** The builds page's facts: the stage (while a build runs) and the last build (always). */
    fun builds(stage: String?, last: LastBuild?): Map<String, String> {
        val out = mutableMapOf<String, String>()
        if (!stage.isNullOrBlank()) out[BUILD_STAGE] = stage
        last?.let { out[LAST_BUILD] = it.line() }
        return out
    }
}

/** The last build attempt: "build failed" means this one, not any build before it. */
data class LastBuild(
    /** The source file that was built. */
    val game: String,
    /** The title ID of that game (empty when the library cannot say which game it was). */
    val titleId: String = "",
    /** The phase it ended in: done, failed, cancelled, interrupted. */
    val phase: String,
    /** When it started. */
    val startedAt: Long,
    /** Its log file. */
    val logPath: String = "",
) {
    /** The one line the assistant reads. */
    fun line(): String {
        val name = File(game).name
        val who = if (titleId.isNotBlank()) "$name ($titleId)" else name
        val when_ = stamp(startedAt)
        val log = if (logPath.isBlank()) "" else "; log: $logPath"
        return "$who $phase $when_$log"
    }
}

/**
 * The last build: the one running now, or the one that just ended.
 *
 * "Build failed" means this one — not any build before it.
 */
fun lastBuildOf(
    port: PortState,
    last: PortState.Record?,
    library: LibraryState,
    nxHome: File,
): LastBuild? {
    if (port.running) {
        return LastBuild(
            game = port.gameFile,
            titleId = library.entryOf(File(port.gameFile))?.titleId.orEmpty(),
            phase = "running",
            startedAt = port.startedAt,
            logPath = buildLogPath(nxHome, port.buildId),
        )
    }
    val record = last ?: return null
    return LastBuild(
        game = record.game,
        titleId = library.entryOf(File(record.game))?.titleId.orEmpty(),
        phase = record.phase.name.lowercase(),
        startedAt = record.startedAt,
        logPath = buildLogPath(nxHome, record.id),
    )
}

/** Where a build's log is: the build history folder's log.txt. */
fun buildLogPath(nxHome: File, id: String): String =
    if (id.isBlank()) "" else File(File(nxHome, "builds"), "$id/log.txt").absolutePath

/** The snapshot's JSON: what the App writes and the assistant reads. */
object PageStateJson {
    /** The format's name: the reader checks it before trusting the file. */
    const val SCHEMA = "nx2a.page_state.v1"

    /** The snapshot's text. */
    fun encode(state: PageState, at: Long): String {
        val facts = JSONObject()
        for ((k, v) in state.facts) facts.put(k, v)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("generatedAt", at)
            .put("page", state.page)
            .put("titleId", state.titleId)
            .put("name", state.name)
            .put("storage", state.storage)
            .put("facts", facts)
            .toString()
    }

    /** The snapshot back (null when the text is not one). */
    fun decode(text: String): PageState? = try {
        val o = JSONObject(text)
        if (o.optString("schema") != SCHEMA) return null
        val facts = mutableMapOf<String, String>()
        o.optJSONObject("facts")?.let { f ->
            val keys = f.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                facts[k] = f.optString(k)
            }
        }
        PageState(o.optString("page"), o.optString("titleId"), o.optString("name"), facts, o.optString("storage"))
    } catch (_: Exception) {
        null
    }
}

/**
 * The room left on the phone: "2.2G free of 450G (99% used)".
 *
 * The App gets it from the filesystem (a syscall). The assistant would have to measure the
 * tree to learn the same thing — hundreds of gigabytes, minutes — and the build that died of
 * "No space left on device" is exactly the case where minutes are the wrong answer.
 */
fun storageFact(freeBytes: Long, totalBytes: Long): String? {
    if (totalBytes <= 0) return null
    val free = freeBytes.coerceIn(0, totalBytes)
    val used = ((totalBytes - free) * 100) / totalBytes
    return "${bytes(free)} free of ${bytes(totalBytes)} ($used% used)"
}

/** A size, the way a human reads it. */
fun bytes(v: Long): String {
    val gb = v / (1024.0 * 1024 * 1024)
    return when {
        gb >= 10 -> String.format(Locale.US, "%.0fG", gb)
        gb >= 1 -> String.format(Locale.US, "%.1fG", gb)
        v >= 1024 * 1024 -> String.format(Locale.US, "%.0fM", v / (1024.0 * 1024))
        else -> "${v}B"
    }
}

/** A moment, the way the assistant reads it. */
internal fun stamp(at: Long): String =
    if (at <= 0L) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))

/**
 * The snapshot's file: written in one go (a temporary file, then a rename), so a reader never sees
 * half of it. The assistant may be reading it while the App writes it.
 */
object PageStateFile {

    /** Writes the text; false when it could not be written. */
    fun writeAtomic(file: File, text: String): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(file)) true else { tmp.delete(); false }
    } catch (_: Exception) {
        false
    }

    /** The snapshot's text (null when there is none). */
    fun read(file: File): String? = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }
}
