#!/usr/bin/env python3
"""The build history: what was built, how it went, and where the failing one went wrong.

Every build the app ran left a record: nx2a/builds/<id>/record.json (the game, the phase, the
stage, the time it took) and nx2a/builds/<id>/log.txt (what the porter said).

    python3 $NX2A_REPO/tools/builds.py
    python3 $NX2A_REPO/tools/builds.py --limit 5 --errors
"""
import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import emit, fail, home, table  # noqa: E402

# The phases the app writes (PortPhase). DONE is a build that worked; the rest did not.
RESULT = {'DONE': 'ok', 'FAILED': 'failed', 'CANCELLED': 'cancelled', 'INTERRUPTED': 'interrupted',
          'RUNNING': 'running', 'IDLE': 'idle'}
ERROR = re.compile(r'\[RED\]|\b(error|failed|failure|exception|traceback|cannot|unable to|denied)\b', re.I)


def records(root):
    """Every build record, newest first."""
    out = []
    if not root.is_dir():
        return out
    for folder in sorted(root.iterdir()):
        path = folder / 'record.json'
        if not path.is_file(): continue
        try:
            record = json.loads(path.read_text(errors='replace'))
        except (OSError, ValueError) as exc:
            out.append({'id': folder.name, 'error': f'record unreadable: {exc}'})
            continue
        record['id'] = folder.name
        record['folder'] = str(folder)
        out.append(record)
    out.sort(key=lambda r: r.get('startedAt') or 0, reverse=True)
    return out


def first_error(folder):
    """The first line of the build log that says something went wrong."""
    path = folder / 'log.txt'
    try:
        with open(path, 'r', errors='replace') as f:
            for number, line in enumerate(f, 1):
                if ERROR.search(line):
                    return {'line': number, 'text': line.strip()}
    except OSError:
        return None
    return None


def when(millis):
    """A start time as text."""
    if not millis: return ''
    import datetime
    return datetime.datetime.fromtimestamp(millis / 1000).strftime('%Y-%m-%d %H:%M')


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--limit', type=int, default=10, help='how many builds to show (default 10)')
    parser.add_argument('--errors', action='store_true', help='also show the first error of every failed build')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    root = home() / 'builds'
    builds = records(root)
    shown = builds[:args.limit] if args.limit > 0 else builds

    rows = []
    for record in shown:
        if 'error' in record:
            rows.append((record['id'], '?', '?', record['error'], ''))
            continue
        phase = str(record.get('phase', ''))
        result = RESULT.get(phase, phase.lower())
        seconds = record.get('seconds') or 0
        stage = record.get('stage') or ''
        rows.append((when(record.get('startedAt')), result, record.get('game', ''),
                     f'{stage} ({seconds}s)' if stage else f'{seconds}s', record['id']))

    text = f'{root}\n\n' + table(rows, ('started', 'result', 'game', 'stage', 'id'),
                                 empty='no build yet')
    if args.errors:
        for record in shown:
            if 'error' in record or RESULT.get(str(record.get('phase', ''))) == 'ok': continue
            found = first_error(Path(record['folder']))
            if found:
                text += f'\n{record.get("game", "?")} ({record["id"]}): line {found["line"]}: {found["text"]}'

    emit(args, {'root': str(root), 'builds': shown}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
