package com.nx.android.agent.runtime

import com.nx.android.core.storage.AppPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The assistant's own working folder: the rendered conversations and the memory, the ones the
 * assistant writes for itself.
 *
 * The material the assistant works by (the working agreement, the skills, the agent definitions,
 * the knowledge we ship) is not here: it is planted on the device where the engine reads it, and
 * the tree it comes from is the App's.
 */
@Singleton
class AgentWorkspace @Inject constructor(
    paths: AppPaths,
    private val planter: AgentPlanter,
) {

    val root: File = paths.agentWorkspace
    private val memoryMd: File get() = File(root, "MEMORY.md")

    fun ensure(): File {
        root.mkdirs()
        // the material has to be on the device before the engine starts: it reads its agreement,
        // its skills and its agent definitions when it comes up
        planter.plant()
        // the memory is the assistant's own: it writes that, we only read it
        if (!memoryMd.isFile) memoryMd.writeText("")
        return root
    }

    fun readMemory(): String = memoryMd.takeIf { it.isFile }?.readText() ?: ""
}
