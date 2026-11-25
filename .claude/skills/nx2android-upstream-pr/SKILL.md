---
name: nx2android-upstream-pr
description: How to contribute a port to the upstream engine repository (shirai91/nx2android): the branch and the GitHub fork a pull request needs, what a port's pull request carries (the profile, the host rows for that build, the tool fixes and their tests, the games table row, the device image, the checklist line), the mechanical procedure (check the upstream's open pull requests first, carry the profile in the upstream's shape, pass the upstream's own gates), the review's recurring demands, and the commands that open, update and answer it. Use whenever the task is to push a port upstream, open or update a pull request against shirai91/nx2android, rebase a pull request onto the upstream main, answer the maintainer's review, or decide what a contribution to upstream has to carry. Bringing a game up on the phone is the nx2android-dev skill's; cutting a release is nx2android-release's.
---

# nx2android: contributing a port upstream

Upstream is **shirai91/nx2android**: the engine repository, where the game profiles and the shared core live and where the games table is kept. This checkout (zFitness/nx2androidX) is the product line: the phone app, the release line, and its own `main` (the upstream mirror, fast-forward only), `android` (product and release) and `next` (integration). A port reaches upstream as a **pull request**; this is that procedure, and the traps that come with it.

The port itself (the profile, the phone, the bring-up) is the **nx2android-dev** skill's ([.claude/skills/nx2android-dev](../nx2android-dev/SKILL.md)); the whole flow from a phone case to both pull requests is the **nx2android-case-pr** skill's ([.claude/skills/nx2android-case-pr](../nx2android-case-pr/SKILL.md)); a release is **nx2android-release**'s ([.claude/skills/nx2android-release](../nx2android-release/SKILL.md)). What is here is the part that leaves this checkout.

## The pull request needs a fork of upstream

GitHub wants a fork relation: `zFitness/nx2androidX` is **not** a fork of the upstream (it is the product's own repository), so a pull request against `shirai91/nx2android` needs one:

```bash
gh repo fork shirai91/nx2android --clone=false        # zFitness/nx2android
git remote add fork git@github.com:zFitness/nx2android.git
```

Every `gh` command about the pull request names the repository it acts on: `--repo shirai91/nx2android` for the pull request, `--repo zFitness/nx2android` for the branch's own repository.

## The branch

A contribution is a branch off the **current upstream main**, in a worktree (the main checkout keeps the product line):

```bash
git fetch upstream main                                  # the truth; the local mirror and the fork's main go stale
git worktree add ~/.local/share/opencode/worktree/<slug> -b feat/<slug> upstream/main
```

The local `main` mirror and `zFitness/nx2android`'s own `main` both lag the upstream (the mirror is synced on its own schedule, the hub change's "upstream sync" line). Always `git fetch upstream main` before branching or rebasing, and compare with `git rev-list --left-right --count HEAD...upstream/main`.

## Before you write anything: the upstream's open pull requests

**The upstream has over a hundred open pull requests, and the one you need may already be there.** The
work of a port is often a feature another contributor is already asking for: the online SDK opt-in of
#430 (Tetris Effect's EOS NRO) is exactly what TMNT needed, and the MGFX v10 reader of seedds/nxnative
#2546 was closed unmerged, so it had to be carried by hand. Write the implementation first and you
write it twice.

So before the first line of code, look:

```bash
gh pr list --repo shirai91/nx2android --state open --limit 200 --json number,title \
  --jq '.[] | select(.title | test("opt-in|online|EOS|shader|split"; "i")) | "\(.number)\t\(.title)"'
gh pr view <n> --repo shirai91/nx2android --json files --jq '.files[].path'
gh pr diff <n> --repo shirai91/nx2android > /tmp/pr<n>.diff
```

**Take a shared implementation from the upstream pull request that owns it, unchanged.** Then the two
pull requests carry the same hunks, whichever lands first the other one rebases to nothing, and the
maintainer merges them in any order. To apply only the files you want:

```bash
python3 - <<'EOF'
import re
src = open('/tmp/pr430.diff').read()
parts = re.split(r'(?m)^(?=diff --git )', src)
keep = {'nxnative/dm/host/hle_ro.c', 'nxnative/dm/tools/nro_collect.py'}
open('/tmp/core.diff', 'w').write(''.join(p for p in parts
    if (m := re.match(r'diff --git a/(\S+)', p)) and m.group(1) in keep))
EOF
git apply -3 /tmp/core.diff          # -3: the three-way merge does it when the context moved
```

**A shared piece belongs to the pull request that owns it.** The upstream keeps the shared keys and
their behaviour in one place (the `online_sdk 1` key of #430, the `DM_ONLINE_SDK` switch it reads),
and the game rows in their own pull requests, so that a game you did not port cannot ride along in
yours. When your game needs a feature that another pull request is still waiting for, say so in the
body and keep your pull request to what is yours — and carry the shared piece **identically**, so the
two merge.

**Where the material comes from**, cheapest first:

| From | What |
|---|---|
| the upstream's own open pull request | a shared implementation, its test and its documentation |
| this line's case branch (`case/<title ID>-<slug>`) | the profile, in the upstream's layout already |
| seedds/nxnative's pull requests | a reader that was closed unmerged (read the diff, merge the hunks by hand) |
| your own new code | only what nobody else has |

## What a port's pull request carries

| Part | Where | Note |
|---|---|---|
| The profile | `nxnative/dm/games/<title ID>/` | `game.tsv`, `android.tsv`, `patches.py`, the shader tables |
| The host rows **for that build** | `nxnative/dm/host/hle_*.c` | per-build tables keyed by the NSO build ID: a regional or other release is **its own binary**, so it needs its own rows (the allocator's object and vtable, the console variables) |
| The tool fixes the port needed | `nxnative/dm/gfx/tools/`, `nxnative/scripts/`, `porter/` | with their tests (`nxnative/dm/tools/tests/test_*.py`) |
| The game's own page | `nxnative/dm/games/<title ID>/README.md` | the facts block is made by `docs/gen_tables.py`; the five sections under it are written by hand (no `TODO:` left) |
| The games table row | `README.md` | with the device's gameplay frame in `docs/images/<slug>.jpg` (1280x720) |
| The checklist line | `docs/PORT_CHECKLIST.md` | the game ticked, and the `N / M ported` count **with the total raised too** |
| The profile's readme rows | `android.tsv` | `readme_name`, `readme_image`, `readme_tag` (`v<major>.<minor>.<patch>`): they put the game into the README's games table and give it its own page |

A pull request may carry more than the profile — the tool fix the port needed is part of it (the AES
provider that only a Mac had, for instance, is what made the phone able to decode an encrypted pak at
all).

## The upstream's gates

The upstream's own tests are the specification of a profile; read them **before** writing one, not
after a failure. Four files hold the rules (a few KB each, cheaper than a wrong guess):

| File | What it decides |
|---|---|
| `nxnative/dm/android/tools/android_profile.py` (`check()`) | the keys an `android.tsv` may carry, the status values, the `readme_*` rows, and that a profile's `env` key has a row of its own in `nxnative/dm/settings.tsv` (scope `profile`) |
| `tests/test_settings.py` | every row of `nxnative/dm/settings.tsv` is read by some core source: **a registered switch and the source that reads it travel together** |
| `tests/arch_rules.py` | what a profile may and may not contain |
| `docs/GAME_README_TEMPLATE.md` | the five sections the game's own page must have |

The rules that bite, in one place:

- **A profile's `env` key needs a row in `nxnative/dm/settings.tsv`** (scope `profile`), or the port
  fails with "no such setting". The row in turn needs a core source that reads the switch, so the
  registration and the reader are one change — they cannot be split over two pull requests.
- **`readme_image` must be a file in the repository** (the check looks for it), so the device frame
  comes before the tables are made.
- **The device frame is 1280x720**, the game's own picture, with the on-screen buttons on (the
  upstream's own frames have them) and without the phone's status bar. The player takes it on the
  device; the frame has to be in the repository before the tables are made (`readme_image` is checked).
- **The version is the update NCA's own number** (`1.0.z` → `z * 65536`, so 1.0.9 is v589824), and it
  belongs in `build`, `package`, `shader_versions`, the `tested` row and the `patches.py` comment.
  Verify it against a public listing (tinfoil.io's title page; a release's own update series), and
  say in a comment where the number comes from.
- **`docs/gen_tables.py` makes the tables**: the README's games table, the game's own page and the
  checklist's count. The checklist's **row** is written by hand (the script only ticks it and counts).
- **The quick gate, before the full suite** (seconds, not minutes):

```bash
python3 -m unittest tests.test_docs_tables tests.test_settings tests.arch_rules
python3 docs/gen_tables.py --check          # the tables are what the profiles make
```

The full suite is `python3 -m unittest discover tests` (660 tests, about 90 s); run it once, at the
end. `nxnative/dm/tools/tests/` is a second suite the CI does not run: some of its tests fail on the
upstream's main already (check with `git stash` before blaming your change).

## The procedure

```bash
# 1. the truth, and the open pull requests that may already carry the feature
git fetch upstream main
gh pr list --repo shirai91/nx2android --state open --limit 200 --json number,title \
  --jq '.[] | select(.title | test("opt-in|online|EOS|shader|split"; "i")) | "\(.number)\t\(.title)"'

# 2. the branch, off the upstream's main
git worktree add ~/.local/share/opencode/worktree/<slug> -b feat/<slug> upstream/main

# 3. the profile, in the upstream's shape (the case branch already has it in that layout)
git checkout <case branch> -- nxnative/dm/games/<title ID>/
#    the shared implementation, from the pull request that owns it
git apply -3 /tmp/core.diff

# 4. the tables (the README's games table, the game's page, the checklist's count)
python3 docs/gen_tables.py

# 5. the gates: the quick ones, then the whole suite
python3 -m unittest tests.test_docs_tables tests.test_settings tests.arch_rules
python3 -m unittest discover tests

# 6. the fork's world must not be in it
git diff upstream/main | grep -iE "the fork|nx2androidX|phone_tools|app-debug|packaging/android-apk"

# 7. the pull request
git push fork feat/<slug>
gh pr create --repo shirai91/nx2android --base main --head zFitness:feat/<slug> \
    --title "feat(profile): <game> plays on Android" --body-file <body>
```

The body follows [references/pr-body-template.md](references/pr-body-template.md): the game and its
version, the profile and the device it was tested on, what the port needed in the core (with the
pull request it shares a piece with, named), and what the pull request does **not** carry.


## The review's recurring demands

The maintainer reads the pull request against the upstream's own rules, and the same points come up every time. The worked example (the Chinese release of OCTOPATH TRAVELER 0, PR #301) is in [references/pr301-review.md](references/pr301-review.md).

- **The games table is re-flowed from the current main.** Every game is kept — the diff is large even for one cell, because adding a cell re-pairs the rows after it, so check the **set** of games and not the diff. An odd number of cells ends with a row of one game and an empty cell (`| ![game](…) | |`).
- **The version is the real one.** `profile_guess` writes `v1` when it cannot read the update's version tag (it looks for an English `[v<N>]` in the file name; a Chinese dump spells it `[升级档v1.0.5]`). Register the update's **real** version: the number is the update NCA's own — the one a dump's `[v<N>]` tag carries — and not the display version the NACP holds (the build row's second column). A regional release numbers its updates **on its own**, so take the number from that release's own update series: the public update listings name every update's tag (the international OCTOPATH TRAVELER 0's 1.0.2/1.0.3/1.0.4/1.0.5 are v131072/v196608/v262144/v327680) and tinfoil.io lists the build IDs of a title and of its patch title per version — so register that release's own update build (the patch title 0100…800's), not the international one's. The number belongs in `build`, `package`, `shader_versions`, the profile's `android.tsv` `tested` row and the `patches.py` comment; say in a comment where it comes from, so the next reader does not have to ask.
- **A row for another game's port is that port's own pull request.** The gamepad theme table (`nxnative/dm/android/themes/themes.tsv`) gives every title ID a pack, and a row changes that game's APK: a row for a game you did not port cannot ride along in your pull request. The maintainer took the row for the international release (another contributor's port, #195) out of PR #301 and asked for it in its own pull request, where that port's author can see it. Your pull request adds rows for the titles it ports, and no others.
- **The upstream is a PC repository; the fork's world does not belong in it.** The phone app's payload (`files/tool/pylibs`), "the phone app's own machine" and the fork's pipeline names (`oodle_job`, `oodle_where`) are this fork's: there the porter runs on a Windows or Linux PC (pycryptodome is in `requirements.txt` and in those bundles) and the pipeline calls `device_oodle_job`. Write the upstream's world into the upstream's files — a fake port in a test implements the upstream's own names, and a comment says "a Windows or Linux PC".
- **The tests run on Windows too.** The CI runs the suite on three platforms: never write a path separator into an expected value (build it with the platform's own, e.g. `nsp.ue_stamp(dir)`), and a test that needs an optional package skips when it is missing instead of erroring.
- **The image is the device's.** A gameplay frame captured on the device, in `docs/images/`, not the publisher's artwork and not a frame from another release.
- **Rebase onto the current main** before asking for a merge, and take the counts in the checklist from that main (the games it added are counted there too).
- **The review comes in rounds.** The first round of PR #301 had three blockers and five notes; the second (after the fixes and a rebase) one blocker and six notes the maintainer called small and non-blocking. Answer every note anyway — they are the next round's blockers — one paragraph per point, and say what each fix was.

## Answering a review round

The maintainer's notes come in three kinds, and each one wants a different thing:

| Kind | What it means | What to do |
|---|---|---|
| a blocker | the change breaks something that works today, or a gate fails | fix it, and say what the fix was |
| "not blocking" | a wording, a bound, a smaller shape | do it too: it is the next round's blocker |
| a request for a test | the point has no test | one test per case, named after the case |

The recurring blockers have a shape: **the check must not refuse what works today** ("a keyset that ports today
was refused"), **the gate must pass on the runner that is not yours** (the Windows runner's cp1252 cannot encode
the `→` in a table's header; the porter runs with `PYTHONUTF8=1`, the CI does not), and **the change must not
reach further than it has to** ("limit it to the packets whose time repeats").

The answer is a comment on the pull request (`gh pr comment <n> --repo shirai91/nx2android --body-file <file>`),
one paragraph per point: what the point was, what the fix is, and (when the rebase brought something else) what
else came with it. The worked example is the round of 2026-11-25 (the pull requests #443, #409, #408, #402): the
answer named the fix per point, said that the optional notes were done too, and explained the three things the
rebase had brought.

## A branch that fell behind main

A pull request that waited a week is a hundred commits behind, and the rebase brings things the branch never saw.
Check the distance first (`git rev-list --left-right --count HEAD...upstream/main`), then expect:

- **The tables move.** The games table, the game's page and the checklist's count are made by
  `docs/gen_tables.py`: run it, and take the count from that main (the games it added are counted there too).
- **A profile may need a page of its own now.** Main's `android_profile.py` dropped the `readme_note` row: the
  note moved into `nxnative/dm/games/<title ID>/README.md`, and a profile with `readme_image` and `readme_tag`
  needs that page. `docs/gen_tables.py` makes it from `docs/GAME_README_TEMPLATE.md`; the five sections under the
  made block are written by hand (the check fails without them, or with a `TODO:` left). The checklist's **row**
  is written by hand too (the script only ticks it and counts) — a regional release goes next to its base game.
- **A new upstream test may copy parts of your source file.** The harness cuts the file into pieces by the
  markers in it and compiles them with `-Werror`: a static helper that the copied part does not call is an
  "unused function" there. Put the helpers next to their users (the ones only `mavc_decode` calls go with
  `mavc_decode`), so the harness compiles.
- **The conflicts are unions.** The upstream's new code and the branch's both belong in the file: take the
  upstream's part where it is new and the branch's where the branch changed it.

Then the gates again (the quick ones, the whole suite), the tables' check, and the force-push.

## Opening and updating it

```bash
git push fork feat/<slug>                                # the branch goes to the fork
gh pr create --repo shirai91/nx2android --base main --head zFitness:feat/<slug> \
    --title "feat(profile): <game> plays on android" --body-file <body>

gh pr view <n> --repo shirai91/nx2android --json reviews,comments,mergeable
gh api repos/shirai91/nx2android/pulls/<n>/reviews --jq '.[] | "=== " + (.id|tostring) + " " + .state + "\n" + .body'
gh api repos/shirai91/nx2android/pulls/<n>/comments --jq '.[] | "=== " + .path + ":" + (.line|tostring) + "\n" + .body'
```

After a rebase the branch is rewritten: `git push --force-with-lease fork feat/<slug>`. The pull request follows the branch; its commits are replaced in place.

Answer the review as a comment on the pull request, one paragraph per point, and say what each fix was. A point the maintainer leaves to himself (a row that touches another game's port, for instance) is **not** yours to keep: he takes it out and asks for it in its own pull request, where that port's author can see it — so drop it and say where it will go.

## The fork's own pull request follows the upstream one

The same port lives in two pull requests: the upstream's (the engine and the profile, in the upstream's
shape) and this line's (the product line, `zFitness/nx2androidX`). **The upstream's is the final
shape of the profile**, so the fork's copy follows it: the same `nxnative/dm/games/<title ID>/`
files, the same version number, the same readme rows. When the upstream pull request changes (a
review round, a rebase), the fork's profile changes with it — take the upstream's version of the
files rather than editing both by hand.

**A shared fix carries the upstream's text, line for line.** The fork's copy of a core change is the
upstream's own lines (the same function body, the same comment): the next upstream sync then finds
them identical and merges them without a conflict. Do not rewrite it "for the fork" — the difference
belongs in the fork's own files, not in the shared core. What the fork's pull request adds is the
fork's app, the fork's tables (its counts differ from the upstream's) and the fork's own world.

## The traps

- **The upstream's open pull requests are where the shared work is**: check them before writing the
  first line of code, and take a shared implementation from the pull request that owns it, unchanged.
- **A registered switch and the source that reads it are one change**: a row in
  `nxnative/dm/settings.tsv` without a core source that reads it fails the upstream's own test, and a
  profile's `env` key without its row fails the port.
- **A stale mirror makes a wrong "rebase"**: the fork's `main` and the local `main` are both behind the upstream; `git fetch upstream main` is the only truth.
- **The main moves while you wait**: fetch it again before every push. The rebase can bring a fix your branch's copy of a test needed (the overlay harness's `_Atomic` declaration, #309), and the whole suite has to pass on the branch — a test that only compiles on your machine breaks the pull request.
- **The games table's diff lies**: one added cell rewrites the rows after it. Compare the set of game names, not the diff.
- **`v1` in a profile is a placeholder**, not a version: a player's dump named the usual way then lands on a derived profile (a red line and a bigger address region).
- **A regional release is its own build**: its own main build ID, its own host rows, its own title ID, its own games table cell, its own image — and its own checklist line.
- **A theme pack has two artboards**: the phone one and the tablet one. The tablet carries the tablet's values (`stickSize 184`, `menuBounds [-570, 570, -6, 710]`), the phone its own (`160`, `[-425, 426, -2, 374]`); copying the phone's values into the tablet is a slip that only shows in the canvas preview, not in the rendered atlas.
- **A test that leaves a folder behind**: `mkdtemp` without a cleanup leaves one folder per run in the temp directory; `self.addCleanup(shutil.rmtree, root, True)` after it, as the other tests in the file do.
- **A force-push needs `--force-with-lease`**: the pull request's branch is shared with the fork.
- **The upstream's CI runs the suite on Windows**: a test that only passes on a Mac breaks the pull request, not the local run.
- **The local mirror is not updated by a pull request**: syncing this checkout's `main` with the upstream is its own line of work (the hub change's "upstream sync" tasks), not a side effect of the contribution.
