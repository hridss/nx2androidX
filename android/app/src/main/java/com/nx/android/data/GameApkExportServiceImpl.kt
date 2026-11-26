package com.nx.android.data

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.nx.android.core.storage.AppPaths
import com.nx.android.domain.GameApkExport
import com.nx.android.domain.GameEntry
import com.nx.android.domain.PortablePack
import com.nx.android.data.library.PortablePackFiles
import com.nx.android.domain.GameApkExportService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The export of a finished port as a game of its own: the data folder the player can carry, and the APK that
 * runs it.
 *
 * The order matters:
 * 1. the data is copied into the folder the player picked (the same copy the folder export makes, with the
 *    management files rewritten for the exported game's own directory);
 * 2. the APK is built in the guest (the host for a NativeActivity of its own, then the APK);
 * 3. the data is moved into the exported game's own directory, over the App's ADB channel (the platform lets
 *    no app write into another app's directory, but the shell can).
 *
 * When step 3 cannot happen (Wireless debugging off, never paired), the APK and the folder are still there and
 * the player can copy the data by hand: the interface says which directory it goes to.
 */
@Singleton
class GameApkExportServiceImpl @Inject constructor(
    private val paths: AppPaths,
    private val exporter: GameApkExporter,
    private val prefs: PrefsStore,
    @ApplicationContext private val context: Context,
) : GameApkExportService {

    /**
     * Exports the game into [targetDir].
     *
     * @param onProgress how far it has come (the copy, the build, the move)
     * @param log the guest's own lines (the interface shows the last one)
     */
    override fun export(entry: GameEntry, targetDir: File,
               onProgress: (GameApkExport.Progress) -> Unit,
               log: (String) -> Unit): GameApkExport.Result {
        val pack = entry.pack ?: return fail("no_pack")
        val manifest = PortablePack.readManifest(pack.pack) ?: return fail("no_manifest")
        val packageName = manifest.optString("package")
        if (packageName.isEmpty()) return fail("no_package")
        val gameDir = GameApkExport.gameFilesDir(packageName)
        val folder = File(targetDir, GameApkExport.dataFolderName(entry.label))
        val apk = File(targetDir, GameApkExport.apkName(entry.label))
        // the player's folder already holds something of this game's: nothing is overwritten
        if (folder.exists() || apk.exists()) return fail("exists")
        val romfs = if (pack.romfsAt.isNotEmpty()) File(pack.romfsAt) else File(pack.pack, PortablePack.ROMFS)
        if (!romfs.isDirectory) return fail("no_romfs")

        // the space: the data twice (the folder the player picked and the game's own directory) and the APK
        val dataBytes = GameApkExport.treeBytes(pack.pack) + GameApkExport.treeBytes(romfs)
        if (freeBytes(targetDir) < dataBytes + GameApkExport.APK_LIMIT_WITH_DRIVER) return fail("no_space")
        if (freeBytes(File(gameDir)) < dataBytes) return fail("no_space")

        // 1. the data folder
        onProgress(GameApkExport.Progress(GameApkExport.Stage.COPY, 0f))
        val copied = PortablePackFiles.export(pack.pack, pack.romfsAt, targetDir, entry.label) { p ->
            onProgress(GameApkExport.Progress(GameApkExport.Stage.COPY, p.fraction))
        }
        if (!copied.ok) return fail(copied.failure ?: "copy_failed")
        val env = File(folder, PortablePack.ENV).takeIf { it.isFile }?.readText().orEmpty()
        if (!prepareFont(folder, env, log)) { folder.deleteRecursively(); return fail("no_font") }
        File(folder, PortablePack.ENV).writeText(GameApkExport.envForGameDir(env, gameDir))

        // 2. the APK
        onProgress(GameApkExport.Progress(GameApkExport.Stage.BUILD, 0f))
        val driver = GameApkExport.envValue(env, "DM_VK_DRIVER")?.let { File(it) }?.takeIf { it.isFile }
        val built = exporter.build(folder, apk, keyFile(), gameDir, driver, nextOffset(entry.titleId)) { log(it) }
        if (!built.ok) {
            folder.deleteRecursively()
            apk.delete()
            return fail(built.reason.ifEmpty { "build_failed" })
        }

        // 3. the data into the exported game's own directory
        onProgress(GameApkExport.Progress(GameApkExport.Stage.MOVE, 0f))
        val moved = moveToGameDir(folder, gameDir, log)
        onProgress(GameApkExport.Progress(GameApkExport.Stage.DONE, 1f))
        return GameApkExport.Result(
            ok = true, apk = apk.absolutePath, dataFolder = folder.absolutePath,
            packageName = packageName, size = built.size, moved = moved,
        )
    }

    /**
     * The shared font travels with the exported folder: the game's own copy is in the exporting phone's
     * private directory, which the exported game cannot read (its Chinese text would draw as "?").
     */
    private fun prepareFont(folder: File, env: String, log: (String) -> Unit): Boolean {
        if (GameApkExport.envValue(env, "DM_SHARED_FONT") == null) return true   // the pack has no font row
        if (File(folder, GameApkExport.STANDARD_FONT).isFile) return true          // the pack carries one
        val shared = File(paths.tool, "nxnative/dm/tools/shared_cjk.ttf")
        if (!shared.isFile) {
            log("[RED] the shared font is missing (the tool tree has no shared_cjk.ttf): the game's Chinese text draws as \"?\"")
            return true
        }
        return runCatching { shared.copyTo(File(folder, GameApkExport.STANDARD_FONT), overwrite = true) }
            .onFailure { log("[RED] the shared font could not be copied: ${it.message}") }
            .isSuccess
    }

    /**
     * Moves a data folder into the exported game's own directory, as the shell user (the platform lets no app
     * write there; the shell can). False when the channel is not there or the copy failed.
     */
    override fun move(dataFolder: File, packageName: String, log: (String) -> Unit): Boolean =
        moveToGameDir(dataFolder, GameApkExport.gameFilesDir(packageName), log)

    private fun moveToGameDir(folder: File, gameDir: String, log: (String) -> Unit): Boolean {
        val ok = runCatching {
            AdbChannels.exec(context, GameApkExport.copyCommand(folder, gameDir), COPY_TIMEOUT_MS)
        }.onFailure {
            Log.i(TAG, "the copy into the game's own directory failed: ${it.message}")
            log("[RED] the data could not be moved into the game's own directory: ${it.message}")
        }.isSuccess
        if (!ok) {
            // a device whose tar cannot do it: cp is slower but does the same
            val fallback = runCatching {
                AdbChannels.exec(context, GameApkExport.copyCommandFallback(folder, gameDir), COPY_TIMEOUT_MS)
            }.onFailure { Log.i(TAG, "the copy with cp failed too: ${it.message}") }.isSuccess
            if (fallback) log("the data is in the game's own directory")
            return fallback
        }
        log("the data is in the game's own directory")
        return true
    }

    /** The signing key: one for every exported game (the app's own private folder). */
    private fun keyFile(): File = File(paths.nxHome, "export-signing-key.json")

    /**
     * The versionCode offset: the app counts the exports of the same game, so a later one installs over an
     * earlier one (Android refuses to install over a lower versionCode).
     */
    private fun nextOffset(titleId: String): Int {
        val counts = prefs.json(PrefsStore.LIB_EXPORTS)
        val n = counts.optInt(titleId, 0)
        counts.put(titleId, n + 1)
        prefs.putJson(PrefsStore.LIB_EXPORTS, counts)
        return n
    }

    /** The room on the volume a path is on (the nearest existing folder above it). */
    private fun freeBytes(target: File): Long {
        var dir = target
        while (!dir.exists() && dir.parentFile != null) dir = dir.parentFile ?: break
        return runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)
    }

    private fun fail(why: String) = GameApkExport.Result(false, why)

    companion object {
        private const val TAG = "nx2a-export"
        /** A game's files are many: the copy over the wire's socket takes minutes, not seconds. */
        private const val COPY_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GameApkExportModule {
    @Binds
    @Singleton
    abstract fun bindGameApkExportService(impl: GameApkExportServiceImpl): GameApkExportService
}
