"""The netdisk pipeline (devtools/netdisk/): which release is published, what every netdisk does with it, and
that one netdisk's trouble never holds the others back."""
import json
import os
import platform
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from devtools.netdisk import Asset, Outcome, Target, TargetError, TargetUnavailable   # noqa: E402
from devtools.netdisk import pipeline, release                                         # noqa: E402
from devtools.netdisk.targets import quark                                             # noqa: E402

# a stand-in for the kuake CLI: it writes down what it was asked and answers with what the test says
FAKE = '''#!/usr/bin/env python3
import json, os, sys
here = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
with open(os.path.join(here, 'calls.jsonl'), 'a') as f:
    f.write(json.dumps(args) + '\\n')
answers = {}
path = os.path.join(here, 'answers.json')
if os.path.exists(path):
    with open(path) as f:
        answers = json.load(f)
created = []
cpath = os.path.join(here, 'created.json')
if os.path.exists(cpath):
    with open(cpath) as f:
        created = json.load(f)
if args[0] == 'create':
    created.append(args[1])
    with open(cpath, 'w') as f:
        json.dump(created, f)
key = args[0]
if key == 'list' and len(args) > 1:
    key = 'list ' + args[1]
answer = answers.get(key, answers.get(args[0], {'success': True, 'code': 'OK', 'message': 'ok', 'data': {}}))
if key.startswith('list ') and key.rsplit('/', 1)[-1] in created:
    answer = []
if isinstance(answer, list):
    for item in answer:
        print(json.dumps(item))
else:
    print(json.dumps(answer))
    sys.exit(0 if answer.get('success', True) else 1)
'''


def file_entry(name, size):
    """One file as the CLI's stream mode writes it."""
    return {'success': True, 'code': 'OK', 'message': 'OK',
            'data': {'fid': 'FID' + name, 'file_name': name, 'size': size, 'dir': False}}


class Fake:
    """The fake CLI in a directory of its own, with the answers the test gives it."""

    def __init__(self, answers=None):
        self.dir = Path(tempfile.mkdtemp(prefix='nx2a-fake-'))
        script = self.dir / 'kuake'
        script.write_text(FAKE)
        script.chmod(0o755)
        self.write(answers or {})

    def write(self, answers):
        (self.dir / 'answers.json').write_text(json.dumps(answers))

    def calls(self):
        path = self.dir / 'calls.jsonl'
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def target(self, cookie='COOKIE', **kw):
        return quark.QuarkTarget(binary=str(self.dir / 'kuake'), cookie=cookie,
                                 folder='/我的/nx2AndroidX', archive='/我的/旧版本',
                                 workdir=self.dir / 'work', **kw)


class Tool(unittest.TestCase):
    """The tool the netdisks drive"""

    def test_the_machine_is_named_the_way_the_release_names_it(self):
        """the runner calls its cpu x86_64, the release calls it amd64"""
        for machine, build in (('x86_64', 'amd64'), ('amd64', 'amd64'),
                               ('aarch64', 'arm64'), ('arm64', 'arm64')):
            original = platform.machine
            platform.machine = lambda m=machine: m
            try:
                self.assertEqual(quark._machine(), build)
            finally:
                platform.machine = original

    def test_a_machine_the_release_has_no_build_for_has_none(self):
        original = platform.machine
        platform.machine = lambda: 'risc-v'
        try:
            self.assertEqual(quark.build_for_this_machine(), '')
        finally:
            platform.machine = original


class LatestRelease(unittest.TestCase):
    """Which release is published"""

    def test_the_latest_release_is_the_one_that_was_cut_last(self):
        answer = [{'tag_name': 'v0.4.1-alpha02', 'draft': False,
                   'assets': [{'name': 'nx2android-v0.4.1-alpha02.apk', 'size': 273313264,
                               'digest': 'sha256:abc'}, {'name': 'nx2a-profiles-r1-bf1e4edd.zip', 'size': 96160472}]}]
        original = release.gh
        release.gh = lambda args, timeout=None: json.dumps(answer)
        try:
            r = release.latest()
        finally:
            release.gh = original
        self.assertEqual(r.tag, 'v0.4.1-alpha02')
        self.assertEqual([a.name for a in r.assets],
                         ['nx2android-v0.4.1-alpha02.apk', 'nx2a-profiles-r1-bf1e4edd.zip'])
        self.assertEqual(r.assets[0].digest, 'sha256:abc')

    def test_a_draft_is_not_a_release(self):
        original = release.gh
        release.gh = lambda args, timeout=None: json.dumps([{'tag_name': 'v9', 'draft': True}])
        try:
            with self.assertRaises(release.ReleaseError):
                release.latest()
        finally:
            release.gh = original

    def test_a_release_that_carries_nothing_is_told_so(self):
        original = release.gh
        release.gh = lambda args, timeout=None: json.dumps([{'tag_name': 'v1', 'draft': False, 'assets': []}])
        try:
            with self.assertRaises(release.ReleaseError):
                pipeline.publish(out=lambda m: None)
        finally:
            release.gh = original

    def test_a_file_that_is_not_what_the_release_says_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'a.apk'
            path.write_bytes(b'not the file')
            asset = Asset(name='a.apk', size=9, digest='sha256:' + '0' * 64)
            with self.assertRaises(release.ReleaseError):
                release.check_digest(asset, path)

    def test_the_file_the_release_names_is_the_file_that_is_there(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'a.apk'
            path.write_bytes(b'the file')
            digest = hashlib.sha256(b'the file').hexdigest()
            release.check_digest(Asset(name='a.apk', digest='sha256:' + digest), path)


class QuarkNetdisk(unittest.TestCase):
    """What the Quark target does with the release"""

    def setUp(self):
        self.fake = Fake({
            'user': {'success': True, 'code': 'OK', 'message': 'ok', 'data': {'nickname': 'qu'}},
            'list /我的/nx2AndroidX': [],
            'list /我的/旧版本': [],
            'upload': {'success': True, 'code': 'OK', 'message': '上传成功', 'data': {'fid': 'FID'}},
            'move': {'success': True, 'code': 'OK', 'message': 'ok', 'data': {}},
            'create': {'success': True, 'code': 'OK', 'message': 'ok', 'data': {}},
        })

    def _asset(self, name='nx2android-v0.4.1-alpha02.apk', size=100):
        tmp = tempfile.mkdtemp(prefix='nx2a-asset-')
        self.addCleanup(shutil.rmtree, tmp, True)
        path = Path(tmp) / name
        path.write_bytes(b'x' * size)
        return Asset(name=name, size=size, path=path)

    def test_a_file_that_is_not_there_yet_is_uploaded(self):
        target = self.fake.target()
        target.prepare()
        outcomes = target.publish([self._asset()])
        self.assertEqual([o.state for o in outcomes], ['uploaded'])
        uploads = [c for c in self.fake.calls() if c[0] == 'upload']
        self.assertEqual(len(uploads), 1)
        self.assertEqual(uploads[0][2], '/我的/nx2AndroidX/nx2android-v0.4.1-alpha02.apk')

    def test_a_file_the_netdisk_has_already_is_not_uploaded_again(self):
        self.fake.write({'list /我的/nx2AndroidX': [file_entry('nx2android-v0.4.1-alpha02.apk', 100)],
                         'user': {'success': True, 'code': 'OK', 'message': 'ok', 'data': {}}})
        target = self.fake.target()
        target.prepare()
        outcomes = target.publish([self._asset()])
        self.assertEqual([o.state for o in outcomes], ['skipped'])
        self.assertEqual([c for c in self.fake.calls() if c[0] == 'upload'], [])

    def test_the_files_that_are_not_this_releases_go_into_the_archive(self):
        self.fake.write({'list /我的/nx2AndroidX': [file_entry('nx2android-v0.4.1-alpha01.apk', 100),
                                                    file_entry('nx2android-v0.4.1-alpha02.apk', 100)],
                         'list /我的/旧版本': [],
                         'user': {'success': True, 'code': 'OK', 'message': 'ok', 'data': {}}})
        target = self.fake.target()
        target.prepare()
        outcomes = target.publish([self._asset()])
        self.assertIn('moved', [o.state for o in outcomes])
        moves = [c for c in self.fake.calls() if c[0] == 'move']
        self.assertEqual(len(moves), 1)
        self.assertEqual(moves[0][1], '/我的/nx2AndroidX/nx2android-v0.4.1-alpha01.apk')
        self.assertEqual(moves[0][2], '/我的/旧版本')

    def test_the_archive_folder_is_made_when_it_is_not_there_yet(self):
        self.fake.write({'list /我的/nx2AndroidX': [file_entry('nx2android-v0.4.1-alpha01.apk', 100)],
                         'list /我的/旧版本': {'success': False, 'code': 'NOT_FOUND', 'message': 'no such folder'},
                         'user': {'success': True, 'code': 'OK', 'message': 'ok', 'data': {}}})
        target = self.fake.target()
        target.prepare()
        target.publish([self._asset()])
        creates = [c for c in self.fake.calls() if c[0] == 'create']
        self.assertEqual(len(creates), 1)
        self.assertEqual(creates[0][1], '旧版本')
        self.assertEqual(creates[0][2], '/我的')

    def test_without_a_cookie_the_netdisk_is_skipped_and_nothing_is_uploaded(self):
        target = self.fake.target(cookie='')
        with self.assertRaises(TargetUnavailable):
            target.prepare()

    def test_a_cookie_the_netdisk_refuses_is_a_failure(self):
        self.fake.write({'user': {'success': False, 'code': 'UNAUTHORIZED', 'message': 'the cookie is over'}})
        target = self.fake.target()
        with self.assertRaises(TargetError):
            target.prepare()

    def test_the_cookie_goes_to_the_cli_and_nothing_else_does(self):
        target = self.fake.target()
        target.prepare()
        env = target._env()
        self.assertEqual(env.get('KUAKE_COOKIE'), 'COOKIE')
        self.assertNotIn('NX2A_SECRET', env)


class FakeTarget(Target):
    """A netdisk in the test: it can go on, it can be without a cookie, or it can fall over."""

    def __init__(self, name, state='ok'):
        self.name = name
        self.state = state
        self.published = []

    def prepare(self):
        if self.state == 'no-cookie':
            raise TargetUnavailable('the cookie is missing')
        if self.state == 'no-tool':
            raise TargetError('the tool is not there')

    def publish(self, assets):
        if self.state == 'refused':
            raise TargetError('the cookie was refused')
        self.published = [a.name for a in assets]
        return [Outcome(a.name, 'uploaded', '') for a in assets]


class WaitingForTheFiles(unittest.TestCase):
    """A release is published before the build that its tag started attaches the files"""

    def test_a_release_that_carries_the_apk_is_not_waited_for(self):
        r = release.Release('v1', [Asset(name='nx2android-v1.apk', size=1)])
        original = release.by_tag
        release.by_tag = lambda repo, tag: r
        try:
            self.assertIs(release.wait_for_assets('r/r', r, timeout=1), r)
        finally:
            release.by_tag = original

    def test_the_wait_ends_when_the_apk_is_there_and_the_files_stop_changing(self):
        empty = release.Release('v1', [])
        with_apk = release.Release('v1', [Asset(name='nx2android-v1.apk', size=1),
                                          Asset(name='nx2a-profiles-r1-abcdef12.zip', size=2)])
        answers = [empty, with_apk, with_apk]
        original, original_sleep = release.by_tag, time.sleep
        release.by_tag = lambda repo, tag: answers.pop(0)
        time.sleep = lambda seconds: None
        try:
            got = release.wait_for_assets('r/r', empty, timeout=60, every=1)
        finally:
            release.by_tag, time.sleep = original, original_sleep
        self.assertEqual([a.name for a in got.assets],
                         ['nx2android-v1.apk', 'nx2a-profiles-r1-abcdef12.zip'])

    def test_a_release_without_an_apk_is_not_published(self):
        original = release.gh
        release.gh = lambda args, timeout=None: json.dumps([{'tag_name': 'v1', 'draft': False,
                                                              'assets': [{'name': 'nx2a-profiles.zip', 'size': 1}]}])
        try:
            with self.assertRaises(release.ReleaseError) as caught:
                pipeline.publish(out=lambda m: None)
            self.assertIn('no APK', str(caught.exception))
        finally:
            release.gh = original


class Pipeline(unittest.TestCase):
    """The fan-out"""

    def _assets(self):
        return [Asset(name='nx2android-v1.apk', size=1)]

    def test_one_netdisk_that_falls_over_does_not_hold_the_others_back(self):
        good = FakeTarget('good')
        broken = FakeTarget('broken', 'refused')
        without = FakeTarget('without', 'no-cookie')
        ready, failed = pipeline.prepare([good, broken, without], out=lambda m: None)
        self.assertEqual([t.name for t in ready], ['good', 'broken'])
        self.assertEqual(failed, 0)
        results = pipeline.run(ready, self._assets(), out=lambda m: None)
        states = {r.name: r.state for r in results}
        self.assertEqual(states, {'good': 'ok', 'broken': 'failed'})
        self.assertEqual(good.published, ['nx2android-v1.apk'])

    def test_a_netdisk_without_a_cookie_is_skipped_and_the_others_still_go(self):
        good = FakeTarget('good')
        without = FakeTarget('without', 'no-cookie')
        ready, failed = pipeline.prepare([good, without], out=lambda m: None)
        self.assertEqual([t.name for t in ready], ['good'])
        self.assertEqual(failed, 0)

    def test_a_netdisk_without_a_tool_is_a_failure_and_not_a_skip(self):
        ready, failed = pipeline.prepare([FakeTarget('x', 'no-tool')], out=lambda m: None)
        self.assertEqual(ready, [])
        self.assertEqual(failed, 1)

    def test_the_exit_code_tells_whether_every_netdisk_went_well(self):
        assets = [Asset(name='nx2android-v1.apk', size=1)]

        def fake_latest(repo):
            return release.Release('v1', assets)

        original_latest, original_fetch = release.latest, release.fetch
        release.latest = fake_latest
        release.fetch = lambda repo, rel, dest: rel.assets
        try:
            code = pipeline.publish(targets=['a', 'b'], out=lambda m: None,
                                    make=lambda name: FakeTarget(name, 'refused' if name == 'b' else 'ok'))
            self.assertEqual(code, 1)
            code = pipeline.publish(targets=['a'], out=lambda m: None, make=lambda name: FakeTarget(name))
            self.assertEqual(code, 0)
        finally:
            release.latest, release.fetch = original_latest, original_fetch


if __name__ == '__main__':
    unittest.main()
