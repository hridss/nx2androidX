package com.nx.android.data.saves

import java.io.File

/**
 * The one save root the app uses: a folder holding `<titleId>` and `<titleId>_device`.
 *
 * By default that folder is the app's internal `files/save`. When the player picks an external
 * folder it becomes the only root — the guest, the in-game save manager and the library panel all
 * use it. No copy, no fallback: changing the root changes where saves are, not just where they are
 * imported from.
 */
data class SaveRoot(val base: File) {
    fun live(titleId: String): File = File(base, titleId)
    fun device(titleId: String): File = File(base, "${titleId}_device")

    companion object {
        fun internal(filesDir: File): SaveRoot = SaveRoot(File(filesDir, "save"))
        fun external(folder: File): SaveRoot = SaveRoot(folder)

        /** A blank preference means the app's own default folder; anything else is the chosen root. */
        fun of(prefPath: String, filesDir: File): SaveRoot =
            if (prefPath.isBlank()) internal(filesDir) else external(File(prefPath))
    }
}

/** Resolves the configured root. Pure so unit tests can supply a fake provider without a Context. */
interface SaveRootProvider {
    fun resolve(filesDir: File): SaveRoot
}
