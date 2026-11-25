"""Publish a release to the netdisks: the APK and the game profiles package go into the folder the players
download from, and the older versions are moved out of the way.

The pipeline is a fan-out. Every netdisk is a target of its own: it has its own credentials, its own tool and
its own folder, and a target that cannot go on (no cookie, a network that is down, a drive that is full) never
holds the other targets back. A new netdisk is a new module under targets/ and a line in the registry there.

    python3 -m devtools.netdisk publish                     # the latest release, every netdisk that has its credentials
    python3 -m devtools.netdisk publish --target quark       # one netdisk
    python3 -m devtools.netdisk install                      # fetch the tool the netdisks drive
"""
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]      # the repository root


@dataclass
class Asset:
    """One file of a release: the APK, the game profiles package."""
    name: str
    size: int = 0
    digest: str = ''          # 'sha256:...' as the release gives it; empty when the release does not say
    path: Path = None         # the local copy, once it has been fetched


@dataclass
class Outcome:
    """What happened to one file on one netdisk."""
    name: str
    state: str                # 'uploaded', 'skipped' or 'failed'
    detail: str = ''


class TargetError(Exception):
    """A netdisk that could not do its work."""


class TargetUnavailable(TargetError):
    """A netdisk that has no credentials: it is skipped, and that is not a failure."""


class Target:
    """A netdisk the release is published to.

    A target belongs to one run of the pipeline and is never shared with another target: its credentials, its
    working directory and its tool are its own. The pipeline gives every target a thread of its own, so a netdisk
    that is slow or down does not hold the others back.
    """
    name = ''

    def prepare(self):
        """Check that this netdisk can go on.

        Raise TargetUnavailable when it has no credentials (the pipeline skips it) and TargetError when its
        tool is missing (the pipeline reports it as a failure).
        """

    def publish(self, assets) -> list:
        """Put the assets into this netdisk's folder.

        One file that fails does not stop the others: every file gets an outcome of its own.
        """

    def close(self):
        """Let go of what this netdisk made (its working directory). It never raises."""
