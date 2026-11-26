"""The export of a port as a standalone game APK: the manifest it reads, the version number it gives the APK,
the driver's row in env.txt, and the size limits the App holds it to."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from porter import export_apk


def manifest(**over):
    man = {'title_id': '0100BB8017F24000', 'name': 'Teenage Mutant Ninja Turtles', 'package': 'org.nx2a.t0100bb8017f24000',
           'version': 'v589824', 'display': '1.0.9', 'icon': 'icon.png'}
    man.update(over)
    return man


class ExportManifestTest(unittest.TestCase):
    def test_the_manifest_is_read(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'nx2a-data.json').write_text(json.dumps(manifest()), encoding='utf-8')
            man = export_apk.manifest(data)
            self.assertEqual(man['package'], 'org.nx2a.t0100bb8017f24000')

    def test_a_folder_without_a_manifest_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(Exception):
                export_apk.manifest(Path(directory))

    def test_a_manifest_without_a_package_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'nx2a-data.json').write_text(json.dumps(manifest(package='')), encoding='utf-8')
            with self.assertRaises(Exception):
                export_apk.manifest(data)

    def test_the_title_id_is_read_as_the_profiles_write_it(self):
        self.assertEqual(export_apk.title_id_of(manifest(title_id='0100bb8017f24000')), '0100BB8017F24000')
        for bad in ('', '0100BB8017F2400', '0100BB8017F24000X', 'ZZZZZZZZZZZZZZZZ'):
            with self.assertRaises(Exception):
                export_apk.title_id_of(manifest(title_id=bad))


class ExportVersionCodeTest(unittest.TestCase):
    def test_the_version_code_follows_the_game_version(self):
        # the pipeline's rule: the Switch version number plus one (the port's own build)
        self.assertEqual(export_apk.version_code('v589824'), 589825)

    def test_the_offset_lifts_it_above_an_earlier_export(self):
        self.assertEqual(export_apk.version_code('v589824', 3), 589828)
        self.assertGreater(export_apk.version_code('v589824', 1), export_apk.version_code('v589824', 0))

    def test_an_offset_out_of_range_is_refused(self):
        from porter.pipeline import VERCODE_OFFSET_MAX
        with self.assertRaises(Exception):
            export_apk.version_code('v589824', -1)
        with self.assertRaises(Exception):
            export_apk.version_code('v589824', VERCODE_OFFSET_MAX + 1)


class ExportDriverRowTest(unittest.TestCase):
    def test_the_row_names_the_library_the_apk_carries(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'env.txt').write_text('DM_LANG=zh-Hans\nDM_VK_DRIVER=/data/user/0/app/files/drivers/Turnip/libvulkan_freedreno.so\n',
                                          encoding='utf-8')
            self.assertTrue(export_apk.set_driver(data, Path('/somewhere/libvulkan_freedreno.so')))
            text = (data / 'env.txt').read_text(encoding='utf-8')
            self.assertIn('DM_VK_DRIVER=libvulkan_freedreno.so', text)
            self.assertNotIn('/data/user/0/app', text)   # the old path is gone

    def test_without_a_driver_the_row_goes(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            (data / 'env.txt').write_text('DM_LANG=zh-Hans\nDM_VK_DRIVER=libvulkan_freedreno.so\n', encoding='utf-8')
            self.assertTrue(export_apk.set_driver(data, None))
            text = (data / 'env.txt').read_text(encoding='utf-8')
            self.assertNotIn('DM_VK_DRIVER', text)
            self.assertIn('DM_LANG=zh-Hans', text)        # the other rows stay

    def test_a_folder_that_cannot_be_written_says_so_instead_of_failing(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory) / 'missing'
            self.assertFalse(export_apk.set_driver(data, None))


class ExportSizeTest(unittest.TestCase):
    def test_the_apk_stays_within_the_limits_the_app_holds_it_to(self):
        # the limits the App tells the player about: 25 MB without a driver, 35 MB with one
        self.assertEqual(25 * 1024 * 1024, 26214400)
        self.assertEqual(35 * 1024 * 1024, 36700160)


if __name__ == '__main__':
    unittest.main()
