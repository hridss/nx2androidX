#!/usr/bin/env python3
"""The memory layout of a game: the alias window, the stack and the heap, with their real addresses.

The guest's address space is one reserved block (the profile's mem_reserve). Inside it the host
lays out, from the block's base: a 256 MB gap for the SDK's node area, then the alias window
(mem_alias), then the stack (mem_stack), then the heap (what is left of the block).

    python3 $NX2A_REPO/tools/mem_layout.py --title 01006FE013472000
    python3 $NX2A_REPO/tools/mem_layout.py --profile ../ports/Smbw-data/profile/0100F2C0115B4000/game.tsv

Every size is given in both notations, and a layout that does not fit is an error, not a warning.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import both_hex, emit, fail, home, repo, table  # noqa: E402

# The gap the host leaves at the start of the block for the SDK's node area (hle_svc.c).
NODE_AREA = 0x10000000


def parse_rows(path):
    """A profile's rows: {name: value}. The value is the text after the tab."""
    rows = {}
    try:
        text = Path(path).read_text(errors='replace')
    except OSError as exc:
        fail(f'cannot read {path}: {exc}')
    for line in text.splitlines():
        if not line or line.startswith('#'): continue
        name, _, value = line.partition('\t')
        if value: rows[name.strip()] = value.strip()
    return rows


def number(rows, key):
    """A row's value as a number (the profiles write them in hex)."""
    if key not in rows: return None
    try:
        return int(rows[key], 0)
    except ValueError:
        fail(f'the profile\'s {key} is not a number: {rows[key]}')


def find_profile(title):
    """The profile of a game: the player's own layer first, then the tool tree's."""
    candidates = [home() / 'profiles' / title / 'game.tsv',
                  repo() / 'nxnative' / 'dm' / 'games' / title / 'game.tsv']
    for path in candidates:
        if path.is_file(): return path
    return None


def layout(rows):
    """The layout: (reserve, alias, stack, heap size) or an error about why it does not fit."""
    reserve = number(rows, 'mem_reserve')
    alias = number(rows, 'mem_alias')
    stack = number(rows, 'mem_stack')
    if reserve is None or alias is None or stack is None:
        missing = [k for k, v in (('mem_reserve', reserve), ('mem_alias', alias), ('mem_stack', stack)) if v is None]
        fail(f'the profile has no {", ".join(missing)}')
    used = NODE_AREA + alias + stack
    if used > reserve:
        over = used - reserve
        fail(f'the layout does not fit the reserved block: the alias window ({both_hex(alias)}) + the stack '
             f'({both_hex(stack)}) + the 256 MB node area need {both_hex(used)}, the block is {both_hex(reserve)}: '
             f'{both_hex(over)} too much')
    return reserve, alias, stack, reserve - used


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--title', default='', help='the title ID of the game (16 hex digits)')
    parser.add_argument('--profile', default='', help='the path of a profile\'s game.tsv')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    path = Path(args.profile) if args.profile else (find_profile(args.title) if args.title else None)
    if path is None:
        fail('give a title ID (--title) or a profile path (--profile)')
    if not path.is_file():
        fail(f'no profile at {path}')

    rows = parse_rows(path)
    reserve, alias, stack, heap = layout(rows)
    pool = number(rows, 'app_pool')

    regions = [
        ('reserved block', '0x0', both_hex(reserve), 'the whole guest address space'),
        ('node area', both_hex(NODE_AREA), both_hex(NODE_AREA), "the SDK's node area (always at the block's start)"),
        ('alias window', both_hex(NODE_AREA), both_hex(NODE_AREA + alias), 'the mapped resources'),
        ('stack', both_hex(NODE_AREA + alias), both_hex(NODE_AREA + alias + stack), 'the guest stack'),
        ('heap', both_hex(NODE_AREA + alias + stack), both_hex(reserve), 'what the game allocates'),
    ]
    text = f'{path}\n\n' + table([(name, start, end, what) for name, start, end, what in regions],
                                 ('region', 'start', 'end', 'what it is'))
    if pool:
        text += f'\n\napplication memory pool (app_pool): {both_hex(pool)}'
    text += ('\n\nThe addresses are relative to the block\'s base: the host places the block wherever the\n'
             'kernel gives it room, so the numbers shift from run to run.')

    payload = {'profile': str(path), 'reserve': reserve, 'alias': alias, 'stack': stack, 'heap': heap,
               'node_area': NODE_AREA, 'app_pool': pool,
               'regions': [{'name': n, 'start': s, 'end': e} for n, s, e, _ in regions]}
    emit(args, payload, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
