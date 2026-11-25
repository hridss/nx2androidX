"""The Quark drive: the kuake CLI (https://github.com/zhangjingwei/kuake_cli) does the work.

The CLI takes the cookie from the environment and reads a .env in the directory it runs in, so the target gives
every netdisk a directory of its own: one netdisk's .env can never reach another one, and one netdisk's cookie
is never handed to another one.
"""
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

from .. import ROOT, Asset, Outcome, Target, TargetError, TargetUnavailable
from ..release import check_digest

FOLDER = '/我的/nx2AndroidX'       # the folder the share link points at
ARCHIVE = '/我的/旧版本'            # where the older versions go
COOKIE = 'KUAKE_COOKIE'             # the CLI's own name for the cookie
BINARY = 'NX2A_KUAKE_BIN'           # where the CLI is, when it is not in tools/kuake
FOLDER_ENV = 'NX2A_NETDISK_QUARK_FOLDER'
ARCHIVE_ENV = 'NX2A_NETDISK_QUARK_ARCHIVE'
REPO = 'zhangjingwei/kuake_cli'
TIMEOUT = 3600

# the CLI's build for this machine
PLATFORMS = {
    ('darwin', 'amd64'): 'darwin-amd64',
    ('darwin', 'arm64'): 'darwin-arm64',
    ('linux', 'amd64'): 'linux-amd64',
    ('linux', 'arm64'): 'linux-arm64',
    ('windows', 'amd64'): 'windows-amd64.exe',
}


def _machine():
    """The machine's cpu, as the release names it (the runner calls its cpu x86_64, the release amd64)."""
    machine = platform.machine().lower()
    if machine in ('x86_64', 'amd64'):
        return 'amd64'
    if machine in ('aarch64', 'arm64'):
        return 'arm64'
    return machine


def build_for_this_machine():
    """The CLI's build for this machine, as the release names it ('' when it has none)."""
    system = sys.platform
    if system.startswith('linux'):
        system = 'linux'
    elif system.startswith('win'):
        system = 'windows'
    return PLATFORMS.get((system, _machine()), '')


def find_binary():
    """The kuake CLI: the one in tools/kuake, or the one on PATH."""
    local = ROOT / 'tools' / 'kuake' / 'kuake'
    if local.exists():
        return str(local)
    return shutil.which('kuake') or ''


def install(repo=REPO, dest=None, out=print):
    """The kuake CLI, fetched into tools/kuake/.

    The binary is not in this repository (the toolchain's place is tools/, which is not committed) and it is not
    redistributed either: the pipeline fetches it when it is needed, and checks it against the digest the
    release gives. A machine the release carries no binary for (the macOS on Apple Silicon, for one) gets the
    tool built from that release's source.

    The tool's repository is public, so it is read without a token: the release's own token does not reach it.
    """
    build = build_for_this_machine()
    if not build:
        raise TargetError(f'no kuake build for {sys.platform} {platform.machine()}')
    data = _public_json(f'https://api.github.com/repos/{repo}/releases/latest')
    tag = data.get('tag_name') or ''
    names = [a.get('name', '') for a in data.get('assets') or []]
    name = next((n for n in names if n.startswith('kuake-v') and n.endswith(build)), '')
    dest = Path(dest or (ROOT / 'tools' / 'kuake'))
    dest.mkdir(parents=True, exist_ok=True)
    target = dest / ('kuake.exe' if build.endswith('.exe') else 'kuake')
    if _version_of(target) == tag:
        out(f'kuake {tag}: {target} (already there)')
        return target
    if name:
        digest = next((a.get('digest') or '' for a in data.get('assets') or [] if a.get('name') == name), '')
        url = next((a.get('browser_download_url') or '' for a in data.get('assets') or [] if a.get('name') == name), '')
        with tempfile.TemporaryDirectory(prefix='nx2a-kuake-') as tmp:
            src = Path(tmp) / name
            _fetch(url, src)
            if not src.exists() or not src.stat().st_size:
                raise TargetError(f'{name} was not in the release {tag}')
            check_digest(Asset(name=name, digest=digest), src)
            shutil.move(str(src), str(target))
        out(f'kuake {tag}: {target} (the release\'s binary)')
    else:
        build_from_source(repo, tag, target)
        out(f'kuake {tag}: {target} (built from the release\'s source)')
    target.chmod(0o755)
    return target


def _public_json(url):
    """A JSON document from a public URL."""
    request = urllib.request.Request(url, headers={'User-Agent': 'nx2android-release',
                                                   'Accept': 'application/vnd.github+json'})
    with urllib.request.urlopen(request, timeout=120) as r:
        return json.loads(r.read().decode('utf-8'))


def _fetch(url, dest: Path):
    """A file from a public URL."""
    request = urllib.request.Request(url, headers={'User-Agent': 'nx2android-release'})
    with urllib.request.urlopen(request, timeout=600) as r, open(dest, 'wb') as f:
        shutil.copyfileobj(r, f)


def _version_of(binary: Path):
    """The version of a kuake that is there, or '' when there is none."""
    if not binary.exists():
        return ''
    p = subprocess.run([str(binary), 'version'], capture_output=True, text=True)
    answer = _answer(p.stdout)
    if answer and isinstance(answer.get('data'), dict):
        return str(answer['data'].get('version') or '')
    return ''


def build_from_source(repo, tag, target: Path):
    """The release carries no binary for this machine: it is built from that release's source."""
    if not shutil.which('go'):
        raise TargetError('the kuake CLI carries no binary for this machine, and Go is not there to build one')
    with tempfile.TemporaryDirectory(prefix='nx2a-kuake-src-') as tmp:
        p = subprocess.run(['git', 'clone', '--depth', '1', '--branch', tag, f'https://github.com/{repo}.git', tmp],
                           capture_output=True, text=True)
        if p.returncode != 0:
            raise TargetError(f'the source of {tag} could not be fetched: {(p.stderr or p.stdout).strip()}')
        p = subprocess.run(['go', 'build', '-trimpath', '-o', str(target), './cmd'], cwd=tmp,
                           capture_output=True, text=True, env={**os.environ, 'CGO_ENABLED': '0'})
        if p.returncode != 0:
            raise TargetError(f'kuake could not be built: {(p.stderr or p.stdout).strip()}')


class QuarkTarget(Target):
    """The Quark drive: the release's files go into the folder the share link points at, and the files that are
    not this release's are moved into the archive folder beside it."""

    name = 'quark'

    def __init__(self, binary='', cookie=None, folder='', archive='', workdir=None):
        self.binary = binary or os.environ.get(BINARY, '') or find_binary()
        self.cookie = os.environ.get(COOKIE, '') if cookie is None else cookie
        self.folder = folder or os.environ.get(FOLDER_ENV, '') or FOLDER
        self.archive = archive or os.environ.get(ARCHIVE_ENV, '') or ARCHIVE
        self.workdir = Path(workdir) if workdir else None
        self._own_workdir = False

    def prepare(self):
        if not self.binary:
            raise TargetError('the kuake CLI is not there: python3 -m devtools.netdisk install')
        if not Path(self.binary).exists() and not shutil.which(self.binary):
            raise TargetError(f'the kuake CLI is not there ({self.binary}): python3 -m devtools.netdisk install')
        if not self.cookie:
            raise TargetUnavailable(f'the cookie is missing ({COOKIE})')
        if self.workdir is None:
            self.workdir = Path(tempfile.mkdtemp(prefix='nx2a-quark-'))
            self._own_workdir = True
        else:
            self.workdir.mkdir(parents=True, exist_ok=True)
        self._check()

    def close(self):
        if self._own_workdir and self.workdir:
            shutil.rmtree(self.workdir, ignore_errors=True)
            self.workdir = None

    def publish(self, assets) -> list:
        there = self._list(self.folder)
        outcomes = []
        for asset in assets:
            outcomes.append(self._put(asset, there))
        outcomes.extend(self._sweep({a.name for a in assets}))
        return outcomes

    def _check(self):
        """The cookie has to work before anything is downloaded or uploaded."""
        code, out, err = self._call('user')
        if code != 0:
            raise TargetError(f'the cookie was refused: {_detail(out, err)}')

    def _put(self, asset, there):
        """One file: the netdisk takes it, unless it already has the very same one."""
        if there.get(asset.name) == asset.size:
            return Outcome(asset.name, 'skipped', 'the netdisk has it already')
        code, out, err = self._call('upload', str(asset.path), f'{self.folder}/{asset.name}', '--policy', 'rsync')
        detail = _detail(out, err)
        if code != 0:
            return Outcome(asset.name, 'failed', detail)
        answer = _answer(out)
        if answer and answer.get('code') == 'SKIPPED':
            return Outcome(asset.name, 'skipped', str(answer.get('message') or ''))
        return Outcome(asset.name, 'uploaded', detail)

    def _sweep(self, keep):
        """The files that are not this release's go into the archive folder: the folder the players see holds
        the current version, and the older ones are kept, not thrown away."""
        there = self._list(self.folder)
        stale = sorted(n for n in there if n not in keep)
        if not stale:
            return []
        self._ensure_archive()
        outcomes = []
        for name in stale:
            code, out, err = self._call('move', f'{self.folder}/{name}', self.archive)
            detail = _detail(out, err)
            outcomes.append(Outcome(name, 'moved' if code == 0 else 'failed', detail))
        return outcomes

    def _ensure_archive(self):
        """The archive folder has to be there before a file can go into it."""
        code, out, err = self._call('list', self.archive, '--stream')
        if code == 0:
            return
        name, parent = _split(self.archive)
        self._call('create', name, parent)
        code, out, err = self._call('list', self.archive, '--stream')
        if code != 0:
            raise TargetError(f'the archive folder {self.archive} is not there: {_detail(out, err)}')

    def _list(self, path):
        """The files in a folder: {name: size}."""
        code, out, err = self._call('list', path, '--stream')
        if code != 0:
            raise TargetError(f'the folder {path} cannot be read: {_detail(out, err)}')
        files = {}
        for line in out.splitlines():
            answer = _answer(line)
            if not answer:
                continue
            if not answer.get('success', True):
                raise TargetError(f'the folder {path} cannot be read: {answer.get("message", "")}')
            data = answer.get('data') or {}
            if 'list' in data:
                for item in data['list'] or []:
                    if item.get('file_name'):
                        files[item['file_name']] = item.get('size', 0)
                continue
            if data.get('file_name'):
                files[data['file_name']] = data.get('size', 0)
        return files

    def _call(self, *args, timeout=TIMEOUT):
        """One CLI call: (code, out, err). The CLI's answer is on stdout, its progress on stderr."""
        p = subprocess.run([self.binary, *args], capture_output=True, text=True,
                           env=self._env(), cwd=str(self.workdir), timeout=timeout)
        return p.returncode, p.stdout, p.stderr

    def _env(self):
        """The CLI's own environment: its cookie and the few variables it needs, and nothing of ours."""
        env = {}
        for key in ('PATH', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR',
                    'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'ALL_PROXY',
                    'http_proxy', 'https_proxy', 'no_proxy', 'all_proxy'):
            value = os.environ.get(key)
            if value:
                env[key] = value
        env.setdefault('PATH', '/usr/bin:/bin')
        env.setdefault('HOME', str(self.workdir))
        if self.cookie:
            env[COOKIE] = self.cookie
        return env


def _split(path):
    """'/我的/旧版本' -> ('旧版本', '/我的'): the name a folder is made under, and its parent."""
    parent, _, name = path.rstrip('/').rpartition('/')
    return name, parent or '/'


def _answer(text):
    """The JSON object in the text (the CLI prints one answer per call, over one line or over several)."""
    text = (text or '').strip()
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        pass
    for line in reversed(text.splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            return json.loads(line)
        except ValueError:
            continue
    return None


def _detail(out, err):
    """What the CLI said: its answer's message, or what it wrote on stderr."""
    answer = _answer(out)
    if answer:
        return str(answer.get('message') or answer.get('code') or '')
    return (err or out).strip()
