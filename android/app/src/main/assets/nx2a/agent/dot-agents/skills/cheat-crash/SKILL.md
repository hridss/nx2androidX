---
name: cheat-crash
description: Use it when a game crashes, freezes, or misbehaves only after a cheat was turned on - tell the host's refusals apart from a real crash, find the cheat that did it, and say what to do about it. It is written for the case worker (the game's log, the switches the host remembers, and the bisection that finds the one cheat to blame).
---

# The game crashes with a cheat on

A cheat writes into the running game's memory. One that writes the wrong place crashes the
game; one the host refuses does not crash anything - it turns itself off.

**First tell those two apart.** Everything else follows from it.

## 1. Was the cheat refused, or did it crash?

The host's refusals are in the game's log (the data pack's `log.txt`, and the run before it in
`log.prev.txt`), and they are the lines that start with `[RED] cheat`:

| the line | what it means |
|---|---|
| `[RED] cheat "X": it writes the host's key page: the cheat is turned off` (or `the host's patch area`) | the cheat wrote somewhere it must not. It is off now; nothing crashed because of it. |
| `[RED] cheat "X": it writes a word the rewriter changed (main+0x...): it cannot run in this version` | the same, found before the cheat ever ran. |
| `[RED] cheats: <file>: "X" line N mixes code words with "...", the cheat stays off` | a malformed line: that one cheat is off. |
| `[RED] cheat "X": write at guest address 0x... failed (...): the cheat stays on and retries` | an address that is not mapped: the access is ignored and retried, the cheat stays on. |
| `cheats: <BUILD> loaded: N entries (N cheats, N labels)` | the file was loaded. Between this line and the crash, the cheats ran. |

Two verdicts leave **no line in the log**: an opcode the host does not implement, and a
runaway loop. Both turn the cheat off and put the reason in its row in the drawer
(`unsupported opcode X`, `over 100000 instructions in one pass`). Look there, not in the log.

If the crash happened with a cheat on and no refusal line names it, the cheat wrote
something the game could not live with.

## 2. The timeline

- **The App's own log** (`$NX2A_HOME/log.txt`): `cheats: cheat N -> on` (and `-> off`) says
  which cheat the player switched, and when. The number is the cheat's position in the list
  the drawer shows.
- **The host's log** (the data pack's `log.txt`): `cheats: <BUILD> loaded: ...` and the
  `[RED] cheat ...` lines. The crash's backtrace is usually in `log.prev.txt` - the host
  renames the log at the start of a run. `log_triage.py` triages it: the crash's signature,
  the last errors, which log holds the backtrace.
- **The switches the host remembers**: `<build ID>.enabled` beside the cheat file
  (`$NX2A_HOME/../cheats/<title ID>/`). One cheat name a line. It says which cheats were
  left on; the App clears it at every launch, so it holds the switches of the last session
  only.
- **The cheat's own row** in the drawer: the name, whether it is on, and the reason it
  cannot run.

## 3. What cannot be the cause

The host's protections, so do not spend the search on them:

- A file of another build is never loaded: the host loads the file named after the build it
  runs. A file with the right name but the wrong contents is a different thing - see below.
- A write into the host's own pages or into a word the rewriter changed is refused: the cheat
  turns itself off.
- A runaway loop is capped (100000 instructions per pass): the cheat is turned off.
- An access to an unmapped address is ignored and retried: it does not take the game down.

## 4. The usual causes

- **The codes are for another version of the game.** The file's name matches the build (the
  community's file is named by the build), but the addresses inside it belong to another
  version of the same game, or to another game. The cheat then writes garbage into the
  game's heap every frame, and the game dies somewhere else entirely. This is the usual one.
- **Two cheats fight.** Two entries write the same place with different values: the game
  ends up with whatever the last one wrote, or with a value nobody expects.
- **A code patch breaks the game's logic.** A cheat that patches an instruction can turn a
  value into something the game cannot handle (a length, a count, a pointer).
- **A resolution patch was added by hand.** The App's own render scale and a resolution
  cheat change the same thing; the importer drops those entries, a file written by hand does
  not.
- **The cheat writes where the rewriter works.** The rewriter changed or hooked a word, and a
  cheat that writes it puts the game's own code back to something the host does not expect.

## 5. The bisection

When the log does not name the cheat, find it by halves:

1. Start the game again with the cheats all off: the App clears the switches at every launch
   (that is why a cheat that crashes the game cannot lock the player out of it). To be sure,
   delete `<build ID>.enabled` in `$NX2A_HOME/../cheats/<title ID>/` - it is in the App's own
   folder, so the assistant can remove it, the player cannot. If the game is stable now, a
   cheat was the cause.
2. Turn one cheat on (in the drawer, the Cheats tab), play, and watch.
3. When it crashes, the last cheat that was switched on is the one to blame - unless it
   crashes only in combination with another, in which case keep the pair together and treat
   it as the pair's doing.

Each run is short: the crash usually comes within a minute or two of the cheat writing.

## 6. What to write down and to say

- **In the case file**: the cheat's name, the file it came from (its build and its source),
  the `[RED] cheat ...` line if there is one, the log line of the crash, and the outcome of
  the bisection (which cheat, alone or with which other).
- **To the player**: which cheat is the problem, that turning it off fixes it, and where a
  file for this exact build can come from (the libraries; the App's drawer downloads the file
  of the running build).
- **Do not** "fix" a cheat by editing its codes. The codes belong to the game's own build:
  a guess is a crash. The fix is to leave that cheat off, or to get the file of the exact
  build.

Never invent a reading of the log. If the log does not say it, it did not happen.
