#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Writes GooseStation's patch marks for the bundled cheat database.

Reads data/resources/chtdb.txt and prints chtdb_patches.txt: one line per patch code and serial,
`serial<TAB>Group\\Name<TAB>kind and needs`, where the kind is `widescreen` (with its ratio), `framerate` (with an
overclock when one is known) or `displaymode`, and `choice=<title>` joins codes that are alternatives of each other.

Codes are picked by their names, and every call that isn't a plain name rule is in the tables below: names that look
like patches but are cheats, overclocks measured on the user's library, and the ratio of unusual widescreen codes.
The database itself is never changed.

    python3 -I scripts/generate_patch_marks.py data/resources/chtdb.txt > data/resources/chtdb_patches.txt
"""

import re
import sys

WIDESCREEN = re.compile(r"wide ?screen|16[-:x/]9|16[-:]10|21:9|eyefinity", re.I)
FRAME_RATE = re.compile(r"\d+ ?fps|fps|frame ?rate|uncap", re.I)
DISPLAY_MODE = re.compile(r"pal ?2 ?ntsc|ntsc mode|black bars|\b60 ?hz\b", re.I)

# Names (after the last backslash, notes included) that the rules above catch, but that aren't patches.
NOT_PATCHES = [
    re.compile(p, re.I)
    for p in (
        r"^uncapped fireball",           # a weapon
        r"^uncap weapon",                # firing speed
        r"^uncap ice",                   # firing speed
        r"slowmotion",                   # slow motion
        r"^30 FPS \"Slow motion",        # slow motion
        r"^turbo mode",                  # game speed
        r"^remove dither \(use with widescreen code\)",  # a dither tweak, not the widescreen code
        r"^increase track detail",       # detail, mentions overclocking
    )
]

# Overclocks (percent) measured on the user's library on 2026-11-28 (D3D11 regtest on pcsx2-win, from a gameplay
# state): the lowest that holds the patch's rate in every 600-frame window (594 of 600 for 60 fps, all 300 for 30), or
# "none" where it holds without one. Each applies to every serial of the code's block.
MEASURED_OVERCLOCK = {
    # Crash Bandicoot - Warped (USA), in the Time Twister hub: 130% dips to 572, 135% holds 598-622. The code uncaps
    # the game, so more runs it faster than 60.
    "Enable 60 FPS (Play with Overclock to get preferred speed)": 135,
    "Enable 60 FPS Alternative (Play with Overclock to get preferred speed)": 135,
    # Parasite Eve II (USA), at the shooting range: 600 of 600 at 100%.
    "Force and cap game to 60 FPS": "none",
    # King's Field II (USA), on the first beach: the 60 fps uncap dips to 591 at 190% and holds 607-613 at 195% (its
    # name's 160% gives about 50 fps); the 30 fps uncap holds 300 at 180% (its name says 200%).
    "Uncap Game at 60 FPS (160 Overclock for stable 60 FPS)": 195,
    "Uncap game at 30 FPS (200 Overclock for stable 30 FPS)": 180,
}

# The measured games' serials, so a measurement applies only to them (other games may share a code's name).
MEASURED_SERIALS = {
    "Enable 60 FPS (Play with Overclock to get preferred speed)": {"SCUS-94244", "SCUS-94244CE"},
    "Enable 60 FPS Alternative (Play with Overclock to get preferred speed)": {"SCUS-94244", "SCUS-94244CE"},
    "Force and cap game to 60 FPS": {"SLUS-01042", "SLUS-01055"},
    "Uncap Game at 60 FPS (160 Overclock for stable 60 FPS)": {"SLUS-00255"},
    "Uncap game at 30 FPS (200 Overclock for stable 30 FPS)": {"SLUS-00255"},
}

# Ratios for widescreen codes whose names don't say 16:9.
RATIOS = [
    (re.compile(r"21:9", re.I), "21:9"),
    (re.compile(r"16[-:]10", re.I), "16:10"),
    (re.compile(r"eyefinity", re.I), "48:9"),
]

# A name's words that only say which variant of a widescreen code it is, so codes whose names differ only by these
# replace each other (one choice), where other widescreen codes of a game combine (menus, 2P, an optional extra).
VARIANT_MARKERS = re.compile(
    r"\((?:vert-[^)]*|alt|adjusted|rev 1[^)]*|normal release|for rev 1[^)]*|ntsc-j [\d.]+)\)"
    r"|\[alt\]|\balt\b|\b2\.0\b|16[-:]9|21:9|16[-:]10",
    re.I,
)

OVERCLOCK_IN_NAME = re.compile(r"(\d{2,3}) ?%? ?overclock", re.I)


def read_blocks(path):
    """The database's blocks: ([serials], [(key, lines)]), each code's key as the core reads it."""
    blocks = []
    serials, codes = [], []
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith(";"):
                continue
            if line.startswith(":"):
                if codes:
                    blocks.append((serials, codes))
                    serials, codes = [], []
                serials.append(line[1:])
            elif line.startswith("#"):
                codes.append(line[1:])
    if codes:
        blocks.append((serials, codes))
    return blocks


def split_key(key):
    slash = key.rfind("\\")
    return (key[:slash], key[slash + 1 :]) if slash >= 0 else ("", key)


def kind_of(key):
    group, name = split_key(key)
    if any(p.search(name) for p in NOT_PATCHES):
        return None
    if WIDESCREEN.search(key):
        return "widescreen"
    if DISPLAY_MODE.search(key):
        return "displaymode"
    if FRAME_RATE.search(key):
        return "framerate"
    return None


def variant_base(name):
    # A note follows a colon, but not the colon of a ratio ("16:9 Widescreen").
    base = re.split(r"(?<!\d):|:(?!\d)", name, maxsplit=1)[0]
    base = VARIANT_MARKERS.sub(" ", base)
    return " ".join(base.lower().split())


def marks_for_block(serials, codes):
    patches = [(key, kind_of(key)) for key in dict.fromkeys(codes)]
    patches = [(key, kind) for key, kind in patches if kind]

    choice = {}
    # Codes under a "Select …" group are one choice.
    for key, _ in patches:
        group, _ = split_key(key)
        if group.lower().startswith("select "):
            choice[key] = group
    # A game's ungrouped frame-rate codes are alternatives: a game runs at one rate.
    rates = [key for key, kind in patches if kind == "framerate" and key not in choice and not split_key(key)[0]]
    if len(rates) > 1:
        for key in rates:
            choice[key] = "Frame rate"
    # Ungrouped widescreen codes whose names differ only by a variant's words.
    wides = {}
    for key, kind in patches:
        if kind == "widescreen" and key not in choice and not split_key(key)[0]:
            wides.setdefault(variant_base(split_key(key)[1]), []).append(key)
    for keys in wides.values():
        if len(keys) > 1:
            for key in keys:
                choice[key] = "Widescreen"

    lines = []
    for serial in serials:
        if serial == "UNKNOWN":
            continue
        for key, kind in patches:
            field = [kind]
            if kind == "widescreen":
                field.append(next((ratio for p, ratio in RATIOS if p.search(key)), "16:9"))
            elif kind == "framerate":
                overclock = MEASURED_OVERCLOCK.get(key) if serial in MEASURED_SERIALS.get(key, ()) else None
                if overclock is None:
                    m = OVERCLOCK_IN_NAME.search(key)
                    overclock = int(m.group(1)) if m else None
                if overclock is not None:
                    field.append(f"overclock={overclock}")
            if key in choice:
                field.append(f"choice={choice[key]}")
            lines.append(f"{serial}\t{key}\t{' '.join(field)}")
    return lines


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: generate_patch_marks.py path/to/chtdb.txt")

    out = [
        "; GooseStation's patch marks for chtdb.txt, written by scripts/generate_patch_marks.py: don't edit by hand.",
        "; One line per patch code and serial: serial<TAB>Group\\Name<TAB>kind and needs. Kinds: widescreen <ratio>,",
        "; framerate [overclock=<percent>], displaymode; choice=<title> joins codes of which only one can be on.",
    ]
    seen = set()
    read_serials = set()
    for serials, codes in read_blocks(sys.argv[1]):
        # The core reads a serial's first block only.
        serials = [s for s in serials if s not in read_serials]
        read_serials.update(serials)
        for line in marks_for_block(serials, codes):
            key = line.rsplit("\t", 1)[0]
            if key not in seen:
                seen.add(key)
                out.append(line)
    sys.stdout.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
