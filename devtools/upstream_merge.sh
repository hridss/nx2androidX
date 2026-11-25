#!/usr/bin/env bash
# Merge the upstream (shirai91/nx2android) into this line, taking only the engine's core.
#
# The upstream is merged whole, but only part of it is ours to take: the host's system calls,
# the graphics replay, the shader and NSO tools, the game profiles (the sync surface, see
# .claude/skills/nx2android-upstream-sync). The porter's pipeline and the front are two
# different worlds and are never synced (2026-11-25), so the merge puts ours back where the
# upstream touched them.
#
#   devtools/upstream_merge.sh [--upstream upstream/main] [--ours android] [--dry-run]
#
# It stops before the commit: the merge is left staged, and the checks (the test suite, the
# app's unit tests, a build, a run on the phone) are the ones a human runs before the commit.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
upstream=upstream/main
ours=android
dry_run=0

while [ $# -gt 0 ]; do
  case "$1" in
    --upstream) upstream=$2; shift 2 ;;
    --ours) ours=$2; shift 2 ;;
    --dry-run) dry_run=1; shift ;;
    *) echo "usage: $0 [--upstream <ref>] [--ours <ref>] [--dry-run]" >&2; exit 2 ;;
  esac
done

cd "$root"

say() { printf '\n>> %s\n' "$1"; }

[ -z "$(git status --porcelain)" ] || { echo "error: the worktree is not clean; commit or stash first" >&2; exit 1; }
[ "$(git branch --show-current)" = "$ours" ] || { echo "error: not on $ours (on $(git branch --show-current))" >&2; exit 1; }

say "the upstream"
git fetch "$upstream" 2>/dev/null || git fetch upstream main
behind=$(git rev-list --count "$ours..$upstream")
ahead=$(git rev-list --count "$upstream..$ours")
echo "   $upstream is $behind commit(s) ahead of $ours, $ours is $ahead ahead of it"
if [ "$behind" -eq 0 ]; then
  echo "   nothing to take; the mirror is refreshed below and the script stops"
fi

say "the conflict surface"
python3 "$here/upstream_conflict_surface.py" --upstream "$upstream" --ours "$ours" | sed 's/^/   /'

say "the mirror (main, fast-forward only)"
git fetch . "$upstream:main"
git push origin main
if [ "$(git log main ^$upstream --oneline | wc -l | tr -d ' ')" != "0" ]; then
  echo "error: the mirror holds commits of our own" >&2; exit 1
fi
echo "   main is $upstream"

if [ "$behind" -eq 0 ]; then
  say "done: the upstream has nothing new"
  exit 0
fi

if [ "$dry_run" -eq 1 ]; then
  say "dry run: nothing was merged"
  exit 0
fi

say "the merge"
git merge --no-commit --no-ff "$upstream"

# The two worlds: put ours back where the upstream touched them. The rules are the ones the
# report uses (devtools/upstream_conflict_surface.py), so the two never disagree: a path we
# have goes back to our version, a path the upstream added goes away with the merge.
PYTHONUTF8=1 python3 "$here/upstream_conflict_surface.py" --apply-skip --upstream "$upstream" | sed 's/^/   /'

say "the tables (README, the checklist: by script, not by hand)"
PYTHONUTF8=1 python3 docs/gen_tables.py | sed 's/^/   /'

say "what the merge left"
git status --short | sed 's/^/   /'

cat <<'EOF'

Next, before the commit:

  python3 -m unittest discover tests
  cd android && ./gradlew :app:testDebugUnitTest && cd ..
  a run on the phone, with the whole APK (an app-only build does not replace the tool tree)

then commit the merge.
EOF
