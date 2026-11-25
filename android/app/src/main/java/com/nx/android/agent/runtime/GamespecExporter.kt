package com.nx.android.agent.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nx.android.core.storage.AppPaths
import com.nx.android.data.SaveShare
import com.nx.android.domain.UpdateVersion
import com.nx.android.domain.environment.GuestProcessLauncher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导出一局的攻关档案：在环境里跑打包器，把 zip 放进下载目录。
 *
 * 档案本身不动——导出是快照，导出完了还能继续往里记。玩家拿去提 issue 用的是这个包。
 */
@Singleton
class GamespecExporter @Inject constructor(
    private val paths: AppPaths,
    private val guest: GuestProcessLauncher,
) {

    /**
     * 打包一局的档案。
     *
     * @return 成功时是下载目录里那份 zip；失败时是失败的原因（一句话，给玩家看的）
     */
    suspend fun export(context: Context, titleId: String): Result<File> {
        if (titleId.isBlank()) return Result.failure(IllegalArgumentException("no title id"))
        val tmp = File(paths.tmp, "gamespec-export.zip")
        val failure = runCatching {
            val tool = File(File(paths.tool, "tools"), "gamespec_pack.py")
            if (!tool.isFile) return@runCatching "the packer is not on this phone"
            val process = guest.guest(listOf(tool.absolutePath, "--game", titleId, "--out", tmp.absolutePath))
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
                    ?: "the packer exited with code $code"
            } else null
        }.getOrElse { it.message ?: "the export failed" }
        if (failure != null) return Result.failure(IllegalStateException(failure))

        // 分享出去的与下载目录里的是同一份：玩家在文件管理器里也能找到
        val dir = File(SaveShare.packagesDir(context), "gamespec")
        val target = File(dir, "$titleId-gamespec.zip")
        runCatching {
            dir.mkdirs()
            tmp.copyTo(target, overwrite = true)
        }.onFailure { return Result.failure(it) }
        return Result.success(target)
    }

    companion object {
        /**
         * 提 issue 的页面：玩家拿着导出的包去那里说问题。
         *
         * 直接落在「Case file」那个模板上：包、机型、版本、附件都在表单里，玩家不用自己挑模板。
         */
        const val ISSUE_URL = "${UpdateVersion.REPO_URL}/issues/new?template=case_file.yml"
    }
}

/**
 * 打开提 issue 的页面：导出成功之后把玩家送到那儿，包他自己在表单里附上。
 *
 * 没有浏览器就安静地过去——App 里没有别的路可以走到那里。
 */
fun openGamespecIssue(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GamespecExporter.ISSUE_URL)))
    } catch (e: Exception) {
        // 没有浏览器：App 里没有别的路可以走到那里，安静地过去
    }
}
