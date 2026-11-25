#!/usr/bin/env python3
"""What the phone's porter and engine differ from the upstream's.

The guest has no git, so the comparison is done with a tarball of the upstream repository: the
tool fetches it (once), unpacks it, and compares the trees file by file.

    python3 $NX2A_REPO/tools/upstream_diff.py                 # what differs from the upstream's main
    python3 $NX2A_REPO/tools/upstream_diff.py --update        # fetch the upstream again first
    python3 $NX2A_REPO/tools/upstream_diff.py --path porter   # only the porter

Only the porter and the engine are compared: the game profiles are the player's own (the app does
not carry them), and the desktop tools are not on the phone.
"""
import argparse
import hashlib
import os
import shutil
import sys
import tarfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import emit, fail, repo, table  # noqa: E402
from fetch import fetch  # noqa: E402

DEFAULT_REPO = 'shirai91/nx2android'
DEFAULT_PATHS = ['porter', 'nxnative/dm/android']
TARBALL = 'https://codeload.github.com/{repo}/tar.gz/refs/heads/{ref}'


def cache_dir():
    """Where the upstream is kept between the runs."""
    home = os.environ.get('HOME')
    base = Path(home) if home else Path(os.environ.get('TMPDIR', '.'))
    return base / '.cache' / 'nx2a-upstream'


def download(repo_name, ref, target, update):
    """The upstream's tarball, fetched once and kept."""
    if target.exists() and not update:
        return target
    url = TARBALL.format(repo=repo_name, ref=ref)
    status, _, body, attempts, seconds = fetch(url, timeout=120, retries=4)
    if status != 200:
        fail(f'the upstream answered {status} for {url}')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(body)
    print(f'fetched {url} ({len(body)} bytes, {attempts} attempt(s), {seconds:.1f}s)')
    return target


def unpack(tarball, into):
    """The tarball's tree, unpacked (the tarball holds one folder: the repository's name)."""
    if into.exists():
        shutil.rmtree(into, ignore_errors=True)
    into.mkdir(parents=True)
    with tarfile.open(tarball, 'r:gz') as tar:
        for member in tar.getmembers():
            name = member.name.split('/', 1)
            if len(name) < 2: continue
            member.name = name[1]
            tar.extract(member, into, filter='data')
    return into


def digest(path):
    """A file's SHA-256."""
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def tree(root, sub):
    """{relative path: sha256} of a folder of the tree."""
    out = {}
    base = root / sub
    if not base.is_dir(): return out
    for path in base.rglob('*'):
        if not path.is_file(): continue
        rel = path.relative_to(root)
        if '__pycache__' in rel.parts or path.suffix in ('.pyc', '.apk', '.so', '.dmsv', '.spv'): continue
        out[str(rel)] = digest(path)
    return out


def compare(mine, theirs):
    """(only on the phone, only upstream, different)."""
    only_mine = sorted(set(mine) - set(theirs))
    only_theirs = sorted(set(theirs) - set(mine))
    different = sorted(p for p in set(mine) & set(theirs) if mine[p] != theirs[p])
    return only_mine, only_theirs, different


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--repo', default=DEFAULT_REPO, help=f'the upstream repository (default {DEFAULT_REPO})')
    parser.add_argument('--ref', default='main', help='the branch to compare against (default main)')
    parser.add_argument('--path', action='append', default=[], help='a folder to compare (repeatable)')
    parser.add_argument('--update', action='store_true', help='fetch the upstream again before comparing')
    parser.add_argument('--limit', type=int, default=40, help='how many files to list per group (default 40)')
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    paths = args.path or DEFAULT_PATHS
    mine_root = repo()
    if not (mine_root / 'porter').is_dir():
        fail(f'the tool tree is not where it should be ({mine_root}); NX2A_REPO points elsewhere')

    cache = cache_dir()
    tarball = cache / f'{args.repo.replace("/", "_")}-{args.ref}.tar.gz'
    download(args.repo, args.ref, tarball, args.update)
    theirs_root = unpack(tarball, cache / f'{args.repo.replace("/", "_")}-{args.ref}')
    # the tarball's own folder (the repository's name) is the root of the tree
    entries = [p for p in theirs_root.iterdir() if p.is_dir()]
    if len(entries) == 1: theirs_root = entries[0]

    groups = []
    for sub in paths:
        only_mine, only_theirs, different = compare(tree(mine_root, sub), tree(theirs_root, sub))
        groups.append({'path': sub, 'only_mine': only_mine, 'only_theirs': only_theirs, 'different': different})

    text = f'{mine_root} against {args.repo}/{args.ref}\n\n'
    for group in groups:
        text += (f"{group['path']}: {len(group['only_mine'])} only on the phone, "
                 f"{len(group['only_theirs'])} only upstream, {len(group['different'])} different\n")
        for label, key in (('only on the phone', 'only_mine'), ('only upstream', 'only_theirs'), ('different', 'different')):
            if not group[key]: continue
            text += f'  {label}:\n'
            for name in group[key][:args.limit]:
                text += f'    {name}\n'
            if len(group[key]) > args.limit:
                text += f'    ... and {len(group[key]) - args.limit} more\n'

    emit(args, {'repo': args.repo, 'ref': args.ref, 'groups': groups}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
