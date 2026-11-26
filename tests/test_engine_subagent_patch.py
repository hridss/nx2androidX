# The engine's sub-agent extension: the wait path of get_subagent_result must not hold a session. The build
# patches the engine's own file when it stages it (packaging/android-apk/build_assets.py).
import importlib.util, shutil, subprocess, tempfile, unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / 'packaging' / 'android-apk' / 'build_assets.py'
spec = importlib.util.spec_from_file_location('build_assets', SCRIPT)
build_assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build_assets)

# The three places the patch touches, as they are in the engine's file. They are read from the patch itself,
# so a patch that changes is a patch that is tested.
WAIT_DESCRIPTION, WAIT_GUARD, RUNNING_TEXT = (old for old, _ in build_assets.SUBAGENT_PATCHES)

# The engine's file as it is in the staged tree (the build's cache; the test skips when it is not there).
CACHED = (Path(__file__).resolve().parent.parent / 'packaging' / 'android-apk' / '.cache' / 'assets' / 'agent'
          / 'runtime' / 'extensions' / 'pi-subagents' / 'dist' / 'index.js')


class EngineSubagentPatch(unittest.TestCase):
    def setUp(self):
        self.dir = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.file = self.dir / 'extensions' / 'pi-subagents' / 'dist' / 'index.js'
        self.file.parent.mkdir(parents=True)
        self.file.write_text('\n'.join([
            'function defineTool(o) { return o; }',
            WAIT_DESCRIPTION,
            WAIT_GUARD,
            RUNNING_TEXT,
        ]), encoding='utf-8')

    def patch(self):
        build_assets.patch_engine_subagents(self.dir)
        return self.file.read_text(encoding='utf-8')

    def test_the_wait_is_taken_from_a_top_level_agent(self):
        got = self.patch()
        self.assertIn('isTopLevelAgent(record)', got)
        self.assertIn('else if (params.wait && (record.status', got)

    def test_the_running_answer_does_not_teach_the_wait_anymore(self):
        got = self.patch()
        # the nested agent keeps the old answer, the top-level one gets a new one
        self.assertIn('? "Agent is still running. Do not wait on it', got)
        self.assertIn(': "Agent is still running. Use wait: true or check back later.";', got)

    def test_the_description_says_who_may_wait(self):
        self.assertIn('nested agent', self.patch())

    def test_a_file_that_moved_stops_the_build(self):
        self.file.write_text('something else entirely\n', encoding='utf-8')
        with self.assertRaises(SystemExit) as e:
            build_assets.patch_engine_subagents(self.dir)
        self.assertIn('does not look the way the patch expects', str(e.exception))

    def test_an_engine_without_the_extension_stops_the_build(self):
        shutil.rmtree(self.file.parent.parent)
        with self.assertRaises(SystemExit) as e:
            build_assets.patch_engine_subagents(self.dir)
        self.assertIn('carries no sub-agent extension', str(e.exception))

    @unittest.skipUnless(CACHED.is_file(), 'the engine is not staged in this checkout')
    def test_the_real_engine_file(self):
        """The engine's own file, patched: every patch matches once, and the file is still one program."""
        work = self.dir / 'real'
        (work / 'extensions' / 'pi-subagents' / 'dist').mkdir(parents=True)
        shutil.copy2(CACHED, work / 'extensions' / 'pi-subagents' / 'dist' / 'index.js')
        build_assets.patch_engine_subagents(work)
        got = (work / 'extensions' / 'pi-subagents' / 'dist' / 'index.js').read_text(encoding='utf-8')
        self.assertIn('if (params.wait && isTopLevelAgent(record))', got)
        self.assertIn('else if (params.wait && (record.status === "running"', got)
        self.assertNotIn('\n            if (params.wait && (record.status === "running"', got)

    @unittest.skipUnless(CACHED.is_file() and shutil.which('node'), 'the engine is not staged (or node is missing)')
    def test_the_patched_file_is_still_a_program(self):
        """The patched file has to parse: a patch that breaks the extension breaks the assistant."""
        work = self.dir / 'real'
        (work / 'extensions' / 'pi-subagents' / 'dist').mkdir(parents=True)
        shutil.copy2(CACHED, work / 'extensions' / 'pi-subagents' / 'dist' / 'index.js')
        build_assets.patch_engine_subagents(work)
        patched = work / 'extensions' / 'pi-subagents' / 'dist' / 'index.js'
        as_module = work / 'index.mjs'   # the file is a module: node checks it as one
        as_module.write_bytes(patched.read_bytes())
        check = subprocess.run(['node', '--check', str(as_module)], capture_output=True, text=True)
        self.assertEqual(0, check.returncode, check.stderr)


if __name__ == '__main__':
    unittest.main()
