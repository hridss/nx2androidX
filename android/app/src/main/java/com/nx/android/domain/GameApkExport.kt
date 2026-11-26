package com.nx.android.domain

import java.io.File

/**
 * A port that leaves the phone as a game of its own: an APK the player installs by hand, and the data folder
 * that game reads.
 *
 * The exported game is the upstream's APK front (dm/android/front_android.c): its data root is the app's own
 * external directory (/storage/emulated/0/Android/data/<package>/files), which the system gives every app and
 * asks for no permission. So the exported game starts without a dialog and runs on the device's own driver
 * unless the pack says otherwise.
 *
 * The data has to be *in* that directory before the game starts. The app cannot write there (the platform lets
 * no app write into another app's directory), but the shell can, so the app moves it over its own ADB channel.
 * When that channel is not there, the data stays in the folder the player picked and the app says how to copy it
 * by hand.
 */
object GameApkExport {

    /** The APK's name: the game's name, cleaned, with the suffix. */
    fun apkName(label: String): String = PortablePack.folderName(label) + ".apk"

    /** The data folder's name: the game's name, cleaned (the same rule the folder export uses). */
    fun dataFolderName(label: String): String = PortablePack.folderName(label)

    /** The exported game's own directory on the device: the one the system gives every app. */
    fun gameFilesDir(packageName: String): String = "/storage/emulated/0/Android/data/$packageName/files"

    /** The APK's size limits, in bytes: without a driver, and with one. */
    const val APK_LIMIT = 25L * 1024 * 1024
    const val APK_LIMIT_WITH_DRIVER = 35L * 1024 * 1024

    /** The stages of an export, in the order they happen. */
    enum class Stage { COPY, BUILD, MOVE, DONE }

    /** How far an export has come. */
    data class Progress(val stage: Stage, val fraction: Float, val message: String = "")

    /**
     * The outcome of an export.
     *
     * [moved] says whether the data reached the game's own directory. When it did not, the APK and the data
     * folder are still there and the player can copy the data by hand.
     */
    data class Result(
        val ok: Boolean,
        val failure: String? = null,
        val apk: String = "",
        val dataFolder: String = "",
        val packageName: String = "",
        val size: Long = 0L,
        val moved: Boolean = false,
    )

    /**
     * The paths inside a pack that travel with it: an env row whose value ends with one of these points into
     * the pack, so it follows the folder wherever the folder is.
     */
    private val PACK_PATHS = listOf("/aoc", "/sysdata", "/standard.ttf", "/nintendo_ext.ttf")

    /** The font the exported folder carries when the pack does not have one of its own. */
    const val STANDARD_FONT = "standard.ttf"

    /**
     * The env.txt the exported data folder carries: the rows that pointed inside the pack follow the exported
     * game's own directory (that is where the folder ends up), the shared font travels with the folder (the
     * exporting phone's copy is out of the game's reach), and the row that pointed at the exporting phone's
     * build workspace is gone (the game's files are inside the folder now).
     */
    fun envForGameDir(env: String, gameDir: String): String {
        val base = gameDir.trimEnd('/')
        val kept = env.lineSequence().mapNotNull { line ->
            if (line.trimStart().startsWith("#") || !line.contains('=')) return@mapNotNull line
            val key = line.substringBefore('=', "")
            val value = line.substringAfter('=')
            when {
                // the game's files are inside the folder: the front reads <its own directory>/RomFS
                key.trim() == "DM_ROMFS" -> null
                // the shared font travels with the folder
                key.trim() == "DM_SHARED_FONT" -> "$key=$base/$STANDARD_FONT"
                else -> {
                    val suffix = PACK_PATHS.firstOrNull { value.endsWith(it) && value.length > it.length }
                        ?: return@mapNotNull line
                    val head = value.dropLast(suffix.length)
                    if (!head.startsWith("/")) return@mapNotNull line
                    "$key=$base$suffix"
                }
            }
        }.toList()
        return kept.joinToString("\n").trimEnd('\n') + "\n"
    }

    /** One env.txt row's value, or null when the row is not there. */
    fun envValue(env: String, key: String): String? = env.lineSequence()
        .firstOrNull { it.contains('=') && !it.trimStart().startsWith("#") && it.substringBefore('=').trim() == key }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    /**
     * The shell command that copies a folder's contents into the exported game's own directory.
     *
     * tar pipes the tree in one pass (cp walks it file by file, which is slow on a game's tens of thousands of
     * files). The chmod at the end is needed: the files arrive owned by the shell with the shell's group, and
     * the exported game (another uid) could not read them — `a+rX` gives everyone the read (and the traverse on
     * the directories) without touching the owner's write. The command is quoted: the folder is named after the
     * game, and games have spaces in their names.
     */
    fun copyCommand(folder: File, gameDir: String): String {
        val src = folder.absolutePath
        val dst = gameDir.trimEnd('/')
        // tar 的非致命抱怨（如顶层目录的 settime 不归它管）不得跳过 chmod——那会让导出的游戏读不了自己的数据。
        // chmod 无条件执行；用清单文件在不在做一次廉价的完整性检查（每个数据文件夹都有它）。
        return "mkdir -p '$dst' && cd '$src' && (tar -cf - . | (cd '$dst' && tar -xf -)); chmod -R a+rX '$dst' && [ -f '$dst/nx2a-data.json' ]"
    }

    /** The same copy with cp, for a device whose tar cannot do it. */
    fun copyCommandFallback(folder: File, gameDir: String): String {
        val src = folder.absolutePath
        val dst = gameDir.trimEnd('/')
        return "mkdir -p '$dst' && cp -r '$src'/. '$dst'/ ; chmod -R a+rX '$dst' && [ -f '$dst/nx2a-data.json' ]"
    }

    /** How big a tree is (only stat: files and bytes, no content read). */
    fun treeBytes(dir: File): Long {
        var bytes = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else bytes += child.length()
            }
        }
        return bytes
    }

    /** A human size for the interface (the App's own wording). */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.0f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.0f kB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
