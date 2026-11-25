package com.nx.android.data.library

import com.nx.android.core.storage.AppPaths
import com.nx.android.domain.GameEntry
import com.nx.android.domain.GameStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The library's snapshot: the whole list, in one file the assistant and the phone's tools read.
 *
 * The app scans its own folder and the removable volumes and knows which card is which; whoever asks
 * (the assistant, the tools) reads this file instead of scanning again — the assistant cannot
 * reproduce the merge of the volumes and the live states, and its own summary lists ten games
 * where the library holds twelve.
 *
 * The file is written atomically (a temporary file, then a rename): the assistant may be reading it
 * while the app writes it.
 */
@Singleton
class LibrarySnapshot @Inject constructor(private val paths: AppPaths) {

    /** Where the snapshot lives: the player's nx2android folder, beside the assistant's workspace */
    fun file(): File = File(paths.nxHome, FILE)

    /**
     * Writes the list.
     *
     * [statusOf] is the app's own reading of a game's state (a game being ported right now is
     * "porting"), so the snapshot says the same thing the library page says.
     */
    fun write(games: List<GameEntry>, statusOf: (GameEntry) -> GameStatus) {
        val array = JSONArray()
        for (e in games) {
            array.put(JSONObject()
                .put("name", e.label)
                .put("titleId", e.titleId)
                .put("status", status(statusOf(e)))
                .put("profileStatus", e.pack?.status ?: "")
                .put("pack", e.pack?.pack?.absolutePath ?: "")
                .put("source", e.sourcePath ?: "")
                .put("work", e.work?.absolutePath ?: "")
                .put("imported", e.isImported))
        }
        val text = JSONObject()
            .put("schema", SCHEMA)
            .put("generatedAt", System.currentTimeMillis())
            .put("count", games.size)
            .put("games", array)
            .toString()
        writeAtomic(file(), text)
    }

    private fun writeAtomic(file: File, text: String): Boolean = try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (tmp.renameTo(file)) true else { tmp.delete(); false }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val FILE = "library.json"
        const val SCHEMA = "nx2a.library.v1"

        /** The states, as the file names them (the tools and the assistant read them) */
        fun status(s: GameStatus): String = when (s) {
            GameStatus.NOT_PORTED -> "not_ported"
            GameStatus.PORTING -> "porting"
            GameStatus.DONE -> "ported"
        }
    }
}
