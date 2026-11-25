package com.nx.android.agent.runtime

import java.io.File

/**
 * The material out of the App's own assets folder, read from the disk: the tests read the very
 * files that go into the package, so the two cannot drift apart.
 */
class FileSeedSource(private val root: File) : AgentSeedSource {

    override fun files(): List<String> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path }
            .sorted()
            .toList()

    override fun text(path: String): String? =
        File(root, path).takeIf { it.isFile }?.readText()
}
