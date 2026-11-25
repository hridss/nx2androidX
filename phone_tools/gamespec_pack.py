#!/usr/bin/env python3
"""把一局的攻关档案导成一个交给上游的 zip。

    python3 $NX2A_REPO/tools/gamespec_pack.py --game <title ID>            # 打那局的档案
    python3 $NX2A_REPO/tools/gamespec_pack.py --game <title ID> --out <zip>

包里是那局的攻关文档（spec.md）、改过的文件（files/，按它们在工具树里的相对路径）与清单（fix.md）。
改动前的原件（orig/，同样的相对路径）在包里时，还多一份 diff.patch：那是从原件到改后文件的
unified diff，上游 `git apply` 直接就能打。导出的是快照：档案本身不动，导出完了还能继续往里记。

一条红线守着这个包：游戏文件与密钥不进包。清单里写了游戏文件、密钥或者工具树之外的路径，
这个包就不打——并说清是哪一项被拒。
"""
import argparse
import difflib
import os
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from nx2a_common import add_json, emit, fail, home, size_text  # noqa: E402

SPEC = 'spec.md'
MANIFEST = 'fix.md'
FILES = 'files'
# 改动前的原件：diff 从这里来（没有原件就只带整份文件，上游自己比）
ORIG = 'orig'
# 包里那份 diff：原件 → 改后文件
PATCH = 'diff.patch'

# 游戏文件与密钥：这些名字的东西不进包（仓库的红线：游戏文件与 prod.keys 不进包、不进聊天）。
FORBIDDEN_NAMES = ('prod.keys', '.keys')
FORBIDDEN_SUFFIXES = ('.nsp', '.nsz', '.xci', '.xcz', '.nca', '.nso')


def gamespec_dir(game):
    """那局的档案文件夹：玩家 nx2android 文件夹下的 gamespec/<title ID>/。"""
    return home() / 'gamespec' / game


def refuse_reason(path):
    """这一项为什么不能进包；None 表示它没问题。"""
    if not path or path.strip() == '':
        return '清单里有一行是空的'
    if path.startswith('/') or (len(path) > 1 and path[1] == ':'):
        return f'{path}：不是相对路径'
    parts = Path(path).parts
    if '..' in parts:
        return f'{path}：路径里往上走了'
    name = parts[-1] if parts else path
    lower = name.lower()
    for bad in FORBIDDEN_NAMES:
        if bad in lower:
            return f'{path}：密钥不进包'
    for bad in FORBIDDEN_SUFFIXES:
        if lower.endswith(bad):
            return f'{path}：游戏文件不进包'
    return None


def read_manifest(manifest):
    """清单里的那些相对路径（空行与注释不算）。"""
    if not manifest.is_file():
        fail(f'这局没有 {MANIFEST}：没有改过文件就没有清单')
    out = []
    for line in manifest.read_text(encoding='utf-8').splitlines():
        text = line.strip()
        if not text or text.startswith('#'):
            continue
        out.append(text)
    return out


def collect(case, paths):
    """包里要收的文件（相对路径 -> 档案里的那份），清单与 files/ 对不上的当场拒。"""
    files = {}
    for path in paths:
        bad = refuse_reason(path)
        if bad:
            fail(bad)
        source = case / FILES / path
        if not source.is_file():
            fail(f'清单里列了 {path}，但 files/ 里没有它')
        files[path] = source
    return files


def collect_originals(case, paths):
    """改动前的原件（相对路径 -> 档案里的那份）：有原件的才有 diff。"""
    out = {}
    for path in paths:
        source = case / ORIG / path
        if source.is_file():
            out[path] = source
    return out


def diff_text(path, before, after):
    """原件 → 改后文件的 unified diff（a/ b/ 前缀，上游 git apply 直接能用）。"""
    old = before.read_text(encoding='utf-8', errors='replace').splitlines(keepends=True)
    new = after.read_text(encoding='utf-8', errors='replace').splitlines(keepends=True)
    return ''.join(difflib.unified_diff(old, new, fromfile=f'a/{path}', tofile=f'b/{path}'))


def patch_text(originals, files):
    """包里那份 patch 的正文：每个有原件的文件一段；改过头的才有内容。"""
    chunks = []
    diffed = []
    for path in sorted(originals):
        chunk = diff_text(path, originals[path], files[path])
        if chunk:
            chunks.append(chunk)
            diffed.append(path)
    return ''.join(chunks), diffed


def pack(case, out, paths, files, originals, patch):
    """打成 zip：攻关文档、清单、改过的文件（保持它们在工具树里的相对路径），有原件的还带 diff。"""
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name in (SPEC, MANIFEST):
            source = case / name
            if source.is_file():
                zf.write(source, name)
        for path, source in sorted(files.items()):
            zf.write(source, f'{FILES}/{path}')
        # 原件也进包：diff 是从它来的，上游想自己看原始文件时也在手边
        for path, source in sorted(originals.items()):
            zf.write(source, f'{ORIG}/{path}')
        if patch:
            zf.writestr(PATCH, patch)
    return out


def main():
    parser = argparse.ArgumentParser(description="把一局的攻关档案导成交上游的 zip")
    parser.add_argument('--game', required=True, help='那局的 title ID')
    parser.add_argument('--out', default='', help='zip 放在哪儿（默认：档案旁边）')
    add_json(parser)
    args = parser.parse_args()

    case = gamespec_dir(args.game)
    if not case.is_dir():
        fail(f'这局没有攻关档案：{case}')
    spec = case / SPEC
    if not spec.is_file():
        fail(f'这局的档案里没有 {SPEC}：攻关还没写文档')

    paths = read_manifest(case / MANIFEST)
    files = collect(case, paths)
    originals = collect_originals(case, paths)
    patch, diffed = patch_text(originals, files)
    out = Path(args.out) if args.out else case.parent / f'{args.game}-gamespec.zip'
    pack(case, out, paths, files, originals, patch)

    payload = {
        'game': args.game,
        'zip': str(out),
        'bytes': out.stat().st_size,
        'files': sorted(files),
        'originals': sorted(originals),
        'diffed': diffed,
    }
    text = '\n'.join([
        f'packed {out} ({size_text(payload["bytes"])})',
        f'  {SPEC}: the case file',
        f'  {MANIFEST}: {len(paths)} file(s) listed',
        f'  {PATCH}: {len(diffed)} file(s) diffed' if diffed else '  no originals: whole files only',
    ] + [f'  {path}' for path in payload['files']])
    emit(args, payload, text)


if __name__ == '__main__':
    main()
