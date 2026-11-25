# The READMEs' games gallery and counts, and docs/PORT_CHECKLIST.md's marks and count, are made from the profiles (docs/gen_tables.py): a pull request that adds a game
# edits its android.tsv and runs the script; this test fails when the files are not what the profiles make.
import re, subprocess, sys, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
READMES = ('README.md', 'README.zh.md')


class DocsTables(unittest.TestCase):
    def test_tables_match_the_profiles(self):
        r = subprocess.run([sys.executable, str(REPO / 'docs/gen_tables.py'), '--check'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_every_readme_image_exists(self):
        sys.path.insert(0, str(REPO / 'nxnative/dm/android/tools')); import android_profile
        for g, p in android_profile.all_profiles():
            if not p or not p.get('readme_image'): continue
            img = p.get('readme_image')
            self.assertTrue((REPO / img).is_file(), img)

    def test_the_counts_are_the_profiles(self):
        sys.path.insert(0, str(REPO / 'docs')); import gen_tables
        checklist = (REPO / 'docs/PORT_CHECKLIST.md').read_text(encoding='utf-8')
        m = re.search(r'\*\*(\d+) / (\d+) ported\*\*', checklist)
        self.assertIsNotNone(m, 'the count is not in the checklist')
        self.assertEqual(gen_tables.counts()['ported'], f'{m.group(1)} / {m.group(2)}')

    def test_the_count_slots_hold_the_counts(self):
        sys.path.insert(0, str(REPO / 'docs')); import gen_tables
        values = gen_tables.counts()
        for name in READMES:
            text = (REPO / name).read_text(encoding='utf-8')
            self.assertEqual(gen_tables.readme_gaps(text, values), [], name)
            for slot, value in values.items():
                m = re.search(rf'<!-- count:{slot} -->(.*?)<!-- /count -->', text, re.S)
                self.assertIsNotNone(m, f'{name}: the count:{slot} slot')
                self.assertEqual(m.group(1), value, f'{name}: the count:{slot} slot')

    def test_a_page_without_the_slots_is_a_gap(self):
        sys.path.insert(0, str(REPO / 'docs')); import gen_tables
        gaps = gen_tables.readme_gaps('a page with nothing in it', gen_tables.counts())
        self.assertIn('the games table markers', gaps)
        self.assertIn('the count:ported slot', gaps)


if __name__ == '__main__':
    unittest.main()
