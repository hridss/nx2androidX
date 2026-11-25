---
name: card-unpack
description: Use it to unpack a card - an XCI or an NSP cartridge dump - into one NSP per title (the base game, its update, its DLCs), written into a folder next to the card itself. Also use it when the player asks to split a card, to take the base game or the update out of it, or to get a single file to port or to test.
---

# Unpacking a card: one NSP per title

The player says "unpack this card", "split the cartridge", or wants a single base game or
update to port or to test. Follow this.

## Use the tools, do not write your own script

- `python3 $NX2A_REPO/tools/card.py "<card>" --keys $NX2A_HOME/../prod.keys`: what is on
  the card (which titles, whether the update is merged in, what the porter makes of it).
  Read-only.
- `python3 $NX2A_REPO/tools/card_split.py "<card>"`: cut it into one NSP per title.
  **No second argument**: the tool puts them into `<card name>-<base title ID>/` next to
  the card. prod.keys defaults to $NX2A_HOME/../prod.keys.

Do not write the unpacker yourself: the script that used to live in a temporary directory
is not in the tool tree, so it is gone on the next phone, and it dies with the next change
of the card format.

Reading the game files themselves (what is in romfs and exefs) is another job - that is
nx_extract. The App does not need it to pick a file.

## Where the output goes, and what it is called (the rules)

- The output folder is **next to the card**: `<the card's folder>/<card name without its
  suffix>-<base title ID>/`.
  - Example: the card is `/storage/emulated/0/Download/Fire Emblem.xci` and the base title
    ID is `010055D009F78000`, so the output is
    `/storage/emulated/0/Download/Fire Emblem-010055D009F78000/`.
  - You do not have to work the folder out: card_split.py uses it when no output folder is
    given. If you pass a second argument, pass that one.
  - The title ID in the folder name is the **base game's** one: card.py's output says what
    each title in the card is (base / update / DLC), so read it off there instead of
    guessing. A card without a base game is named after the first title in it.
  - Why here: the card is where the player put the game and where the file picker can reach
    it, so the split files can be reached too. The App's file picker does not see the App's
    private folders (files/..., files/tmp, files/tmpwork, $TMPDIR); those are good for
    scratch work (intermediate products) and are deleted afterwards.
  - The library merges by title ID: the split NSPs are the same game as the card, so they
    do not add a second card to the library.
  - When two cards sit in the same folder, the base title ID keeps them apart: never let
    two cards share one output folder.
- Flat inside that folder, no subfolders: no "base/update/DLC" subfolders - the player
  asked for one level, so keep one level.
- The tool names the files: `<title ID>_base.nsp`, `<title ID>_update.nsp`,
  `<title ID>_dlc.nsp` (a DLC's number is in its title ID), anything else `_other.nsp`. Do
  not rename them.
- Do not touch the player's card: no move, no rename, no deleting the original. The split
  reads it and writes beside it.

## Space

- The split NSPs together are about the size of the card (a 13 GB card needs 13 GB of
  room). Look before writing: `df -h "<the card's folder>" | tail -1`.
- The tool does the arithmetic itself: if there is not enough room it says so in one line
  and writes nothing.
- If the room is short, say so plainly. Do not delete the player's things to make space.

## When it is done

- Tell the player the name and the size of every NSP, and which folder they are in (the
  folder is next to the card, so one sentence is enough).
- Delete the intermediate products (the unpacked romfs, the listings, the temporary
  folders) unless the player wants them kept.
