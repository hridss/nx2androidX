package com.nx.android.appstate

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nx.android.domain.environment.EnvironmentSetup
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/**
 * The environment's build: the one thing the app does when it brings another environment.
 *
 * The environment travels in one archive and carries a stamp. When the stamp is not the one the phone's
 * environment was built with, the whole thing is built again — the old tree goes, the archive is unpacked, and
 * the stamp is written at the end. A build that was cut short (the process died) is done again at the next
 * start, because the stamp was not written.
 *
 * The build is one thing with one state, so the guided setup's button, the settings page and the app's own
 * start all go through here and show the same progress.
 */
@Singleton
class EnvBuildState @Inject constructor(
    private val env: EnvironmentSetup,
    private val envCheck: EnvCheckState,
) {

    /** The environment is being built */
    var running by mutableStateOf(false); private set

    /** How much of it is done (0-100) */
    var progress by mutableStateOf(0); private set

    /** What went wrong (empty when there is nothing wrong) */
    var error by mutableStateOf(""); private set

    /**
     * The app brought another environment: it is built again, with a progress the player can see.
     *
     * It happens at the app's start, before any port: a port that is running is never interrupted.
     */
    fun startIfNeeded() {
        if (!env.needsRebuild()) return
        start("the app brought another environment")
    }

    /**
     * The build starts (the guided setup's button, the settings page).
     *
     * @param reason why it starts: it is in the log, so a build nobody asked for can be traced
     */
    fun start(reason: String) {
        if (running) return
        running = true
        progress = 0
        error = ""
        envCheck.preparing = true
        Log.i(TAG, "the environment's build starts ($reason)")
        thread(name = "env-build") {
            try {
                env.rebuild { p -> post { progress = p } }
                post {
                    running = false
                    progress = 100
                    envCheck.preparing = false
                    envCheck.refresh()
                }
            } catch (e: Exception) {
                Log.e("Nx2aEnv", "the environment could not be built: ${e.message}")
                post {
                    running = false
                    error = e.message ?: e.javaClass.simpleName
                    envCheck.preparing = false
                    envCheck.refresh()
                }
            }
        }
    }

    /** The build is called again after a failure */
    fun retry() {
        error = ""
        start("after a failure")
    }

    /** The main thread: the build runs in a thread of its own */
    private fun post(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    companion object {
        private const val TAG = "Nx2aEnv"
    }
}
