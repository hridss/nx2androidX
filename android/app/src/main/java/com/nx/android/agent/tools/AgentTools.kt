package com.nx.android.agent.tools

import com.nx.android.appstate.LibraryState
import com.nx.android.domain.GameStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one piece of app state the agent's turns still carry as context: the library summary.
 * Everything else pi does itself — read files, run commands — through its own tools.
 *
 * English: this text rides in the turn's [device] block, and the prompts are English.
 */
@Singleton
class AgentTools @Inject constructor(private val library: LibraryState) {

    fun libraryStatus(): String {
        val games = library.visible()
        if (games.isEmpty()) return "the game library is empty."
        val done = games.count { library.statusOf(it) == GameStatus.DONE }
        val out = StringBuilder("the library has ${games.size} game(s), $done of them ported:")
        for ((i, e) in games.take(10).withIndex()) {
            out.append('\n').append(i + 1).append(". ").append(e.label)
                .append(" (").append(e.titleId).append(") - ").append(statusLabel(library.statusOf(e)))
        }
        val rest = games.size - 10
        if (rest > 0) out.append("\n... and $rest more not listed.")
        return out.toString()
    }

    private fun statusLabel(s: GameStatus): String = when (s) {
        GameStatus.NOT_PORTED -> "not ported"
        GameStatus.PORTING -> "porting"
        GameStatus.DONE -> "done"
    }
}
