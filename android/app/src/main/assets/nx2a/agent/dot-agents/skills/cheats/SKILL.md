---
name: cheats
description: Use it for the cheats of a Switch game on the phone - what a game has (the cheat file on the phone, what the community libraries hold for its build) and how to add one (writing the file, naming it after the build, making it take effect). Use it when the player asks what cheats a game has, asks for a cheat to be added, asks where a cheat comes from, or asks why the game does not show its cheats.
---

# Cheats: what a game has, and how one is added

The cheats are the community's Atmosphère-format files. The App carries none of them: they
come from the community libraries or from the player, and they live in the App's own folder.

**The codes are never yours to invent.** A cheat writes into the running game's memory at
addresses that only the game's own build knows. Get the codes from a source (a library, a file
the player brings) - never make them up, and never carry them over from another build.

## The lay of the land

- **The file**: `files/cheats/<title ID>/<build ID>.txt` - that is
  `$NX2A_HOME/../cheats/<title ID>/<build ID>.txt` from the assistant's side. Beside it:
  - `<build ID>.credit` - who wrote it (the library's line of credit).
  - `<build ID>.enabled` - the switches the **host** remembers (the name of each cheat that is
    on, one a line). The App clears this file at every launch, so every session starts with
    all the cheats off.
- The folder is in the App's private storage: rebuilding the data pack does not touch it.
- **The build ID**: the first 16 hex digits of the main NSO's build ID, upper case. The host
  loads the file of the build it runs and nothing else (a file of another build would write
  the wrong places), so the name has to be exactly right.
  - Where to read it before the game runs: the data pack's own table, the head line of
    `<pack>/mods/main.fixups.tsv` (`# BUILD_ID <hex>`).
  - The data pack's path is in `$NX2A_HOME/library.json` (the `pack` field of the game's
    entry); the pack folder is the `<something>-data` folder (it also holds the game's own
    `log.txt`).
  - The host knows the same value once it has loaded the modules; the App asks it and falls
    back to the pack's table.

## What the phone has

    ls -l "$NX2A_HOME/../cheats/<title ID>/"

- One `.txt` per build: the file of the build the game runs is the one in use; the others are
  kept but never loaded.
- The entries in a file: each starts with a header - `[Name]` for a cheat the player can
  switch, `{Name}` for one that is always on - followed by its code words. The text before the
  first header is prose and is ignored.
- `.credit` says where the file came from; show it to the player when you quote a cheat.

## What the libraries hold

The App downloads a file for the running build from the first source that has one (nothing is
merged across the sources: two files that patch the same place would fight):

- **switch-cheats-db** (the aggregator): one JSON per game, named by its title ID in upper
  case, with one member per build. The member's value is a map of cheat name to the cheat's
  text; the file also has an `attribution` member, which is the credit.

      python3 $NX2A_REPO/tools/fetch.py "https://cdn.jsdelivr.net/gh/HamletDuFromage/switch-cheats-db@master/cheats/<TITLE ID>.json"

  The CDN has three edges and one of them is often blocked, so try
  `cdn.jsdelivr.net`, `fastly.jsdelivr.net` and `gcore.jsdelivr.net` in turn. The App does
  the same.
- **MyNXCheats** (one author's collection): the files are already in the Atmosphère format,
  and the repository's file tree says which file belongs to which game and build
  (`/<game name>/<title ID>/cheats/<build ID>.txt`). The tree comes from
  `https://data.jsdelivr.com/v1/packages/gh/Arch9SK7/MyNXCheats@master?structure=flat`; the
  file itself is fetched from the same CDN edges.
- **vgtxc/switch-cheat** (the Chinese community's collection): the App does not read it, but
  the desktop script does - `devtools/cheats_pull.py` tries all three and cuts a package with
  `devtools/cheats_zip.py`. That is how this line's own cheat repository
  (`zFitness/nx2a-cheats`) is kept, and the package it cuts is what the player imports.

The player can also import a zip themselves: **settings -> environment -> cheats -> import
zip**. The App unpacks it into the same folder (the file of a build replaces the old one).

## Adding one

1. **Get the codes** from a source: a library file for this exact build, or a file the player
   brings. Say where the codes came from - the player needs to know that later.
2. **Know the build ID** (above) and write the file to
   `$NX2A_HOME/../cheats/<title ID>/<build ID>.txt`.
3. **Keep the format** the host reads:
   - A header line starts an entry: `[Name]` is a cheat the player switches, `{Name}` is one
     that is always on. The name is what lies between the brackets.
   - The code words are hex, eight digits each; a run of hex digits is cut into words of
     eight wherever the whitespace falls (words glued together are fine too). A line of prose
     is ignored, but a line that mixes prose with words is malformed and turns that one cheat
     off.
   - The file is written as it is - the community's file byte for byte. Do not reformat it.
4. **Keep it within the host's limits**: at most 128 entries in a file, at most 64 KB per
   file, at most 0x400 code words per cheat, at most 100000 instructions per pass (a runaway
   loop is stopped and that cheat is turned off).
5. **Leave the resolution patches out.** An entry whose name mentions a resolution
   (`1080p`, `downscale`, `upscale`, `resolution`) changes the render scale, and the App has
   its own: with both on, the picture is not what the player expects. The importer drops those
   entries - a file you write by hand has to drop them too.
6. **Make it take effect**:
   - With the game's master switch on, the host is pointed at the folder at the game's start
     (`DM_CHEATS`), and it loads the file of the build it runs.
   - While the game runs, turning the master's switch off and on again re-loads the folder -
     that is the way to pick up a file you have just written.
   - The single cheats are switched in the game's drawer, on the **Cheats** tab. They start
     off every session.
7. **Say what you did**: the file's name, where it came from, and that the player has to turn
   the master switch (and then the cheats themselves) on in the drawer.

## What the host refuses (so you do not promise what it cannot do)

- A write into the game's own code is **allowed**: the host opens the write bit on the pages
  it needs, writes, and flushes the instruction cache. The execution bit never goes off.
- Three places stay off limits, and a cheat that writes there is turned off with the reason
  the App shows: the host's own key page, the host's patch area (its own stubs), and the words
  the rewriter changed or hooked.
- An opcode the host does not implement (the pause, resume and debug-log forms) turns that
  cheat off before it ever runs.
- A write or a read to an address that is not mapped is a failed access: it is ignored and
  retried, and the cheat stays on.

When a cheat cannot run, the App's Cheats tab shows the reason. Say that reason to the player
rather than the cheat's name alone.

## Never

- Never invent a cheat's codes or an address. A wrong address is a crash, not a cheat.
- Never write a file of another build's name to make it load: it would write the wrong places.
- Never read the contents of the game files to work out an address. The game's own memory
  layout is not something to guess at on the phone.
