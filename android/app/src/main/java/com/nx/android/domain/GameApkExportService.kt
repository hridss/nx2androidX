package com.nx.android.domain

import java.io.File

/**
 * The export of a finished port as a game of its own: an APK the player installs by hand, and the data folder
 * that game reads.
 *
 * The interface lives here so the interface layer does not depend on Android; the work is in
 * `data/GameApkExportServiceImpl.kt`.
 */
interface GameApkExportService {

    /**
     * Exports the game into [targetDir]: the data folder and the APK.
     *
     * @param onProgress how far it has come (the copy, the build, the move)
     * @param log the guest's own lines (the last one is what the player reads)
     */
    fun export(entry: GameEntry, targetDir: File,
               onProgress: (GameApkExport.Progress) -> Unit = {},
               log: (String) -> Unit = {}): GameApkExport.Result

    /**
     * Moves a data folder into the exported game's own directory (the shell does it: the platform lets no app
     * write into another app's directory).
     *
     * It is a step of its own because the shell channel needs the App's key to be paired, and the player pairs
     * it after the export (the export still produced everything; only the move was left).
     */
    fun move(dataFolder: File, packageName: String, log: (String) -> Unit = {}): Boolean
}
