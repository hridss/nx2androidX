// Android port: the app's adapter (the :play process). One libdmhost.so per game (build.py --play): the
// neutral core (front_android.c) statically linked with this file, so the JNI surface and the host travel
// together and the linker never resolves between two of the player's games. Java (com.nx.android.game.Native)
// hands over the Surface, raw input as snapshots (front_input.h's plain-data entries — a plain Activity has
// no AInputQueue), the lifecycle and the start parameters; the core answers exactly as it does for the
// NativeActivity APK. There are no APK assets for the game here: the gamepad's theme and the overlay's font
// come from the pack's own folder (overlay_theme.c's file fallback). The app's own Compose layer over the
// surface is the assistant only — the core draws the gamepad and every panel itself.
//
// The JNI surface is small on purpose: start / setSurface / motion / key / lifecycle / the audio routing.
// The game's settings are files (the app writes settings.txt and env.txt rows before the start); the
// in-game panels are the core's own.
#include "dm.h"
#include "front_input.h"
#include <android/input.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <string.h>

extern void dm_audio_route_device(int32_t id);   // hle_audio.c: the live output streams move to that device at once
extern int32_t dm_aaudio_get_device(void);       // audiotoolbox_aaudio.c: the output device in force (0 = the system's own)
extern void dm_perf_game_mode(int mode);         // perf_android.c: the Android game mode (2 = performance) picks the pacing policy

static int jx(JNIEnv *E) { if (!(*E)->ExceptionCheck(E)) return 0; (*E)->ExceptionClear(E); return 1; }

// The launcher's env rows (KEY=VALUE; the frame rate pin, the GPU's clock, the motion switches) go in before
// the host's own env.txt read — the pack's own rows win, the launcher's are the defaults it started with.
static void start_env(JNIEnv *E, jobjectArray rows) {
    if (!rows) return;
    jsize n = (*E)->GetArrayLength(E, rows);
    for (jsize k = 0; k < n; k++) {
        jstring row = (jstring)(*E)->GetObjectArrayElement(E, rows, k);
        const char *r = row ? (*E)->GetStringUTFChars(E, row, NULL) : NULL;
        if (r) { char key[128] = {0}; const char *eq = strchr(r, '='); if (eq && (size_t)(eq - r) < sizeof key) { memcpy(key, r, (size_t)(eq - r)); setenv(key, eq + 1, 1); } }
        if (r) (*E)->ReleaseStringUTFChars(E, row, r);
        if (row) (*E)->DeleteLocalRef(E, row);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_start(JNIEnv *E, jclass c, jstring pack, jstring internal, jstring title_id, jint dpi,
                                      jobjectArray env, jobject ctx) {
    (void)c;
    // 存档与临时目录按游戏分开：title id 进环境，dm_front_boot 的 DM_SAVE/DM_TEMP 子目录用它（settings.tsv 的
    // DM_GAME_ID 行说的就是它）。上游的 NativeActivity 一包一游戏，没有这个 env，那里的存档就是平铺的。
    if (title_id) { const char *t = (*E)->GetStringUTFChars(E, title_id, NULL);
                    if (t) { setenv("DM_GAME_ID", t, 1); (*E)->ReleaseStringUTFChars(E, title_id, t); } }
    start_env(E, env);
    const char *p = pack ? (*E)->GetStringUTFChars(E, pack, NULL) : NULL;
    const char *i = internal ? (*E)->GetStringUTFChars(E, internal, NULL) : NULL;
    if (!p || !i) { if ((*E)->ExceptionCheck(E)) (*E)->ExceptionClear(E); LOG("[RED] [adapter] start without the pack or the app folder"); return JNI_FALSE; }
    // the hosting context: the activity object (Java callbacks: the audio menu, the IME's readKeyboard) and
    // its assets; the VM from the environment; the platform's SDK level for the storage-permission checks
    JavaVM *vm = NULL; if ((*E)->GetJavaVM(E, &vm) != JNI_OK) { if (p) (*E)->ReleaseStringUTFChars(E, pack, p); return JNI_FALSE; }
    jobject assets_obj = NULL; AAssetManager *assets = NULL;
    jclass cc = (*E)->GetObjectClass(E, ctx);
    jmethodID ga = cc ? (*E)->GetMethodID(E, cc, "getAssets", "()Landroid/content/res/AssetManager;") : NULL;
    if (ga && !jx(E)) assets_obj = (*E)->CallObjectMethod(E, ctx, ga);
    if ((*E)->ExceptionCheck(E)) (*E)->ExceptionClear(E);
    if (assets_obj) assets = AAssetManager_fromJava(E, assets_obj);
    if (assets_obj) (*E)->DeleteLocalRef(E, assets_obj);
    if (cc) (*E)->DeleteLocalRef(E, cc);
    int32_t sdk = 0; { jclass v = (*E)->FindClass(E, "android/os/Build$VERSION");
        jfieldID f = v ? (*E)->GetStaticFieldID(E, v, "SDK_INT", "I") : NULL;
        if (f && !jx(E)) sdk = (*E)->GetStaticIntField(E, v, f); if (v) (*E)->DeleteLocalRef(E, v); }
    int rc = dm_front_boot(p, i, dpi, sdk, vm, E, ctx, assets);
    if (p) (*E)->ReleaseStringUTFChars(E, pack, p);
    if (i) (*E)->ReleaseStringUTFChars(E, internal, i);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;   // non-zero: the save import asked to end — Java finishes without starting
}

// The Surface is here (the reference passes over: held until the surface is gone).
static ANativeWindow *g_held;

JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_setSurface(JNIEnv *E, jclass c, jobject surface) {
    (void)c;
    if (g_held) { dm_front_window_destroyed(); ANativeWindow_release(g_held); g_held = NULL; }
    if (!surface) return;
    ANativeWindow *w = ANativeWindow_fromSurface(E, surface);   // our reference; the window itself passes over owned
    if (!w) { if ((*E)->ExceptionCheck(E)) (*E)->ExceptionClear(E); dm_front_window_destroyed(); return; }
    g_held = w;
    dm_front_window_created(w);
}

JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_motion(JNIEnv *E, jclass c, jint source, jint device, jint action, jintArray ids, jfloatArray xs, jfloatArray ys, jfloatArray axis) {
    (void)c;
    jint n = ids ? (*E)->GetArrayLength(E, ids) : 0;
    jint ni = xs ? (*E)->GetArrayLength(E, xs) : 0, ny = ys ? (*E)->GetArrayLength(E, ys) : 0;
    if (ni < n) n = ni; if (ny < n) n = ny; if (n > 16) n = 16;
    jint *idv = n && ids ? (*E)->GetIntArrayElements(E, ids, NULL) : NULL;
    jfloat *xv = n && xs ? (*E)->GetFloatArrayElements(E, xs, NULL) : NULL, *yv = n && ys ? (*E)->GetFloatArrayElements(E, ys, NULL) : NULL;
    jboolean consumed;
    if ((source & AINPUT_SOURCE_TOUCHSCREEN) == AINPUT_SOURCE_TOUCHSCREEN) {
        dm_front_pointer p[16];
        for (jint k = 0; k < n; k++) { p[k].id = idv ? idv[k] : 0; p[k].x = xv ? xv[k] : 0; p[k].y = yv ? yv[k] : 0; }
        consumed = dm_front_pointers(source, device, action & AMOTION_EVENT_ACTION_MASK,
                                     (int32_t)(action >> AMOTION_EVENT_ACTION_POINTER_INDEX_SHIFT), p, (int)n) ? JNI_TRUE : JNI_FALSE;
    } else {   // a controller's axes (the Java side carries X Y Z RZ LTRIGGER RTRIGGER HAT_X HAT_Y then BRAKE GAS RX RY)
        jfloat *av = axis && (*E)->GetArrayLength(E, axis) >= 10 ? (*E)->GetFloatArrayElements(E, axis, NULL) : NULL;
        float a[8] = {0};
        if (av) { for (int k = 0; k < 8; k++) a[k] = av[k];
                  if (a[4] == 0) a[4] = av[8]; if (a[5] == 0) a[5] = av[9];   // BRAKE/GAS stand in for the triggers when they read zero
                  (*E)->ReleaseFloatArrayElements(E, axis, av, JNI_ABORT); }
        consumed = dm_front_axes(source, device, a) ? JNI_TRUE : JNI_FALSE;
    }
    if (idv) (*E)->ReleaseIntArrayElements(E, ids, idv, JNI_ABORT);
    if (xv) (*E)->ReleaseFloatArrayElements(E, xs, xv, JNI_ABORT);
    if (yv) (*E)->ReleaseFloatArrayElements(E, ys, yv, JNI_ABORT);
    return consumed;
}

JNIEXPORT jboolean JNICALL
Java_com_nx_android_game_Native_key(JNIEnv *E, jclass c, jint device, jint source, jint code, jint action, jint meta, jint repeat) {
    (void)E; (void)c;
    return dm_front_key(source, device, code, action, repeat, meta) ? JNI_TRUE : JNI_FALSE;
}

// The activity's lifecycle (0 pause, 1 resume, 2 window focus, 3 window unfocus — GameActivity's own numbering).
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_lifecycle(JNIEnv *E, jclass c, jint ev) {
    (void)E; (void)c;
    if (ev == 0) dm_front_pause();
    else if (ev == 1) dm_front_resume();
    else dm_front_focus(ev == 2 ? 1 : 2);
}

// The audio output the player picked in the host menu's own dialog (openAudioMenu on the activity): the live
// output streams move to that device at once. 0 = the system's own choice.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_audioSet(JNIEnv *E, jclass c, jint id) {
    (void)E; (void)c;
    dm_audio_route_device(id);
}
JNIEXPORT jint JNICALL
Java_com_nx_android_game_Native_audioGet(JNIEnv *E, jclass c) {
    (void)E; (void)c;
    return dm_aaudio_get_device();
}

// The system's game mode (2 = performance): the pacing policy lets the game's own timing speak in that mode.
JNIEXPORT void JNICALL
Java_com_nx_android_game_Native_perfGameMode(JNIEnv *E, jclass c, jint mode) {
    (void)E; (void)c;
    dm_perf_game_mode(mode);
}
