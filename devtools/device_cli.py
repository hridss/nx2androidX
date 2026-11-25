#!/usr/bin/env python3
"""Runs a command in the app's own environment on the phone: the rootfs's glibc, the porter, the build tools.

    python3 devtools/device_cli.py env                   the environment it runs with
    python3 devtools/device_cli.py python <args...>      the rootfs's python (-m porter doctor ...)
    python3 devtools/device_cli.py guest <bin> <args..>  any guest program through the rootfs's loader
    python3 devtools/device_cli.py raw <adb args...>      plain adb shell (no guest environment)
    python3 devtools/device_cli.py launch <title ID>     start a game in the app (no tapping)
    python3 devtools/device_cli.py ctl <title ID> <line> send DM_CTL lines to the running game (key presses)
    python3 devtools/device_cli.py shot <local path>      the phone's screen into a local PNG

The device comes from devtools/device.py (device.json or the environment), never from a serial in here.
The environment is the app's guestEnv() (android/app/src/main/java/com/nx/android/data/Env.kt): that is what the
app gives the tools it runs, and a missing variable (LD_LIBRARY_PATH, NX2A_REPO ...) makes the tools miss their
own libraries or their tree.

Every command is written as a script on the device and run there: adb shell's nested quoting breaks easily (one
'cat > f' once wrote a file to the wrong place and broke the phone's compiler).
"""
import re
import shlex
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from device import ADB, PKG  # noqa: E402

F = f'/data/user/0/{PKG}/files'   # the app's own folder (the guest environment lives there)
T = f'{F}/tool'
R = f'{F}/rootfs'
LOADER = f'{R}/usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1'
PYTHON = f'{R}/usr/bin/python3.13'
SCRIPT = f'{F}/tmp/nx2a_run.sh'   # the script the command is written to (the app's own temp folder)

ENV = {
    'PATH': f'{T}/toolchain/clang/bin:{T}/toolchain/bin:{R}/usr/bin',
    'HOME': f'{F}/home',
    'TMPDIR': f'{F}/tmp',
    'PYTHONHOME': f'{R}/usr',
    'PYTHONPATH': f'{T}/pylibs',
    # the toolchain's own libraries first (the pipeline needs the toolchain's libshaderc, which glslc needs)
    'LD_LIBRARY_PATH': f'{T}/toolchain/lib:{R}/usr/lib/aarch64-linux-gnu:{R}/usr/lib',
    'SSL_CERT_FILE': f'{R}/etc/ssl/certs/ca-certificates.crt',
    'NX2A_HOME': f'{F}/nx2a',
    'NX2A_NDK': f'{T}/toolchain/ndk',
    'NX2A_REPO': T,
    'NX2A_TOOLCHAIN': f'{T}/toolchain',
    'DOTNET_ROOT': f'{T}/toolchain/dotnet',
    'NX2A_NX_EXTRACT': f'{T}/tools/nx_extract',
    'NX2A_WORK': f'{F}/work',
    'NX2A_RUNTIMES': f'{F}/runtimes',
    # NX2A_PLAYER_API_REVISION is deliberately not set: the app sets it (the tools it runs must match the app),
    # and the tree's own tools came from that app. Setting it by hand makes the porter refuse to work.
    'PYTHONUNBUFFERED': '1',
    'PYTHONIOENCODING': 'utf-8',
    'DOTNET_SYSTEM_GLOBALIZATION_INVARIANT': '1',
    'DOTNET_EnableWriteXorExecute': '0',
    'DOTNET_CLI_TELEMETRY_OPTOUT': '1',
    'DOTNET_SKIP_FIRST_TIME_EXPERIENCE': '1',
    'NUGET_PACKAGES': f'{F}/nx2a/nuget',
}


def guest(args, extra=None, cwd=None, timeout=7200, body=None):
    """Runs a guest command on the device, returns (rc, output). extra adds environment variables, body is the
    script's last line (by default it execs the arguments)."""
    env = dict(ENV)
    env.update(extra or {})
    lines = ['#!/bin/sh', f'cd {shlex.quote(cwd or T)}']
    for k, v in env.items():
        lines.append(f'export {k}={shlex.quote(v)}')
    lines.append(body if body is not None else 'exec ' + ' '.join(shlex.quote(a) for a in args))
    subprocess.run(ADB + ['shell', f'run-as {PKG} sh -c "cat > {SCRIPT}"'],
                   input='\n'.join(lines) + '\n', capture_output=True, text=True, check=True)
    r = subprocess.run(ADB + ['shell', f'run-as {PKG} sh {SCRIPT}'],
                       capture_output=True, text=True, timeout=timeout)
    return r.returncode, (r.stdout or '') + (r.stderr or '')


def run(args, timeout=7200, extra=None, cwd=None):
    rc, out = guest(args, extra=extra, cwd=cwd, timeout=timeout)
    print(out, end='')
    return rc


def shell(cmd, timeout=600):
    """One adb shell command (run-as when it needs the app's own files)."""
    r = subprocess.run(ADB + ['shell', cmd], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or '') + (r.stderr or '')


def ctl_file(title_id=None):
    """The game's control file (the host polls it every 100 ms and acts on the lines appended to it)."""
    if title_id:
        return f'{F}/ctl-{title_id}.txt'
    found = re.findall(r'ctl-([0-9A-Fa-f]{16})\.txt', shell(f'run-as {PKG} ls {F}'))
    if len(found) != 1:
        sys.exit(f'device_cli: {len(found)} games are running (ctl files); name the one: ctl <title ID> <line>')
    return f'{F}/ctl-{found[0]}.txt'


def ctl(lines, title_id=None):
    """Appends lines to the game's control file: a key press ("A", "Down+Right", "T0.5x0.5"), "shot <path>",
    "passdump <n>", "env KEY=VALUE", ... (the host's own list is in nxnative/dm/host/hle_hid.c)."""
    path = ctl_file(title_id)
    p = subprocess.Popen(ADB + ['shell', f'run-as {PKG} sh -c "cat >> {path}"'],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p.communicate(('\n'.join(lines) + '\n').encode())
    print(f'{path}: {len(lines)} line(s)')


def launch(title_id):
    """Starts a game in the app: the shortcut's own intent (the app's library card and the home-screen icon use
    the same door), so a walkthrough needs no tapping."""
    r = subprocess.run(ADB + ['shell', 'am', 'start', '-a', 'com.nx.android.action.LAUNCH_GAME',
                              '-n', f'{PKG}/.MainActivity', '--es', 'shortcut_title_id', title_id],
                       capture_output=True, text=True)
    print((r.stdout or '') + (r.stderr or ''), end='')
    return r.returncode


def shot(path):
    """The phone's screen into a local PNG file (the app's window, the game in it)."""
    remote = '/data/local/tmp/nx2a_shot.png'
    subprocess.run(ADB + ['shell', 'screencap', '-p', remote], capture_output=True, check=True)
    subprocess.run(ADB + ['pull', remote, path], capture_output=True, check=True)
    subprocess.run(ADB + ['shell', 'rm', '-f', remote], capture_output=True)
    print(path)


def main(argv):
    if not argv or argv[0] == 'env':
        for k, v in ENV.items():
            print(f'{k}={v}')
        return 0
    what = argv[0]
    if what == 'python':
        return run([LOADER, PYTHON] + argv[1:])
    if what == 'guest':
        return run([LOADER] + argv[1:])
    if what == 'raw':
        r = subprocess.run(ADB + ['shell'] + argv[1:], capture_output=True, text=True)
        print(r.stdout or '', end='')
        print(r.stderr or '', end='')
        return r.returncode
    if what == 'launch':
        return launch(argv[1])
    if what == 'ctl':
        rest = argv[1:]
        title = rest[0] if rest and re.fullmatch(r'[0-9A-Fa-f]{16}', rest[0]) else None
        ctl(rest[1:] if title else rest, title)
        return 0
    if what == 'shot':
        shot(argv[1])
        return 0
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
