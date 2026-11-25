#!/usr/bin/env python3
"""What is on a game card, and what the porter will do with it.

A card dump that was rebuilt with the update inside (a 1G+1U+1D card) is one version as far as
the porter is concerned: it merges the card's own files into one RomFS. When the game profile
registers no update, the porter then takes the card as the base version - and the player gets the
old game without being told why.

This says what the card holds: every title in it (the base game, the update, the DLC), whether the
update is merged inside, and what the porter will make of it.

    python3 $NX2A_REPO/tools/card.py "Game (1G 1U 1D).xci"
    python3 $NX2A_REPO/tools/card.py "Game.xci" --keys ../prod.keys
    python3 $NX2A_REPO/tools/card.py "Game.xci" --profile ../profiles/0100F2C0115B4000/game.tsv

The tickets in the dump name their titles (the first 16 hex digits of a ticket's name are its title
ID). With prod.keys the content meta NCAs' headers can be read as well, which finds the titles a
ticketless card holds.
"""
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from nx2a_common import base_high_of, emit, fail, table, title_kind  # noqa: E402

TICKET = re.compile(r'^([0-9A-Fa-f]{16})[0-9A-Fa-f]{16}\.tik$')


def load_porter():
    """The porter's own NSP script, as a library (the same one the porter uses)."""
    try:
        from porter.upstream import nsp
    except ImportError as exc:
        fail(f'the porter\'s own modules are not reachable ({exc})')
    return nsp


def kinds(entries):
    """What the dump holds: how many of each kind of file."""
    out = {}
    for name, _, _ in entries:
        suffix = Path(name).suffix.lower()
        out[suffix or '(none)'] = out.get(suffix or '(none)', 0) + 1
    return out


def ticket_titles(entries):
    """The titles the tickets name: {title ID: [ticket names]}."""
    found = {}
    for name, _, _ in entries:
        m = TICKET.match(Path(name).name)
        if m: found.setdefault(int(m.group(1), 16), []).append(Path(name).name)
    return found


def role(title, base_high=None):
    """What a title ID is: the base game, an update or a DLC."""
    return {'base': 'base game', 'update': 'update', 'dlc': 'DLC', 'other': 'other'}[title_kind(title, base_high)]


def application(title):
    """The base game's title ID of a title."""
    low = title & 0xFFF
    if low == 0: return title
    if low == 0x800: return title & ~0xFFF
    return (title & ~0xFFF) - 0x1000


def meta_titles(nsp, path, header_key):
    """The titles the content meta NCAs name (the header key is needed to read them)."""
    if not header_key: return {}
    found = {}
    try:
        with open(path, 'rb') as f:
            for name, offset in nsp.dump_files(f):
                if not name.endswith(('.nca', '.ncz')): continue
                title = nsp.meta_title(f, offset, header_key)
                if title is not None: found.setdefault(title, []).append(Path(name).name)
    except Exception as exc:      # the header may be of a kind this cannot read: the tickets still say
        return {'_error': str(exc)}
    return found


def registered_versions(profile):
    """The versions a game profile registers: ['v0', 'v655360', ...]."""
    try:
        text = Path(profile).read_text(errors='replace')
    except OSError:
        return []
    out = []
    for line in text.splitlines():
        if line.startswith('#'): continue
        parts = line.split('\t')
        if len(parts) > 1 and parts[0] == 'build' and parts[1].startswith('v'):
            out.append(parts[1])
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                     epilog='example: card.py "Game (1G 1U 1D).xci" --keys ../prod.keys')
    parser.add_argument('card', metavar='CARD', help='the card dump (an NSP or an XCI)')
    parser.add_argument('--keys', default='', help="prod.keys: with it the card's titles can be read from the NCA headers")
    parser.add_argument('--profile', default='', help="the game profile's game.tsv: what versions it registers")
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    path = Path(args.card)
    if not path.is_file():
        fail(f'no card at {path}')

    nsp = load_porter()
    from porter import dumps

    try:
        entries = dumps.entries(path)
    except Exception as exc:
        fail(f'{path.name} cannot be read as a dump: {exc}')

    header_key = None
    if args.keys:
        header_key = nsp.header_key(args.keys)
        if not header_key:
            fail(f'{args.keys} has no header_key line (or cannot be read)')

    tickets = ticket_titles(entries)
    metas = meta_titles(nsp, path, header_key)
    meta_error = metas.pop('_error', None) if isinstance(metas, dict) else None

    titles = {}
    for title, names in tickets.items():
        titles.setdefault(title, {'tickets': [], 'meta': []})['tickets'] = names
    for title, names in metas.items():
        titles.setdefault(title, {'tickets': [], 'meta': []})['meta'] = names

    apps = {application(t) for t in titles}
    holds = None
    if len(apps) == 1:
        app = next(iter(apps))
        try:
            holds = nsp.holds_update(path, app, header_key)
        except Exception:
            holds = None

    versions = registered_versions(args.profile) if args.profile else []
    rows = []
    base_high = base_high_of(titles)
    for title in sorted(titles):
        what = role(title, base_high)
        rows.append((f'{title:016X}', what, len(titles[title]['meta']), len(titles[title]['tickets']),
                     'yes' if title in metas else 'no'))

    text = f'{path.name}: {len(entries)} file(s), {len(titles)} title(s)\n\n'
    text += table(rows, ('title ID', 'what', 'meta NCAs', 'tickets', 'seen in the headers'))

    if meta_error:
        text += f'\n\nThe NCA headers could not be read ({meta_error}); the tickets alone were used.'
    elif not args.keys:
        text += ('\n\nOnly the tickets were read. With prod.keys (--keys) the content meta NCAs\' headers can be read\n'
                 'as well, which finds the titles a ticketless card holds.')

    if holds:
        text += ('\n\nThe update is merged inside this card: the porter merges the card\'s files into one version.\n'
                 'It takes the card as the newest version the profile registers, and as the base version (v0) when the\n'
                 'profile registers no update - that is how a port silently comes out at the old version.')
    if versions:
        text += f'\nThe profile registers: {", ".join(versions)}'
        if holds and versions == ['v0']:
            text += ('\n  → the profile registers no update, so this card is taken as v0: the port will be the old version.\n'
                     '    What to do: register the update in the profile (its build row), or use a card that holds the\n'
                     '    base game alone. The card can also be cut into one NSP per title on the phone: card_split.py\n'
                     '    does it (the content meta NCAs say which title each file belongs to).')

    emit(args, {'card': str(path), 'files': len(entries), 'titles': {f'{t:016X}': v for t, v in titles.items()},
                'holds_update': holds, 'registered': versions}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
