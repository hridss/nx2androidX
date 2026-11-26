"""The player's imported profile copied into the tool tree's dm/games for as long as a port runs: the rewriter finds a profile's patches.py only there (without them a
profile's cuts are not applied), and so do the host's profile header and the runtime update. The layer is not part of DM_GAMES_DIR; the games the tool knows come from
the tree and the layer (porter.profiles)."""
import os
from pathlib import Path
import shutil
import tempfile
import types
import unittest
from unittest import mock

from porter import pipeline, phone_port, profiles, profiles_layer

TID = '0100B8A026F14000'
OTHER = '0100999999999000'


class ProfileSyncTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        patch = mock.patch.dict(os.environ, dict(os.environ, NX2A_HOME=str(self.home)), clear=False)
        patch.start(); self.addCleanup(patch.stop)
        self.layer = profiles_layer.layer_dir() / TID
        self.layer.mkdir(parents=True)
        (self.layer / 'game.tsv').write_text(f'title_id\t{TID}\n', encoding='utf-8')
        (self.layer / 'patches.py').write_text('CUTS = {}\n', encoding='utf-8')
        (self.layer / 'android.tsv').write_text('status\tplayable\n', encoding='utf-8')
        self.dm = Path(self.temp.name) / 'dm'
        self.games = self.dm / 'games'
        self.games.mkdir(parents=True)

    def test_the_imported_profile_is_copied_into_the_games_folder(self):
        dst = profiles_layer.copy_to_games(TID, self.games)
        self.assertEqual(dst, self.games / TID)
        self.assertTrue((dst / 'patches.py').is_file())       # the rewriter's patches: the reason for the copy
        self.assertTrue((dst / 'android.tsv').is_file())

    def test_the_copy_is_made_fresh(self):
        profiles_layer.copy_to_games(TID, self.games)
        (self.games / TID / 'game.tsv').write_text('a stale import\n', encoding='utf-8')   # an older import left in place
        dst = profiles_layer.copy_to_games(TID, self.games)   # a copy of ours is refreshed from the layer
        self.assertEqual((dst / 'game.tsv').read_text(encoding='utf-8'), f'title_id\t{TID}\n')

    def test_a_profile_the_tree_has_of_its_own_is_left_alone(self):
        own = self.games / TID
        own.mkdir()
        (own / 'game.tsv').write_text(f'title_id\t{TID}\n', encoding='utf-8')
        self.assertIsNone(profiles_layer.copy_to_games(TID, self.games))
        self.assertEqual((own / 'game.tsv').read_text(encoding='utf-8'), f'title_id\t{TID}\n')
        self.assertFalse((own / profiles_layer.MARKER).exists())   # the tree's own profile is never marked or replaced

    def test_a_game_the_player_imported_no_profile_of_is_not_copied(self):
        self.assertIsNone(profiles_layer.copy_to_games(OTHER, self.games))
        self.assertFalse((self.games / OTHER).exists())

    def test_a_checkout_is_not_written_to(self):
        (self.games / '.git').mkdir()
        self.assertIsNone(profiles_layer.copy_to_games(TID, self.games))
        self.assertFalse((self.games / TID).exists())

    def test_the_port_copies_the_profile_and_removes_it_when_it_is_done(self):
        port = types.SimpleNamespace(g=None, synced_profile=None)
        with mock.patch.object(profiles_layer, 'DM', self.dm):
            phone_port.PhonePort.sync_profile(port, TID)
        self.assertTrue((self.games / TID / 'patches.py').is_file())
        phone_port.PhonePort.drop_profile(port)
        self.assertFalse((self.games / TID).exists())

    def test_a_failure_to_copy_is_a_warning_not_an_error(self):
        port = types.SimpleNamespace(g=None, synced_profile=None)
        with mock.patch.object(profiles_layer, 'DM', self.dm), mock.patch.object(profiles_layer, 'copy_to_games', side_effect=OSError('read-only')):
            phone_port.PhonePort.sync_profile(port, TID)   # no exception: the port goes on without the profile's patches
        self.assertIsNone(port.synced_profile)


class ProfilesLayerFallbackTest(unittest.TestCase):
    """The layer is not in DM_GAMES_DIR: the games the tool knows are the tree's own plus the layer's."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        patch = mock.patch.dict(os.environ, dict(os.environ, NX2A_HOME=str(self.home)), clear=False)
        patch.start(); self.addCleanup(patch.stop)
        os.environ.pop('DM_GAMES_DIR', None)
        for tid, name, status in ((TID, '最终幻想 共鸣', 'playable'), (OTHER, '另一款游戏', 'wip')):
            d = profiles_layer.layer_dir() / tid
            d.mkdir(parents=True)
            (d / 'game.tsv').write_text(f'title_id\t{tid}\nname\t{name}\n', encoding='utf-8')
            (d / 'android.tsv').write_text(f'status\t{status}\n', encoding='utf-8')

    def test_by_title_finds_a_profile_only_the_layer_has(self):
        g, p = phone_port.by_title(int(OTHER, 16))
        self.assertIsNotNone(g)
        self.assertEqual(g.id, OTHER)
        self.assertIsNotNone(p)
        self.assertEqual(p.status, 'wip')

    def test_the_games_the_tool_knows_include_the_layer(self):
        ids = {e.id for e in phone_port.all_entries(android_only=False)}
        self.assertIn(TID, ids)
        self.assertIn(OTHER, ids)

    def test_a_game_without_android_tsv_is_not_listed_as_ported(self):
        third = '0100AAAAAAAAA000'
        d = profiles_layer.layer_dir() / third
        d.mkdir(parents=True)
        (d / 'game.tsv').write_text(f'title_id\t{third}\nname\t只有 iOS 档案的游戏\n', encoding='utf-8')
        self.assertEqual([e for e in phone_port.all_entries() if e.id == third], [])   # android_only hides it
        rows = [e for e in phone_port.all_entries(android_only=False) if e.id == third]
        self.assertEqual(len(rows), 1)
        self.assertFalse(rows[0].android)


if __name__ == '__main__':
    unittest.main()
