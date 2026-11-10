#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Reads the game database's two files and checks their keys and codes.

The files have a fixed layout (see docs/gamedb.md), and these readers accept only that layout: a line they don't
understand is an error, so a reformatted file fails here instead of being misread. yamllint checks that the files
are valid YAML; this checks what yamllint can't:

- no key appears twice in a file;
- no serial is a ``codes`` entry of two gamedb.yaml entries;
- no gamedb.yaml key is another entry's code.

Whichever entry the core loads first owns a code, so a clash makes a disc find the wrong entry. Clashes that came with
the base's database are listed in known-clashes.txt and left as they are. Shared track hashes in discdb.yaml are
normal and not checked.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_RESOURCES = REPO / "data" / "resources"
DEFAULT_KNOWN_CLASHES = REPO / "scripts" / "gamedb" / "known-clashes.txt"

_KEY = re.compile(r"^([^\s#]\S*):$")
_CODE = re.compile(r"^    - ([A-Za-z0-9][A-Za-z0-9-]*)$")
_QUOTED = r'"((?:[^"\\]|\\["\\])*)"'
_NAME = re.compile(r"^  name: " + _QUOTED + "$")
_VERSION = re.compile(r"^    - version: " + _QUOTED + "$")
_SIZE = re.compile(r"^        - size: ('?)(\d+)\1$")
_MD5 = re.compile(r'^          md5: "([0-9a-f]{32})"$')


class FormatError(Exception):
    """A line the reader doesn't understand."""


@dataclass(frozen=True)
class GameEntry:
    key: str
    line: int
    codes: tuple[str, ...]


@dataclass(frozen=True)
class DiscEntry:
    key: str
    line: int
    name: str
    track_sets: tuple[tuple[tuple[int, str], ...], ...]


def _lines(text: str, path: str) -> list[str]:
    if not text.endswith("\n"):
        raise FormatError(f"{path}: the file doesn't end in a newline")
    lines = text[:-1].split("\n")
    for number, line in enumerate(lines, 1):
        if not line.strip():
            raise FormatError(f"{path}:{number}: blank line")
    return lines


def _unquote(quoted: str) -> str:
    return re.sub(r"\\([\"\\])", r"\1", quoted)


def parse_gamedb(text: str, path: str = "gamedb.yaml") -> list[GameEntry]:
    entries: list[GameEntry] = []
    key: str | None = None
    key_line = 0
    codes: list[str] | None = None
    in_codes = False

    def finish() -> None:
        if key is not None:
            entries.append(GameEntry(key, key_line, tuple(codes or ())))

    for number, line in enumerate(_lines(text, path), 1):
        if in_codes:
            match = _CODE.match(line)
            if match:
                codes.append(match.group(1))
                continue
            if line.startswith("   "):  # a comment, a quoted code, deeper indentation: never guess at codes
                raise FormatError(f"{path}:{number}: codes must be plain '    - SERIAL' lines")
            if not codes:
                raise FormatError(f"{path}:{number}: codes has no entries")
            in_codes = False

        if not line.startswith(" "):
            match = _KEY.match(line)
            if not match:
                raise FormatError(f"{path}:{number}: expected a serial key")
            finish()
            key, key_line, codes = match.group(1), number, None
            continue

        if key is None or not line.startswith("  "):
            raise FormatError(f"{path}:{number}: unexpected indentation")
        if line.startswith("  codes") and not line.startswith("   "):
            if line != "  codes:" or codes is not None:
                raise FormatError(f"{path}:{number}: codes must be a single block list")
            codes = []
            in_codes = True

    if in_codes and not codes:
        raise FormatError(f"{path}: codes has no entries at the end of the file")
    finish()
    return entries


def parse_discdb(text: str, path: str = "discdb.yaml") -> list[DiscEntry]:
    entries: list[DiscEntry] = []
    lines = _lines(text, path)
    i = 0

    def expect(pattern: re.Pattern[str] | str, what: str) -> re.Match[str] | None:
        nonlocal i
        line = lines[i] if i < len(lines) else ""
        match = pattern.match(line) if isinstance(pattern, re.Pattern) else (line == pattern or None)
        if not match:
            raise FormatError(f"{path}:{i + 1}: expected {what}")
        i += 1
        return match if isinstance(match, re.Match) else None

    while i < len(lines):
        key_line = i + 1
        key = expect(_KEY, "a serial key").group(1)
        name = _unquote(expect(_NAME, "a quoted name").group(1))
        expect("  trackData:", "trackData")
        track_sets = []
        while i < len(lines) and lines[i].startswith("    - "):
            if lines[i] == "    - tracks:":
                i += 1
            else:
                expect(_VERSION, "tracks or version")
                expect("      tracks:", "tracks")
            tracks = []
            while i < len(lines) and lines[i].startswith("        - "):
                size = int(expect(_SIZE, "a track size").group(2))
                md5 = expect(_MD5, "a track md5").group(1)
                tracks.append((size, md5))
            if not tracks:
                raise FormatError(f"{path}:{i + 1}: a track set without tracks")
            track_sets.append(tuple(tracks))
        if not track_sets:
            raise FormatError(f"{path}:{i + 1}: {key} has no track sets")
        entries.append(DiscEntry(key, key_line, name, tuple(track_sets)))
    return entries


def _duplicate_keys(entries: list[GameEntry] | list[DiscEntry], path: str) -> list[str]:
    lines = defaultdict(list)
    for entry in entries:
        lines[entry.key].append(entry.line)
    return [
        f"duplicate key {key} in {path} (lines {', '.join(map(str, found))})"
        for key, found in lines.items()
        if len(found) > 1
    ]


def find_problems(games: list[GameEntry], discs: list[DiscEntry], known_clashes: set[str]) -> list[str]:
    problems = _duplicate_keys(games, "gamedb.yaml") + _duplicate_keys(discs, "discdb.yaml")

    owners = defaultdict(list)
    for entry in games:
        for code in dict.fromkeys(entry.codes):
            owners[code].append(entry.key)

    for code, keys in owners.items():
        if len(keys) > 1 and code not in known_clashes:
            problems.append(f"code {code} is in {', '.join(keys)}")

    for entry in games:
        if entry.key in known_clashes:
            continue
        others = [key for key in owners.get(entry.key, []) if key != entry.key]
        if others:
            problems.append(f"key {entry.key} is also a code of {', '.join(others)}")

    return problems


def read_known_clashes(path: Path) -> set[str]:
    serials = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            serials.add(line)
    return serials


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check the game database's keys and codes.")
    parser.add_argument("--resources", type=Path, default=DEFAULT_RESOURCES, help="folder with the two files")
    parser.add_argument("--known-clashes", type=Path, default=DEFAULT_KNOWN_CLASHES, help="clashes to allow")
    args = parser.parse_args(argv)

    try:
        games = parse_gamedb((args.resources / "gamedb.yaml").read_text(encoding="utf-8"))
        discs = parse_discdb((args.resources / "discdb.yaml").read_text(encoding="utf-8"))
    except FormatError as error:
        print(f"gamedb check: {error}", file=sys.stderr)
        return 1

    known = read_known_clashes(args.known_clashes)
    problems = find_problems(games, discs, known)
    for problem in problems:
        print(f"gamedb check: {problem}", file=sys.stderr)
    if problems:
        return 1

    print(f"gamedb check: {len(games)} games, {len(discs)} discs, no new clashes ({len(known)} known)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
