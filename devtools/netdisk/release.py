"""The GitHub side: which release is published, what it carries, and the files themselves."""
import hashlib
import json
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import Asset

REPO = 'zFitness/nx2androidX'
TIMEOUT = 1800


class ReleaseError(Exception):
    """The release is not there, or its files are not what it says they are."""


@dataclass
class Release:
    tag: str
    assets: list = field(default_factory=list)

    @property
    def apk(self):
        """The APK of this release (its name carries the tag)."""
        return next((a for a in self.assets if a.name == f'nx2android-{self.tag}.apk'), None)


def gh(args, timeout=TIMEOUT):
    """The output of a gh call. A call that fails is an error, not an empty answer."""
    p = subprocess.run(['gh', *args], capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        raise ReleaseError(f"gh {' '.join(args)}: {(p.stderr or p.stdout).strip()}")
    return p.stdout


def latest(repo=REPO) -> Release:
    """The release that was cut last: the newest one.

    A pre-release counts too. It is a version like any other, and the pipeline publishes what was cut last.
    (The app's update check reads GitHub's "Latest", which is another thing: the newest full release.)
    """
    releases = json.loads(gh(['api', f'repos/{repo}/releases?per_page=20']))
    for item in releases:
        if item.get('draft'):
            continue
        return _release(item)
    raise ReleaseError(f'{repo} has no release yet')


def by_tag(repo, tag) -> Release:
    """A release by its tag: what the build of that tag put out."""
    return _release(json.loads(gh(['api', f'repos/{repo}/releases/tags/{tag}'])))


def wait_for_assets(repo, release, timeout=3600, every=30):
    """The release's files, once they are all there.

    A release is published right after the tag is pushed, and the build that tag started attaches the APK (and the
    game profiles package) a while later. So a release that was just published carries no files yet: the wait ends
    when the APK is there and the files have not changed for a poll.
    """
    if release.apk:
        return release
    deadline = time.time() + timeout
    last = sorted(a.name for a in release.assets)
    while True:
        time.sleep(every)
        current = by_tag(repo, release.tag)
        names = sorted(a.name for a in current.assets)
        if current.apk and names == last:
            return current
        last = names
        if time.time() > deadline:
            raise ReleaseError(f'the release {release.tag} carries no APK yet ({len(names)} file(s))')


def _release(data) -> Release:
    tag = data.get('tag_name') or ''
    assets = []
    for a in data.get('assets') or []:
        name = a.get('name') or ''
        if not name:
            continue
        assets.append(Asset(name=name, size=int(a.get('size') or 0), digest=a.get('digest') or ''))
    return Release(tag=tag, assets=assets)


def fetch(repo, release, dest: Path) -> list:
    """The release's files, fetched and checked against the digests the release gives."""
    dest.mkdir(parents=True, exist_ok=True)
    out = []
    for asset in release.assets:
        path = dest / asset.name
        if not path.exists():
            gh(['release', 'download', release.tag, '--repo', repo, '--pattern', asset.name, '--dir', str(dest)])
        if not path.exists():
            raise ReleaseError(f'{asset.name} is not in the release {release.tag}')
        check_digest(asset, path)
        asset.path = path
        out.append(asset)
    return out


def check_digest(asset, path: Path):
    """The file is what the release says it is: the digest is the release's own."""
    algo, _, want = (asset.digest or '').partition(':')
    if algo != 'sha256' or not want:
        return
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    got = h.hexdigest()
    if got != want:
        raise ReleaseError(f'{asset.name}: the digest is {got}, the release says {want}')
