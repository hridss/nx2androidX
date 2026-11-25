#!/usr/bin/env python3
"""Split a card into one NSP per title: the base game, its update, its DLCs.

A cartridge dump (an XCI) holds several titles in one secure partition: the base game's NCAs, the
update's and the DLCs'. Every title's files end with its content meta NCA, whose header names the
title - so with prod.keys' header key the dump can be cut into one NSP per title, each of which the
porter reads on its own.

    python3 $NX2A_REPO/tools/card_split.py "/storage/emulated/0/Download/Game.xci"

The NSPs are written flat into a folder next to the card, one per title:
<title ID>_<what>.nsp, where the what is base, update or dlc (a title ID's low 12 bits say what it
is: 0 is the base game, 0x800 the update; a DLC lives one 0x1000 above the base game's own high
part). A card whose titles are numbered differently gets 'other'. The source is left alone and
nothing else is written.

The folder is the card's own, <card name>-<title ID>/, unless the player names another one: the
card is where the player put the game, so the game goes where the card is.

A compressed dump (NSZ, XCZ) cannot be split this way - its NCAs are still compressed. Decompress it
first (the porter does that during a build).
"""
import argparse
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nx2a_common import base_high_of, emit, fail, home, size_text, table, title_kind  # noqa: E402

CHUNK = 4 << 20


def load_porter():
    """The porter's own modules, as libraries (the same ones the porter uses)."""
    try:
        from porter.upstream import nsp
        from porter import dumps
    except ImportError as exc:
        fail(f'the porter\'s own modules are not reachable ({exc})')
    return nsp, dumps


def groups_of(nsp, path, entries, header_key):
    """The dump's files cut into one group per title: [(title ID, [(name, offset, size)])].

    A title's files are the ones that follow the previous title's content meta and end with their
    own content meta NCA - the meta is the last of them. The files before the first content meta
    have no title of their own and are left out.
    """
    groups, pending = [], []
    with open(path, 'rb') as f:
        for name, offset, size in entries:
            pending.append((name, offset, size))
            if not name.lower().endswith(('.nca', '.ncz')): continue
            title = nsp.meta_title(f, offset, header_key)
            if title is None: continue
            groups.append((title, pending))
            pending = []
    return groups, pending


def free_bytes(folder):
    """The room left on the disk the folder is on (the folder itself may not exist yet)."""
    path = Path(folder)
    while not path.exists() and path != path.parent: path = path.parent
    return shutil.disk_usage(path).free


def default_out(card, groups):
    """Where the NSPs go when the player named no folder: next to the card, in a folder of its own.

    The card is where the player put the game, so the game goes where the card is - and the title ID
    keeps two cards in the same folder apart. The base game's own title ID names the folder; a card
    without a base game falls back to the first title in it.
    """
    title = next((t for t, _ in groups if (t & 0xFFF) == 0), groups[0][0])
    return card.parent / f'{card.stem}-{title:016X}'


def write_nsp(dumps, card, out, group):
    """One title's files → an NSP at out (written to out.partial, then renamed)."""
    names = [name for name, _, _ in group]
    sizes = [size for _, _, size in group]
    header = dumps.pfs0_header(names, sizes)
    tmp = out.with_name(out.name + '.partial')
    try:
        with open(card, 'rb') as f, open(tmp, 'wb') as o:
            o.write(header)
            for name, offset, size in group:
                f.seek(offset)
                left = size
                while left > 0:
                    data = f.read(min(CHUNK, left))
                    if not data:
                        raise OSError(f'{name} is short: the dump ends before its header says')
                    o.write(data)
                    left -= len(data)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    tmp.replace(out)
    return len(header) + sum(sizes)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                     epilog='example: card_split.py "/storage/emulated/0/Download/Game.xci"')
    parser.add_argument('card', metavar='CARD', help='the card dump (an XCI or an NSP)')
    parser.add_argument('out', metavar='OUT', nargs='?', default='',
                        help="the folder the NSPs are written into (it is created if it is not there; default: a folder next to the card, <card name>-<title ID>)")
    parser.add_argument('--keys', default='', help="prod.keys: with it the titles can be read from the NCA headers (default: the player's own prod.keys)")
    parser.add_argument('--json', action='store_true', help='print the result as JSON instead of a table')
    args = parser.parse_args()

    card = Path(args.card)
    if not card.is_file():
        fail(f'no card at {card}')

    nsp, dumps = load_porter()
    if dumps.compressed(card):
        fail(f'{card.name} is a compressed dump (NSZ / XCZ): its NCAs are still compressed, so it cannot be split. Decompress it first.')

    keys = args.keys
    if not keys:
        keys = str(home().parent / 'prod.keys')
    if not Path(keys).is_file():
        fail(f'no prod.keys at {keys}: without its header key the titles cannot be read from the NCA headers')
    header_key = nsp.header_key(keys)
    if not header_key:
        fail(f'{keys} has no header_key line (or cannot be read)')
    if nsp.aes_ecb(header_key[:16]) is None:
        fail('the AES module is missing (pycryptodome): the NCA headers cannot be read')

    try:
        entries = dumps.entries(card)
    except Exception as exc:
        fail(f'{card.name} cannot be read as a dump: {exc}')

    groups, leftover = groups_of(nsp, card, entries, header_key)
    if not groups:
        fail(f'{card.name} holds no title that can be read (its content meta NCAs name no title)')

    out = Path(args.out) if args.out else default_out(card, groups)
    out.mkdir(parents=True, exist_ok=True)
    need = 0
    for title, group in groups:
        need += len(dumps.pfs0_header([n for n, _, _ in group], [s for _, _, s in group]))
        need += sum(s for _, _, s in group)
    room = free_bytes(out)
    if need > room:
        fail(f'not enough room for {size_text(need)} of NSPs: {size_text(room)} free on the disk {out} is on')

    rows, written = [], []
    base_high = base_high_of([title for title, _ in groups])
    for title, group in groups:
        name = f'{title:016X}_{title_kind(title, base_high)}.nsp'
        path = out / name
        size = write_nsp(dumps, card, path, group)
        rows.append((f'{title:016X}', title_kind(title, base_high), name, size_text(size)))
        written.append({'title': f'{title:016X}', 'kind': title_kind(title, base_high), 'name': name,
                        'path': str(path), 'bytes': size,
                        'files': [n for n, _, _ in group]})

    text = f'{card.name}: {len(entries)} file(s), {len(groups)} title(s)\n\n'
    text += table(rows, ('title ID', 'what', 'file', 'size'))
    if leftover:
        text += f'\n\n{len(leftover)} file(s) before the first content meta NCA have no title of their own and were left out.'
    text += f'\n\nThe NSPs are in {out}'

    emit(args, {'card': str(card), 'out': str(out), 'titles': written}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
