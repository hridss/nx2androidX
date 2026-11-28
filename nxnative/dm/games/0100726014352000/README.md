# Diablo II: Resurrected (`0100726014352000`)

Update 1.0.34.0 (v2228224, with Reign of the Warlock; PC build 3.3.94012), SDK 11.4.3, NVN. Blizzard's D2R engine, FMOD, its data in Blizzard's CASC storage (`data/*.data`, 40 archives, 8.4 GB, listed by text manifests). Modules `main`, `subsdk0` (NintendoSDK_movie) and `sdk`. This is the game d2r-mobile, where this repository's approach comes from, was written for; nothing of its code is used here.

On the Mac (2026-11-12, from a fresh save): the Blizzard logo movie, the title, the gamma, screen-space and colour-blind calibration screens, the Act I opening cinematic (skipped by holding A), the main menu, the Online / Offline choice (Offline), hero class selection (the lit 3D heroes around the fire), the game modes, naming the hero (`DM_KBD`), and the Rogue Encampment as a Barbarian: walking, the automap, the camp's palisade and gate. No `[RED]` line, sound, 2.79 GB physical footprint in the camp. 30 fps with the game's own present interval (GPU 6 to 7 ms a frame), 60 fps with `present_interval display` (59 to 60, CPU 65 % of one core) at the same game speed. Offline: "Connection to Battle.net required" on Online, as expected. Not run on the iPhone; no fight yet.

## What it needs

| Setting | Where | Why |
|---|---|---|
| Offline play without a recent online login | [`patches.py`](patches.py) | the game allows offline play only within 30 days of a Battle.net login; see [Found on this game](#found-on-this-game). Bound to the game: `CUTS` for 1.0.34.0 and 1.0.33.0 (this fork's rewriter reads `CUTS` only; its `MATCH_CUTS` table is upstream's and is not applied here) |
| `present_interval display` | [`game.tsv`](game.tsv) | the game asks for interval 2 (30 fps, as on the Switch); its logic runs on its own clock, so at 60 fps the same 3 s walk from the Rogue Encampment's start ends at the same place as at 30 |
| 4 GB layout: `mem_reserve 0x100000000`, `app_pool 0xCD500000`, `mem_used 0x4c00000` | `game.tsv` | the survey's guesses held: no system resource, `main` sets a heap of the pool less its modules (3.14 GB on the Mac); the rewritten modules are 72 MB, over the default 64 MB |
| `shader_groups /data/=data` | `game.tsv` | the CASC storage; see [Shaders](#shaders) |

## Shaders

- **Current:** 6,931 (2,344 vertex, 4,559 pixel, 26 compute, 1 hull, 1 domain): 6,922 from the `SHADSHAD` containers in the CASC storage (`data/hd/shaders/*.bin`, 162 files, 10,847 records), 9 from `main` ([Blizzard's CASC storage and SHAD containers](../../../docs/reference/shaders.md#blizzards-casc-storage-and-shad-containers)). The split reads all 132,413 current files in 12 s.
- The storage's `build_key.txt` names the current build's config key, and `build_manifest.psv` (the manifest with that key) lists each file's archive, offset (the encoded key's last five bytes), stored and decoded length and encoding: 98,298 zlib streams, 997 stored, 33,118 BLTE containers (music and video). `base_manifest.psv` lists the base game's build.
- Each shader file holds one stage (its name starts `vs_`, `ps_`, `cs_`, `hs_`, `ds_`) and its permutations; the game pairs vertex and pixel shaders per draw, so every vertex shader is paired with a cover of the pixel shaders' inputs (one pixel shader).
- `main` holds 10 shader records (3 vertex, 3 pixel, 4 compute; two of the vertex ones are the same shader) in the same form as the containers', which its code points at.
- All 38 programs the game asked for up to its title (dumped with `DM_NVN_SHDUMP` before the readers existed) are among them. The hull and domain shaders (`hs_terrain_h`, `ds_terrain_d`) were not seen drawn.

## Mac routes

- Fresh save: about 50 s to the title; A, then A on each of the gamma, screen-space and colour-blind screens, the cinematic starts; Plus shows "Hold any button to skip" and A held 3 s (`A~3`) skips it. The main menu opens with tutorial tips (A three times), then New Character, Down and A for Offline, A on a class, A to select it, A to accept the game modes, A for the software keyboard (`DM_KBD=<name>`), Plus to accept: the Rogue Encampment.
- With a character: after the logo movie and the cinematic (`Plus`, `A~3`) the main menu shows Play.
- The camp's gate is on the side of the river, from the start to the right and down (the automap is on Minus). The left stick moves, A attacks.

## On Android

Update 1.0.33.0 (`v2162688`, the player's files; 1.0.34.0 not available) on the OnePlus PKX110 (Snapdragon 8 Elite, Adreno 830v1, Android 17) with a Turnip driver, through the app, on 2026-11-28: the title (its "press A to start" with Chinese glyphs), the colour-blind calibration screen with its 3D scene, the Act I opening cinematic skipped (Plus, A held), the Online/Offline choice (Offline, no Battle.net error — the cut is registered for this build too), the class selection, the game modes, naming the hero through the software keyboard (`DM_KBD`), and the Rogue Encampment with the full HUD (health 55/55, mana 10/10, the skill bar) at the game's 30 fps with sound. Anything past reaching the camp is not verified.

The player's merged XCI's DLC partition fails the NCA's hash check (`ResultFsNonRealDataVerificationFailed`); its DLC carry a licence and no content, so `nx_extract --allow-damaged` (which the DLC extraction now passes) skips it with red lines and the port goes on. The rewriter applies the offline cut through the build row this profile now registers for 1.0.33.0 (this fork's rewriter reads `CUTS` only). The shader split reads this build's CASC with the readers this fork grafted from upstream ([Found on this game](#found-on-this-game)): 6,063 shaders (2,344 vertex, 3,690 pixel, 27 compute, 1 hull, 1 domain; sources data 6,054, main 9).

Three things stood between the first build and a visible frame: the game asks for 95 shaders the split did not find in 1.0.33's data (captured at run time over the first three builds; each build translates the last one's captures in, and the captures have since converged to none), 20 of the translated shaders read texture slot 136 as a 1D fetch while a texture buffer is bound there — answered in [`shader_facts_manual.tsv`](shader_facts_manual.tsv), without which their draws came out black (the hero and the camp's ground) — and the glyph atlases are CPU-filled as Diablo III's are, so [`android.tsv`](android.tsv) sets `DM_NVN_CPU_TEX2D_FIRST_USE=1`; without it not one label drew. The virtual gamepad did not draw during the bring-up session (its state in the app's drawer, not the port's), so walking is not verified; the menus answered every injected key.

## Found on this game

General tool changes first needed here; each applies to every game:

- **Blizzard's CASC storage and SHAD containers** (`dm/gfx/tools/casc.py`, `casc_jobs` / `casc_shaders` / `shad_shaders` in `shader_split.py`, and `raw_shaders`'s records in `main`): [Blizzard's CASC storage and SHAD containers](../../../docs/reference/shaders.md#blizzards-casc-storage-and-shad-containers).

Game behaviour:

- **"Failed to authenticate" at the title** (current, fixed by the cut): ["Failed to authenticate" at the title](../../../docs/reference/game-state.md#failed-to-authenticate-at-the-title-offline-play-only-after-a-recent-online-login). The title's login (`main+0x2d0710`) asks the account request state machine (`main+0xe114b0`), which fails offline; whether the game then allows offline play is the flag `main+0x1978ac` sets from the saved "User Last Online" time. The cut sets it. d2r-mobile's notes on a community offline fix (`LFS Mod Offline Fix`) first described the check, with two more comparisons (`main+0x5b14dc`, `main+0x5f3184`) whose meaning they did not find and a cut that skips the logo and title; none of those is needed here, and none of its bytes is used.
- **The cut on other builds** (2026-11-12): the function that reads "User Last Online" (`main+0x1978ac`, 316 bytes) is a `MATCH_CUTS` rule upstream, where a copy of 1.0.34.0's `main` with another build ID rewrites byte for byte as the registered build and both cuts land `[function match]`. This fork reads `CUTS` only, so another build carries its own row: 1.0.33.0 (`19436173…`) got one on 2026-11-28, its cut sites at `main+0x19741c` / `+0x197424` with byte-identical originals (the function's tail at `main+0x1973dc`, the key at `main+0x1d4ad9f` through `main+0xdeecd0`, the clock `main+0x1b62b30`, the flag a byte at `[x20+0x2c]`; found from a player's 1.0.33 dump). The base game (v0, `main` 4de22400…) has no such check: it only writes "User Last Online" after a Battle.net login (`main+0x206bf4`), and no 30-day constant is in its code, so it needs no cut (its rewrite logs the rule as skipped)

## Open

- Not run on the iPhone.
- No fight yet: the route reached the camp's gate in the 10 minutes of a gameplay test.
- Long frames in the camp, about six a minute at 30 fps: 80 to 105 ms with submit and GPU at 5 to 7 ms (the game's own CPU work), and single first-use pipeline builds (50 to 90 ms).
- Only update 1.0.34.0 is registered; the base game alone (v0) is not checked.
