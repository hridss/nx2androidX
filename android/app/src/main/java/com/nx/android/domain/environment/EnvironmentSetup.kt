package com.nx.android.domain.environment

/** Operations that prepare and validate the bundled Android porter environment. */
interface EnvironmentSetup {
    val ready: Boolean
    val lastCheck: String

    /**
     * Whether the environment has to be built again: the app brought another one (an update) and the phone's
     * was built with the old one.
     */
    fun needsRebuild(): Boolean

    /**
     * Builds the environment again, with the archive the app carries.
     *
     * @param onProgress how much of it is done (0-100)
     */
    fun rebuild(onProgress: (Int) -> Unit)

    /**
     * Rewrites the interpreter of the programs that are started in the guest (the compiler, the .NET runtime,
     * the assistant's engine): Android has no /lib, so their own path is pointed at this phone's loader.
     */
    fun patchInterpreters()
}
