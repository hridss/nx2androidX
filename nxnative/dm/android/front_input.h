// The front's plain-data input surface (the neutral core inside front_android.c): a shell — the NativeActivity
// path in front_android.c itself, the app's JNI adapter (jni_android.c), or another platform's front (ARM Linux)
// — converts whatever its platform hands it into these calls. The int they return is the event-consumed flag the
// AInputQueue's finishEvent expects (1 = the front handled it, 0 = the system should too).
#pragma once

#include <stdint.h>
#include <jni.h>
#include <android/asset_manager.h>
#include <android/native_window.h>

// One finger (or one pointer): the id is the platform's pointer id masked to 5 bits, x/y in window pixels.
typedef struct { int32_t id; float x, y; } dm_front_pointer;

// A key: source and device identify the input device, code/action/repeat/meta are the platform's own key
// event fields (Android's AKEYCODE_* / AKEY_EVENT_ACTION_* / meta state values).
int dm_front_key(int32_t source, int32_t device, int32_t code, int32_t action, int32_t repeat, int32_t meta);

// A touch/pointer event: action is the platform's pointer action (Android's AMOTION_EVENT_ACTION_* masked
// value), action_pointer the pointer index the action names, one dm_front_pointer per active pointer.
int dm_front_pointers(int32_t source, int32_t device, int32_t action, int32_t action_pointer,
                      const dm_front_pointer *pointers, int n);

// A controller's axes, in Android's AMOTION_EVENT_AXIS_* order: X, Y, Z, RZ, LTRIGGER, RTRIGGER, HAT_X,
// HAT_Y — the shell folds BRAKE/GAS into LTRIGGER/RTRIGGER when those read zero.
int dm_front_axes(int32_t source, int32_t device, const float axis[8]);

// ---- 引导与生命周期：壳在准备好数据根与环境后调用（NativeActivity 的 onCreate、App 的 JNI 适配层、
// 将来的 Linux 前端）。jvm/env/activity 是宿主的 Java 对象（Java 方法回调与监视线程用）。----
// 引导：装载数据、读设置、起监视线程与游戏线程。返回 0 = 游戏开跑；非 0 = 壳应当结束自己
// （存档管理要求结束、RomFS 不可读——dm_front_quit_pending() 也会说）。
int dm_front_boot(const char *root, const char *internal, int32_t dpi, int32_t sdk,
                  JavaVM *jvm, JNIEnv *env, jobject activity, AAssetManager *assets);

// 生命周期（NativeActivity 形态由回调调；App 形态由适配层按活动生命周期调）。
void dm_front_pause(void);          // 到后台：游戏被按住，GPU 的钟还给调频器
void dm_front_resume(void);         // 回前台：继续（dm_front_quit_pending() 时壳应先结束自己）
void dm_front_focus(int focused);   // 窗口焦点的得失（失焦如 Switch 的 HOME 覆盖层，不按住）
void dm_front_destroy(void);        // 活动销毁：一个进程一个游戏，直接结束进程
int  dm_front_quit_pending(void);   // 引导时被要求结束（存档管理的导入、RomFS 不可读）

// 窗口（呈现目标）的进出：NativeActivity 形态由回调调；App 形态由 setSurface/releaseSurface 调。
void dm_front_window_created(ANativeWindow *w);
void dm_front_window_destroyed(void);

// 引导后的查询：宿主菜单的「震动」开关当前是什么（壳用它启动自己的震动后端）。
int dm_front_rumble_on(void);
