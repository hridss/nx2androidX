package com.nx.android.appstate

import com.nx.android.core.storage.AppPaths
import com.nx.android.data.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import android.os.StatFs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The page state snapshot: the file the assistant reads when it wants to know where the player is.
 *
 * Two writers: the main screen writes the page it shows, the game window writes the running game (while it
 * is up, it is the one that counts). The file is written atomically (a temporary file, then a rename), so a
 * reader never sees half of it, and the writes are merged: moving the selected card through the library with
 * the pad lands one write, not one per card.
 *
 * Nothing here decides what the page state is — the screens do that. This class only keeps the file in step
 * with what they said.
 */
@Singleton
class PageStateStore @Inject constructor(private val paths: AppPaths) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var pending: Job? = null

    @Volatile private var main: PageState = PageState()
    @Volatile private var game: PageState? = null

    /** The snapshot's file: the player's nx2android folder, beside the library snapshot. */
    fun file(): File = File(paths.nxHome, FILE)

    /** The main screen's page. */
    fun setMain(state: PageState) {
        synchronized(lock) { main = state }
        schedule()
    }

    /** The game window's page (null when the game is gone: the main screen's page counts again). */
    fun setGame(state: PageState?) {
        synchronized(lock) { game = state }
        schedule()
    }

    /** The page state as it stands right now. */
    fun current(): PageState = synchronized(lock) { game ?: main }

    /** Writes it now, without waiting for the merge to settle. */
    fun flush() {
        synchronized(lock) { pending?.cancel() }
        write()
    }

    private fun schedule() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(MERGE_MS)
                write()
            }
        }
    }

    private fun write() {
        val state = current()
        val text = PageStateJson.encode(state.copy(storage = storageLine()), System.currentTimeMillis())
        if (!PageStateFile.writeAtomic(file(), text)) AppLog.line("Page state snapshot failed")
    }

    /**
     * The room left on the phone.
     *
     * The filesystem answers in one syscall — no directory walk. The assistant would have to
     * measure the tree for the same number, and that is minutes over hundreds of gigabytes.
     */
    private fun storageLine(): String {
        val stat = runCatching { StatFs(paths.files.absolutePath) }.getOrNull() ?: return ""
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return storageFact(free, total).orEmpty()
    }

    companion object {
        /** The snapshot's file name, in the player's nx2android folder. */
        const val FILE = "page-state.json"

        /** How long the writes wait for the next change before landing. */
        private const val MERGE_MS = 250L
    }
}
