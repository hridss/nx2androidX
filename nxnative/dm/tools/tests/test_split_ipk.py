"""shader_split: UbiArt IPK archives (Rayman Legends: Definitive Edition's bundle_nn.ipk). The .fxb effect files are read, zlib or stored, and nothing else; shaders_dev/ is left
out; a damaged archive is named and left out, not fatal. Synthetic bytes only."""
import contextlib
import io
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / 'dm/gfx/tools'))
import shader_split as ss  # noqa: E402

GLSLC = b'\x91\x68\x86\x19'   # the GLSLC output's magic
CTL, GFX = ss.CTL_MAGIC, ss.GFX_MAGIC


def stage(sph_type):
    """(control section, code area) of one stage: the control's +0x20 holds its length, +0x704 the code area's; the code area is a 0x30 header, the SPH (word 0: stage in bits 10..13)"""
    code = bytearray(GFX + bytes(0x2c)); code += struct.pack('<I', sph_type) + bytes(0x4c); code += bytes.fromhex('0f00000000000000') * 8
    ctl = bytearray(0x880); ctl[:4] = CTL; struct.pack_into('<I', ctl, 0x20, 0x880); struct.pack_into('<I', ctl, 0x704, len(code))
    return bytes(ctl), bytes(code)


VS, PS = stage(0x0461), stage(0x1462)


def glslc(stages):
    """A GLSLC output: magic, +0x44 the whole length, +0x4c the section count, section headers at +0x90 + 0x90 i ({size, data offset, type 0 = GPU code}, +0x30 control offset,
    +0x34 code offset, +0x38 code length, +0x3c control length); each section's data is its control section with the code area right after it"""
    d = bytearray(0x90 * (len(stages) + 1)); d[:4] = GLSLC; struct.pack_into('<I', d, 0x4c, len(stages))
    for i, (ctl, code) in enumerate(stages):
        at = len(d); d += ctl + code
        h = 0x90 + 0x90 * i; struct.pack_into('<III', d, h, len(ctl) + len(code), at, 0); struct.pack_into('<4I', d, h + 0x30, 0, len(ctl), len(code), len(ctl))
    struct.pack_into('<I', d, 0x44, len(d))
    return bytes(d)


PROGRAM = glslc([VS, PS])


def ipk(entries, version=7):
    """A version 7 IPK: the 0x38-byte header (magic, version, platform, base offset, file count), then per entry u32 offset count, size, compressed size (0: stored),
    u64 time, u64 offset from the base, u32 + directory, u32 + name, u32 CRC, u32 0; the data from the base. entries: (directory, name, data, compress)"""
    table, data = b'', b''
    for d, n, body, comp in entries:
        packed = zlib.compress(body) if comp else body
        table += struct.pack('>IIIQQ', 1, len(body), len(packed) if comp else 0, 0, len(data))
        table += struct.pack('>I', len(d)) + d.encode() + struct.pack('>I', len(n)) + n.encode() + struct.pack('>II', 0, 0)
        data += packed
    base = 0x38 + len(table)
    head = ss.UBIART_IPK + struct.pack('>IIII', version, 0x10, base, len(entries))
    return head + bytes(0x38 - len(head)) + table + data


FXB = 'enginedata/shaders/compiled/nx/'


class Ipk(unittest.TestCase):
    def scan(self, data):
        with tempfile.TemporaryDirectory() as t, contextlib.redirect_stdout(io.StringIO()) as log:
            path = Path(t) / 'bundle_nn.ipk'; path.write_bytes(data)
            result = ss.scan_file((str(path), 'bundle_nn.ipk'))
        return result, log.getvalue()

    def test_fxb_entries_are_read(self):
        for comp in (True, False):
            with self.subTest(compressed=comp):
                (rel, keys, pairs, extra), log = self.scan(ipk([(FXB, 'renderpct.fx.fxb', PROGRAM, comp)]))
                self.assertEqual(log, ''); self.assertEqual(rel, 'bundle_nn.ipk')
                self.assertEqual(sorted(v[1] for v in keys.values()), ['ps', 'vs'])
                self.assertEqual({v[0] for v in keys.values()}, {'/bundle_nn.ipk/' + FXB + 'renderpct.fx.fxb#GLSLC@0x0#var'}); self.assertEqual(len(pairs), 1)

    def test_other_entries_are_left_out(self):
        (_, keys, pairs, _), log = self.scan(ipk([('enginedata/shaders_dev/compiled/nx/', 'renderpct.fx.fxb', PROGRAM, True), ('world/', 'level.isc.ckd', PROGRAM, True),
                                                 (FXB, 'font.fx.fxb', b'no shader here', True)]))
        self.assertEqual((keys, pairs, log), ({}, set(), ''))

    def test_damage_is_named(self):
        good = ipk([(FXB, 'a.fxb', PROGRAM, True)])
        bad_stream = bytearray(good); bad_stream[-len(zlib.compress(PROGRAM)):] = bytes(len(zlib.compress(PROGRAM)))
        cut_table = bytearray(good); struct.pack_into('>I', cut_table, 0x10, 2)   # two entries announced, one in the table
        for label, data, why in (('version', ipk([(FXB, 'a.fxb', PROGRAM, True)], version=6), 'UbiArt IPK version 6'), ('zlib', bytes(bad_stream), 'invalid zlib stream'),
                                 ('table', bytes(cut_table), 'runs past the IPK table'), ('data', good[:-8], 'runs past the end of the archive'),
                                 ('header', good[:0x20], 'shorter than a UbiArt IPK header')):
            with self.subTest(damage=label):
                (_, keys, pairs, extra), log = self.scan(data)
                self.assertEqual((keys, pairs), ({}, set()))
                self.assertEqual(extra, {})
                self.assertTrue(log.startswith('[RED] /bundle_nn.ipk'), log); self.assertIn(why, log)


if __name__ == '__main__': unittest.main()