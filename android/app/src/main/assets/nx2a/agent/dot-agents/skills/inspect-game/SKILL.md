---
name: inspect-game
description: Use it to analyse an NS game file (NSP / XCI / NSZ / XCZ) or a folder of game files - the same read the App's build page runs (porter inspect's JSON). It says what the file is, which base game, update and DLC are in the folder, and whether it can be ported. Use it when the player asks what a file is, whether it can be ported, or what their game folder holds.
---

# Analysing a game file: what is in it

The player asks "what game is this", "can this NSP/XCI be ported", "what is in this folder",
or "have a look at my game files". Follow this.

The App's build page does the same thing: it runs the command below, so the assistant and
the page say the same thing about the same file.

## The command

    cd $NX2A_REPO && python3 -m porter inspect "<file or folder>" --json

- A file works (one NSP / XCI / NSZ / XCZ), and so does a folder full of files: for a
  folder it sorts the base game, the updates and the DLCs; for a file it takes that file as
  the base game and picks up the updates and the DLCs of the same game in that folder (and
  in its first-level subfolders).
- It reads **files**, not the titles inside a cartridge dump: how many titles a card holds
  and whether the update is merged into it is card.py's job (the card-unpack skill).
- Add `-k "<the path of prod.keys>"` when the keys are not in the default place: without
  them a card without a ticket gives no title ID.
- It only reads: it unpacks nothing and writes nothing.
- The exit code: 0 = it can be ported, 2 = it cannot, or it could not be read (the JSON is
  `{"error": "..."}`).

## Every field of the JSON

| field | what it means | what to tell the player |
|---|---|---|
| `title_id` | the title ID, 16 hex digits in capitals | the game's identity |
| `name` | the name in the profile (no profile, no field) | what the game is called |
| `base` | the file name of the base game | the base game is this file |
| `updates[]` | the updates: `version` (v65536 and the like), `file`, `folder` | whether there are updates, which version, in which subfolder |
| `dlc[]` | the DLCs: `title_id`, `version`, `file`, `folder` | how many DLCs there are |
| `pack` | the version that would be packed (the newest one the folder holds) | this version is the one that gets packed |
| `versions[]` | the versions the folder holds together | which versions are on the phone |
| `registered{}` | the versions the profile registers (version -> shown version) | which versions the profile knows |
| `derived` | the version that would be packed is not one the profile registers | the pack uses a derived profile, without that version's fixes |
| `android` | the profile's state on Android: `id`, `name`, `android`, `status`, `tested`, `package` | how far the port has got |
| `profile` | the profile's id | whether there is a profile at all |
| `supported` | can it be ported | yes or no |
| `reason` | why not, or "it can, but not completely" | say it as it is, do not improvise |
| `experimental` | there is no official profile | `porter port --experimental` can be tried, but it may not start |
| `compressed[]` | the compressed files (NSZ / XCZ) | the build decompresses them by itself |

`android.status` has four values: `playable` (it plays), `wip` (it plays, but parts are
missing), `boots` (it starts), `experimental` (a trial).

## The usual answers

- `reason` says `no game profile for XXXX`: nobody has made a profile for this game yet ->
  it cannot be ported.
- `reason` says `has a game profile (iOS) but is not ported to Android yet`: there is a
  profile, but it has not been ported to Android -> experimental can be tried.
- `reason` says `is a work in progress`: it plays, but parts of it are missing (the
  profile's own row says which).
- `reason` mentions `is not a version the profile was brought up on`: the version that
  would be packed is not one the profile registers, so the port uses a derived profile and
  that version's fixes are not in it.
- `android.status` is `wip` or `boots`: tell the player plainly how far it has got.

## How it fits the other tools

- `card.py` / `card_split.py`: the cartridge's view - which titles are on the card,
  whether the update is merged into it, how to cut it open.
- This skill: the port's view - whether it can be ported, the state of the profile, whether
  the versions match up.
- Reading the game files themselves (what is in romfs and exefs): nx_extract, another job.
- A compressed dump (NSZ / XCZ) can be read (it shows up in `compressed`), but it cannot
  be split: card_split refuses it, so it has to be decompressed first.
