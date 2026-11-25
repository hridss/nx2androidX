#!/usr/bin/env python3
"""What a run's log says: the crash's signature, the last errors, and which log holds the backtrace.

The host renames the log when it starts, so log.txt is the run that is going on (or the last one)
and log.prev.txt is the one before it. A crash's backtrace is in the log of the run that crashed,
which is why a crash seen after a restart is in log.prev.txt.

    python3 $NX2A_REPO/tools/log_triage.py --port ../ports/Smbw-data
    python3 $NX2A_REPO/tools/log_triage.py --port ../ports/Smbw-data --lines 40
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import emit, fail, size_text, table  # noqa: E402

# The lines that mean something went wrong. The first group is a crash; the second is the host
# saying a thing is not right; the third is an ordinary error the game printed.
CRASH = [
    (re.compile(r'CRASH'), 'crash'),
    (re.compile(r'\bSIG(SEGV|ABRT|BUS|FPE|ILL|TRAP)\b'), 'signal'),
    (re.compile(r'backtrace|stack trace|Stack trace'), 'backtrace'),
    (re.compile(r'fatal error|FATAL|panic|abort'), 'fatal'),
    (re.compile(r'segmentation fault|SIGSEGV'), 'signal'),
    (re.compile(r'\bassert\b|assertion failed'), 'assert'),
]
RED = re.compile(r'\[RED\]')
ERROR = re.compile(r'\b(error|failed|failure|cannot|unable to|denied|invalid)\b', re.I)
# The host's own lines: [dm] is the host, [vk] the Vulkan backend, [perf] the frame timer.
HOST = re.compile(r'^\[(dm|vk|perf|movie|lan)\]')


def read_log(path):
    """A log's lines (the file may be big: only the tail is kept)."""
    try:
        with open(path, 'r', errors='replace') as f:
            return f.read().splitlines()
    except OSError:
        return None


def scan(lines):
    """(crash lines, red lines, error lines) in a log."""
    crashes, reds, errors = [], [], []
    for number, line in enumerate(lines, 1):
        for pattern, kind in CRASH:
            if pattern.search(line):
                crashes.append((number, kind, line.strip()))
                break
        if RED.search(line):
            reds.append((number, line.strip()))
        elif ERROR.search(line) and not HOST.match(line.strip()):
            errors.append((number, line.strip()))
    return crashes, reds, errors


def tail(lines, count):
    """The last lines of a log, the ones that matter when nothing else says more."""
    return [(number, line.strip()) for number, line in list(enumerate(lines, 1))[-count:]]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--port', default='', help="the data pack's folder (the one with log.txt)")
    parser.add_argument('--lines', type=int, default=15, help='how many of the last lines to show (default 15)')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    folder = Path(args.port) if args.port else None
    if folder is None:
        fail('give the data pack\'s folder (--port)')
    if not folder.is_dir():
        fail(f'no data pack at {folder}')

    logs = []
    for name in ('log.txt', 'log.prev.txt'):
        path = folder / name
        if not path.is_file():
            logs.append({'name': name, 'path': str(path), 'there': False})
            continue
        lines = read_log(path)
        crashes, reds, errors = scan(lines)
        logs.append({
            'name': name, 'path': str(path), 'there': True,
            'bytes': path.stat().st_size, 'lines': len(lines),
            'crashes': [{'line': n, 'kind': k, 'text': t} for n, k, t in crashes],
            'red': [{'line': n, 'text': t} for n, t in reds],
            'errors': [{'line': n, 'text': t} for n, t in errors],
            'tail': [{'line': n, 'text': t} for n, t in tail(lines, args.lines)],
        })

    with_crash = [log for log in logs if log.get('crashes')]
    if with_crash:
        where = with_crash[0]['name']
        verdict = f'the crash is in {where}'
        if where == 'log.prev.txt':
            verdict += ' (the run before the last one: the game was started again after it crashed)'
        elif where == 'log.txt':
            verdict += ' (the run that is going on, or the last one)'
    elif any(log.get('there') for log in logs):
        verdict = 'no crash signature in either log'
    else:
        verdict = 'there is no log in this data pack yet'

    text = f'{folder}\n\n{verdict}\n'
    for log in logs:
        if not log.get('there'):
            text += f'\n{log["name"]}: (not there)\n'
            continue
        text += (f'\n{log["name"]}: {size_text(log["bytes"])}, {log["lines"]} lines, '
                 f'{len(log["crashes"])} crash line(s), {len(log["red"])} [RED] line(s)\n')
        if log['crashes']:
            text += '\n' + table([(c['line'], c['kind'], c['text']) for c in log['crashes']],
                                 ('line', 'kind', 'text')) + '\n'
        if log['red']:
            text += '\n[RED] lines:\n' + table([(c['line'], c['text']) for c in log['red']], ('line', 'text')) + '\n'
        if log['errors']:
            text += '\nlast errors:\n' + table([(c['line'], c['text']) for c in log['errors'][-args.lines:]],
                                                ('line', 'text')) + '\n'
        text += f'\nlast {args.lines} lines:\n' + table([(c['line'], c['text']) for c in log['tail']],
                                                        ('line', 'text')) + '\n'

    emit(args, {'port': str(folder), 'verdict': verdict, 'logs': logs}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
