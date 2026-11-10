# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

import tempfile
import unittest
from pathlib import Path

from gamedb_check import (
    DiscEntry,
    FormatError,
    GameEntry,
    find_problems,
    parse_discdb,
    parse_gamedb,
    read_known_clashes,
)

REPO = Path(__file__).resolve().parents[3]

GAMEDB = """\
SLPS-00001:
  name: "Game One (Japan)"
  codes:
    - SLPS-00001
    - SLPS-00002
  metadata:
    publisher: "Someone"
SLUS-00003:
  name: "Game Three (USA)"
  controllers:
    - DigitalController
    # a comment
"""

DISCDB = """\
SLPM-80238:
  name: "'98 Koushien (Japan) (Demo)"
  trackData:
    - tracks:
        - size: 443733024
          md5: "0aa29df886d9f4f3ec5340bdd3b4f3be"
SLPS-01204:
  name: "Say \\"Hi\\" C:\\\\X"
  trackData:
    - tracks:
        - size: 100
          md5: "00000000000000000000000000000001"
        - size: 200
          md5: "00000000000000000000000000000002"
    - version: "Rev 1"
      tracks:
        - size: 300
          md5: "00000000000000000000000000000003"
"""


def game(key, *codes, line=1):
    return GameEntry(key, line, tuple(codes))


def disc(key, *md5s, line=1):
    return DiscEntry(key, line, key, (tuple((1, m) for m in md5s),))


class ParseGamedbTests(unittest.TestCase):
    def test_parse_gamedb_keys_and_codes(self):
        entries = parse_gamedb(GAMEDB)
        self.assertEqual(
            entries,
            [
                GameEntry("SLPS-00001", 1, ("SLPS-00001", "SLPS-00002")),
                GameEntry("SLUS-00003", 8, ()),
            ],
        )

    def test_parse_gamedb_rejects_inline_codes(self):
        text = 'SLUS-00001:\n  name: "X"\n  codes: [SLUS-00001]\n'
        with self.assertRaisesRegex(FormatError, r"gamedb\.yaml:3"):
            parse_gamedb(text)

    def test_parse_gamedb_rejects_codes_it_would_misread(self):
        for line in ["    - SLUS-00002 # note", "    # a comment", '    - "SLUS-00002"', "      - SLUS-00002"]:
            text = f'SLUS-00001:\n  codes:\n    - SLUS-00001\n{line}\n  name: "X"\n'
            with self.assertRaisesRegex(FormatError, r"gamedb\.yaml:4", msg=line):
                parse_gamedb(text)

    def test_parse_gamedb_rejects_unindented_line(self):
        with self.assertRaisesRegex(FormatError, r"gamedb\.yaml:2"):
            parse_gamedb("SLUS-00001:\n name: \"X\"\n")


class ParseDiscdbTests(unittest.TestCase):
    def test_parse_discdb_track_sets(self):
        entries = parse_discdb(DISCDB)
        self.assertEqual(entries[0].key, "SLPM-80238")
        self.assertEqual(entries[0].name, "'98 Koushien (Japan) (Demo)")
        self.assertEqual(entries[0].track_sets, (((443733024, "0aa29df886d9f4f3ec5340bdd3b4f3be"),),))
        self.assertEqual(entries[1].line, 7)
        self.assertEqual(entries[1].name, 'Say "Hi" C:\\X')
        self.assertEqual(
            entries[1].track_sets,
            (
                ((100, "00000000000000000000000000000001"), (200, "00000000000000000000000000000002")),
                ((300, "00000000000000000000000000000003"),),
            ),
        )

    def test_parse_discdb_quoted_size(self):
        entries = parse_discdb(DISCDB.replace("size: 443733024", "size: '443733024'"))
        self.assertEqual(entries[0].track_sets[0][0][0], 443733024)
        with self.assertRaisesRegex(FormatError, r"discdb\.yaml:5"):
            parse_discdb(DISCDB.replace("size: 443733024", "size: '443733024"))

    def test_parse_discdb_rejects_unknown_line(self):
        text = DISCDB.replace('          md5: "0aa2', '          crc: "0aa2', 1)
        with self.assertRaisesRegex(FormatError, r"discdb\.yaml:6"):
            parse_discdb(text)

    def test_parse_discdb_rejects_entry_without_tracks(self):
        with self.assertRaisesRegex(FormatError, r"discdb\.yaml"):
            parse_discdb('SLUS-00001:\n  name: "X"\n  trackData:\n')

    def test_parse_rejects_missing_final_newline(self):
        with self.assertRaisesRegex(FormatError, "newline"):
            parse_gamedb(GAMEDB.rstrip("\n"))
        with self.assertRaisesRegex(FormatError, "newline"):
            parse_discdb(DISCDB.rstrip("\n"))

    def test_parse_rejects_blank_line(self):
        with self.assertRaisesRegex(FormatError, r"gamedb\.yaml:8"):
            parse_gamedb(GAMEDB.replace("SLUS-00003:", "\nSLUS-00003:"))


class ProblemTests(unittest.TestCase):
    def test_duplicate_key(self):
        problems = find_problems([game("SLUS-00001"), game("SLUS-00001", line=5)], [], set())
        self.assertEqual(len(problems), 1)
        self.assertIn("SLUS-00001", problems[0])

    def test_duplicate_disc_key(self):
        problems = find_problems([], [disc("SLUS-00001", "a" * 32), disc("SLUS-00001", "b" * 32)], set())
        self.assertEqual(len(problems), 1)
        self.assertIn("SLUS-00001", problems[0])

    def test_code_in_two_entries(self):
        problems = find_problems([game("SLUS-00001", "SLUS-00009"), game("SLUS-00002", "SLUS-00009")], [], set())
        self.assertEqual(len(problems), 1)
        self.assertIn("SLUS-00009", problems[0])

    def test_key_is_other_entrys_code(self):
        problems = find_problems([game("SLUS-00001"), game("SLUS-00002", "SLUS-00001")], [], set())
        self.assertEqual(len(problems), 1)
        self.assertIn("SLUS-00001", problems[0])

    def test_own_key_in_own_codes_is_fine(self):
        self.assertEqual(find_problems([game("SLUS-00001", "SLUS-00001", "SLUS-00002")], [], set()), [])

    def test_known_clash_is_not_a_problem(self):
        games = [game("SLUS-00001", "SLUS-00009"), game("SLUS-00002", "SLUS-00009")]
        self.assertEqual(find_problems(games, [], {"SLUS-00009"}), [])
        duplicate = [game("SLUS-00001"), game("SLUS-00001")]
        self.assertEqual(len(find_problems(duplicate, [], {"SLUS-00001"})), 1)

    def test_shared_track_hashes_are_fine(self):
        self.assertEqual(find_problems([], [disc("SLUS-00001", "a" * 32), disc("SLUS-00002", "a" * 32)], set()), [])

    def test_read_known_clashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "known.txt"
            path.write_text("# header\n\nSLUS-00001\n  SLUS-00002  \n")
            self.assertEqual(read_known_clashes(path), {"SLUS-00001", "SLUS-00002"})


class ShippedFilesTests(unittest.TestCase):
    def test_shipped_files_pass(self):
        resources = REPO / "data" / "resources"
        games = parse_gamedb((resources / "gamedb.yaml").read_text(encoding="utf-8"))
        discs = parse_discdb((resources / "discdb.yaml").read_text(encoding="utf-8"))
        known = read_known_clashes(REPO / "scripts" / "gamedb" / "known-clashes.txt")
        self.assertEqual(find_problems(games, discs, known), [])


if __name__ == "__main__":
    unittest.main()
