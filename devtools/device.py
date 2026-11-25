# The device and app the dev tools work with, instead of a serial and package in every script:
#   devtools/device.json (not committed) {"serial": "<adb serial, e.g. 192.168.1.20:5555>", "package": "<the app's package>"}, or NX2A_DEVICE / NX2A_PACKAGE (these win).
#   No serial: the only connected device. No package: the profile's package for NX2A_GAME (a title ID); with none of these the tools stop.
# From Python: from device import ADB, PKG, FILES (ADB = the adb command with -s). From a shell script: eval "$(python devtools/device.py --sh)" sets ADB, SERIAL, PKG, F.
import json, os, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from porter.toolchain import find_adb   # noqa: E402


def _conf():
    try: return json.loads((HERE / 'device.json').read_text(encoding='utf-8'))
    except (OSError, ValueError): return {}


def _package(c):
    if os.environ.get('NX2A_PACKAGE') or c.get('package'): return os.environ.get('NX2A_PACKAGE') or c['package']
    if os.environ.get('NX2A_GAME'):
        from porter import profiles
        return profiles.load(os.environ['NX2A_GAME']).package
    sys.exit('devtools: no app to work with: set "package" in devtools/device.json, NX2A_PACKAGE, or NX2A_GAME (a title ID)')


def _connected(adb_exe, serial):
    r = subprocess.run([adb_exe, 'devices'], capture_output=True, text=True)
    return any(line.split('\t')[0] == serial for line in r.stdout.splitlines() if '\tdevice' in line)


def _serial(c, adb_exe):
    """The device to work with: NX2A_DEVICE wins, then the configured serial, then the only device
    that is connected (a phone over the network changes its name: adb-1a2b3c._adb-tls-connect._tcp
    becomes its short serial, and the configured one is gone)."""
    want = os.environ.get('NX2A_DEVICE') or c.get('serial') or ''
    if want and _connected(adb_exe, want): return want
    if os.environ.get('NX2A_DEVICE'): return want        # the caller named a device: keep it, errors and all
    r = subprocess.run([adb_exe, 'devices'], capture_output=True, text=True)
    have = [line.split('\t')[0] for line in r.stdout.splitlines() if '\tdevice' in line]
    if len(have) == 1: return have[0]
    return want


_c = _conf()
ADB_EXE = str(find_adb() or 'adb')
SERIAL = _serial(_c, ADB_EXE)
ADB = [ADB_EXE] + (['-s', SERIAL] if SERIAL else [])
PKG = _package(_c)
FILES = f'/sdcard/Android/data/{PKG}/files'   # the app's files folder: log.txt, env.txt, ctl.txt, the data pack

if __name__ == '__main__':
    if sys.argv[1:] == ['--sh']:
        q = lambda s: "'" + s.replace("'", "'\\''") + "'"
        adb = ADB_EXE.replace('\\', '/')
        print(f'ADB={q(adb + (" -s " + SERIAL if SERIAL else ""))}; SERIAL={q(SERIAL)}; PKG={q(PKG)}; F={q(FILES)}')
    else:
        print(f'adb {ADB_EXE}\nserial {SERIAL or "(the only connected device)"}\npackage {PKG}\nfiles {FILES}')
