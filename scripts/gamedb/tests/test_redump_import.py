# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

import contextlib
import io
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path
from xml.sax.saxutils import quoteattr

from gamedb_check import DiscEntry, GameEntry, parse_discdb, parse_gamedb
from redump_import import (
    Added,
    apply_plan,
    main,
    pr_title,
    render_discdb_entry,
    render_gamedb_entry,
    render_summary,
    yaml_quote,
    Disc,
    ImportPlan,
    ListShapeError,
    RedumpList,
    core_serial,
    first_core_serial,
    plan_import,
    read_redump_zip,
    read_skip_list,
)

VERSION = "2026-06-15 11-55-46"


def md5(n):
    return f"{n:032x}"


def game_xml(name, *, category="Games", serial=None, version=None, tracks=((1000, md5(1)),), cue=True):
    parts = [f"\t<game name={quoteattr(name)}>", f"\t\t<category>{category}</category>"]
    if serial is not None:
        parts.append(f"\t\t<serial>{serial}</serial>")
    if version is not None:
        parts.append(f"\t\t<version>{version}</version>")
    parts.append(f"\t\t<description>{name}</description>")
    if cue:
        parts.append(f'\t\t<rom name={quoteattr(name + ".cue")} size="92" crc="0" md5="{md5(999)}" sha1="0"/>')
    for i, (size, h) in enumerate(tracks, 1):
        parts.append(f'\t\t<rom name={quoteattr(f"{name} (Track {i}).bin")} size="{size}" crc="0" md5="{h}" sha1="0"/>')
    parts.append("\t</game>")
    return "\n".join(parts)


def dat_xml(games, *, name="Sony - PlayStation", count=None, version=VERSION):
    count = len(games) if count is None else count
    return "\n".join(
        [
            '<?xml version="1.0"?>',
            "<datafile>",
            "\t<header>",
            f"\t\t<name>{name}</name>",
            f"\t\t<description>{name} - Discs ({count}) ({VERSION})</description>",
            f"\t\t<version>{version}</version>",
            "\t</header>",
            *games,
            "</datafile>",
            "",
        ]
    )


def make_list(tmp, games, *, name="Sony - PlayStation", count=None, members=None):
    path = Path(tmp) / "list.zip"
    if members is None:
        members = {f"Sony - PlayStation - Datfile ({len(games)}) ({VERSION}).dat": dat_xml(games, name=name, count=count)}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        for member, text in members.items():
            z.writestr(member, text)
    return path


TWO_GAMES = [
    game_xml(
        "Bio Hazard (Japan) (Rev 1)",
        serial="SLPM 86770, SLPS 00222",
        version="Rev 1",
        tracks=((736836912, md5(2)), (1234, md5(3))),
    ),
    game_xml("Demo Disc (Europe)", category="Demos"),
]


class ReadListTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def test_reads_discs(self):
        redump = read_redump_zip(make_list(self.tmp, TWO_GAMES), min_discs=1)
        self.assertEqual(redump.version, VERSION)
        self.assertEqual(
            redump.discs[0],
            Disc(
                "Bio Hazard (Japan) (Rev 1)",
                "Games",
                ("SLPM 86770", "SLPS 00222"),
                "Rev 1",
                ((736836912, md5(2)), (1234, md5(3))),
            ),
        )
        self.assertEqual(redump.discs[1], Disc("Demo Disc (Europe)", "Demos", (), "", ((1000, md5(1)),)))

    def test_rejects_version_not_a_date(self):
        path = make_list(self.tmp, TWO_GAMES, members={"list.dat": dat_xml(TWO_GAMES, version="2026-06-15\nadded=999")})
        with self.assertRaisesRegex(ListShapeError, "version"):
            read_redump_zip(path, min_discs=1)

    def test_rejects_wrong_header_name(self):
        with self.assertRaisesRegex(ListShapeError, "Sony - PlayStation 2"):
            read_redump_zip(make_list(self.tmp, TWO_GAMES, name="Sony - PlayStation 2"), min_discs=1)

    def test_rejects_count_mismatch(self):
        with self.assertRaisesRegex(ListShapeError, "3"):
            read_redump_zip(make_list(self.tmp, TWO_GAMES, count=3), min_discs=1)

    def test_rejects_too_few_discs(self):
        with self.assertRaisesRegex(ListShapeError, "10900"):
            read_redump_zip(make_list(self.tmp, TWO_GAMES))

    def test_rejects_two_members(self):
        path = make_list(self.tmp, TWO_GAMES, members={"a.dat": dat_xml(TWO_GAMES), "b.dat": dat_xml(TWO_GAMES)})
        with self.assertRaises(ListShapeError):
            read_redump_zip(path, min_discs=1)

    def test_rejects_member_not_a_dat(self):
        path = make_list(self.tmp, TWO_GAMES, members={"list.xml": dat_xml(TWO_GAMES)})
        with self.assertRaises(ListShapeError):
            read_redump_zip(path, min_discs=1)

    def test_rejects_not_a_zip(self):
        path = Path(self.tmp) / "list.zip"
        path.write_text("<html><body>503 Service Unavailable</body></html>")
        with self.assertRaisesRegex(ListShapeError, "zip"):
            read_redump_zip(path, min_discs=1)

    def test_rejects_truncated_zip(self):
        path = make_list(self.tmp, TWO_GAMES * 50)
        data = path.read_bytes()
        path.write_bytes(data[: len(data) // 2])
        with self.assertRaises(ListShapeError):
            read_redump_zip(path, min_discs=1)

    def test_rejects_bad_xml(self):
        path = make_list(self.tmp, TWO_GAMES, members={"list.dat": dat_xml(TWO_GAMES)[:-30]})
        with self.assertRaises(ListShapeError):
            read_redump_zip(path, min_discs=1)

    def test_rejects_list_without_serials(self):
        games = [game_xml("A (USA)"), game_xml("B (USA)")]
        with self.assertRaisesRegex(ListShapeError, "serial,version"):
            read_redump_zip(make_list(self.tmp, games), min_discs=1)

    def test_rejects_bad_md5(self):
        games = [game_xml("A (USA)", serial="SLUS-00001", tracks=((1, "XYZ"),))]
        with self.assertRaisesRegex(ListShapeError, "md5"):
            read_redump_zip(make_list(self.tmp, games), min_discs=1)

    def test_rejects_disc_without_tracks(self):
        games = [game_xml("A (USA)", serial="SLUS-00001", tracks=())]
        with self.assertRaisesRegex(ListShapeError, "A \\(USA\\)"):
            read_redump_zip(make_list(self.tmp, games), min_discs=1)


class SerialTests(unittest.TestCase):
    def test_core_serial(self):
        self.assertEqual(core_serial("SLPM 86770"), "SLPM-86770")
        self.assertEqual(core_serial("SCES-02066"), "SCES-02066")
        self.assertEqual(core_serial(" SCES-02066 "), "SCES-02066")
        for odd in [
            "SLUS-01041GH",
            "SLES-03428-GER",
            "SLES-12345#",
            "LSP-123456-1.1",
            "DTL-S1234",
            "slus-00001",
            "SLPM  86770",
            "",
        ]:
            self.assertIsNone(core_serial(odd), odd)

    def test_first_core_serial_skips_odd_ones(self):
        disc = Disc("X", "Games", ("SLUS-01041GH", "SLUS-01041"), "", ((1, md5(1)),))
        self.assertEqual(first_core_serial(disc), "SLUS-01041")
        self.assertIsNone(first_core_serial(Disc("X", "Games", ("DTL-S1234",), "", ((1, md5(1)),))))
        self.assertIsNone(first_core_serial(Disc("X", "Games", (), "", ((1, md5(1)),))))


def rdisc(name, *serials, tracks=None, category="Games", version=""):
    return Disc(name, category, tuple(serials), version, tracks or ((1000, md5(zlib.crc32(name.encode()))),))


KNOWN_TRACKS = ((5000, md5(5000)), (60, md5(60)))
DISCS = [
    DiscEntry("SLUS-00010", 1, "Known (USA)", (((10, md5(10)),), KNOWN_TRACKS)),
]
GAMES = [
    GameEntry("SLUS-00010", 1, ()),
    GameEntry("SLUS-00020", 3, ()),
    GameEntry("SLUS-00030", 5, ("SLUS-00030", "SLUS-00031")),
]


def plan(*discs, games=GAMES, known=DISCS, skip=frozenset()):
    return plan_import(RedumpList(VERSION, tuple(discs)), list(games), list(known), set(skip))


class PlanTests(unittest.TestCase):
    def test_known_disc_is_not_added(self):
        result = plan(rdisc("Known Again (Europe)", "SLES-00001", tracks=KNOWN_TRACKS))
        self.assertEqual(result.added, ())
        self.assertEqual((result.total, result.known), (1, 1))

    def test_partly_known_disc_is_added(self):
        disc = rdisc("Partly (Japan)", "SLPS-00001", tracks=((5000, md5(5000)), (70, md5(70))))
        self.assertEqual(plan(disc).added, (Added("SLPS-00001", disc, True),))

    def test_track_order_matters(self):
        disc = rdisc("Swapped (Japan)", "SLPS-00001", tracks=tuple(reversed(KNOWN_TRACKS)))
        self.assertEqual(len(plan(disc).added), 1)

    def test_spaced_serial_added_normalised(self):
        disc = rdisc("Bio Hazard (Japan)", "SLPM 86770", "SLPS 00222")
        self.assertEqual(plan(disc).added, (Added("SLPM-86770", disc, True),))

    def test_gamedb_entry_only_when_unknown(self):
        as_key = rdisc("A (USA)", "SLUS-00020")
        as_code = rdisc("B (USA)", "SLUS-00031")
        result = plan(as_key, as_code)
        self.assertEqual(result.added, (Added("SLUS-00020", as_key, False), Added("SLUS-00031", as_code, False)))

    def test_no_serial(self):
        disc = rdisc("Prototype (USA)")
        result = plan(disc)
        self.assertEqual((result.added, result.no_serial), ((), (disc,)))

    def test_odd_serial(self):
        disc = rdisc("Greatest Hits (USA)", "SLUS-01041GH")
        result = plan(disc)
        self.assertEqual((result.added, result.odd_serial), ((), (disc,)))

    def test_known_serial_new_tracks(self):
        disc = rdisc("Known (USA) (Beta)", "SLUS-00010")
        result = plan(disc)
        self.assertEqual((result.added, result.known_serial), ((), (("SLUS-00010", disc),)))

    def test_two_new_discs_one_serial(self):
        first = rdisc("Same (Japan)", "SLPS-00009")
        second = rdisc("Same (Japan) (Rev 1)", "SLPS-00009", version="Rev 1")
        result = plan(second, first)
        self.assertEqual(result.added, (Added("SLPS-00009", first, True),))
        self.assertEqual(result.known_serial, (("SLPS-00009", second),))

    def test_skip_list(self):
        by_name = rdisc("Rejected (USA)", "SLUS-00101")
        by_printed = rdisc("Spaced (Japan)", "SLPM 86770")
        by_core = rdisc("Spaced Too (Japan)", "SLPM 86771")
        kept = rdisc("Kept (USA)", "SLUS-00102")
        result = plan(by_name, by_printed, by_core, kept, skip={"Rejected (USA)", "SLPM 86770", "SLPM-86771"})
        self.assertEqual(result.added, (Added("SLUS-00102", kept, True),))
        self.assertEqual(result.skipped, (by_name, by_printed, by_core))

    def test_known_disc_on_skip_list_counts_as_known(self):
        result = plan(rdisc("Known Again (Europe)", "SLES-00001", tracks=KNOWN_TRACKS), skip={"SLES-00001"})
        self.assertEqual((result.known, result.skipped), (1, ()))

    def test_read_skip_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "skip.txt"
            path.write_text("# comment\n\nRejected (USA)\n  SLPM 86770  \n")
            self.assertEqual(read_skip_list(path), {"Rejected (USA)", "SLPM 86770"})

    def test_nothing_new(self):
        result = plan(rdisc("Known Again (Europe)", "SLES-00001", tracks=KNOWN_TRACKS))
        self.assertEqual(result, ImportPlan(VERSION, 1, 1, (), (), (), (), ()))

    def test_plan_is_stable(self):
        discs = [rdisc(f"Game {i} (USA)", f"SLUS-{i + 200:05d}") for i in range(5)] + [rdisc("Z (USA)")]
        self.assertEqual(plan(*discs), plan(*reversed(discs)))
        self.assertEqual([a.disc.name for a in plan(*reversed(discs)).added], [f"Game {i} (USA)" for i in range(5)])


GAMEDB_TEXT = 'SLUS-00010:\n  name: "Known (USA)"\n'
DISCDB_TEXT = 'SLUS-00010:\n  name: "Known (USA)"\n  trackData:\n    - tracks:\n        - size: 5000\n          md5: "%s"\n' % md5(5000)


def resources(tmp):
    folder = Path(tmp) / "resources"
    folder.mkdir()
    (folder / "gamedb.yaml").write_text(GAMEDB_TEXT)
    (folder / "discdb.yaml").write_text(DISCDB_TEXT)
    return folder


class WriteTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = self._tmp.name

    def tearDown(self):
        self._tmp.cleanup()

    def test_render_discdb_entry(self):
        disc = rdisc("New (Japan)", "SLPS 00001", tracks=((100, md5(1)), (200, md5(2))))
        self.assertEqual(
            render_discdb_entry(Added("SLPS-00001", disc, True)),
            "SLPS-00001:\n"
            '  name: "New (Japan)"\n'
            "  trackData:\n"
            "    - tracks:\n"
            "        - size: 100\n"
            f'          md5: "{md5(1)}"\n'
            "        - size: 200\n"
            f'          md5: "{md5(2)}"\n',
        )

    def test_render_discdb_entry_with_version(self):
        disc = rdisc("New (Japan) (Rev 1)", "SLPS-00001", tracks=((100, md5(1)),), version="Rev 1")
        self.assertEqual(
            render_discdb_entry(Added("SLPS-00001", disc, True)),
            "SLPS-00001:\n"
            '  name: "New (Japan) (Rev 1)"\n'
            "  trackData:\n"
            '    - version: "Rev 1"\n'
            "      tracks:\n"
            "        - size: 100\n"
            f'          md5: "{md5(1)}"\n',
        )

    def test_render_gamedb_entry(self):
        added = Added("SLPS-00001", rdisc("New (Japan)", "SLPS-00001"), True)
        self.assertEqual(render_gamedb_entry(added), 'SLPS-00001:\n  name: "New (Japan)"\n')

    def test_name_escaping(self):
        for name in ['A "B" C', "C:\\X", "Key: value # not a comment", "'quoted", "Back\\\"slash"]:
            added = Added("SLPS-00001", rdisc(name, "SLPS-00001"), True)
            self.assertEqual(parse_discdb(render_discdb_entry(added))[0].name, name)
            self.assertEqual(parse_gamedb(render_gamedb_entry(added))[0].key, "SLPS-00001")
        with self.assertRaises(ListShapeError):
            yaml_quote("Tab\there")

    def test_apply_plan_appends_only(self):
        folder = resources(self.tmp)
        new = rdisc("New (Japan)", "SLPS 00001", tracks=((100, md5(1)),))
        known_code = rdisc("Also New (USA)", "SLUS-00010B", "SLUS-00011")
        result = plan(new, known_code, games=[GameEntry("SLUS-00010", 1, ("SLUS-00010", "SLUS-00011"))], known=[])
        apply_plan(result, folder)
        discdb = (folder / "discdb.yaml").read_text()
        gamedb = (folder / "gamedb.yaml").read_text()
        self.assertEqual(discdb, DISCDB_TEXT + "".join(render_discdb_entry(a) for a in result.added))
        self.assertEqual(gamedb, GAMEDB_TEXT + render_gamedb_entry(result.added[1]))
        self.assertEqual([a.serial for a in result.added], ["SLUS-00011", "SLPS-00001"])

    def test_apply_nothing_leaves_files(self):
        folder = resources(self.tmp)
        apply_plan(plan(), folder)
        self.assertEqual((folder / "discdb.yaml").read_text(), DISCDB_TEXT)
        self.assertEqual((folder / "gamedb.yaml").read_text(), GAMEDB_TEXT)


class SummaryTests(unittest.TestCase):
    def test_pr_title(self):
        self.assertEqual(pr_title(plan()), "GameDB: redump import 2026-06-15")

    def test_summary_sections(self):
        result = plan(
            rdisc("New Game (Japan)", "SLPS-00001"),
            rdisc("New Demo (Japan)", "SLPM-80001", category="Demos"),
            rdisc("Known Again (Europe)", "SLES-00001", tracks=KNOWN_TRACKS),
            rdisc("Prototype (USA)", category="Preproduction"),
            rdisc("Greatest Hits (USA)", "SLUS-01041GH"),
        )
        summary = render_summary(result)
        self.assertIn("2026-06-15 11-55-46", summary)
        self.assertIn("5 discs", summary)
        self.assertIn("| Demos | 1 |", summary)
        self.assertIn("| Games | 1 |", summary)
        self.assertIn("- `SLPS-00001` New Game (Japan) (Games)", summary)
        self.assertIn("### Not added: no serial (1)", summary)
        self.assertIn("- Prototype (USA) (Preproduction)", summary)
        self.assertIn("### Not added: serial not in the core's form (1)", summary)
        self.assertIn("SLUS-01041GH", summary)
        self.assertNotIn("known serial, new tracks", summary)
        self.assertNotIn("Skipped", summary)
        self.assertIn("redump-skip.txt", summary)

    def test_summary_known_serial_and_skipped(self):
        summary = render_summary(
            plan(rdisc("Known (USA) (Beta)", "SLUS-00010"), rdisc("No (USA)", "SLUS-00500"), skip={"No (USA)"})
        )
        self.assertIn("### Not added: known serial, new tracks (1)", summary)
        self.assertIn("- `SLUS-00010` Known (USA) (Beta)", summary)
        self.assertIn("### Skipped (redump-skip.txt) (1)", summary)

    def test_summary_truncates(self):
        result = plan(*[rdisc(f"Unlabelled Prototype Number {i:04d} (USA)") for i in range(2000)])
        summary = render_summary(result, limit=5_000)
        self.assertLessEqual(len(summary), 5_000)
        self.assertRegex(summary, r"- …and \d+ more")
        self.assertIn("Unlabelled Prototype Number 1999", render_summary(result, limit=None))


class MainTests(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.folder = resources(self.tmp)
        self.skip = self.tmp / "skip.txt"
        self.skip.write_text("# nothing\n")
        self.list = make_list(self.tmp, [game_xml("New (Japan)", serial="SLPS 00001")])

    def tearDown(self):
        self._tmp.cleanup()

    def run_main(self, *extra):
        args = ["--list", str(self.list), "--resources", str(self.folder), "--skip", str(self.skip)]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return main(args + ["--min-discs", "1", *extra])

    def test_main_dry_run(self):
        output = self.tmp / "output.txt"
        summary = self.tmp / "summary.md"
        self.assertEqual(self.run_main("--dry-run", "--summary", str(summary), "--github-output", str(output)), 0)
        self.assertEqual((self.folder / "discdb.yaml").read_text(), DISCDB_TEXT)
        self.assertIn("`SLPS-00001` New (Japan)", summary.read_text())
        self.assertEqual(output.read_text(), "added=1\ntitle=GameDB: redump import 2026-06-15\n")

    def test_main_writes(self):
        full = self.tmp / "full.md"
        self.assertEqual(self.run_main("--full-summary", str(full)), 0)
        self.assertIn("SLPS-00001:", (self.folder / "discdb.yaml").read_text())
        self.assertIn("SLPS-00001:", (self.folder / "gamedb.yaml").read_text())
        self.assertIn("New (Japan)", full.read_text())

    def test_main_bad_list(self):
        self.list.write_text("<html>not a zip</html>")
        self.assertEqual(self.run_main(), 2)
        self.assertEqual((self.folder / "discdb.yaml").read_text(), DISCDB_TEXT)

    def test_main_bad_database(self):
        (self.folder / "gamedb.yaml").write_text("SLUS-00010:\n  codes: [A]\n")
        self.assertEqual(self.run_main(), 2)


if __name__ == "__main__":
    unittest.main()
