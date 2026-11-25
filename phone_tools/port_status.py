#!/usr/bin/env python3
"""Every data pack on the phone in one table: the game, its title ID, its status, and whether it crashed.

The list comes from the app's own snapshot (nx2a/library.json): the app scans the app's folder and
the removable volumes and knows which card is which, so the tool does not have to guess.

    python3 $NX2A_REPO/tools/port_status.py
    python3 $NX2A_REPO/tools/port_status.py --title 0100F2C0115B4000
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import emit, fail, home, table  # noqa: E402

SNAPSHOT = 'library.json'
# The crash's signature in a run's log (log.txt is the run that is going on, log.prev.txt the one before).
CRASH = re.compile(r'CRASH|SIG(SEGV|ABRT|BUS|FPE|ILL|TRAP)|fatal error|panic', re.I)


def snapshot():
    """The app's snapshot of the library."""
    path = home() / SNAPSHOT
    if not path.is_file():
        fail(f'the library snapshot is not there ({path}); open the app once so it writes one')
    try:
        data = json.loads(path.read_text(errors='replace'))
    except (OSError, ValueError) as exc:
        fail(f'the library snapshot cannot be read ({path}): {exc}')
    games = data.get('games') if isinstance(data, dict) else data
    if not isinstance(games, list):
        fail(f'the library snapshot has no list of games ({path})')
    return data, games


def crashed(pack):
    """Whether the pack's last run crashed, and where the signature is."""
    if not pack: return ('', '')
    folder = Path(pack)
    for name in ('log.txt', 'log.prev.txt'):
        path = folder / name
        if not path.is_file(): continue
        try:
            text = path.read_text(errors='replace')
        except OSError:
            continue
        for number, line in enumerate(text.splitlines(), 1):
            if CRASH.search(line):
                return ('crashed', f'{name} line {number}: {line.strip()[:90]}')
    return ('ran', '')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--title', default='', help='only this title ID')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    data, games = snapshot()
    if args.title:
        games = [g for g in games if str(g.get('titleId', '')).upper() == args.title.upper()]

    rows, entries = [], []
    for game in games:
        title = str(game.get('titleId', ''))
        name = game.get('name') or title
        state, detail = crashed(game.get('pack'))
        rows.append((name, title, game.get('status') or '?', state, detail))
        entries.append({'name': name, 'title_id': title, 'status': game.get('status') or '',
                        'pack': game.get('pack') or '', 'source': game.get('source') or '',
                        'run': state, 'detail': detail})

    text = f'{len(games)} game(s), from {home() / SNAPSHOT}\n\n'
    text += table(rows, ('game', 'title ID', 'status', 'last run', 'crash'), empty='no game yet')
    emit(args, {'generated_at': data.get('generatedAt') if isinstance(data, dict) else None,
                'count': len(entries), 'games': entries}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
