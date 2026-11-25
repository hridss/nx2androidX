package com.nx.android.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build

internal object AppProcess {
    /**
     * 这个 App 进程起来的那一刻。
     *
     * 比它还老的东西是上一个进程留下的：那个进程死的时候，它正在写的东西就停在那一刻。
     * 子代理的 transcript 就是这样——App 被杀掉时正在跑的那一次，永远不会再写一行，
     * 也不会有记录收尾（记录是引擎跑完时写的）。
     */
    @Volatile var startedAt: Long = System.currentTimeMillis()
        private set

    /** App 起来了：这一刻就是「这个进程从哪儿开始算」。 */
    fun markStarted() {
        startedAt = System.currentTimeMillis()
    }

    fun isMainProcess(packageName: String, processName: String): Boolean =
        packageName == processName

    fun isMainProcess(context: Context): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            ActivityManager.RunningAppProcessInfo().let { info ->
                ActivityManager.getMyMemoryState(info)
                info.processName ?: context.packageName
            }
        }
        return isMainProcess(context.packageName, processName)
    }
}
