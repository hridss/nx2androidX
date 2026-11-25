"""The pipeline's command line: publish a release to the netdisks.

    python3 -m devtools.netdisk publish                       # the latest release, every netdisk that has its credentials
    python3 -m devtools.netdisk publish --target quark         # one netdisk
    python3 -m devtools.netdisk publish --dry-run              # say what would be published, touch nothing
    python3 -m devtools.netdisk install                        # fetch the tool the netdisks drive
    python3 -m devtools.netdisk targets                        # the netdisks there are
"""
import argparse
import sys

from . import TargetError
from . import pipeline
from . import release as releases
from .targets import names
from .targets import quark


def main(argv=None):
    ap = argparse.ArgumentParser(prog='python3 -m devtools.netdisk', description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd')

    p = sub.add_parser('publish', help='put a release into the netdisks')
    p.add_argument('--tag', default='', help="the release to publish (default: the latest one)")
    p.add_argument('--repo', default=releases.REPO, help='the repository the releases are in')
    p.add_argument('--target', action='append', default=[], help='the netdisks to publish to (default: all of them)')
    p.add_argument('--dry-run', action='store_true', help='say what would be published, touch nothing')
    p.add_argument('--wait', type=int, default=0,
                   help="how long to wait for the release's files to be there (seconds; 0: do not wait)")

    i = sub.add_parser('install', help='fetch the tool the netdisks drive')
    i.add_argument('--repo', default=quark.REPO, help="the tool's repository")

    sub.add_parser('targets', help='list the netdisks')

    args = ap.parse_args()

    if args.cmd == 'publish':
        try:
            return pipeline.publish(tag=args.tag, repo=args.repo, targets=args.target or None,
                                    dry_run=args.dry_run, wait=args.wait)
        except (releases.ReleaseError, TargetError) as e:
            print(f'publish: {e}', file=sys.stderr)
            return 1
    if args.cmd == 'install':
        try:
            quark.install(repo=args.repo)
        except Exception as e:
            print(f'install: {e}', file=sys.stderr)
            return 1
        return 0
    if args.cmd == 'targets':
        for name in names():
            print(name)
        return 0

    ap.print_help()
    return 2


if __name__ == '__main__':
    sys.exit(main())
