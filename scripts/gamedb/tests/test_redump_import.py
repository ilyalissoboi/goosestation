# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.sax.saxutils import quoteattr

from redump_import import (
    Disc,
    ListShapeError,
    core_serial,
    first_core_serial,
    read_redump_zip,
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


def dat_xml(games, *, name="Sony - PlayStation", count=None):
    count = len(games) if count is None else count
    return "\n".join(
        [
            '<?xml version="1.0"?>',
            "<datafile>",
            "\t<header>",
            f"\t\t<name>{name}</name>",
            f"\t\t<description>{name} - Discs ({count}) ({VERSION})</description>",
            f"\t\t<version>{VERSION}</version>",
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


if __name__ == "__main__":
    unittest.main()
