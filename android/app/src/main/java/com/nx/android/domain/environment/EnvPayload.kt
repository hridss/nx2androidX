package com.nx.android.domain.environment

import java.io.File

/**
 * The rule of the environment's build.
 *
 * The app carries the environment in one archive, with a stamp. The phone's environment remembers the stamp it
 * was built with. When the two differ, the environment is built again.
 *
 * It is a rule of its own (no Android in it), so that it can be tested on the JVM: EnvPayloadTest.
 */
object EnvPayload {

    /**
     * Whether the environment has to be built again.
     *
     * @param want the stamp the app's archive carries (empty when the app carries no environment)
     * @param stored the stamp the phone's environment was built with
     * @param environmentThere whether the phone has an environment at all
     */
    fun stale(want: String, stored: String, environmentThere: Boolean): Boolean {
        // the app carries no environment (a build without the payload): the tree is left alone
        if (want.isEmpty()) return false
        if (want == stored) return false
        // a phone with no environment yet is not a rebuild: the first install is the guided setup's business
        return environmentThere
    }

    /**
     * The folders a rebuild replaces: the environment itself (the rootfs and the tool tree) and the
     * assistant's engine, which comes with it.
     *
     * The player's data is not among them: the porter's folder (the imported profiles, the build products)
     * and the assistant's own folder (its models, its conversations) stay. The engine has its own folder
     * inside the assistant's, so only that one goes.
     */
    fun replaced(rootfs: File, tool: File, agentRuntime: File): List<File> =
        listOf(rootfs, tool, agentRuntime)

    /**
     * Where the archive's trees land on the phone: the first segment of a path says which one it is.
     *
     * The assistant's tree lands in the assistant's own folder, so the engine arrives with the environment
     * and the assistant's data sits beside it.
     */
    fun roots(rootfs: File, tool: File, agent: File): Map<String, File> =
        mapOf("rootfs" to rootfs, "tool" to tool, "agent" to agent)
}
