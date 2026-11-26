package com.nx.android.game

import android.view.Surface

/**
 * 游戏页与宿主核心（上游前端的中性核心）之间的 JNI 面。收敛后的面很小：
 * 启动、窗口、裸数据输入、生命周期、音频路由、游戏模式——游戏内的设置与面板都是宿主自己的。
 *
 * 每个游戏的运行库一份（libdmhost.so，:play 进程启动时 System.load 进来），JNI 符号与宿主一起静态链接。
 */
object Native {

    /** 启动宿主：pack 是数据目录（数据根），internal 是 App 的私有目录（存档与临时文件），env 是启动前的环境行 */
    @JvmStatic external fun start(pack: String, internal: String, titleId: String?, dpi: Int, env: Array<String>, ctx: android.content.Context): Boolean

    /** 游戏的呈现目标（SurfaceView 的 surface；null = 它没了，交换链先销毁再返回） */
    @JvmStatic external fun setSurface(surface: Surface?)

    /** 游戏性能模式（系统的游戏档位推给宿主，宿主据此换节奏策略） */
    @JvmStatic external fun perfGameMode(mode: Int)

    /** 裸数据的触摸/指针事件（动作沿用 Android 的掩码与指针下标；每根手指一份 id 与窗口坐标） */
    @JvmStatic external fun motion(source: Int, device: Int, action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray, axis: FloatArray): Boolean

    /** 裸数据的按键事件（键码/动作/元状态/重复计数，与 KeyEvent 的字段一一对应） */
    @JvmStatic external fun key(device: Int, source: Int, code: Int, action: Int, meta: Int, repeat: Int): Boolean

    /** 活动生命周期：0 暂停（到后台），1 恢复（回前台），2 窗口焦点，3 失焦（失焦不按住游戏） */
    @JvmStatic external fun lifecycle(ev: Int)

    /** 声音输出：游戏的声音走哪个设备（宿主菜单的音频项回调 openAudioMenu 后，App 把选择送回来）。0 = 跟随系统 */
    @JvmStatic external fun audioSet(id: Int)

    /** 宿主现在的音频输出设备（0 = 跟随系统） */
    @JvmStatic external fun audioGet(): Int
}
