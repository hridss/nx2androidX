package com.nx.android.feature.builds

import android.content.Context

/**
 * The list of the games the porter knows.
 *
 * The list is `docs/PORT_CHECKLIST.md`, and the build copies it into the app's assets (the doc is
 * generated from the profiles, so the list is the list of the commit the app was built from).
 *
 * The doc is Markdown: the app renders it with the same renderer the assistant's answers use.
 */
object GameListContent {

    /** The file in the assets: the build copies the doc there (android/app/build.gradle.kts) */
    const val ASSET = "docs/port_checklist.md"

    /**
     * Reads the list.
     *
     * A file that is not there (or is broken) gives an empty list: the page opens and says that it
     * has nothing to show, and nothing else breaks.
     */
    fun load(context: Context): String = runCatching {
        context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    /**
     * The marks of the list: a game that is ported to Android gets a check, a game that has only a
     * profile gets an empty box.
     *
     * The doc says it in the Markdown way (a task list: `- [x]` and `- [ ]`), and the marks are put
     * in before the renderer sees them: a list item and a symbol read the same on a phone screen,
     * and the state of the game is what the player looks for.
     */
    fun withStatusMarks(markdown: String): String =
        markdown
            .replace(Regex("(?m)^(\\s*)- \\[x\\] "), "$1- ✅ ")
            .replace(Regex("(?m)^(\\s*)- \\[ \\] "), "$1- ⬜ ")
}
