"""The phone's tools: what each one says about a game, a log, a build, a card and a frame.

The tools are scripts in phone_tools/ that the assistant runs on the phone; here they are run the
same way (as a program, with its arguments), against folders built for the test.
"""
import importlib.util
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / 'phone_tools'
sys.path.insert(0, str(TOOLS))


def run(tool, *args, env=None):
    """A tool, run as the assistant runs it."""
    environment = dict(os.environ, PYTHONUTF8='1')
    environment.update(env or {})
    return subprocess.run([sys.executable, str(TOOLS / tool), *[str(a) for a in args]],
                          capture_output=True, text=True, encoding='utf-8', env=environment)


class MemLayout(unittest.TestCase):
    PROFILE = """# a profile
name\tTest Game

# ---- Memory ----
mem_reserve\t0x100000000
mem_alias\t0x10000000
mem_stack\t0x10000000
app_pool\t0xCD500000
"""

    def profile(self, text=None, folder=None):
        folder = folder or tempfile.mkdtemp(prefix='nx2a_profile_')
        path = Path(folder) / 'game.tsv'
        path.write_text(text if text is not None else self.PROFILE)
        return path

    def test_the_layout_is_computed(self):
        path = self.profile()
        result = run('mem_layout.py', '--profile', path, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(out['reserve'], 0x100000000)
        self.assertEqual(out['alias'], 0x10000000)
        self.assertEqual(out['stack'], 0x10000000)
        # the heap is what is left of the block after the node area, the alias window and the stack
        self.assertEqual(out['heap'], 0x100000000 - (0x10000000 + 0x10000000 + 0x10000000))

    def test_both_notations_are_shown(self):
        result = run('mem_layout.py', '--profile', self.profile())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('0x10000000', result.stdout)
        self.assertIn('268,435,456', result.stdout)

    def test_a_layout_that_does_not_fit_is_an_error(self):
        text = self.PROFILE.replace('mem_alias\t0x10000000', 'mem_alias\t0xF0000000')
        result = run('mem_layout.py', '--profile', self.profile(text))
        self.assertEqual(result.returncode, 2)
        self.assertIn('does not fit', result.stderr)
        self.assertNotIn('Traceback', result.stderr)

    def test_a_profile_without_the_rows_is_an_error(self):
        result = run('mem_layout.py', '--profile', self.profile('name\tX\n'))
        self.assertEqual(result.returncode, 2)
        self.assertIn('mem_reserve', result.stderr)


class LogTriage(unittest.TestCase):
    def test_the_crash_is_found_in_the_previous_log(self):
        folder = Path(tempfile.mkdtemp(prefix='nx2a_log_'))
        (folder / 'log.txt').write_text('[dm] guest AS 0x7f0000\n[perf] frame 12, 16.6 ms\n')
        (folder / 'log.prev.txt').write_text('[dm] CRASH sig=11\n  backtrace:\n    main+0x1234\n')
        result = run('log_triage.py', '--port', folder, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertIn('log.prev.txt', out['verdict'])
        crashes = out['logs'][1]['crashes']
        self.assertTrue(crashes)
        self.assertEqual(crashes[0]['kind'], 'crash')

    def test_a_run_without_a_crash_says_so(self):
        folder = Path(tempfile.mkdtemp(prefix='nx2a_log_'))
        (folder / 'log.txt').write_text('[dm] guest AS 0x7f0000\n')
        result = run('log_triage.py', '--port', folder)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('no crash signature', result.stdout)

    def test_a_missing_pack_is_an_error(self):
        result = run('log_triage.py', '--port', '/nonexistent/pack')
        self.assertEqual(result.returncode, 2)
        self.assertIn('no data pack', result.stderr)


class Builds(unittest.TestCase):
    def test_the_builds_are_listed(self):
        home = Path(tempfile.mkdtemp(prefix='nx2a_home_'))
        folder = home / 'builds' / 'abc'
        folder.mkdir(parents=True)
        (folder / 'record.json').write_text(json.dumps(
            {'id': 'abc', 'game': 'Game.nsp', 'phase': 'FAILED', 'stage': 'shader split',
             'seconds': 42, 'startedAt': 1732300000000}))
        (folder / 'log.txt').write_text('working\n[RED] the shader table does not match\n')
        result = run('builds.py', '--json', env={'NX2A_HOME': str(home)})
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(len(out['builds']), 1)
        self.assertEqual(out['builds'][0]['game'], 'Game.nsp')

    def test_the_first_error_is_shown(self):
        home = Path(tempfile.mkdtemp(prefix='nx2a_home_'))
        folder = home / 'builds' / 'abc'
        folder.mkdir(parents=True)
        (folder / 'record.json').write_text(json.dumps(
            {'id': 'abc', 'game': 'Game.nsp', 'phase': 'FAILED', 'stage': 'shader split', 'seconds': 42}))
        (folder / 'log.txt').write_text('working\n[RED] the shader table does not match\n')
        result = run('builds.py', '--errors', env={'NX2A_HOME': str(home)})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('the shader table does not match', result.stdout)


class PortStatus(unittest.TestCase):
    def snapshot(self, games):
        home = Path(tempfile.mkdtemp(prefix='nx2a_home_'))
        (home / 'library.json').write_text(json.dumps(
            {'schema': 'nx2a.library.v1', 'generatedAt': 1, 'count': len(games), 'games': games}))
        return home

    def test_every_game_is_listed(self):
        games = [{'name': f'Game {i}', 'titleId': f'{i:016X}', 'status': 'ported', 'pack': ''}
                 for i in range(12)]
        home = self.snapshot(games)
        result = run('port_status.py', '--json', env={'NX2A_HOME': str(home)})
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(out['count'], 12)   # not the first ten

    def test_a_crashed_pack_is_marked(self):
        pack = Path(tempfile.mkdtemp(prefix='nx2a_pack_'))
        (pack / 'log.txt').write_text('[dm] CRASH sig=11\n')
        home = self.snapshot([{'name': 'Game', 'titleId': '0100000000000000', 'status': 'ported',
                               'pack': str(pack)}])
        result = run('port_status.py', '--json', env={'NX2A_HOME': str(home)})
        out = json.loads(result.stdout)
        self.assertEqual(out['games'][0]['run'], 'crashed')

    def test_a_missing_snapshot_says_where_it_should_be(self):
        home = Path(tempfile.mkdtemp(prefix='nx2a_home_'))
        result = run('port_status.py', env={'NX2A_HOME': str(home)})
        self.assertEqual(result.returncode, 2)
        self.assertIn('library.json', result.stderr)


class Shot(unittest.TestCase):
    def frame(self, width=4, height=2, color=(0, 0, 0)):
        pixels = bytes(color) * (width * height)
        header = f'P6\n{width} {height}\n255\n'.encode()
        path = Path(tempfile.mkdtemp(prefix='nx2a_frame_')) / 'frame_000001.ppm'
        path.write_bytes(header + pixels)
        return path

    def test_the_statistics_are_computed(self):
        path = self.frame(color=(10, 20, 30))
        result = run('shot.py', 'stats', path, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(out['frames'][0]['mean'], [10.0, 20.0, 30.0])
        self.assertEqual(out['frames'][0]['black_share'], 0.0)

    def test_a_black_frame_is_black(self):
        path = self.frame()
        result = run('shot.py', 'stats', path, '--json')
        out = json.loads(result.stdout)
        self.assertEqual(out['frames'][0]['black_share'], 1.0)

    def test_a_png_is_written(self):
        path = self.frame()
        result = run('shot.py', 'png', path)
        self.assertEqual(result.returncode, 0, result.stderr)
        png = path.with_suffix('.png')
        self.assertTrue(png.is_file())
        self.assertTrue(png.read_bytes().startswith(b'\x89PNG'))

    def test_two_frames_are_compared(self):
        folder = Path(tempfile.mkdtemp(prefix='nx2a_frames_'))
        first = folder / 'frame_1.ppm'
        second = folder / 'frame_2.ppm'
        first.write_bytes(b'P6\n2 1\n255\n' + bytes((0, 0, 0)) * 2)
        second.write_bytes(b'P6\n2 1\n255\n' + bytes((255, 255, 255)) * 2)
        result = run('shot.py', 'diff', first, second, '--json')
        out = json.loads(result.stdout)
        self.assertEqual(out['changed_share'], 1.0)
        self.assertEqual(out['mean_abs_difference'], 255.0)

    def test_a_file_that_is_not_a_frame_is_an_error(self):
        path = Path(tempfile.mkdtemp(prefix='nx2a_frame_')) / 'x.ppm'
        path.write_bytes(b'not a ppm')
        result = run('shot.py', 'stats', path)
        self.assertEqual(result.returncode, 2)
        self.assertIn('not a PPM', result.stderr)


class Card(unittest.TestCase):
    """A card dump with tickets: the titles are named by the tickets' file names."""

    def dump(self, name, titles):
        sys.path.insert(0, str(ROOT))
        from porter import dumps
        files = [(f'{t:016x}{i:016x}.tik', bytes(16)) for i, t in enumerate(titles)]
        data = (dumps.pfs0_header([n for n, _ in files], [len(d) for _, d in files])
                + b''.join(d for _, d in files))
        path = Path(tempfile.mkdtemp(prefix='nx2a_card_')) / name
        path.write_bytes(data)
        return path

    def test_the_titles_are_listed(self):
        base = 0x0100000000AAA000
        path = self.dump('Merged.nsp', [base, base + 0x800, base + 0x1001])
        result = run('card.py', path, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(len(out['titles']), 3)
        self.assertIn(f'{base:016X}', out['titles'])
        self.assertIn(f'{base + 0x800:016X}', out['titles'])

    def test_a_file_that_is_not_a_dump_is_an_error(self):
        path = Path(tempfile.mkdtemp(prefix='nx2a_card_')) / 'x.nsp'
        path.write_bytes(b'not a dump')
        result = run('card.py', path)
        self.assertEqual(result.returncode, 2)
        self.assertNotIn('Traceback', result.stderr)

    def test_the_dlc_is_told_apart(self):
        """A DLC sits one 0x1000 above the base game's high part - it has to be read as a DLC."""
        base = 0x0100000000AAA000
        path = self.dump('Merged.nsp', [base, base + 0x800, base + 0x1001])
        result = run('card.py', path)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('DLC', result.stdout)
        self.assertIn('update', result.stdout)


class CardSplit(unittest.TestCase):
    """A card cut into one NSP per title: the base game, its update, its DLCs."""

    BASE = 0x0100000000010000
    KEY = bytes(range(32))

    def keys(self):
        """A prod.keys that carries the header key the fixture's NCA headers are encrypted with."""
        path = Path(tempfile.mkdtemp(prefix='nx2a_keys_')) / 'prod.keys'
        path.write_text(f'header_key = {self.KEY.hex()}\n')
        return path

    def sector(self, title, content_type):
        """The NCA header sector: the magic, the content type and the title ID, as the header holds them."""
        plain = bytearray(0x200)
        plain[0:4] = b'NCA3'
        plain[5] = content_type
        struct.pack_into('<Q', plain, 0x10, title)
        return bytes(plain)

    def nca(self, title, content_type):
        """An NCA file: its first sector plain, its second one (the header) encrypted with the header key."""
        from Crypto.Cipher import AES
        data = AES.new(self.KEY[:16], AES.MODE_ECB)
        tweak = AES.new(self.KEY[16:], AES.MODE_ECB)
        plain = self.sector(title, content_type)
        t = int.from_bytes(tweak.encrypt((1).to_bytes(16, 'big')), 'little')
        mask = (1 << 128) - 1
        encrypted = b''
        for i in range(0, len(plain), 16):
            tb = t.to_bytes(16, 'little')
            block = plain[i:i + 16]
            mixed = bytes(a ^ b for a, b in zip(block, tb))
            encrypted += bytes(a ^ b for a, b in zip(data.encrypt(mixed), tb))
            t = (t << 1 & mask) ^ (0x87 if t >> 127 else 0)
        return b'\0' * 0x200 + encrypted

    def card(self):
        """A card dump holding three titles: the base game, its update and a DLC."""
        sys.path.insert(0, str(ROOT))
        from porter import dumps
        titles = [(self.BASE, 'program'), (self.BASE, 'meta'),
                  (self.BASE + 0x800, 'program'), (self.BASE + 0x800, 'meta'),
                  (self.BASE + 0x1001, 'program'), (self.BASE + 0x1001, 'meta')]
        files = [(f'{i:016x}{i:016x}.nca', self.nca(title, 1 if what == 'meta' else 0))
                 for i, (title, what) in enumerate(titles)]
        data = (dumps.pfs0_header([n for n, _ in files], [len(d) for _, d in files])
                + b''.join(d for _, d in files))
        path = Path(tempfile.mkdtemp(prefix='nx2a_split_')) / 'Card.nsp'
        path.write_bytes(data)
        return path

    @unittest.skipUnless(importlib.util.find_spec('Crypto'), 'no pycryptodome')
    def test_every_title_becomes_its_own_nsp(self):
        card = self.card()
        out = Path(tempfile.mkdtemp(prefix='nx2a_split_out_'))
        result = run('card_split.py', card, out, '--keys', self.keys())
        self.assertEqual(result.returncode, 0, result.stderr)
        names = sorted(p.name for p in out.iterdir())
        self.assertEqual(names, [f'{self.BASE:016X}_base.nsp', f'{self.BASE + 0x800:016X}_update.nsp',
                                 f'{self.BASE + 0x1001:016X}_dlc.nsp'])

    @unittest.skipUnless(importlib.util.find_spec('Crypto'), 'no pycryptodome')
    def test_an_nsp_holds_the_files_of_its_title(self):
        sys.path.insert(0, str(ROOT))
        from porter import dumps
        card = self.card()
        out = Path(tempfile.mkdtemp(prefix='nx2a_split_out_'))
        result = run('card_split.py', card, out, '--keys', self.keys())
        self.assertEqual(result.returncode, 0, result.stderr)
        nsp = out / f'{self.BASE:016X}_base.nsp'
        names = [name for name, _, _ in dumps.entries(nsp)]
        self.assertEqual(len(names), 2)          # the base game's program NCA and its content meta NCA
        self.assertTrue(all(name.endswith('.nca') for name in names))

    @unittest.skipUnless(importlib.util.find_spec('Crypto'), 'no pycryptodome')
    def test_the_result_is_json(self):
        card = self.card()
        out = Path(tempfile.mkdtemp(prefix='nx2a_split_out_'))
        result = run('card_split.py', card, out, '--keys', self.keys(), '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out_json = json.loads(result.stdout)
        self.assertEqual(len(out_json['titles']), 3)
        self.assertEqual(out_json['titles'][0]['kind'], 'base')
        self.assertTrue(out_json['titles'][0]['bytes'] > 0)

    @unittest.skipUnless(importlib.util.find_spec('Crypto'), 'no pycryptodome')
    def test_the_nsps_land_next_to_the_card(self):
        card = self.card()
        result = run('card_split.py', card, '--keys', self.keys())
        self.assertEqual(result.returncode, 0, result.stderr)
        out = card.parent / f'Card-{self.BASE:016X}'
        names = sorted(p.name for p in out.iterdir())
        self.assertEqual(names, [f'{self.BASE:016X}_base.nsp', f'{self.BASE + 0x800:016X}_update.nsp',
                                 f'{self.BASE + 0x1001:016X}_dlc.nsp'])

    def test_a_file_that_is_not_a_dump_is_an_error(self):
        path = Path(tempfile.mkdtemp(prefix='nx2a_split_')) / 'x.nsp'
        path.write_bytes(b'not a dump')
        result = run('card_split.py', path, tempfile.mkdtemp(prefix='nx2a_split_out_'), '--keys', self.keys())
        self.assertEqual(result.returncode, 2)
        self.assertNotIn('Traceback', result.stderr)

    def test_a_missing_keys_file_is_an_error(self):
        path = Path(tempfile.mkdtemp(prefix='nx2a_split_')) / 'x.nsp'
        path.write_bytes(b'not a dump')
        missing = Path(tempfile.mkdtemp(prefix='nx2a_keys_')) / 'prod.keys'
        result = run('card_split.py', path, tempfile.mkdtemp(prefix='nx2a_split_out_'), '--keys', missing)
        self.assertEqual(result.returncode, 2)
        self.assertIn('prod.keys', result.stderr)
        self.assertNotIn('Traceback', result.stderr)


class UpstreamDiff(unittest.TestCase):
    def test_the_three_groups_are_told_apart(self):
        import upstream_diff
        mine = {'porter/a.py': '1', 'porter/b.py': '2', 'porter/only-mine.py': '3'}
        theirs = {'porter/a.py': '1', 'porter/b.py': '9', 'porter/only-upstream.py': '4'}
        only_mine, only_theirs, different = upstream_diff.compare(mine, theirs)
        self.assertEqual(only_mine, ['porter/only-mine.py'])
        self.assertEqual(only_theirs, ['porter/only-upstream.py'])
        self.assertEqual(different, ['porter/b.py'])


class UpstreamPr(unittest.TestCase):
    """The upstream's PRs: the fix may already exist, and GitHub may not answer at all."""

    def test_the_search_url_asks_for_pull_requests(self):
        import upstream_pr
        url = upstream_pr.search_url('shirai91/nx2android', 'cross chunk copy', include_issues=False)
        self.assertIn('repo:shirai91%2Fnx2android', url)
        self.assertIn('is:pr', url)

    def test_the_search_url_can_ask_for_issues(self):
        import upstream_pr
        url = upstream_pr.search_url('seedds/nxnative', 'vk_buf_at', include_issues=True)
        self.assertIn('is:issue', url)

    def test_the_hits_are_shortlisted(self):
        import upstream_pr
        answer = {'items': [
            {'number': 179, 'title': 'cross-chunk buffer copies', 'state': 'closed',
             'pull_request': {'merged_at': '2026-01-02T00:00:00Z'},
             'html_url': 'https://github.com/shirai91/nx2android/pull/179',
             'created_at': '2026-01-01T00:00:00Z', 'body': 'fixes the copy'},
            {'number': 20, 'title': 'something else', 'state': 'open',
             'html_url': 'https://github.com/shirai91/nx2android/pull/20',
             'created_at': '2026-01-01T00:00:00Z', 'body': ''},
        ]}
        hits = upstream_pr.results_of(answer)
        self.assertEqual(2, len(hits))
        self.assertTrue(hits[0]['merged'])
        self.assertEqual(179, hits[0]['number'])
        self.assertFalse(hits[1]['merged'])

    def test_a_search_that_does_not_answer_is_one_line(self):
        # 网络不通不是崩溃：工具说清了就停，调用方继续走自己的诊断
        import upstream_pr

        def broken(url):
            raise upstream_pr.FetchError('network', 'the host name could not be resolved', 2)

        original = upstream_pr.ask
        upstream_pr.ask = broken
        try:
            results, unreachable = upstream_pr.collect(('shirai91/nx2android',), 'x', False)
        finally:
            upstream_pr.ask = original
        self.assertEqual([], results)
        self.assertEqual(1, len(unreachable))
        self.assertEqual('network', unreachable[0]['kind'])


class KbSearch(unittest.TestCase):
    """The knowledge search: the upstream cases, the knowledge documents, and the phone's own cases.

    The script is the one that ships with the App (it lives in the assistant's skill folder), and
    it is run the way the assistant runs it.
    """

    SKILL = (ROOT / 'android/app/src/main/assets/nx2a/agent/dot-agents/skills/port-failures')

    DOC = """# A document

## The crash
A crash at the start with `x1 = 0x25a1` is the shared font: it has no glyph for the
fallback character and the lookup dereferenced a null.

## The build
The port stops at a stage when the shader split finds too few shaders.
"""

    def search(self, *args, **kwargs):
        """The search, run as the assistant runs it."""
        env = dict(kwargs.pop('env', {}), PYTHONUTF8='1')
        kwargs.setdefault('encoding', 'utf-8')
        return subprocess.run([sys.executable, str(self.SKILL / 'scripts/kb_search.py'),
                               *[str(a) for a in args]], capture_output=True, text=True,
                              env=env, **kwargs)

    def docs(self, text=None, name='runtime-symptoms.md'):
        """A folder with one knowledge document in it."""
        folder = Path(tempfile.mkdtemp(prefix='nx2a_docs_'))
        (folder / name).write_text(text if text is not None else self.DOC)
        return folder

    def index(self, rows=('shirai91\t419\tMERGED\tprofile\tcrash\thost\tcore\tyes\t\t\t\t\t'
                          'fix(host): readable zero slack behind each module\tA read past the end of a module\t'
                          'https://github.com/shirai91/nx2android/pull/419',)):
        """A case index with a header and a few rows."""
        folder = Path(tempfile.mkdtemp(prefix='nx2a_index_'))
        header = 'source\tref\tstate\tarea\tsymptom\tlayer\tkind\tfork\tgame\tids\tswitches\tpaths\ttitle\tsummary\turl'
        (folder / 'case-index.tsv').write_text('\n'.join([header, *rows]))
        return folder

    def test_a_document_is_found_by_section(self):
        result = self.search('--docs', 'shared font glyph', '--docs-dir', self.docs())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('The crash', result.stdout)
        self.assertIn('lines', result.stdout)          # where the section is
        self.assertIn('0x25a1', result.stdout)         # a snippet of it

    def test_a_section_is_printed_as_it_is(self):
        result = self.search('--docs', '--section', 'The crash', '--docs-dir', self.docs())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('The crash', result.stdout)
        self.assertIn('it has no glyph for the', result.stdout)

    def test_a_section_that_is_not_there_says_so(self):
        result = self.search('--docs', '--section', 'Nothing', '--docs-dir', self.docs())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('no section titled', result.stdout)

    def test_a_loose_hit_is_marked(self):
        result = self.search('--docs', 'shared font glyph nothing', '--docs-dir', self.docs())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('loose', result.stdout)          # not every word is there

    def test_nothing_found_points_at_the_other_corpus(self):
        result = self.search('--docs', 'qwertyuiop', '--docs-dir', self.docs())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('nothing found', result.stdout)
        self.assertIn('kb_search.py', result.stdout)   # the upstream cases may have it

    def test_the_answer_stays_small(self):
        result = self.search('--docs', 'font', '--docs-dir', self.docs())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertLess(len(result.stdout), 4000)

    def test_the_cases_are_capped(self):
        rows = tuple(f'shirai91\t{n}\tMERGED\tprofile\tcrash\thost\tcore\tyes\t\t\t\t\t'
                     f'fix(host): case {n}\tsummary {n}\thttps://example/{n}' for n in range(40))
        result = self.search('crash', '--index', self.index(rows) / 'case-index.tsv')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('40 cases match', result.stdout)
        self.assertIn('12 shown', result.stdout)

    def test_the_json_gives_the_same_count_as_the_text(self):
        rows = tuple(f'shirai91\t{n}\tMERGED\tprofile\tcrash\thost\tcore\tyes\t\t\t\t\t'
                     f'fix(host): case {n}\tsummary {n}\thttps://example/{n}' for n in range(40))
        path = self.index(rows) / 'case-index.tsv'
        text = self.search('crash', '--index', path)
        out = json.loads(self.search('crash', '--index', path, '--json').stdout)
        self.assertEqual(40, out['count'])
        self.assertEqual(12, out['shown'])
        self.assertEqual(12, len(out['results']))
        self.assertIn('40 cases match', text.stdout)

    def test_the_shipped_documents_are_searchable(self):
        result = self.search('--docs', 'shared font')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('shared fonts', result.stdout.lower())

    def test_the_phone_own_cases_are_searched(self):
        home = Path(tempfile.mkdtemp(prefix='nx2a_home_'))
        case = home / 'gamespec' / '0100A3501946E000'
        case.mkdir(parents=True)
        (case / 'spec.md').write_text(
            '# Super Game (0100A3501946E000) case file\n\n'
            '## Problems\n\n'
            '### 2026-11-23 the black screen (status: fixed)\n\n'
            'Symptom: the screen stays black after the logo.\n\n'
            'Cause: the shaders were not in the bundle.\n\n'
            'Fix: capture them and port again.\n')
        result = self.search('--local', 'black screen', env={'NX2A_HOME': str(home)})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Super Game', result.stdout)
        self.assertIn('shaders', result.stdout)


class GamespecPack(unittest.TestCase):
    """The case file of one game, packed into a zip the player hands to the upstream."""

    GAME = '0100A3501946E000'

    def case(self, files=None, manifest=None, spec=True, orig=None):
        """A case file: the document, the manifest and the files it lists (with their originals)."""
        home = Path(tempfile.mkdtemp(prefix='nx2a_gamespec_'))
        case = home / 'gamespec' / self.GAME
        (case / 'files').mkdir(parents=True)
        if spec:
            (case / 'spec.md').write_text('# 攻关档案\n\n## 现状\n卡在着色器解码。\n', encoding='utf-8', newline='\n')
        for path, text in (files or {}).items():
            target = case / 'files' / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding='utf-8', newline='\n')
        for path, text in (orig or {}).items():
            target = case / 'orig' / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding='utf-8', newline='\n')
        (case / 'fix.md').write_text('\n'.join(manifest if manifest is not None else list((files or {}).keys())),
                                     encoding='utf-8', newline='\n')
        return home, case

    def pack(self, home, *args):
        return run('gamespec_pack.py', '--game', self.GAME, *args, env={'NX2A_HOME': str(home)})

    def test_the_case_file_is_packed(self):
        home, case = self.case(files={
            'nxnative/dm/android/gfx_vk_present.c': '// fixed\n',
            'porter/pipeline.py': '# fixed\n',
        })
        result = self.pack(home, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertTrue(out['zip'].endswith('.zip'))

        import zipfile
        with zipfile.ZipFile(out['zip']) as zf:
            names = zf.namelist()
            self.assertIn('spec.md', names)
            self.assertIn('fix.md', names)
            self.assertIn('files/nxnative/dm/android/gfx_vk_present.c', names)
            self.assertIn('files/porter/pipeline.py', names)
            self.assertEqual(zf.read('files/porter/pipeline.py'), b'# fixed\n')

    def test_a_game_file_in_the_manifest_is_refused(self):
        home, case = self.case(files={'game.nsp': 'x'}, manifest=['game.nsp'])
        result = self.pack(home)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('游戏文件', result.stderr)
        self.assertFalse((case.parent / f'{self.GAME}-gamespec.zip').exists())

    def test_a_key_in_the_manifest_is_refused(self):
        home, case = self.case(files={'prod.keys': 'x'}, manifest=['prod.keys'])
        result = self.pack(home)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('密钥', result.stderr)

    def test_a_path_out_of_the_tree_is_refused(self):
        home, case = self.case(files={}, manifest=['../secrets.txt'])
        result = self.pack(home)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('往上走', result.stderr)

    def test_a_listed_file_that_is_missing_is_refused(self):
        home, case = self.case(files={}, manifest=['porter/pipeline.py'])
        result = self.pack(home)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('files/', result.stderr)

    def test_a_game_without_a_case_file_says_so(self):
        home = Path(tempfile.mkdtemp(prefix='nx2a_gamespec_'))
        result = self.pack(home)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('没有攻关档案', result.stderr)

    def test_a_diff_is_packed_when_the_original_is_there(self):
        # 原件在档案里，包里就多一份 diff：上游拿到就能 git apply，不用自己比
        home, case = self.case(
            files={'nxnative/dm/android/gfx_vk_present.c': '// fixed\n'},
            orig={'nxnative/dm/android/gfx_vk_present.c': '// before\n'},
        )
        result = self.pack(home, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(['nxnative/dm/android/gfx_vk_present.c'], out['diffed'])

        import zipfile
        with zipfile.ZipFile(out['zip']) as zf:
            names = zf.namelist()
            self.assertIn('diff.patch', names)
            self.assertIn('orig/nxnative/dm/android/gfx_vk_present.c', names)
            patch = zf.read('diff.patch').decode('utf-8')
            self.assertIn('--- a/nxnative/dm/android/gfx_vk_present.c', patch)
            self.assertIn('+++ b/nxnative/dm/android/gfx_vk_present.c', patch)
            self.assertIn('-// before', patch)
            self.assertIn('+// fixed', patch)

    def test_a_case_without_originals_is_packed_as_it_was(self):
        # 老档案没有原件：照旧打包（整份文件），没有 diff，也不报错
        home, case = self.case(files={'porter/pipeline.py': '# fixed\n'})
        result = self.pack(home, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual([], out['diffed'])

        import zipfile
        with zipfile.ZipFile(out['zip']) as zf:
            names = zf.namelist()
            self.assertNotIn('diff.patch', names)
            self.assertIn('files/porter/pipeline.py', names)

    def test_a_comment_line_in_the_manifest_is_not_a_path(self):
        # fix.md 顶上的那行「基于哪个版本」是注释：不是路径
        home, case = self.case(files={'porter/pipeline.py': '# fixed\n'})
        (case / 'fix.md').write_text(
            '# based on: app 0.4.0-alpha03, porter 0.3.0, fixed 2026-11-24\nporter/pipeline.py\n',
            encoding='utf-8', newline='\n',
        )
        result = self.pack(home, '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        out = json.loads(result.stdout)
        self.assertEqual(['porter/pipeline.py'], out['files'])


class ToolHousekeeping(unittest.TestCase):
    def test_every_tool_has_a_help_and_a_json_flag(self):
        for tool in sorted(TOOLS.glob('*.py')):
            if tool.name.startswith('nx2a_common'): continue
            with self.subTest(tool=tool.name):
                result = run(tool.name, '--help')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('--json', result.stdout)

    def test_the_readme_lists_every_tool(self):
        readme = (TOOLS / 'README.md').read_text()
        for tool in sorted(TOOLS.glob('*.py')):
            if tool.name.startswith('nx2a_common'): continue
            self.assertIn(tool.name, readme, f'{tool.name} is not in the README')


if __name__ == '__main__':
    unittest.main()
