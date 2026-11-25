# How a port is done: measure, then fix

**Read this when**: you are bringing up a new game, reading a crash report, or deciding
where a fix belongs. The other documents answer "what does this symptom mean"; this one
answers "what do I do, in what order, and where does the fix go".

- `build-failures.md` - the build stopped at a stage
- `shader-split.md` - the shaders are missing or the count is wrong
- `runtime-symptoms.md` - the upstream's cases, by symptom
- `device-findings.md` - what the symptom looks like on a phone, and the dead ends
- **this file** - the method: what to measure, how to read the evidence, where a fix goes

Everything here is about **this** fork: the Android app, the Vulkan backend, the host in
`nxnative/dm/`. Where a method comes from the upstream's Apple route, it says so.

## 1. Measure before touching the host

A new title is measured first. Every number below is from a specific game - **measure it,
do not assume it**: the next game differs again.

### What to establish

| Fact | Where it comes from |
|---|---|
| The game's name and display version (the NACP) | `nxnative/dm/tools/nacp.py`. Newer SDKs compress the NACP's title area (a u16 size, then raw deflate inflating to 32 language entries of 0x300 bytes); LibHac 0.19 does not decode that, this tool does |
| The base game, the update, the DLC | the NSP set itself |
| Every module, its build ID and its segment sizes | the ExeFS listing. The loader takes up to 8 modules, in the order given |
| The program ID | the NCA header or `main.npdm`. The host reports it from `svcGetInfo(ProgramId)` |
| The SDK version and its components | the strings in `sdk`: `SDK MW+Nintendo+NintendoSDK_nnSdk-16_2_1-Release`. SDK 16 changed several object sizes and service paths |
| The engine and the graphics API | the markers: NVN, Vulkan, EGL, IL2CPP, sead / agl, Wwise, CRIWARE |
| The RomFS folders, their header magics and extensions | the RomFS listing |

### Three rules that decide most of the work

**1. Not every import needs a hook.** Many SDK functions run natively inside the `sdk`
module. Only the ones that end in an IPC to a system service - the ones that reach
`nn::sm::Initialize` or `svcSendSyncRequest` - must be replaced by a host hook, and the
hook goes on the **public entry point**. A missing one shows up at run time as an abort in
`nn::sm::Initialize` with a backtrace: hook the outermost public function in that
backtrace.

**2. A new SVC needs a case in the host.** The default case logs
`SVC %#x ... (placeholder returns success)` once and returns success. That is wrong for
anything that returns data.

**3. Importing `nvnBootstrapLoader` is not enough.** Read the graphics setup that calls
`nvnBootstrapLoader`, `eglGetDisplay` or `vkCreateInstance`, and count which loaded entry
points the code really reaches. A loader that stores every pointer in a global is countable:
the GOT slots of those globals that code outside the loader uses. One title imports
`nvnBootstrapLoader` and EGL, makes an NVN device for a helper beside its movie player,
and renders everything with NVIDIA's desktop OpenGL.

### The NVN API version is a silent gate

The game asks the device for the NVN API version and refuses to run if the host reports an
older one. Its device setup **returns silently** on a failed check, so **a missing frame
loop with no error in the log is the hint**. The host's answer is `DM_NVN_API`.

### The tools

`nxnative/dm/tools/` has the survey tools, and they are the same ones the upstream uses:
`nacp.py` (the NACP), `nso_syms.py` (the game's imports against the host's hook names),
`insn_census.py` (every site the rewriter must handle: `svc`, `mrs`/`msr` of the
tpidr registers, `x18`, exclusive windows, timers, cache maintenance, system registers),
`nvn_slots_got.py` (which NVN functions the game fetches and which it calls), and
`loader_census.py`. Run `patch_nso.py` on a module and check its counts against the
census: a mismatch means the rewriter met something it does not handle.

These run on a computer with the game's files at hand. On the phone the porter runs the
stages it needs; the profile that comes out carries what was measured.

### A driver A/B: compare the same pass, not the frame rate

The driver is a setting, so a driver change is measured like any other change - but the
frame rate is the wrong number for it: the pacing, the frame rate pin and the game's own
resolution all move with the driver, and none of them changes what a pass costs.

1. **Anchor the two runs on the same moment.** A scene change prints its viewport calls
   (`[nvn] viewport 1360x768 from ...`) within one millisecond. That line is the anchor,
   and the first frame after it is the frame to compare.
2. **Compare the pass list index for index.** The pass order has to agree, and the draw
   counts with it (within a few percent), before the times mean anything.
3. **Read the passes in two groups.** The passes with real fragment work follow the
   driver's fill rate; the passes with a fixed cost (a one-draw resolve, a UI composite)
   follow its overhead. A driver can be much slower at one and much faster at the other -
   a measured case: 3.0-4.8x slower on the four fragment-bound passes and 1.6-3.8x
   *faster* on the two fixed-cost ones. "5-10x slower" was the wrong summary; "3-5x
   slower exactly where this port already loses its frames" was the right one.
4. **Name the settings that moved with the driver.** The frame rate pin (`DM_FPS_PIN`),
   the dynamic resolution steering (`DM_DYN_SCALE`) and the GPU clock (`DM_GPU_TURBO`)
   change what a burst costs the player without changing what a pass costs. The clock is
   the exclusion: a driver that is still slow at its highest clock is not slow because of
   heat or of a clock that never got there.

### A profile that was synced without its tools

A profile is data; the tools it names are code. A profile that arrives from upstream, or
from another phone, may name rules **this tree does not have**: an engine-wide rule file
(the engine upstream's `ue_region` rule), an observation gate the host does not know
(`OBS_UE_REGION`), a key the profile's own table is read by. Nothing says so in the
profile - it reads as a normal profile.

The symptom is always the same: the game asks for something the tree cannot give, and the
log says it in the game's own words. Unreal asks for a 62 GB engine region, the alias
region is 3.12 GB, the request is refused (`AllocateAddressRegion ... refused`, `0x1803`),
and the game aborts in `nninitStartup` before the title screen.

**Check the profile against the tree before the port:** every file, gate and key its
comments name has to exist here. A profile that names a rule this tree lacks is not
broken - it is a profile for another tree. The fix is then either the missing tool (a
general one, into the core) or the per-game configuration this tree does support (into the
profile), and the two are not the same work: the first is a port's worth of engine work,
the second a row.

## 2. Read the log

The run's log is `log.txt` in the data pack folder, and the **previous** run's is
`log.prev.txt` - the backtrace of a crash is usually in the older one. The build's log is
`$NX2A_HOME/builds/<id>/log.txt`. `log_triage.py` reads a run, `builds.py` reads a build.

| Marker | What it means |
|---|---|
| `>>> nnosInitialize`, `>>> nnMain` | the boot stages reached |
| `[RED] ...` | something unimplemented or wrong. Printed once per kind: collect them with `grep -aF "[RED]" log \| sort \| uniq -c` after every run |
| `UNIMPL <name>` | a forced NVN or SDK stub was called |
| `[longframe]` | a long frame, with its submit / GPU / pipeline / upload split |
| `memory up to frame N: resident ...` | the memory report. Printed when memory moves, so **growth that stays is a leak** |
| `[nvn] frame N: ... \| name=count ...` | per-frame NVN call counts |
| `CRASH sig=... pc=... lr=...` | a crash report, with the registers, the backtrace, the stack scan and the thread dump. Section 3 |
| `thread 0x10xx 'name' ... waits:<state> addr=...` | one guest thread: what it waits on, and where |

The thread line is the one worth reading carefully. Its fields are the handle, the name,
the priority, the core, the affinity mask, the CPU seconds, the SVC count, then what it
waits on: `address` (an address arbiter), `mutex` (with its holder), `condvar`, a sync
object, or `running` / `not waiting`. The last two fields are the last SVC it made and
where it returned to. A thread blocked on a mutex names the holder: that is the deadlock.

Useful switches: `DM_FS=1` (file opens and the not-found probes), `DM_SVC=1` (the memory
SVCs and the other syscalls), `DM_THR=1` (the thread SVCs), `DM_WATCH=<seconds>` (dump
every thread after that many seconds), `DM_WATCH_STALL=<seconds>` (dump when the frame
loop stops progressing).

A log may hold bytes that are not UTF-8. Filter with `grep -a` or `LC_ALL=C`.

## 3. Read a crash report

```
[dm] CRASH sig=11 addr=0 pc=main+0x373e04 lr=main+0x373dd8 sp=0x7066dd6370
x0=... x28=...
[dm] bt: main+0x373dd8 main+0x2ea2ac ...          the frame-pointer backtrace
[dm] fp=...  0x100 bytes below sp: ...             the 32 words below sp (the frame that was just popped)
[dm] stack scan sp=...: [-72]main+0x57a14 [+24]main+0x57618 ...   every word near sp that resolves to a module or host symbol
[dm] thread 0x1001 'main thread' ...              every guest thread, what it waits on, its backtrace
```

**Read the registers before the code.** Each of these says a different thing:

- a pointer that is a valid pointer plus garbage in the high bits - something wrote past
  the end, or an index was widened;
- an index of `0xfffffffe` - a negative number widened as unsigned. One fault address
  was `0x70a740c570 + 0xfffffffe*4 + 0x1bac`: an index of -2;
- `pc == lr == 0x100000000` - a `ret` through an overwritten saved LR. Look at the
  zeroed frame below `sp`.

`pc` and `lr` are module-relative (`main+...`, `sdk+...`) or host symbols. **A module
offset is the same in the decompressed NSO and in the ELF** - Ghidra adds 0x100000 to the
image base, so `FUN_xxxx` in Ghidra is the module offset plus 0x100000.

When the frame-pointer backtrace is empty, the stack scan's **negative** offsets (below
`sp`) are the return addresses of the calls the crashing function made last.

The `[dm] *xN:` lines show the first six words each register points at, read safely, so a
bad pointer is skipped rather than faulting. A word inside a module prints as `main+...`:
**the first word of an object is its vtable, which names its class.** Comparing it with
the class's own vtable says whether the object was destroyed (a base-class vtable) or
rebuilt.

### Which signal

The handler takes SIGSEGV, SIGBUS, SIGILL, SIGTRAP, SIGALRM and SIGABRT.

- **SIGALRM is the run's time limit, not a crash.**
- SIGABRT is an `abort()`: an assertion, or an exception nobody caught in the host.
- A run that ends with exit 137 and no `CRASH` line was killed with SIGKILL. On Android
  that is the system's low-memory killer or the battery manager, not the game.

`DM_CRASH_TOMBSTONE=1` hands the signal on to Android's own `debuggerd` after the dump,
so a full tombstone lands in the log next to the host's report.

## 4. The probe toolbox

This is the strongest instrument in the engine and it is **wired for Android in this
fork**. The upstream's own note says "Mac only" - that is the upstream tree. Here the
Darwin compatibility layer (`nxnative/dm/android/compat/darwin_compat.c`) implements
`mach_vm_region` over `/proc/self/maps` - including Scudo's tagged pointers and its
secondary allocator's split mappings - and `probe.c` uses POSIX `mprotect`. The host
build compiles it (`host_probe.c.o` sits next to `host_crash.c.o`).

The switch names are in the registry (`nxnative/dm/settings.tsv`); most of their `doc`
cells are empty there, so this section is the reference for them.

### A probe: what happens at one address

`DM_PROBE=main:4290c0,main:9712c` - module and offset, comma separated. The instruction
at that address is replaced with `brk`, and on every hit the host prints the guest thread
(`T=`), x0-x7 (and the strings they point at), x19-x23, lr, x8 and a backtrace, then runs
the original instruction from a trampoline.

Two rules:

- **Put the probe at a function entry, or where x16 is dead.** The trampoline borrows x16.
- **The probed instruction must not be PC-relative** - no `adrp`, `adr`, `b`, `bl` or a
  literal `ldr`. Probe the next instruction instead.

Each hit prints as one block even when several threads hit at once. **Probes slow the
game down**, which can shift the heap addresses between runs.

The suffixes, in this order:

| Suffix | Effect |
|---|---|
| `@off`, `@*off`, `@xN+off`, `@*xN+off`, `@*xN+off+add` | print 16 bytes at x0+off (or at xN+off); `*` dereferences that address first, and `+add` moves on that many bytes from the pointer read. `@*x0+48+8` on an actor is its position, the way the game reads it |
| `?xN=val`, `?[xN+off]=val` | only report hits where the register (or the 64-bit word at xN+off) equals `val`, in hex; the others pass silently. `main:4743c0?[x0+30]=781548` = only the allocations whose size field is that |
| `!xN` | treat xN as a `sead::ExpHeap` and print its free and used blocks (offset + size, with the alignment pad) |

`DM_PROBE_ALL=1` adds x9-x18. `DM_PROBE_X2=<bytes>` dumps the memory x2 points at.

The heap walk needs the layout of the game's sead version:
`DM_PROBE_HEAP=<name>,<start>,<size>,<free list>,<used list>`, hex offsets in the heap
object. Find them in the heap's constructor and in `tryAlloc` - they differ per sead
version. `DM_PROBE_HEAPNAME=<text>` reports only the heaps whose name contains the text
(resource heaps are named after their file), `DM_PROBE_HEAPWORDS=1` adds the first two
words of each used block (a vtable names the object), `DM_PROBE_HEAPFRAME=<vtable offset
in main>` prints the heaps of that class as frame heaps, and `DM_PROBE_HEAPRAW=1` dumps the
heap object's first 0x130 bytes, to find another class's fields.

### A watch: who writes these bytes

`DM_PROBE_WATCH=<address>[+<length>]` or `<module>:<offset>[+<length>]` - hex, 8 bytes by
default. The host write-protects that page as soon as it is writable and keeps it
protected: the fault handler carries out every store that lands on the page through a
second, writable mapping of it (STR / STUR / STP of general and SIMD registers in their
immediate, pre- and post-index forms, register-offset STR, DC ZVA), so the stores of a
busy page all pass through it. A store that reaches the watched bytes prints
`WATCH ... store of N bytes at ...: old -> new`, the thread, pc, lr and a backtrace.

- **The bytes must lie in one 16 KB host page.**
- **On Android a module's base changes from run to run**, so a raw address from one run
  misses in the next. Use `<module>:<offset>`. A guest object, on the other hand, sits at
  the same address in every run (the guest heap is laid out the same) - so take the
  address from a probe's register dump first.
- Atomics and exclusives are **not emulated**: they print a `not emulated` line once per
  pc and run on an unprotected page, which is protected again a millisecond later. An
  exclusive or atomic store that reaches the watched bytes still prints `WATCH ...
  exclusive store` with its pc and backtrace, every time. But a page that takes many
  atomics stays unprotected most of the time, so even that can miss.

### A poll: when the watch misses

`DM_PROBE_POLL=<address>[+<length>]` - hex, 4 bytes by default, up to 8. A host thread
spins on the bytes; on each change it stops every thread at once, reads each one's pc, lr
and 8 frame-pointer frames, lets them run again, and prints `POLL thread ...` for the
threads with guest code in those frames, the change itself, and, for the first two
changes, the full guest thread dump.

The snapshot comes a few microseconds after the store, so a writer that went on into a
wait shows only its wait. It costs one core while it runs. With a value filter
(`=value`) every change is still logged, but the threads are snapshotted only at changes
to that value.

### What a probe cannot see

The guest's own `memcpy` and `memset` go straight to the host's libc, where a guest
probe cannot see them.

### The other half: the switches that ask the graphics and audio side

| Question | Switch |
|---|---|
| What does it draw, in which pass | `DM_VK_PASSDUMP`, `DM_VK_TRACE_PASS` |
| What is the raw HDR value, before the tone mapping | `DM_VK_PASSDUMP_RAW` |
| Who wrote this pixel, and when | `DM_VK_PIXEL_HISTORY` |
| What did the frame look like on screen | `DM_VK_SHOTS` |
| Which draw is the one that misbehaves | `DM_VK_X_SKIPVS`, `DM_VK_CB_DRAWS`, `DM_VK_SUBMIT_SPLIT` |
| Is the frame clock drifting against the display | `DM_VK_PACE_LOG` |
| Is the depth buffer's low-resolution copy stale | `DM_VK_LRZ_TOUCH` |
| What does the sound device receive | `DM_AUDREN_DUMP`, `DM_AUDREN_TRACE`, `DM_AUDREN_CATCHUP_FRAMES` |

What each one answers, and what the result looks like, is in `device-findings.md`'s
symptom table. The full list of the switches is `nxnative/dm/settings.tsv`.

### Read the game's own data, do not fight the camera

A probe can read a value out of the game instead of guessing where it is on screen. One
route through a 3D level took a dozen runs of screenshots and still lost the character
behind the scenery, and turning the camera mid-route made the same input land differently
each run. What worked: read the player's position every frame with a probe and plan from
the stage's own data. The guest heap is laid out the same in every run, so the player
actor is at the same address - `DM_PROBE=main:1fb2e0@*x0+48+8` printed x, height and z as
three floats per frame - and the stage's data file lists every actor with its position,
including the goal.

The same idea generalises: **when a route or a cutscene does not reproduce, read the
game's state rather than scripting the input.** The input is what the game sees; the
state is what it is.

### Make an intermittent failure happen every time

A crash that needs an unlucky thread interleaving (one run in three, or once in a long
session) gets much more frequent when the CPU is busy, because threads are descheduled at
more places. On a computer, twelve busy shell loops beside the run turn a 2-in-5 crash
into a crash in every run, and the frame rate then switches between 60 and 30 exactly as
on the phone. That is enough to test a fix: same load, same route, a few runs.

The phone has the other half of the trick: **the guest heap is laid out the same in every
run**, so the same address is hit every time, and a scripted route with `DM_KEYS`
(`<seconds>:<button>[+button][~hold]`, repeats with `<seconds>:<button>*<count>/<interval>`)
reproduces the same sequence of inputs. A frame count (`f2500:A`) counts **presented
frames**, not seconds: with another heavy job running, seconds drift away from the game's
progress and a route stops lining up, while frame numbers do not.

## 5. An intermittent failure: a detector before a bisection

A fault that comes and goes needs a **counter** before it needs a bisection. "No glitch
in these 16 frames" proves nothing, and a screenshot misses a one-frame fault entirely.

1. **Count, do not look.** Record the run and count the bad frames with a script:
   `devtools/rec_flicker.py` counts the bad frames in a recording; `devtools/pcm_glitches.py`
   finds the repeats, the clicks on the frame edges, the gaps and the clipping in a PCM
   dump. Look at a few frames by eye before trusting the metric, and tune the metric to
   the shape of the fault - a metric for "texture busyness" misses a flat translucent
   variant of the same fault.
2. **Measure the base rate.** Run it with nothing changed, so you know how often it
   happens per run.
3. **Bisect with a switch.** Skip one draw, one pass or one shader at a time until one
   is left.
4. **Dump that draw's inputs across frames** and compare the good and the bad ones. If
   every input is byte-identical, the difference is on the GPU side: follow the textures
   it samples to whatever writes them.
5. **Do not trust a dump read at the wrong moment.** A dump read at encode, or after the
   submission completed, may not be what the draw read: later work of the same submission
   can rewrite the buffer. When a draw's vertices look wrong, list in command order every
   copy and inline update of the frame from the pass dump (`DM_NVN_PASSDUMP_ALL=1`). A copy
   whose source an earlier write of the same frame covers reads those writes, not the
   bytes the CPU staged.

The same shape works for memory: measure the report line every few hundred frames, see
which region grows, and only then look for what allocates it. Memory that steps up at a
level load and stays flat in between is not a leak; memory that climbs every frame is.

## 6. Where a fix belongs

A fix is anchored to something. The looser that anchor, the longer the fix lives. The
other upstream (`enjoyerofslop/droidplay-core`, MIT) writes its patches this way, and it
is worth copying:

| Binding | Anchored to | Found by | Survives |
|---|---|---|---|
| `host` | Horizon / NVN / Vulkan semantics | nothing in the binary | every game and every update |
| `sdk` | Nintendo's SDK code (the `sdk` module) | a symbol, or a fingerprint of a reviewed SDK implementation | any game shipping that implementation |
| `engine` | middleware code: Unreal, Unity, Snowdrop, KTGL, sead, PhysX | a registered name, a string, or a fingerprint per engine version | any game on that engine version |
| `game` | the game's own code, or its profile's data | a function fingerprint, or a profile key | the game's updates that keep the function |
| `build` | fixed offsets | the NSO build ID | nothing: redone for every update |

**A fix is good design when its binding is the loosest that is still correct.** How to
loosen it, in order:

1. the host's own semantics, instead of the binary's;
2. a registered name or a string, instead of a fingerprint;
3. a function fingerprint, instead of a fixed offset;
4. nothing at all - a general rule that holds for every game.

**A locator fails closed.** A rule that finds its place in the binary applies only where
it finds exactly one match *and* its assumptions check out; otherwise it changes nothing.
That is what makes a looser binding safe on a build nobody has seen: the rule does
nothing rather than doing the wrong thing.

**Say how the fix was checked.** `checked` - what was checked on the game's files, and
when. Or `judgement` - reasoned from the code, not checked against the game.

### Where it goes in this tree

| The fix is about | It goes to |
|---|---|
| a rule that holds for every game | the shared core - `nxnative/dm/host/`, `nxnative/dm/gfx/`, `nxnative/dm/android/` - behind a general switch, off by default |
| one game's own fact | the profile: `nxnative/dm/games/<title ID>/` (`game.tsv`, `android.tsv`, the shader tables) |
| a rewriter cut for one game's own build | that profile's `patches.py`, keyed by the NSO build ID |
| a cut for a Nintendo SDK build several games ship | `nxnative/dm/tools/sdk_patches.py` |
| a host behaviour that uses a fixed module address | behind the build-ID check (`module_t.build_id`), so it never runs on a build it was not found in |

### Writing a cut into the game's own code

A crash in the guest's own code is the one fault the host cannot fix. When the faulting
instruction reads through a null that the guest itself produced - a lookup that answered
nothing, a member that is not in its table - the cut is not a workaround but the guest's own
missing null check, made explicit: the game fails cleanly instead of dying, and it goes on.

The shape of it, from the case it was worked out on (Teenage Mutant Ninja Turtles: Shredder's
Revenge, `case/0100BB8017F24000-tmnt`):

1. **Read the faulting instruction.** `ldr x8, [x8, #0x40]` with `x8 = 0` is a load through a
   null pointer; the register that holds the null was loaded a few instructions earlier.
2. **Find who put it there.** A helper that returns a lookup's result leaves it in the return
   register, so the value is often already in hand where the guest loads it again: the load is
   redundant, and the cut can be `ldr x8, [sp, #0x10]` → `cbz x8, <the helper's own return
   path>` - the guest's null check becomes the branch's path, and the helper returns the null
   it already has.
3. **Cut the uses of the null too.** The guest calls through the member it looked up, and one
   branch that skips the *first* call still crashes inside the *next* one: the branch has to
   skip all of them and land on the join point after the last, not the one after the first.
   (The first version of that cut skipped only the first call, and the next run died inside the
   second one.)
4. **Leave the good case bit-for-bit unchanged.** The branch is not taken when the lookup
   answered, so the code runs exactly as before - the cut costs the game nothing.
5. **Verify on the device.** The crash is gone, the game gets past that point, and the thing
   that failed cleanly (here: the intro video does not play) is the only thing missing.

Two 4-byte cuts did it, in the game's own module, keyed by its build ID. The rewriter asserts
the original bytes, so a wrong offset fails loudly instead of quietly.

## 7. The discipline

**Find the root cause and fix it there.** Before changing code, find why it happens: the
faulting instruction and its decompilation, the value that went wrong and who wrote it,
the Horizon / NVN / Vulkan semantics the host or the translator gets wrong. Then fix that
place.

**A change that only hides the symptom is a workaround**: a stub, a skipped call, a clamp,
a retry, a sleep or a longer timeout, a cache flush, a profile key that turns a feature
off. Use one only when the cause is known and lies where it cannot be fixed - the game's
own code, which a rewriter cut changes deliberately, or a platform limit - or when the
player asks for a stopgap. Then say so in the code comment and in the case file: the
cause, or what is still unknown.

**Measure, do not assume.** Every number in any document came from a specific game. The
next game differs again.

**Per-build knowledge is keyed by the NSO build ID.** When a new build of a game appears,
carry the addresses over with `nxnative/dm/tools/nso_fnmap.py` and check them by
disassembly. **Never let a fixed address run on a build it was not found in.**

**Keep the shared core shared.** A per-game branch in the core is never the answer: the
game gets a profile key.

**A case that turns up is written into the game's case file, not into this knowledge
base.** This knowledge base is maintained by a person, on a computer; the procedure is in
`openspec/specs/port-failures/tasks.md` in the repository.

## Where the depth is

This file holds what can be acted on from a phone: the method, the evidence, and the
probes. The upstream's own guides hold the depth that needs a computer and the game's
files at hand - the archive layers, the survey tools in full, Ghidra and objdump, the
per-title graphics notes. They live in the checkout, at `nxnative/docs/agents/`
(`01-extract.md`, `02-survey.md`, `03-bringup.md`, `04-graphics.md`, `05-debugging.md`).
They describe the upstream's Apple route; the method carries over, the commands do not.
