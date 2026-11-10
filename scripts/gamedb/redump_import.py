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

import argparse
import re
import sys
from collections import Counter
import zipfile
import zlib
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gamedb_check import (  # noqa: E402
    DEFAULT_KNOWN_CLASHES,
    DEFAULT_RESOURCES,
    REPO,
    DiscEntry,
    FormatError,
    GameEntry,
    find_problems,
    parse_discdb,
    parse_gamedb,
    read_known_clashes,
)

MIN_DISCS = 10_900
MAX_DAT_BYTES = 64 * 1024 * 1024
HEADER_NAME = "Sony - PlayStation"
PR_BODY_LIMIT = 60_000  # GitHub allows 65,536 characters
DEFAULT_SKIP = REPO / "scripts" / "gamedb" / "redump-skip.txt"

_CORE_SERIAL = re.compile(r"^([A-Z]{4})[ -](\d{5})$")
_MD5 = re.compile(r"^[0-9a-f]{32}$")
_COUNT = re.compile(r"\((\d+)\) \([^()]*\)$")
_LIST_VERSION = re.compile(r"^\d{4}-\d{2}-\d{2}( \d{2}-\d{2}-\d{2})?$")


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

    version = (header.findtext("version") or "").strip()
    if not _LIST_VERSION.match(version):
        raise ListShapeError(f"the list's version {version!r} isn't a date")

    discs = tuple(_read_disc(game) for game in games)
    if not any(disc.serials for disc in discs):
        raise ListShapeError("no disc has a serial: this is the list without serials; download /datfile/psx/serial,version")
    return RedumpList(version=version, discs=discs)


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


def yaml_quote(text: str) -> str:
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in text):
        raise ListShapeError(f"a control character in {text!r}")
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def render_discdb_entry(added: Added) -> str:
    lines = [f"{added.serial}:", f"  name: {yaml_quote(added.disc.name)}", "  trackData:"]
    if added.disc.version:
        lines += [f"    - version: {yaml_quote(added.disc.version)}", "      tracks:"]
    else:
        lines.append("    - tracks:")
    for size, md5 in added.disc.tracks:
        lines += [f"        - size: {size}", f'          md5: "{md5}"']
    return "\n".join(lines) + "\n"


def render_gamedb_entry(added: Added) -> str:
    return f"{added.serial}:\n  name: {yaml_quote(added.disc.name)}\n"


def _append(path: Path, text: str) -> None:
    if text:
        with path.open("a", encoding="utf-8", newline="\n") as file:
            file.write(text)


def apply_plan(plan: ImportPlan, resources: Path) -> None:
    _append(resources / "discdb.yaml", "".join(render_discdb_entry(added) for added in plan.added))
    _append(resources / "gamedb.yaml", "".join(render_gamedb_entry(added) for added in plan.added if added.gamedb))


def pr_title(plan: ImportPlan) -> str:
    return f"GameDB: redump import {plan.list_version[:10]}"


def _sections(plan: ImportPlan) -> list[tuple[str, list[str]]]:
    def printed(disc: Disc) -> str:
        return ", ".join(f"`{serial}`" for serial in disc.serials)

    sections = [
        ("Added", [f"- `{a.serial}` {a.disc.name} ({a.disc.category})" for a in plan.added]),
        ("Not added: no serial", [f"- {d.name} ({d.category})" for d in plan.no_serial]),
        ("Not added: serial not in the core's form", [f"- {d.name} ({d.category}): {printed(d)}" for d in plan.odd_serial]),
        (
            "Not added: known serial, new tracks",
            [f"- `{serial}` {d.name} ({d.category})" for serial, d in plan.known_serial],
        ),
        ("Skipped (redump-skip.txt)", [f"- {d.name} ({d.category})" for d in plan.skipped]),
    ]
    return [(title, items) for title, items in sections if items]


def _render(plan: ImportPlan, shown: int | None) -> str:
    added = len(plan.added)
    gamedb = sum(a.gamedb for a in plan.added)
    not_added = plan.total - plan.known - added
    lines = [
        f"redump.org's PS1 list of {plan.list_version}: {plan.total} discs, {plan.known} already known. "
        f"This adds {added} discs to discdb.yaml ({gamedb} new name-only gamedb.yaml entries); "
        f"{not_added} new discs aren't added.",
        "",
    ]
    if plan.added:
        lines += ["| Category | Added |", "| --- | --- |"]
        categories = Counter(a.disc.category for a in plan.added)
        lines += [f"| {category} | {count} |" for category, count in sorted(categories.items())]
        lines.append("")
    for title, items in _sections(plan):
        lines.append(f"### {title} ({len(items)})")
        lines.append("")
        if shown is None or len(items) <= shown:
            lines += items
        else:
            lines += items[:shown]
            lines.append(f"- …and {len(items) - shown} more (the run's summary has the full list)")
        lines.append("")
    lines.append(
        "The list is redump.org's (http://redump.org/datfile/psx/serial,version); existing entries are unchanged. "
        "To reject a disc, add its name or serial to scripts/gamedb/redump-skip.txt on master. "
        "This job never merges; see docs/gamedb.md."
    )
    return "\n".join(lines) + "\n"


def render_summary(plan: ImportPlan, limit: int | None = PR_BODY_LIMIT) -> str:
    full = _render(plan, None)
    if limit is None or len(full) <= limit:
        return full
    low, high = 0, max((len(items) for _, items in _sections(plan)), default=0)
    while low < high:  # the most items per list that fit
        middle = (low + high + 1) // 2
        if len(_render(plan, middle)) <= limit:
            low = middle
        else:
            high = middle - 1
    return _render(plan, low)[:limit]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Add redump.org's new PS1 discs to the game database.")
    parser.add_argument("--list", type=Path, required=True, help="the downloaded serial,version list (zip)")
    parser.add_argument("--resources", type=Path, default=DEFAULT_RESOURCES, help="folder with the two files")
    parser.add_argument("--skip", type=Path, default=DEFAULT_SKIP, help="discs not to add")
    parser.add_argument("--known-clashes", type=Path, default=DEFAULT_KNOWN_CLASHES)
    parser.add_argument("--summary", type=Path, help="write the pull request's description here")
    parser.add_argument("--full-summary", type=Path, help="append the untruncated summary here")
    parser.add_argument("--github-output", type=Path, help="append added=<n> and title=<title> here")
    parser.add_argument("--min-discs", type=int, default=MIN_DISCS, help=argparse.SUPPRESS)
    parser.add_argument("--dry-run", action="store_true", help="change no files")
    args = parser.parse_args(argv)

    try:
        redump = read_redump_zip(args.list, args.min_discs)
        games = parse_gamedb((args.resources / "gamedb.yaml").read_text(encoding="utf-8"))
        discs = parse_discdb((args.resources / "discdb.yaml").read_text(encoding="utf-8"))
        plan = plan_import(redump, games, discs, read_skip_list(args.skip))
        if not args.dry_run:
            apply_plan(plan, args.resources)
    except (ListShapeError, FormatError) as error:
        print(f"redump import: {error}", file=sys.stderr)
        return 2

    if args.summary:
        args.summary.write_text(render_summary(plan), encoding="utf-8")
    if args.full_summary:
        _append(args.full_summary, render_summary(plan, limit=None))
    if args.github_output:
        _append(args.github_output, f"added={len(plan.added)}\ntitle={pr_title(plan)}\n")

    not_added = plan.total - plan.known - len(plan.added)
    gamedb = sum(a.gamedb for a in plan.added)
    print(
        f"{plan.list_version}: {len(plan.added)} discs added ({gamedb} gamedb.yaml entries), "
        f"{plan.known} known, {not_added} not added"
    )

    if not args.dry_run:
        games = parse_gamedb((args.resources / "gamedb.yaml").read_text(encoding="utf-8"))
        discs = parse_discdb((args.resources / "discdb.yaml").read_text(encoding="utf-8"))
        problems = find_problems(games, discs, read_known_clashes(args.known_clashes))
        for problem in problems:
            print(f"redump import: after writing: {problem}", file=sys.stderr)
        if problems:
            return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
