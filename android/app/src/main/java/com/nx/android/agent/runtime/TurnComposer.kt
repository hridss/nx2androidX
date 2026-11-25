package com.nx.android.agent.runtime

/**
 * The device state block: the app's own version first, then the library summary.
 *
 * The version leads because nothing else in the turn tells the model which build is installed — an
 * old apk file name in a download folder once had it answer "0.3.0-alpha06" while the installed
 * app was 0.4.0. The line is written before the budget is spent on the library, so a long library
 * can never cut it off.
 *
 * The page the player is on does not ride along: it lives in the page state snapshot, and the
 * assistant reads that file when it needs it (the App never pushes it into the turn).
 */
internal fun deviceState(version: String, library: String, budget: Int): String {
    val head = buildString {
        append("app version: ").append(version)
    }
    val room = (budget - head.length - 1).coerceAtLeast(0)
    return "$head\n${library.take(room)}"
}

/**
 * The player's words as the model reads them: the memory block rides ahead, the words come last.
 * Pure function — the JVM tests drive it.
 */
internal fun composeTurn(userText: String, memory: String = "", deviceState: String = ""): String {
    val hasDevice = deviceState.isNotBlank()
    val hasMemory = memory.isNotBlank()
    if (!hasDevice && !hasMemory) return userText
    val sb = StringBuilder()
    if (hasDevice) sb.append("[device] ").append(deviceState.trim()).append('\n')
    if (hasMemory) sb.append("[memory] ").append(memory.trim()).append('\n')
    return sb.append("player says: ").append(userText).toString()
}
