---
name: environment-check
description: Use it to check whether the player's environment is complete and whether a port can start - the same check the App's readiness page runs (porter doctor's JSON plus a few facts that need no command). It says what is missing and where the player fixes it. Use it when the player asks whether the environment is ready, what is still missing, or why a build gave up right at the start.
---

# Environment check: is this environment complete?

The player asks "is the environment ready", "can I start porting", "what is still missing", or
"why did the build give up right away". Follow this.

The App's readiness page does the same thing: it runs the command in step 1 below. Do not
write a check of your own, and do not say "it should be fine" because it looks fine.

## 1. The toolchain check (the readiness page's own command)

    cd $NX2A_REPO && python3 -m porter doctor --json -k "<the path of prod.keys>"

The output is a JSON array, one entry per check:

| field | what it means |
|---|---|
| `name` | the check: Python, Python modules, Pillow, pycryptodome, fontTools, Android NDK, .NET, prod.keys, Disk space (there is no adb row on the phone: it cannot install adb, so a row about it would only be noise) |
| `ok` | did it pass |
| `detail` | what it is now (version, path, how much is missing) |
| `required` | can a port still happen without it (false = there is a way round) |
| `fix` | how to fix it; empty = it cannot be fixed from here, or it is fine already |

- The exit code: 0 = everything required is there, 1 = a required entry is missing.
- Where the prod.keys path comes from: the App's own settings keep it (the `keys` entry in
  `$NX2A_HOME/../shared_prefs/nx2a.xml`), and players usually keep the file in
  `/storage/emulated/0/switch/prod.keys`. If you cannot pin the path down, ask the player;
  do not turn "not found" into "not there". If you cannot, run it without `-k` first and
  ask separately.

## 2. A few facts that need no command (read them off the disk)

- **Whether the environment itself is there** (the same judgment the readiness page makes -
  all three must be there):

      ls -d "$NX2A_REPO/porter" "$NX2A_REPO/toolchain/clang/bin/clang" "$NX2A_REPO/../rootfs/usr/bin/python3.13"

- **The game profiles**: the player imports them into `$NX2A_HOME/profiles/`.
  - `cat "$NX2A_HOME/profiles/manifest.json"`: count (how many profiles), revision (which
    r), sha256 (the package's fingerprint).
  - How many of them are ported to Android = how many profile folders carry an
    `android.tsv`: `find "$NX2A_HOME/profiles" -maxdepth 2 -name android.tsv | wc -l`
  - None at all = the player has not imported a profile package yet, so a port has no
    profile to work from (the environment section of the settings page imports one or
    updates it online).
- **The firmware** (optional, only for the games that read the console's system data):
  `ls "$NX2A_HOME/../firmware"/*.nca`. Without it the App works as usual; only the games
  like Mii miss something.
- **The child-process limit**: since Android 12 the system kills processes above a limit,
  and the porter is one of them.

      /system/bin/device_config get activity_manager max_phantom_processes

  If it cannot be read, say so - do not guess. The readiness page's own row is the
  authority.
- **Disk space**: `df -h <the disk the game files are on>`. A port takes about twice the
  space of the game files.

## 3. How to answer

- Lead with the verdict (complete / what is missing), then a table: what is missing, and
  where the player fixes it.
- Do not call a `required: false` entry broken: Pillow only costs the icon, pycryptodome
  only matters for NSZ / XCZ, fontTools only for the button icons in the game's text, and
  adb is not used on the phone at all.
- The NDK, .NET and Python on the phone are the App's own: if one of them is missing, the
  environment was not installed properly -> the player re-runs the guided setup in
  "settings - environment" (or updates the App). Do not teach them to pip install on the
  phone.
- prod.keys is the player's own dump from their own Switch -> they import it in
  "settings - environment".
- When a check cannot be read, say that it cannot be read. Do not say "it should be fine".
