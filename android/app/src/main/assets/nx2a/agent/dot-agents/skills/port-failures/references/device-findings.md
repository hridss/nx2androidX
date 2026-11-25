# What the device showed: the symptoms as they appear, and what they turned out to be

The upstream's pull requests say what was **fixed** (runtime-symptoms.md). This file says
what the symptom **looks like on a phone**, what the first move is, and what it turned out to
be - the evidence a case worker needs before it has a cause.

Two sources, marked in the tables:

- **upstream device** - worked out on a phone by the upstream (the symptom table, NEO's
  bring-up, the HDR workflow). The games and the devices are the upstream's.
- **this line** - worked out on this product's own phones (the last section).

**The symptom table is the shortest path.** Find the row that matches what the player sees;
the first move is the thing to do before theorising; the last column is what it was, every
time it has been found so far.

## The symptom table

| You see | First move | What it was |
|---|---|---|
| Coloured or black blocks after moving the view, and they stay | keep the natural incident, look at the raw HDR and the effect chain, then trace the source pixel's draw and depth history (the HDR workflow below) | Odyssey: the compiler missed a reversed perspective multiply/reciprocal pattern in an alpha depth variant; the depth and the material disagreed about alpha coverage, the retained HDR colour overflowed and poisoned the bloom |
| Whole frames dropped, `VK_ERROR_OUT_OF_HOST_MEMORY` at `vkEndCommandBuffer`, as if there were a fixed draw limit | build with the Khronos validation layer (`DM_VK_VALIDATE=1`, the APK must carry it) | an image view made for sampling was reused as a render target - the cache key had no usage in it. Bisecting the draws on the device found *where*, never *why* |
| Thin black slivers over the scene in a few percent of frames, different every frame (triangles with one corner thrown far away) | count them in a recording; check the G-buffer depth under them (nearer than the scene means real geometry); then bisect with `DM_VK_X_SKIPVS`, groups of shaders at a time | XC3: vertex shaders with a rasterizer discard write the transformed meshes inside the G-buffer pass, and Adreno's binning pass let later draws read them unwritten. Fix: a run of such draws gets a pass of its own (`DM_VK_X_RDKEEP=1`). Splitting every draw into its own pass broke Noah's hair: keep a run together |
| Grass or foliage drawn twice (a depth pre-pass, then colour with depth EQUAL) shows black holes in whole blades, different every frame | count G-buffer pixels that have depth but no albedo in the pass dumps (`DM_VK_PASSDUMP_DEPTH=1`); skip each pre-pass writer with `DM_VK_X_SKIPVS` to find whose depth they are | XC3: both passes draw from records that rasterizer-discard vertex shaders write just before, and barriers did not stop Adreno's next passes from reading them unwritten (identical records, instances and shader code; a GPU wait after each submission did not help). Fix: a transfer read of the written memory where the run ends (`DM_VK_RD_TOUCH=0`) |
| Blocks of near objects vanish or change colour for one frame | record the screen (`devtools/rec_flicker.py`) and count the bad frames; screenshots miss it | Adreno's low-resolution depth (LRZ) went stale: at a command-buffer cut between passes that share a depth buffer, and when a pass reloads a depth-stencil buffer in the same command buffer. Fixes: cut only before passes with a new depth image; a one-texel depth read before the load passes (`lrz_touch`) |
| Whole triangles of one draw missing, with straight edges along its mesh, while the camera moves (and at some still angles) | pass dumps over several frames with a fragment shader that writes `gl_PrimitiveID` (`DM_VK_SPV_ALT`): which triangles went, and whether one with `gl_FragDepth` written (so no LRZ) loses any | Adreno applied another depth buffer's LRZ in the binning of a later pass (XC3's cloud dome on its own D32 buffer). Fix: the depth buffer a pass switches away from gets a texel written back (`lrz_touch` in `pass_commit`); a read alone did not drop that LRZ |
| Lights, or other depth-tested draws, go out for a few frames at a time, at a steady rhythm, with the same draws and state every frame; whole halves of quads missing in the pass dumps | A/B `DM_VK_FDZ=0`: a live switch that drops the depth test for the draws that write no depth tells whether the depth hardware is involved | Adreno's early culling on a depth buffer a fragment shader wrote through `gl_FragDepth` (Hades II: Blit2DDepth downsamples the scene depth, then its lights test it). Writing the depth back by transfer and serializing the GPU did not help; the light shader writing `gl_FragDepth = gl_FragCoord.z` did, and that is now the rule |
| Lit areas go dark for one frame while walking, only with command-buffer cuts (`DM_VK_CB_DRAWS=0` clears it) | count the one-frame dark spikes over a recording; A/B `DM_VK_CB_DRAWS`, `DM_VK_SUBMIT_SPLIT`, `DM_VK_LRZ_TOUCH` | a pass going on after a cut, with a depth buffer that was drawn before it (Hades II): such a pass gets a texel of its depth written back first |
| A one or two frame glitch at a transition | `scripts/frame_shots.sh N DIR '<trigger line>'`: is it in the game's output, or added at the presentation? | the second LRZ case above |
| Fog everywhere, the ground missing, dark silhouettes | pass dumps (`DM_VK_PASSDUMP`, with the depth); try `DM_VK_NOCULL=1` | the front-face winding was inverted, so the ground was culled, the depth stayed empty and the fog maxed out |
| The picture is upside down in one mode only | pass dumps of each copy in that mode's chain | the clip-space y convention: NVN's upper-left mode is y-up clip space, so it needs a negative-height viewport with the scissor unflipped |
| Shadows detached from the characters, distant people flickering | check the deferred uniform updates and their shadow copies | a shadow mixed the newest update with memory a still-running GPU copy had not written yet |
| A flat odd colour where a texture should be | the `[alias] stale read` lines (textures over the same memory with other descriptions are separate images). The fallback textures are black, so a non-black flat colour is not the fallback | in NEO this was the LRZ case, not aliasing |
| The frames never appear, and every present waits a second | the present paths that return early | the present ticket has to complete on every path |
| Every draw is skipped as "no program" | the replay counters | NVN binds programs by stage mask: replace only the masked stages |
| A model drawn as a flat black silhouette, with `shader ... is not in the bundle` lines | pass dumps: the depth is there and the G-buffer albedo is black, so its G-buffer draw was skipped | the shaders are in no game file (Xenoblade Chronicles 3: the effects and the model materials). The app captures them (`captured_shaders/`); port again with `--install` to translate them in |
| Noise, or a flat colour, on one material that changes every frame (Fire Emblem: Three Houses: Byleth's irises white with blue specks in close-ups) | pass dumps to find the first pass where it is wrong, then `DM_VK_TRACE_PASS=-2` and `=n` for that pass's draws, and `DM_VK_X_FLAT_VS=<vs>:1` to confirm the draw; the red line `reads texture slot N as 2D rows ... a texture buffer is bound there` | a 1D integer fetch with no type answer is translated as 2D rows of 16384 texels, and this host has no image over a buffer (a dummy). Answer the slot in the profile's `shader_facts_manual.tsv` (`{"tex":{"N":"buffer"}}`; the red line prints the row) and port twice - the first run updates the split tables. A red line `as a texel buffer ... but a texture of target T` means that answer is wrong |
| No sound | the "silent sink" in the log | the real output defaulted on for iOS only |
| A crash in the audio device callback, or "more than 8 AudioOuts open", after a pause-menu mode switch | count `OpenAudioOut` against `Close` | the game closes an output only when its `AudioOut` struct is marked open (the first word non-zero, as the SDK leaves it); unmarked, every mode change opened another one beside the old |
| Buzz, stutter or clicks in the music and the voices, with 0 underruns in the log | record the output (`env.txt` `DM_AUDREN_DUMP=<files>/aud.pcm`), pull it and run `devtools/pcm_glitches.py`: repeats, clicks on the 240-sample frame edges, gaps, clipping; `catch-up renders so far` growing tens a second, and `DM_AUDREN_TRACE` showing pairs of updates under 1 ms apart | Fire Emblem: Three Houses updates twice per renderer event: the catch-up frame for every second update ran its looping stream buffers past the refill (46% of the blocks were repeats). The profile sets `DM_AUDREN_CATCHUP_FRAMES=200` |
| A frame shown twice about once a minute at a steady 60 fps, with `display timing` resyncs and 0 frames late | `env.txt` `DM_VK_PACE_LOG=1`: a present margin that falls steadily between the resyncs (3 ms to 0 in 30-60 s) is the game's frame clock drifting against the display, not GPU load | Fire Emblem: Three Houses' limiter counted each wait from the frame's real end, so every wake-up overshoot slowed its clock (about 100 µs a second); its wait now targets the last planned end plus one frame (the profile's `patches.py`): 19 resyncs in 20 minutes became 0 |
| A crash a few seconds in, `x1 = <code point>` (U+25A1 the commonest) | read the log's `shared font (standard.ttf): <path> (<N> bytes)` lines, then probe the served file's cmap for that code point | the served font lacks the glyph and the game wrote through the null record - see The shared fonts |
| The frame rate falls to 10-15 fps for a second or two at an enemy spawn, then recovers by itself, and the player is left on a lower step | `DM_VK_PASS_TIMES=1` for the costliest passes and their size, and `DM_NVN_VIEWPORT_CALLER=1360x768` to see who resized; read the counters on the burst frames | the engine's own dynamic resolution: it measures its GPU time, and a game that is fast at a low resolution walks the resolution up until it is not. The cost steps with the area and nothing else (0 new pipelines, 0 texture uploads), so an enemy spawn only looks like the cause |
| The main menu is fully laid out - frames, buttons, background - but no label carries any text, and the save-slot timestamp draws as a plain box | `DM_VK_LOG_UPLOADS=1` and `DM_NVN_TEXINFO=512x512`: is the atlas among the first-use uploads, and in what format? | a CPU-filled single-channel atlas (the run's one non-identity swizzle, `(0,0,0,R)`, is a glyph atlas read as alpha from R) that the Vulkan first-use path never uploaded: it covers compressed, packaged and linear textures only, so an uncompressed 2D texture needs `DM_NVN_CPU_TEX2D_FIRST_USE=1`. The box is a different texture, one that does upload |
| The game aborts a few minutes in with `FATAL [vk] GPU timeline wait did not complete: -4` (a device lost), always after a load and never at the idle menu | `DM_VK_CRUMBS=1`: the GPU's last finished pass and the one it never finished. Then `DM_VK_LOG_FS=<key>` and `DM_VK_PIPE_LOG=1` for that pass | one frame too long for one submission: the cut waits for the depth attachment to change, and a title that keeps one depth buffer for a whole scene puts 1213 draws over 97 passes into one command buffer, which the GPU refuses at that size |

## Crashes and boots

NEO (The World Ends with You), on a Lenovo Legion Y700, brought up in about a day. What broke
and what it was:

| The symptom | What it was | The fix |
|---|---|---|
| The boot aborts in `nninitStartup` | the about 3 GB StandardAllocator alias region's alignment depended on where ASLR put it | over-reserve, then trim to a 1 GB alignment (`host/hle_svc.c`) |
| The boot reaches `nnMain`, the NVN init and the save mount, 53 frames, then a null-pointer crash | the RomFS was not fully pushed yet | gone once the files were complete (inferred, not proven on its own) |
| The app crashes with SIGSEGV at `main+0x2a59b4`, and only in the app | the IL2CPP garbage collector scans the main thread's stack from its reported bottom, and with bionic's own stack that range included an unreadable guard page | mmap a 16 MB stack with a 64 KB guard and pass it with `pthread_attr_setstack` (`front_player.c`) |
| The frames never appear; every present waits a second | a present with nothing drawn never completed its ticket, so each waited out its timeout | the present ticket has to complete on every path |
| Every draw is skipped as "no program" | NVN binds the vertex and the fragment programs separately by stage mask, and the replay replaced every stage on each bind | replace only the masked stages |

The lesson that is worth more than the fixes: **when the driver refuses work, run the
validation layer before theorising.** NEO's "draw 712 limit" took hours of guesses; the
validation layer named the cause in one run. And the early per-draw tests were flawed
because they changed only draw 712 while the later draws failed too.

## Black and missing

| The symptom | What it was | The fix |
|---|---|---|
| Handheld only: `VK_ERROR_OUT_OF_HOST_MEMORY` at `vkEndCommandBuffer` from about draw 712 | the image-view cache key ignored usage, so a sampling view was reused as a colour attachment | `attach` went into the view key (`gfx_vk_tex.c`) |
| Fog everywhere, the ground missing, brown silhouettes | the front-face winding was inverted, so the ground was culled and the depth stayed empty | the winding rule (`gfx_vk_exec.c`) |
| The docked 3D scene is upside down, the UI is fine | NVN's upper-left mode is y-up clip space, and every copy was flipped; docked has an odd number of copies | a negative-height viewport with the scissor unflipped (`DM_VK_FLIPY`). A first try (`pass_h - y`) shifted the handheld by 360 rows |
| No draws, or a black screen, in some runs | the shader bundle was looked up before it was opened, and the misses were remembered | `pthread_once` (`gfx_vk_shader.c`) |

## Garbled and flickering

| The symptom | What it was | The fix |
|---|---|---|
| Light-blue 8x8 blocks on the near trees, the bus and the signals | Adreno's LRZ went stale when the frame was cut between passes that continue the same depth buffer | cut only before a pass whose depth image is new in the submission (`depth_sub`); `DM_VK_X_CUTANY=1` restores the old rule |
| The second flicker, a day later: the user reported "flickering every time the camera zooms in-out in a story scene", reproducible with X then B | a brown wipe: 32x32 squares of the old frame drawn flat brown inside the wipe, 2 bad frames per transition | `lrz_touch` in `gfx_vk_exec.c`: one texel of depth read before the passes that load a depth-stencil attachment. 2 of 8 bad frames became 0 of 8, and the GPU and frame times did not change. `DM_VK_LRZ_TOUCH=0` brings the brown back |

How that one was found, because the method is the point:

1. A recording with an X/B script showed 2 bad frames per transition.
2. `DM_VK_CB_DRAWS=0` changed nothing, so it was not the cut rule.
3. `DM_VK_SHOTS=N` saved every presented frame: the brown was in the game's own output, in
   the first two scene frames after the menu's grey frames.
4. The pass dumps never caught a brown frame. Combining the shots with a `DM_VK_PASSDUMP=N`
   countdown showed the dumped frame came out clean while the next was still brown:
   **the dump hid the bug.**
5. Two theories died on counters: the fallback-texture one (the fallbacks are black) and
   texture aliasing (0 stale reads).
6. Each piece of the dump was repeated alone: copying the colour target out was still brown;
   a barrier alone was mixed; **copying the depth buffer out was fixed**; one texel of depth
   was fixed; one texel only before the passes that LOAD a depth-stencil attachment was
   fixed.

## Frozen and stalled

| The symptom | What it was |
|---|---|
| A thread dump leaves the game frozen | the dump took the same slot the released thread was waiting for, so that thread waited for ever |
| A one-second stutter each time an asset loads (frames of 100-580 ms) | the pool lock was held across the hardware buffer's allocation, and every pool lookup waited on it |
| The threads block 350-430 ms of every three seconds | the guest's threads hand work to each other about 2500 times a second, and every sync call took the lock and parked at once |
| The title holds 30 fps with 1 ms of GPU work | the governor counted the present wait as frame work |
| 30 fps on screen although the game runs at 25 ms a frame | the cadence snapped every frame's target to the vsync grid |

## The HDR and NaN workflow

Use it when block corruption appears after moving the view and then persists, or when a few
bright pixels contaminate the bloom. It is not a general image-correctness detector: finite
wrong colours, absent geometry and wrong lighting have no NaN in them.

1. **Keep the natural incident.** Save the screenshot, the log, the process id, the driver
   and the active switches before any probe. Ask the user to stop when the error is visible.
   Never inject a confirm press into the user's save to reproduce it.
2. **Look before the tone mapping.** Ordinary PPM output clips HDR above 1, so an apparently
   solid white is not evidence of an overflow. `DM_VK_PASSDUMP_RAW=1` keeps the float32 RGB
   in `pass_NN.ppm.f32`.
3. **Find the HDR target.** Use a pass list (`DM_VK_TRACE_PASS=-2`) to identify it, then
   look at the colour and the post-processing inputs separately. The pass numbers change
   while the view moves: match the dimensions, the formats, the target identity and the
   shader keys, not the number.
4. **Capture the transition, not only the result.** A clean strip says nothing about the rest
   of the frame. Keep the last finite batch and the first non-finite batch, with the present
   ids. `finite -> large finite -> infinity` proves growth; a missing interval does not
   identify the first bad write.
5. **Find the depth writer.** `DM_VK_PIXEL_HISTORY=WxH:X,Y` records one pixel after each
   logical draw and clear (kind 1 is a colour clear, 2 a depth clear, 3 an array draw, 4 an
   indexed draw). Look at the depth changes, the retained HDR words and the candidate
   vertex and index buffers.
6. **Do not trust a probe that cures it.** Pass cuts and transfer reads are intrusive. If the
   poisoned pixel comes out clean under the history capture, the capture changed the
   pass/copy behaviour: investigate that instead of treating the history as an untouched
   frame. Never use these runs for performance comparisons.
7. **Verify the repair in the same process.** Original, then candidate, then original
   again, at an unchanged view: a diagnostic clear can erase the retained colour
   irreversibly, and the disappearance alone then cannot identify the first cause. Close
   the loop with ordinary shader data after a restart, then with the user's own movement,
   turns and scene transitions. A compile check is not a gameplay check.

The Odyssey case (2026-11-12) that the workflow was written for: a depth alpha shader kept
the perspective multiply and reciprocal round trip while the material used direct
interpolation, and the existing compiler rule missed the reversed outer operands. The
inconsistent alpha coverage left the depth with stale colour, a later multiply amplified it
to infinity, and the bloom spread the NaNs into blocks. The repair is upstream PR `#316`.

## The dead ends

As valuable as the fixes: the theories that were tested and ruled out, so nobody spends the
time again.

- **On the refused submissions (NEO):** unclipped blits, 8-bit indices, an MSAA mismatch;
  descriptor pool exhaustion, the scratch block boundary, a command-buffer size cap; UBWC
  format lists, the Float64 capability, pixel interlock; depth bias, quads, rasterizer
  discard, zeroed uniform buffers; "an Adreno limit of about 712 draws per submission".
- **On the tree flicker (NEO):** swizzle or re-upload (0 uploads a frame), `ViewportInverse`,
  a CPU/GPU race (instrumented: 0 racing reads in about 3 million), UBWC. NEO's near-black
  shadows are the game's real style, confirmed against screenshots.
- **On the HDR corruption:** the fallback-texture theory (the fallbacks are black) and
  texture aliasing (0 stale reads) both died on counters.
- **On the GPU abort (STAR OCEAN THE SECOND STORY R):** memory (8.1 GB available, the pool
  flat at 1.1 of 3.2 GB, the live texture count flat for 23 minutes); the descriptor pools
  (966 new sets a frame against a pool of 4096, which grows another pool instead of
  failing); an empty colour slot 0 (that one loses the device *every frame* - this run held
  30 fps for 25 minutes and died in one pass, after one load); a compute dispatch (none ever
  ran); the two `vkQueueSubmit -4` sites, which are where the loss is noticed and not where
  it happens. The LRZ texel transfer died the same way: turning it off moved the death and
  did not stop it.
- **On the frame-rate cliff (Bayonetta 3):** shader and pipeline compilation and texture
  uploads are excluded by the log itself (`new pipelines 0`, `texture uploads 0` on every
  burst frame); the CPU side is cheap throughout (replay 1.2-3.5 ms against a 94 ms GPU
  frame); the draw count does not explain it (draws 3.1x, the frame 12.7x, and the
  correlation between the GPU time and the draws a frame is +0.52). What is left is the
  area: the cost per pixel is the same at every rung.

## The measurement traps

- The CPU may be capped by policy, not by heat: the Y700's `scaling_max_freq` stayed at
  about 2.0 GHz (of 3.5 / 4.3) at 55 °C. `dumpsys thermalservice` showed cached
  temperatures (93 °C) hours old; read `/sys/class/thermal/thermal_zone*/temp` instead.
- Dropped submissions leave stale GPU timestamps.
- One thermal sysfs zone can be stale; read several.
- The GPU clock is not readable on retail devices; infer it from the GPU busy at a fixed
  rate.
- `simpleperf` is refused on retail devices, and an in-process SIGPROF sampler crashed the
  game: use the replay's timers and per-thread `/proc` sampling.
- Runs that reached different scenes are not comparable.
- A replay that is slow while no thread is near a full core is reading slow memory: the
  `hardware buffers:` start-up line gives the CPU's read speed against malloc memory. On a
  Retroid Pocket 5 every hardware buffer and every Vulkan memory type, HOST_CACHED
  included, is read at 1.7-1.9 GB/s (`DM_VK_ZEROCOPY_LAZY`).
- On a Retroid Pocket 5 the kgsl counters stand still in its high performance mode: they
  read the same for minutes and say nothing.
- Pass timestamps disturb Adreno's binning, so compare runs only.

## The shared fonts

A game's system text - the standard, Chinese and Korean faces, and the button icons - is
served by `dm/host/hle_sdk16.c` out of three sources, in a fixed order. The font crash and
the text that draws as "?" both come from not knowing which source won, so the chain
first:

- **type 0** (standard): `DM_SHARED_FONT` -> the console's own font from system data
  (`DM_SYSDATA`, porter `--firmware`) -> the platform's font (Roboto on Android).
- **types 1-4** (Chinese, Korean): **`DM_SHARED_FONT` set: that one file, and the console's
  regional files are not opened at all.** Unset: the console's regional font -> the
  platform's CJK font -> the standard font.
- **type 5** (button icons): the console's own font from system data first, then the
  generated stand-in.

**An app port always sets `DM_SHARED_FONT`** (`porter/pipeline.py` `shared_font()`): the
player's own font with `--shared-font`, else the payload's `dm/tools/shared_cjk.ttf`. So on
this product types 0-4 are always that one file, and a firmware dump only ever reaches type
5. The app has no `--shared-font` input today (the porter config has no key for it), so the
env.txt edit below is the only override a case worker can make.

**What the shipped font is** (every cell measured with a cmap probe, not assumed):

| Font | Glyphs | post | Latin/punct | CJK/kana | symbols □■●★→①℃ |
|---|---|---|---|---|---|
| OpenSans-Regular.ttf (the Latin base) | - | 2.0 | yes | none | none |
| DroidSansFallbackFull.ttf (the CJK source) | 49,382 | 3.0 (no names) | **none** (not even an en dash) | yes | none |
| shared_cjk.ttf (the merge; the payload ships it) | 32,052 | 2.0 | yes | yes | **none** |

"Full" in DroidSansFallbackFull means full coverage of its own scripts: it is AOSP's
ideograph companion of Roboto, which draws the symbols on phones. The merge exists because
a game that parses the font itself (Mario vs. Donkey Kong) builds its glyph table from post
2.0 glyph names, and the raw Droid font crashed on the null glyph of every lookup. The
charset `uni` in `cjk_font.py` carries no symbol block - and adding blocks to `uni` alone
fixes nothing, because **neither source font has the glyph outlines**. A symbol donor has
to be a third font: an open-licensed symbol font (DejaVu Sans, Noto Sans Symbols) merged in
by `cjk_font.py`. Not macOS's Arial Unicode (it has every symbol but is an Apple font and
must not be redistributed), and not the player's phone fonts (not reproducible in the
repo's build). Kana is already in through Big5 - do not chase it.

**The crash and the first moves.** The signature is
`CRASH sig=11 addr=0x38 x1=<code point>` seconds in (Super Smash Bros. Ultimate 13.0.5:
4.86 s, `x1=0x25a1`, the game's fallback glyph; Echoes of Wisdom was upstream `#470`):
x1 IS the missing code point, and the game wrote through the null record its lookup
returned.

1. Read the log's `shared font (standard.ttf): <path> (<N> bytes)` lines: which file was
   served and its size. The shipped font is 3,163,172 bytes; another size is a player's or
   a device font, and the diagnosis starts from that file.
2. Probe the served file's cmap for the code point before changing anything.
3. **The one-line workaround, no re-port**: the data pack's `env.txt`, `DM_SHARED_FONT` ->
   a device font that has the glyph and covers CJK. Measured on a HONOR phone:
   `/system/fonts/DroidSansChinese.ttf` (28,432 glyphs: U+25A1, U+25CF, the arrows),
   `/system/fonts/HONORSansVFCN.ttf` second. It must be a static TTF: a TTC is not served,
   and a variable font crashed Super Mario Odyssey's font engine once already. The host
   mmaps it at launch, so a restart is enough.
4. The workaround outlives a re-port (a port writes nothing under `tool/`) but not an app
   payload refresh, which re-extracts `tool/` and puts the shipped font back. The durable
   fix is one commit in the repo: a symbol donor in `dm/tools/`, the blocks added to
   `cjk_font.py`, `shared_cjk.ttf` regenerated and shipped with the payload.

Where the fix belongs: `nxnative/dm/tools/cjk_font.py` + the shipped `shared_cjk.ttf` (the
asset IS the fix; a port never builds the font). The host is not at fault - it serves what
it is told. Upstream's answer, the console's own fonts from system data (#311, #287,
#422), only reaches players with a firmware dump, because the app port's `DM_SHARED_FONT`
outranks system data for types 0-4.

## This line's own findings

These are this product's own, on its own phones (the Legion Y700, a Xiaomi 23117RK66C, an
OPPO PLP120, a HONOR AAP-AN00). The games are named; the upstream's are not.

| The symptom | What it was | The fix |
|---|---|---|
| Frame generation on, and the game turns into slow motion: the source rate falls from 60 to 19 and the picture is presented at 30 | the window asked the system for the **game's** frame rate, not the generated one, so two presents could not fit into one 16.6 ms refresh and the present thread ran at half speed | the present thread passes the output period to the vote, and the vote asks for `1/out_period` when generation is on (`gfx_vk_present.c`) |
| Frame generation on, and the host's own adaptive rate steps the game down to 40 and then 30, with `late on screen N of 64` in the log | the adaptive governor assumes one frame is one present, and counts the wait for the present thread as frame work at the 60 step; with generation on, that wait grows by design | the wait for the present thread counts as pacing, not work, while generation is on (`dm_perf_pq_pace()`) |
| Frame generation drags the game below the floor: the source rate falls to 19, and it keeps falling | the multiple mode has a fixed number of generated frames per game frame, with no brake: the game slows, the generation continues, and it gets slower | a floor on the game's rate (`DM_LSFG_MIN_FPS`, default 30); it only applies to a slowdown the generation caused, so a game that is slow by nature still generates. A first version failed because the reference rate was updated from the slowed frame, and the floor let itself go |
| The target frame rate is set to 60, the source is 60, and the HUD reads `60 -> 91 fps` | the pacer's state carried over from the previous mode: the limit left at 1 by the multiple mode, and the target mode never lowers it | a mode change resets the limit, the output allowance and the probing state (`lsfg_pacer.c`) |
| The HUD says "charging 27%" and never shows the wattage, whether the charger is plugged in or not | not an app bug: the device was left in a battery test mode and the sticky battery broadcast had been frozen for eleven days | read the battery state rather than the sticky broadcast (`dumpsys battery` says so) |
| A 4.2 GB resource archive: the port dies in the shader split with "this file does not fit in this device's memory" | the phone has less memory than the file; the split reads a file whole | mmap the file instead of reading it into memory: the file stays on disk and the pages are read as they are needed. Note that an mmap object is not bytes: it has no `index`, `split`, `startswith` or `decode`, and `in` means something else, so a reader written for bytes turns a clean red line into a traceback |
| A character's partner is a flat coloured silhouette in a split-screen match, in both viewports | the game decides the tint by whether the partner is occluded, and the host judged it occluded always | **not fixed yet**, shipped as a known issue. The chain was dumped end to end: the scene depth pre-pass, the G-buffer, and the marker pass that paints the player id into the RGB and the stencil test. One real bug was already fixed on the way: the depth/stencil compositing guessed the plane from the view format, so the post-processing read the stencil bytes as depth |
| The black border on the left of a landscape game is wider than the one on the right, on a phone with a punch hole | not rendering and not fitting: the system's punch-hole policy cropped the **window**, and the app's decor still reported the cut-out inset, so the content box was cropped too | declaring the cut-out mode is not enough on its own: the window has to be let through, and the inset ignored. The presentation (proportional fit, centred) and the input rectangle were already symmetric |
| The build dies on the phone with "this file does not fit in this device's memory" | the same 4.2 GB archive: the split reads a file whole and the phone has less memory than the file | the same: mmap |
| The app is killed in the middle of a build (the build page says "the port was interrupted"), always at the same stage | not out of memory and not a pipeline crash: MIUI's battery manager (`com.miui.powerkeeper`, log tag `SmartPower`) force-stops a build that keeps every core busy for minutes as abnormal power drain, even in the foreground (`adj 0`). Twice it died at the same line, "stage 5/8: Vulkan shaders / translating 66,230 shaders" | the app asks for the battery policy to be "no restrictions"; the player can raise the power, clear the memory, allow the background run and lock the app in the recents |
| A build ran 36 minutes and produced no data pack | two causes on top of each other: the profile registered the real version (`v65536`), so the version folder changed and every stage's cache key changed, and the whole chain ran again - that is the one-off cost of bring-up; and an App update at 17:50:29 restarted the app before the packaging, and the build process (the guest python) died with it, so only the build folder's cache was left | register the real version once, early; keep the App from updating during a build. Every finished stage is cached, so a build that dies catches up in seconds when it is started again |
| The port stops at the shader split with "found no shaders in main or the RomFS" | the game keeps its shaders in Rockstar's RPF6 archives: the entry and name tables are AES-256 ECB encrypted and the resources are zstd-compressed, so the split's magic scan sees nothing. The shaders are there - 3 628 of them | the reader is in the phone's case file (`nxnative/dm/gfx/tools/rpf6.py`), ported from the engine upstream: the key is searched for in `main` and never leaves it. Verified on the phone: the split ends with 3,628 shaders (vertex 2,134, pixel 1,494), and its three tables are byte-identical to the registered profile's rows |
| Black after the intro movie, and the draws per frame fall from 131 to 2 | the game opens a second audio renderer for its movies; the host drives one, so the second renderer's event never fires and the game waits for it inside the movie: it keeps presenting at 60 fps but stops drawing the scene | the second renderer gets its own host thread and its own output. Verified on the phone: the log reads "audren: second renderer driven by a host thread", the draws per frame are back to 131 (2,615 on the title screen), and the frames are the title screen instead of 95.7 % black |
| The characters far from the camera are not drawn at all, and they appear when the player walks up to them | the backend fakes the NVN SAMPLES_PASSED counter with a constant, and the game takes the difference of two reports: it reads 0, so every distant character is on the occluded list and its low-detail body is dropped | **not fixed yet** - the plan waits for the player. The shape to remember: a constant that makes a single report read "visible" makes a game that takes the difference read the opposite |
| The game dies one page below its own stack | the gate (the rewriter's svc replacement) ran on the guest thread's stack, and the host's printf frame ran off the end of a thread with 1.8 KB left | the dispatch runs on the thread's host stack instead; the gate's own frame stays on the guest's, because the handler writes the guest's registers back into it |
| The frame rate collapses at an enemy spawn and recovers by itself, and the player is left on a lower step | the engine's own dynamic resolution ran up to native: the game was fast at a low resolution, so it spent the headroom on more pixels. The counters say so - 0 new pipelines, 0 texture uploads on every burst frame, and the GPU time steps with the area (18 -> 60 -> 103 ms) while the size steps 640x360 -> 960x540 -> 1120x628 -> 1360x768; the engine is caught calling `nvnCommandBufferSetViewports` at the big size | `DM_DYN_SCALE=high` in the profile's env rows: the engine reads the GPU clock k times as fast and believes the GPU is slow, so it stops taking the top rungs. Measured: the settled state holds 30 fps with no frame above 29.83 ms, while the fight that started with the ladder at the top ran at about 10 fps |
| The main menu is laid out but no label carries its text, and the save-slot timestamp draws as a box | the CPU-filled glyph atlas was never uploaded: the Vulkan first-use path fills a texture from guest memory only when it is packaged, linear, block-compressed or over a CPU-uncached pool, and an uncompressed 2D texture needs `DM_NVN_CPU_TEX2D_FIRST_USE=1`. A texture the game flushed before its first use has a second rule (the Metal path's `nvn_flushed`), which the Vulkan path does not have | the switch, as a profile row (`env`), so it survives a re-port. The second rule (the Metal parity for a flushed texture) is **not in this checkout yet** |
| The game aborts 4-5 minutes into the main menu with a GPU device lost | the frame reached the GPU as one submission: the cut waits for the depth attachment to change, and this title keeps one depth buffer for the whole scene, so 1213 draws over 97 passes went into one command buffer. The size is the cause, not the draws: skipping the draws the crumbs named moved the death but not the place | the cut stops waiting for the depth change (`DM_VK_X_CUTANY=1` in the profile, so the core default is untouched), and the parts are bounded (`DM_VK_ORD_PARTS` for a frame heavier than 15 x 128 draws) |
| A PlatinumGames game (a PKZ archive) is refused at the extract stage, with "needs a step that boots the game in the Mac host" | the porter treated "Oodle-compressed" as a GFPAK or a Trinity pack only (it matched the words of the description), so the PKZ pack was sent to the Mac host. The decoder was never missing: the game's own streaming decoder is in `nxnative/dm/host/hle_oodle.c` and already runs on the phone | the PKZ entries are decoded one entry at a time over a pipe (`DM_OODLE_HOST`), and the split counts the entries it cannot decode and leaves them out instead of stopping (`case/01004A4010FEA000-pkz`) |
