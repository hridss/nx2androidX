package com.nx.android.core.storage

import java.io.File
import java.security.MessageDigest

/**
 * The assistant's folder layout: where its pieces live inside the player's nx2android folder.
 *
 * Cross-feature path logic, so it belongs with the app's paths ([AppPaths]) and not with the feature
 * that uses it. Free of Android, so the JVM tests drive it.
 */
object AgentLayout {

    /** The assistant's own folder, inside the player's nx2android folder. */
    const val DIR = "agent"

    /** The engine binary's name inside the runtime folder. */
    const val BINARY = "pi"

    /** The installed package's manifest: what is in, and what it needs. */
    const val MANIFEST = "manifest.json"

    /** The assistant's folder: the engine, its own home, the models, the conversations. */
    fun agentDir(nxHome: File): File = File(nxHome, DIR)

    /** Where the engine binary and the installed package's manifest live. */
    fun runtimeDir(agent: File): File = File(agent, "runtime")

    /** The rendered conversations, the memory, the player's copy of the agreement. */
    fun workspaceDir(agent: File): File = File(agent, "workspace")

    /** The model profiles (the keys are encrypted). */
    fun modelsFile(agent: File): File = File(agent, "models.json")

    /**
     * A file we wrote, brought up to date: a file still carrying our text is refreshed; a file the
     * player (or the assistant) has changed is left alone.
     *
     * How we tell our own text from theirs: beside the file we keep a mark with the digest of the
     * text we wrote. The file is ours while that digest still matches it; the moment it is
     * changed, it is theirs. A file whose text is already the one we would write is ours too -
     * that is how a file we wrote before the mark existed (or before we wrote one) is adopted.
     */
    fun seedOwned(file: File, seed: String) {
        val text = file.takeIf { it.isFile }?.readText()
        when {
            text == null -> writeSeeded(file, seed)          // 没有这一份：写进去
            text == seed -> adopt(file, text)                // 已经是这一份：认领它
            isOurs(file, text) -> writeSeeded(file, seed)   // 我们写的旧版本：换成新的
            else -> Unit                                    // 玩家或助手改过的：不碰
        }
    }

    /** The file is ours (its text is the one we would write): record that, so a later seed can replace it. */
    private fun adopt(file: File, text: String) {
        runCatching { seedMark(file).writeText(digest(text)) }
    }

    /** A text's SHA-256, as hex. */
    fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * The mark beside a file we wrote: the digest of the text we wrote.
     *
     * The file is ours while that digest still matches it; the moment the player changes it, it is
     * theirs. Beside the agreement it is what keeps a changed seed flowing to the phone.
     */
    fun seedMark(file: File): File = File(file.parentFile, "${file.name}.seed")

    /** Is this file still the one we wrote (and the player has not touched since)? */
    fun isOurs(file: File, text: String): Boolean {
        val mark = seedMark(file)
        return mark.isFile && mark.readText().trim() == digest(text)
    }

    /** Write the text and the mark beside it. */
    fun writeSeeded(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
        runCatching { seedMark(file).writeText(digest(text)) }
    }
}
