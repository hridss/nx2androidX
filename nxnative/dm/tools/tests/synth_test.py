"""Builds the synthetic module of synth.S into a minimal NSO and drives patch_nso.py over it.
Runs the working tree's rewriter and (with --old) the committed one; the new recognizers must
classify funcB's br x18 as a tail call, where the old one stops at `jump table`. Test only."""
import struct, subprocess, sys, os, tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEXT_SZ, RO_SZ, DATA_SZ = 0x400, 0x100, 0x100


def build_nso(with_funcb=True):
    asm = subprocess.run(['aarch64-linux-gnu-as', '-o', '/tmp/synth.o', str(HERE / 'synth.S')], capture_output=True, text=True)
    assert asm.returncode == 0, asm.stderr
    ln = subprocess.run(['aarch64-linux-gnu-ld', '-Ttext=0', '-e', 'funcA', '-o', '/tmp/synth.elf', '/tmp/synth.o'], capture_output=True, text=True)
    assert ln.returncode == 0, ln.stderr
    obj = subprocess.run(['aarch64-linux-gnu-objcopy', '-O', 'binary', '-j', '.text', '/tmp/synth.elf', '/tmp/synth.bin'], capture_output=True, text=True)
    assert obj.returncode == 0, obj.stderr
    text = bytearray(open('/tmp/synth.bin', 'rb').read())
    text += bytes(TEXT_SZ - len(text))
    assert len(text) <= TEXT_SZ, hex(len(text))
    # the MOD0 offset field lives at image offset 4 (the header word above); MOD0 goes at the start of data
    m0 = TEXT_SZ + RO_SZ
    struct.pack_into('<I', text, 4, m0)
    img = bytearray(text)
    img += bytes(RO_SZ)
    # MOD0: magic, ..., +16: the eh_frame_hdr's offset from MOD0
    img += b'MOD0' + bytes(12) + struct.pack('<i', 0x14) + bytes(DATA_SZ - 0x20)
    ehs = m0 + 0x14
    # eh_frame_hdr: version, penc udata4, cenc udata4, tenc datarel|sdata4; the pointer; the count; (start, size) pairs
    syms = find_syms()
    fa, fb, fb_end = syms['funcA'], syms['funcB'], syms['tbl']   # the third FDE bounds funcB: the parser reads FDE starts only
    img[ehs:ehs + 4] = bytes([0, 0x03, 0x03, 0x3B])
    img[ehs + 4:ehs + 8] = struct.pack('<I', 0)          # the eh_frame pointer, unused
    img[ehs + 8:ehs + 12] = struct.pack('<I', 3 if with_funcb else 1)   # FDE starts
    img[ehs + 12:ehs + 20] = struct.pack('<ii', fa - ehs, 0x30 - ehs)
    if with_funcb:
        img[ehs + 20:ehs + 28] = struct.pack('<ii', fb - ehs, 0x30 - ehs)
        img[ehs + 28:ehs + 36] = struct.pack('<ii', fb_end - ehs, 0x60 - ehs)
    else:   # without funcB the third FDE bounds funcA (its next start is tbl)
        img[ehs + 20:ehs + 28] = struct.pack('<ii', fb_end - ehs, 0x60 - ehs)
    # NSO container: header, flags 0 (no compression), three segments (file offset, memory offset, size), bss, build id
    nso = bytearray(0x100)
    nso[0:4] = b'NSO0'
    struct.pack_into('<I', nso, 0xc, 0)
    segs = [(0x100, 0, TEXT_SZ), (0x100 + TEXT_SZ, TEXT_SZ, RO_SZ), (0x100 + TEXT_SZ + RO_SZ, TEXT_SZ + RO_SZ, DATA_SZ)]
    for i, (fo, mo, sz) in enumerate(segs):
        struct.pack_into('<III', nso, 0x10 + i * 0x10, fo, mo, sz)
    struct.pack_into('<III', nso, 0x60, TEXT_SZ, RO_SZ, DATA_SZ)   # compressed sizes, unused without the flags
    struct.pack_into('<I', nso, 0x3c, 0)                            # bss
    nso[0x40:0x50] = bytes(range(16))                               # the build ID
    nso[0x100:0x100 + TEXT_SZ + RO_SZ + DATA_SZ] = img
    return nso, fa, fb


def find_syms():
    nm = subprocess.run(['aarch64-linux-gnu-nm', '/tmp/synth.elf'], capture_output=True, text=True).stdout
    return {name: int(addr, 16) for addr, _, name in (l.split() for l in nm.splitlines() if len(l.split()) == 3)}


def run(pnso, src, outdir):
    return subprocess.run([sys.executable, str(pnso), str(src), str(outdir), 'main'], capture_output=True, text=True)


def main():
    nso, fa, fb = build_nso()
    syms = find_syms()
    fa, fb = syms['funcA'], syms['funcB']
    src = Path('/tmp/synth.nso'); src.write_bytes(nso)
    old = HERE.parent / 'old_pnso.py'   # the committed rewriter, written beside patch_nso.py by the caller (it loads sdk_patches.py from its own folder)
    new = HERE.parent / 'patch_nso.py'
    # Byte-identical regression on the form both rewriters know (funcA alone)
    src_a = Path('/tmp/synth_a.nso'); src_a.write_bytes(build_nso(with_funcb=False)[0])
    with tempfile.TemporaryDirectory() as oa, tempfile.TemporaryDirectory() as ob:
        assert run(old, src_a, oa).returncode == 0 and run(new, src_a, ob).returncode == 0, 'funcA alone must rewrite under both'
        ha = __import__('hashlib').sha256((Path(oa) / 'main.patched.bin').read_bytes()).hexdigest()
        hb = __import__('hashlib').sha256((Path(ob) / 'main.patched.bin').read_bytes()).hexdigest()
        assert ha == hb, f'funcA output differs between the old and the new rewriter ({ha} vs {hb})'
        print('funcA output byte-identical between the old and the new rewriter')
    with tempfile.TemporaryDirectory() as od1, tempfile.TemporaryDirectory() as od2:
        r_old = run(old, src, od1)
        r_new = run(new, src, od2)
        print('old rewriter:', 'assert-failed as expected' if r_old.returncode != 0 else 'SUCCEEDED (unexpected)',
              '|', [l for l in r_old.stderr.splitlines() if 'jump table' in l or 'AssertionError' in l][:1])
        print('new rewriter:', 'succeeded' if r_new.returncode == 0 else 'FAILED', r_new.stderr.strip().splitlines()[-1] if r_new.returncode else '')
        assert r_old.returncode != 0 and 'jump table' in r_old.stderr, 'the old rewriter should stop at funcB'
        assert r_new.returncode == 0, 'the new rewriter should rewrite funcB'
        r_new_image = (Path(od2) / 'main.patched.bin').exists()
        assert r_new_image, 'the new rewriter wrote the patched image'
        print('new rewriter:', 'succeeded, image written')
    print('synth test: PASS')


if __name__ == '__main__':
    main()
