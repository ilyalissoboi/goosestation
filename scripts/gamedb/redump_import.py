#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Adds the discs in redump.org's PS1 list that the game database doesn't know.

Reads a downloaded list (http://redump.org/datfile/psx/serial,version), and appends to discdb.yaml an entry for each
disc none of whose known track sets match, under its first serial in the core's form; gamedb.yaml gets a name-only
entry when that serial isn't a key or code there. Existing entries never change. Writes a summary for the pull
request, listing what wasn't added. The list is untrusted: run this with ``python3 -I``. See docs/gamedb.md.
"""

from __future__ import annotations

import re
import sys
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gamedb_check import DiscEntry, GameEntry  # noqa: E402

MIN_DISCS = 10_900
MAX_DAT_BYTES = 64 * 1024 * 1024
HEADER_NAME = "Sony - PlayStation"

_CORE_SERIAL = re.compile(r"^([A-Z]{4})[ -](\d{5})$")
_MD5 = re.compile(r"^[0-9a-f]{32}$")
_COUNT = re.compile(r"\((\d+)\) \([^()]*\)$")


class ListShapeError(Exception):
    """The downloaded list isn't the list we expect."""


@dataclass(frozen=True)
class Disc:
    name: str
    category: str
    serials: tuple[str, ...]
    version: str
    tracks: tuple[tuple[int, str], ...]


@dataclass(frozen=True)
class RedumpList:
    version: str
    discs: tuple[Disc, ...]


@dataclass(frozen=True)
class Added:
    serial: str
    disc: Disc
    gamedb: bool  # a name-only gamedb.yaml entry is added too


@dataclass(frozen=True)
class ImportPlan:
    list_version: str
    total: int
    known: int
    added: tuple[Added, ...]
    no_serial: tuple[Disc, ...]
    odd_serial: tuple[Disc, ...]
    known_serial: tuple[tuple[str, Disc], ...]  # the serial is taken: discdb.yaml has it, or an earlier new disc
    skipped: tuple[Disc, ...]


def core_serial(printed: str) -> str | None:
    match = _CORE_SERIAL.match(printed.strip())
    return f"{match.group(1)}-{match.group(2)}" if match else None


def first_core_serial(disc: Disc) -> str | None:
    for printed in disc.serials:
        serial = core_serial(printed)
        if serial:
            return serial
    return None


def _read_dat(path: Path) -> bytes:
    try:
        with zipfile.ZipFile(path) as archive:
            members = archive.infolist()
            if len(members) != 1 or not members[0].filename.endswith(".dat"):
                names = ", ".join(member.filename for member in members)
                raise ListShapeError(f"expected one .dat in the zip, found: {names or 'nothing'}")
            if members[0].file_size > MAX_DAT_BYTES:
                raise ListShapeError(f"the .dat is {members[0].file_size} bytes, over {MAX_DAT_BYTES}")
            return archive.read(members[0])
    except (zipfile.BadZipFile, EOFError, zlib.error) as error:
        raise ListShapeError(f"not a readable zip: {error}") from error


def _read_disc(game: ElementTree.Element) -> Disc:
    name = game.get("name") or ""
    serials = tuple(
        part.strip()
        for element in game.findall("serial")
        for part in (element.text or "").split(",")
        if part.strip()
    )
    tracks = []
    for rom in game.findall("rom"):
        if (rom.get("name") or "").lower().endswith(".cue"):
            continue
        size, md5 = rom.get("size") or "", rom.get("md5") or ""
        if not size.isdigit() or not _MD5.match(md5):
            raise ListShapeError(f"{name}: a track with size {size!r} and md5 {md5!r}")
        tracks.append((int(size), md5))
    if not name or not tracks:
        raise ListShapeError(f"a disc without a name or tracks: {name!r}")
    return Disc(
        name=name,
        category=(game.findtext("category") or "").strip(),
        serials=serials,
        version=(game.findtext("version") or "").strip(),
        tracks=tuple(tracks),
    )


def read_redump_zip(path: Path, min_discs: int = MIN_DISCS) -> RedumpList:
    try:
        root = ElementTree.fromstring(_read_dat(path))
    except ElementTree.ParseError as error:
        raise ListShapeError(f"the .dat isn't valid XML: {error}") from error

    header = root.find("header")
    if root.tag != "datafile" or header is None:
        raise ListShapeError("the .dat isn't a Logiqx datafile")
    name = (header.findtext("name") or "").strip()
    if name != HEADER_NAME:
        raise ListShapeError(f"the list is {name!r}, not {HEADER_NAME!r}")

    games = root.findall("game")
    count = _COUNT.search((header.findtext("description") or "").strip())
    if not count or int(count.group(1)) != len(games):
        stated = count.group(1) if count else "no count"
        raise ListShapeError(f"the header says {stated} discs, the list has {len(games)}")
    if len(games) < min_discs:
        raise ListShapeError(f"the list has {len(games)} discs, fewer than {min_discs}")

    discs = tuple(_read_disc(game) for game in games)
    if not any(disc.serials for disc in discs):
        raise ListShapeError("no disc has a serial: this is the list without serials; download /datfile/psx/serial,version")
    return RedumpList(version=(header.findtext("version") or "").strip(), discs=discs)


def read_skip_list(path: Path) -> set[str]:
    entries = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            entries.add(line)
    return entries


def _is_skipped(disc: Disc, skip: set[str]) -> bool:
    names = {disc.name, *disc.serials, *filter(None, map(core_serial, disc.serials))}
    return not names.isdisjoint(skip)


def plan_import(redump: RedumpList, games: list[GameEntry], discs: list[DiscEntry], skip: set[str]) -> ImportPlan:
    known_tracks = {track_set for entry in discs for track_set in entry.track_sets}
    taken = {entry.key for entry in discs}
    in_gamedb = {entry.key for entry in games} | {code for entry in games for code in entry.codes}

    known = 0
    added, no_serial, odd_serial, known_serial, skipped = [], [], [], [], []
    for disc in sorted(redump.discs, key=lambda disc: (disc.name, disc.tracks)):
        if disc.tracks in known_tracks:
            known += 1
        elif _is_skipped(disc, skip):
            skipped.append(disc)
        elif not disc.serials:
            no_serial.append(disc)
        elif (serial := first_core_serial(disc)) is None:
            odd_serial.append(disc)
        elif serial in taken:
            known_serial.append((serial, disc))
        else:
            taken.add(serial)
            added.append(Added(serial, disc, serial not in in_gamedb))

    return ImportPlan(
        list_version=redump.version,
        total=len(redump.discs),
        known=known,
        added=tuple(added),
        no_serial=tuple(no_serial),
        odd_serial=tuple(odd_serial),
        known_serial=tuple(known_serial),
        skipped=tuple(skipped),
    )
