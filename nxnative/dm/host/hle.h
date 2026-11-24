// HLE common: register host implementations by Switch mangled name.
// Note the differences between Apple AAPCS64 and Switch: Apple requires the caller to extend <32-bit arguments (bool/char/short), Switch code does not extend ⇒
// such parameters are always declared as int/unsigned and truncated by ourselves. Variadic arguments go on the stack on Apple and in registers on Switch ⇒ variadic functions cannot use C's "..." directly.
#pragma once
#include "dm.h"
typedef struct { const char *name; void *fn; } hle_ent;
#define HLE(mangled, ret, cname, ...)                                                             \
    static ret cname(__VA_ARGS__);                                                                \
    __attribute__((used, DM_SECTION("swhle"))) static const hle_ent hle_ent_##cname = {mangled, (void *)cname}; \
    static ret cname(__VA_ARGS__)

typedef uint32_t Result;   // nn::Result: w0, 0 = success
#define RES_OK 0u
#define RES_FAIL 0x2A2u      // Generic failure (cut features such as networking uniformly return this)

// Guest address → host object side table
void *objmap_get(const void *guest);
void objmap_put(const void *guest, void *host);
void *objmap_del(const void *guest);
uint64_t now_ns(void);
// This thread is doing the host's own heavy work (NVN submit / present: encoding Metal commands; on hardware that is the GPU's business and does not occupy a guest core). The same-core preemption approximation (hle_thread.c) does not count it as "running"
extern __thread _Atomic int dm_host_work;
uint64_t guest_ns(void);              // Guest clock (hle_os.c; does not advance while frozen, guest_clock_freeze)
int dm_detclock_enabled(void);
void dm_guest_clock_advanced(void);
uint64_t dm_guest_frame(void);        // Incremented each time the host presents a frame; unaffected by machine load
void guest_clock_freeze(int on);
void guest_clock_freeze_for(int reason, int on);   // Freeze by reason (1 background, 2 system keyboard); see hle_os.c
void guest_clock_read_wait(void);     // A guest read of the system counter: waits while the app is in the background (hle_os.c)

// Forced takeover: even if the SDK module has a definition, all references to that symbol at load time (JUMP_SLOT/GLOB_DAT/ABS64) are resolved to the host implementation.
// Used only for the service-layer nn:: (service clients of fs/oe/aoc/pctl...); the SDK's libc++ and nn::os internals do not go into this table.
// Every entry must state the reason for the takeover; an entry in the table that no module references makes loading report a failure and abort (a wrong list must fail loudly).
typedef struct { const char *name; void *fn; const char *why; } hle_force_ent;
#define HLE_FORCE(mangled, why, ret, cname, ...)                                                   \
    static ret cname(__VA_ARGS__);                                                                \
    __attribute__((used, DM_SECTION("swforce"))) static const hle_force_ent hle_force_##cname = {mangled, (void *)cname, why}; \
    static ret cname(__VA_ARGS__)
// Same as above, but the implementation runs on this thread's big host stack (hle_bigstack.c): what is registered is a stub generated at compile time (the implementation's address goes into x16, then a jump to dm_bigstack_enter).
// For take-overs with large stack use (the file system sizes its buffers by PATH_MAX): a game thread's stack can be as small as 8 KB. Only register arguments are carried over (<= 8 integer, <= 8 floating point); not for variadic functions
#define HLE_FORCE_BIG(mangled, why, ret, cname, ...)                                               \
    static ret cname(__VA_ARGS__) __attribute__((used));                                          \
    extern const char cname##_bs[];                                                               \
    __asm__(".text\n.p2align 2\n.globl " DM_ASM_SYM(#cname "_bs") "\n" DM_ASM_PRIVATE " " DM_ASM_SYM(#cname "_bs") "\n" DM_ASM_SYM(#cname "_bs") ":\n" \
            "    adrp x16, " DM_ASM_PAGE(#cname) "\n    add x16, x16, " DM_ASM_PAGEOFF(#cname) "\n    b " DM_ASM_SYM("dm_bigstack_enter") "\n"); \
    __attribute__((used, DM_SECTION("swforce"))) static const hle_force_ent hle_force_##cname = {mangled, (void *)(uintptr_t)cname##_bs, why}; \
    static ret cname(__VA_ARGS__)

// Host service sessions (hle_sf.c): a request on a session the host serves, as its service sees it, and the answer it fills in. The input is the raw CMIF data after the "SFCI"
// header; the buffers are the client's A (send), B (receive) and X (pointer) descriptors, read and written in place (one address space); the output is the raw data after "SFCO",
// zeroed beforehand. A service may copy one kernel handle to the client (an event's read end) and move one (a new session: an out object outside a domain)
typedef struct {
    unsigned cmd, object;                              // CMIF command; domain object (0 outside a domain)
    const uint8_t *in; size_t in_len;                  // raw input data
    uint32_t copy[4], move[4]; unsigned ncopy, nmove;  // handles the client sent
    struct { uint8_t *p; uint64_t size; } a[4], b[4], x[4]; unsigned na, nb, nx;
    uint8_t out[0xc0]; size_t out_len;                 // raw output data (zeroed; out_len is how much of it the service wrote)
    uint32_t out_copy, out_move;                       // handles for the client (0: none)
    unsigned out_object;                               // an out object id in a domain session (0: none)
    void *ctx;                                         // the session's own state (sf_session)
} sf_req;
typedef Result (*sf_answer)(sf_req *r);
uint32_t sf_session(const char *name, sf_answer answer, void *ctx, void (*close)(void *ctx));   // a new host session (its handle), for a service to move to its client
// A service the host serves in its own file: SF_SERVICE("name", answer) registers it (the swsf section), and connecting to that name gives a session answered by it
typedef struct { const char *name; sf_answer answer; } sf_service_ent;
#define SF_SERVICE(svc, fn) __attribute__((used, DM_SECTION("swsf"))) static const sf_service_ent sf_service_##fn = {svc, fn}
// Host-made kernel events (hle_thread.c) for a service that hands its client an event: a new event's read end (the write end in *w, none when NULL), a signal by the write end
// from any thread (0 once that end is closed), and a second read end of an event (0 when h is no read end)
uint32_t hle_new_event(uint32_t *w);
int hle_signal_event_handle(uint32_t w);
uint32_t hle_dup_readable_handle(uint32_t h);

// Operation mode (0 handheld / 1 docked): owned by hle_oe.c, and the hid controller style follows it; the front end can switch it at runtime
int dm_opmode(void);
void dm_set_opmode(int m);          // On change, push notification 30 / 31 according to the switches the game has turned on
void dm_hid_opmode_changed(void);   // hle_hid.c: an operation mode change signals the Handheld and No1 style update events (player 1 moves between them)
// Focus (1 foreground / 2 focus lost / 3 background): called by the front end on lifecycle changes; a change pushes notification 15
void dm_focus_set(int st);
// Audio pause / resume following the lifecycle (called by the front end on going to background / returning to foreground)
extern uint8_t *dm_fps_state;   // frame step state page (loader.c install_fps_state): +0 float target fps, +4 movie flag, +0x10 float render scale, +0x18 double game seconds stepped; NULL when the build has none
void dm_audio_suspend(int on);
uint64_t dm_audio_render_calls(void);   // Cumulative sound card callback count (the silent sink is not counted)
void dm_audio_reset(void);   // iOS media services reset: recreate the AudioUnits (hle_audio.c)
