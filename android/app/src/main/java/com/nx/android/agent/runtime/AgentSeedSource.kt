package com.nx.android.agent.runtime

/**
 * Where the prompt material comes from: the App's assets in the app, the file tree in the tests.
 *
 * The material is a tree, and its paths mirror the player's nx2android folder: the planter needs no
 * map of its own, so adding a skill is adding a file.
 */
interface AgentSeedSource {

    /** The material, as paths relative to the seed root. */
    fun files(): List<String>

    /** One piece of material, as text; null when it cannot be read. */
    fun text(path: String): String?
}
