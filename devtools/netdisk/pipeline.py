"""The fan-out: the release goes to every netdisk that has its credentials, and one netdisk's trouble never
holds the others back."""
import tempfile
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from pathlib import Path

from . import TargetError, TargetUnavailable
from . import release as releases
from .targets import build, names as target_names


def say(msg):
    print(msg, flush=True)


@dataclass
class Result:
    name: str
    state: str                    # 'ok', 'skipped' or 'failed'
    detail: str = ''
    outcomes: list = field(default_factory=list)


def publish(tag='', repo=releases.REPO, targets=None, dry_run=False, out=say, make=None, wait=0) -> int:
    """Put a release into the netdisks. The exit code is 0 when every netdisk went well."""
    release = releases.by_tag(repo, tag) if tag else releases.latest(repo)
    if wait:
        release = releases.wait_for_assets(repo, release, timeout=wait)
    if not release.assets:
        raise releases.ReleaseError(f'the release {release.tag} carries no files')
    if not release.apk:
        raise releases.ReleaseError(f'the release {release.tag} carries no APK yet (the build attaches it a while '
                                    f'later): run it again, or pass --wait')
    out(f'{release.tag}: {len(release.assets)} file(s)')
    for asset in release.assets:
        out(f'  {asset.name} ({asset.size} bytes)')
    if dry_run:
        out('(dry run: nothing was uploaded)')
        return 0
    make = make or build
    wanted = [make(name) for name in (targets or target_names())]
    ready, failed = prepare(wanted, out)
    if not ready:
        out('no netdisk was ready: nothing was published')
        return 1 if failed else 0
    with tempfile.TemporaryDirectory(prefix='nx2a-netdisk-') as tmp:
        assets = releases.fetch(repo, release, Path(tmp))
        results = run(ready, assets, out)
    return 1 if failed or any(r.state == 'failed' for r in results) else 0


def prepare(targets, out=say):
    """The netdisks that can go on.

    The ones that cannot are told right away (a netdisk without a cookie is skipped, one without its tool is a
    failure), and neither of them holds the others back.
    """
    ready, failed = [], 0
    for target in targets:
        try:
            target.prepare()
            ready.append(target)
        except TargetUnavailable as e:
            out(f'{target.name}: skipped ({e})')
        except Exception as e:
            out(f'{target.name}: failed ({e})')
            failed += 1
            _close(target)
    return ready, failed


def run(targets, assets, out=say) -> list:
    """Every netdisk gets a thread and a ground of its own: one that is down does not hold the others back."""
    results = []
    with ThreadPoolExecutor(max_workers=max(1, len(targets))) as pool:
        futures = [pool.submit(_one, target, assets) for target in targets]
        for future in futures:
            results.append(future.result())
    report(results, out)
    return results


def _one(target, assets) -> Result:
    """One netdisk's work: what it managed, or why it could not go on."""
    try:
        outcomes = target.publish(assets)
    except Exception as e:
        return Result(target.name, 'failed', str(e))
    finally:
        _close(target)
    state = 'failed' if any(o.state == 'failed' for o in outcomes) else 'ok'
    return Result(target.name, state, '', outcomes)


def _close(target):
    try:
        target.close()
    except Exception:
        pass


def report(results, out=say):
    """What every netdisk made of the release."""
    for r in results:
        if r.state == 'skipped':
            out(f'{r.name}: skipped ({r.detail})')
            continue
        if r.state == 'failed':
            out(f'{r.name}: failed ({r.detail})')
        else:
            out(f'{r.name}: {len(r.outcomes)} file(s)')
        for o in r.outcomes:
            out(f'  {o.name}: {o.state} ({o.detail})')
