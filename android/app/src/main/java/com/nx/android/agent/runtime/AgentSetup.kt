package com.nx.android.agent.runtime

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.data.AppVersion
import com.nx.android.data.PrefsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The assistant's own state: the engine that is installed.
 *
 * Installing the engine is the act of turning the assistant on — there is no switch next to it. The
 * engine, the models and the conversations live in the assistant's own folder, so the environment's
 * reset does not take them away.
 */
@Singleton
class AgentSetup @Inject constructor(
    private val prefs: PrefsStore,
    private val engine: AgentEngineStore,
    @ApplicationContext private val ctx: Context,
) {

    private companion object {
        const val KEY_INTRO_SHOWN = "agent.intro.shownFor"
    }

    /** The engine that is installed (the binary is there). */
    var engineInstalled by mutableStateOf(engine.installed)
        private set

    /** The assistant is ready: the engine is there. A model is added later, in the settings. */
    val ready: Boolean get() = engineInstalled

    /** Reads the fact again: an install (or the one-time move) moves it. */
    fun refresh() {
        engineInstalled = engine.installed
    }

    /**
     * Whether the introduction should be shown: the engine is not there yet and this version has not
     * asked.
     */
    val shouldIntroduce: Boolean
        get() = !ready && prefs.str(KEY_INTRO_SHOWN) != AppVersion.of(ctx)

    /** The introduction has been shown (or put aside): this version does not ask again. */
    fun markIntroduced() {
        prefs.put(KEY_INTRO_SHOWN, AppVersion.of(ctx))
    }
}
