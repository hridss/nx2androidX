#!/usr/bin/env python3
"""Look at what the game is drawing: catch a frame, turn it into a PNG, and say what is in it.

The Vulkan backend writes the frames it presents as PPM files (DM_VK_SHOTS=N). This turns those into
something to look at and into numbers to argue about: is the picture black, is it moving, what is
in that corner.

    python3 $NX2A_REPO/tools/shot.py list                       # the frames that are there
    python3 $NX2A_REPO/tools/shot.py stats frame_000738.ppm     # how dark is it, really
    python3 $NX2A_REPO/tools/shot.py png frame_000738.ppm        # a PNG beside it
    python3 $NX2A_REPO/tools/shot.py diff frame_000738.ppm frame_000745.ppm
    python3 $NX2A_REPO/tools/shot.py arm --count 4               # ask the running game for frames

The statistics are the point: "the screen is black" becomes "every pixel is 0".
"""
import argparse
import os
import re
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import emit, fail, table  # noqa: E402

# Where the backend puts the frames: DM_VK_SHOT_DIR, else the run's own folder (DM_ROOT).
SHOT_DIR_ENV = 'DM_VK_SHOT_DIR'
FRAME = re.compile(r'^frame[_-]\d+\.ppm$', re.I)


def shot_dir(explicit):
    """The folder the frames are in."""
    if explicit: return Path(explicit)
    named = os.environ.get(SHOT_DIR_ENV)
    if named: return Path(named)
    root = os.environ.get('DM_ROOT')
    if root: return Path(root)
    home = os.environ.get('HOME')
    if home: return Path(home).parent
    return Path.cwd()


def read_ppm(path):
    """A PPM (P6) as (width, height, pixels)."""
    try:
        data = Path(path).read_bytes()
    except OSError as exc:
        fail(f'cannot read {path}: {exc}')
    if not data.startswith(b'P6'):
        fail(f'{path} is not a PPM (P6) file')
    fields, at = [], 2
    while len(fields) < 3:
        while at < len(data) and data[at:at + 1].isspace(): at += 1
        if data[at:at + 1] == b'#':
            while at < len(data) and data[at:at + 1] not in (b'\n', b'\r'): at += 1
            continue
        start = at
        while at < len(data) and not data[at:at + 1].isspace(): at += 1
        try:
            fields.append(int(data[start:at]))
        except ValueError:
            fail(f'{path} has a broken PPM header')
    at += 1   # the single whitespace after the header
    width, height, maximum = fields
    if maximum != 255:
        fail(f'{path} has a {maximum}-bit PPM; only 8 bits (255) is read')
    pixels = data[at:at + width * height * 3]
    if len(pixels) < width * height * 3:
        fail(f'{path} is cut short: {len(pixels)} of {width * height * 3} bytes')
    return width, height, pixels


def write_png(path, width, height, pixels):
    """A PNG written with the standard library alone (the frames are 8-bit RGB)."""
    rows = b''.join(b'\0' + pixels[y * width * 3:(y + 1) * width * 3] for y in range(height))

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data +
                struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n' +
           chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) +
           chunk(b'IDAT', zlib.compress(rows, 6)) +
           chunk(b'IEND', b''))
    Path(path).write_bytes(png)


def channels(pixels):
    """The three channels of the pixels, as bytes."""
    return pixels[0::3], pixels[1::3], pixels[2::3]


def statistics(pixels):
    """(min, max, mean) per channel and the share of black and of white pixels."""
    red, green, blue = channels(pixels)
    total = len(red)
    if not total: return None
    black = sum(1 for i in range(total) if red[i] == green[i] == blue[i] == 0)
    white = sum(1 for i in range(total) if red[i] == green[i] == blue[i] == 255)
    return {
        'min': [min(red), min(green), min(blue)],
        'max': [max(red), max(green), max(blue)],
        'mean': [round(sum(red) / total, 2), round(sum(green) / total, 2), round(sum(blue) / total, 2)],
        'black_share': round(black / total, 4),
        'white_share': round(white / total, 4),
        'pixels': total,
    }


def region_mean(pixels, width, height, box):
    """The mean of a region: (x, y, w, h)."""
    x, y, w, h = box
    x, y = max(0, x), max(0, y)
    w, h = min(w, width - x), min(h, height - y)
    if w <= 0 or h <= 0: return None
    total = [0, 0, 0]
    count = 0
    for row in range(y, y + h):
        base = row * width * 3
        for column in range(x, x + w):
            at = base + column * 3
            total[0] += pixels[at]; total[1] += pixels[at + 1]; total[2] += pixels[at + 2]
            count += 1
    return [round(value / count, 2) for value in total]


def difference(a, b):
    """How much two frames differ: the mean absolute difference and the share of pixels that changed."""
    if len(a) != len(b):
        return {'comparable': False, 'reason': 'the frames have different sizes'}
    total, changed = 0, 0
    count = len(a) // 3
    for i in range(count):
        at = i * 3
        delta = abs(a[at] - b[at]) + abs(a[at + 1] - b[at + 1]) + abs(a[at + 2] - b[at + 2])
        total += delta
        if delta: changed += 1
    return {'comparable': True, 'pixels': count,
            'mean_abs_difference': round(total / (count * 3), 3),
            'changed_share': round(changed / count, 4)}


def frames(folder):
    """The frames in a folder, oldest first."""
    if not folder.is_dir(): return []
    found = [p for p in folder.iterdir() if FRAME.match(p.name)]
    return sorted(found, key=lambda p: p.name)


def ctl_file(explicit):
    """The control file the running game reads: the app's files/ctl-<title ID>.txt (the host sets
    DM_CTL to it when the game starts). Without a name we take the newest one, which is the game
    that ran last."""
    if explicit: return Path(explicit)
    home = os.environ.get('HOME')
    folder = Path(home).parent if home else Path.cwd()
    found = sorted(folder.glob('ctl-*.txt'), key=lambda p: p.stat().st_mtime, reverse=True)
    return found[0] if found else folder / 'ctl.txt'


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('command', choices=('list', 'stats', 'png', 'diff', 'arm'))
    parser.add_argument('files', nargs='*', help='the frames to look at')
    parser.add_argument('--dir', default='', help='the folder the frames are in')
    parser.add_argument('--region', default='', metavar='X,Y,W,H', help='a region\'s mean (stats)')
    parser.add_argument('--count', type=int, default=1, help='how many frames to ask for (arm)')
    parser.add_argument('--ctl', default='', help="the control file to write to (arm; by default the game's own ctl-<title ID>.txt)")
    parser.add_argument('--json', action='store_true', help='print the result as JSON')
    args = parser.parse_args()

    folder = shot_dir(args.dir)

    if args.command == 'list':
        found = frames(folder)
        text = f'{folder}\n\n' + table([(p.name, p.stat().st_size) for p in found], ('frame', 'bytes'),
                                       empty='no frame there')
        emit(args, {'dir': str(folder), 'frames': [p.name for p in found]}, text)
        return 0

    if args.command == 'arm':
        ctl = ctl_file(args.ctl)
        line = f'env DM_VK_SHOTS={args.count}\n'
        try:
            ctl.parent.mkdir(parents=True, exist_ok=True)
            with open(ctl, 'a') as f:
                f.write(line)
        except OSError as exc:
            fail(f'cannot write {ctl}: {exc}')
        text = (f'asked for {args.count} frame(s) through {ctl}\n'
                'the frames land in DM_VK_SHOT_DIR; a release build ignores the ctl file')
        emit(args, {'ctl': str(ctl), 'count': args.count}, text)
        return 0

    if not args.files:
        fail('give the frame(s) to look at')

    if args.command == 'diff':
        if len(args.files) != 2:
            fail('diff takes two frames')
        first = read_ppm(args.files[0])
        second = read_ppm(args.files[1])
        if first[0] != second[0] or first[1] != second[1]:
            fail('the frames have different sizes')
        result = difference(first[2], second[2])
        text = (f'{args.files[0]} vs {args.files[1]}\n\n'
                f"mean absolute difference: {result['mean_abs_difference']} per channel\n"
                f"pixels that changed: {result['changed_share'] * 100:.2f}%")
        emit(args, {'a': args.files[0], 'b': args.files[1], **result}, text)
        return 0

    rows, results = [], []
    for name in args.files:
        width, height, pixels = read_ppm(name)
        if args.command == 'png':
            target = str(name)[:-4] + '.png'
            write_png(target, width, height, pixels)
            rows.append((name, f'{width}x{height}', target))
            results.append({'frame': name, 'png': target, 'width': width, 'height': height})
        else:
            stats = statistics(pixels)
            if stats is None:
                fail(f'{name} has no pixels')
            row = [name, f'{width}x{height}',
                   f"{stats['mean'][0]}/{stats['mean'][1]}/{stats['mean'][2]}",
                   f"{stats['min'][0]}-{stats['max'][0]}", f"{stats['black_share'] * 100:.1f}%",
                   f"{stats['white_share'] * 100:.1f}%"]
            if args.region:
                x, y, w, h = (int(v) for v in args.region.split(','))
                mean = region_mean(pixels, width, height, (x, y, w, h))
                row.append(str(mean))
            rows.append(row)
            results.append({'frame': name, 'width': width, 'height': height, **stats})

    if args.command == 'png':
        text = table(rows, ('frame', 'size', 'png'))
    else:
        headers = ('frame', 'size', 'mean r/g/b', 'min-max', 'black', 'white')
        if args.region: headers = headers + ('region mean',)
        text = table(rows, headers)
    emit(args, {'frames': results}, text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
