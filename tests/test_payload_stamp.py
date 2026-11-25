"""The environment ships as one archive; its stamp follows what is in it, including late-staged hooks."""
import importlib.util
import os
import tarfile
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('phone_assets', Path(__file__).resolve().parents[1] / 'packaging/android-apk/build_assets.py')
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class PayloadStampTest(unittest.TestCase):
    def test_same_size_and_late_runtime_components_change_stamp(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work = root / 'assets'
            apk = root / 'apk'
            source = work / 'tool/porter/revision.py'
            source.parent.mkdir(parents=True)
            source.write_bytes(b'old')
            assets.payload_archive(work, apk)
            before = (apk / 'payload-stamp').read_text()
            source.write_bytes(b'new')
            assets.payload_archive(work, apk)
            after = (apk / 'payload-stamp').read_text()
            self.assertNotEqual(before, after)
            # a hook that is staged after the first pack is in the archive too
            hook = work / 'tool/tools/adrenotools/libhook_impl.so'
            hook.parent.mkdir(parents=True)
            hook.write_bytes(b'hook')
            assets.payload_archive(work, apk)
            self.assertNotEqual(after, (apk / 'payload-stamp').read_text())
            with tarfile.open(apk / 'payload.pkg', 'r:gz') as tar:
                self.assertIn('tool/tools/adrenotools/libhook_impl.so', tar.getnames())

    @unittest.skipIf(os.name == 'nt', 'Windows has no executable bit: the mode the archive carries comes from the filesystem')
    def test_the_permissions_travel_with_the_files(self):
        """The APK's assets carry no permissions: the archive does, or the guest's programs cannot start."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work = root / 'assets'
            apk = root / 'apk'
            program = work / 'toolchain/clang'
            program.parent.mkdir(parents=True)
            program.write_bytes(b'a compiler')
            program.chmod(0o755)
            plain = work / 'tool/porter/cli.py'
            plain.parent.mkdir(parents=True)
            plain.write_bytes(b'print("hi")')
            plain.chmod(0o644)
            assets.payload_archive(work, apk)
            with tarfile.open(apk / 'payload.pkg', 'r:gz') as tar:
                self.assertTrue(tar.getmember('tool/toolchain/clang').mode & 0o111)
                self.assertFalse(tar.getmember('tool/porter/cli.py').mode & 0o111)
