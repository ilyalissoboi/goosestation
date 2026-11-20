#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Turns docs/releases/MAJOR.MINOR.md into a GitHub release's notes.

GitHub shows every line break in a release's notes as a break, so the file's wrapped lines would come out broken:
each paragraph and list item is joined onto one line. Headings, blank lines and fenced code stay as they are, and
the licence header is left out.

    release_notes.py docs/releases/0.2.md <commit> > notes.md
"""

import re
import sys

# A line that starts a block of its own rather than continuing the one before.
BLOCK_START = re.compile(r"(#{1,6} |[-*+] |\d+[.)] |>|\|)")
# A code fence: three or more backticks or tildes. Only a fence of the same character, at least as long and with
# nothing after it, closes one.
FENCE = re.compile(r"`{3,}|~{3,}")


def unwrap(text: str) -> str:
    out: list[str] = []
    joinable = False  # whether the last line out is prose a next line may continue
    fence = ""  # the open code fence's delimiter, if any
    for line in text.splitlines():
        stripped = line.strip()
        if fence:
            out.append(line)
            if FENCE.fullmatch(stripped) and stripped[0] == fence[0] and len(stripped) >= len(fence):
                fence = ""
        elif stripped.startswith("<!-- SPDX-"):
            continue
        elif opening := FENCE.match(stripped):
            fence = opening.group(0)
            out.append(line)
            joinable = False
        elif not stripped:
            out.append("")
            joinable = False
        elif joinable and not BLOCK_START.match(stripped):
            out[-1] += " " + stripped
        else:
            out.append(line.rstrip())
            joinable = not stripped.startswith("#")
    return "\n".join(out).strip("\n")


def release_body(text: str, commit: str) -> str:
    return f"{unwrap(text)}\n\nBuilt from {commit}.\n"


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__.strip())
    with open(sys.argv[1], encoding="utf-8") as f:
        sys.stdout.write(release_body(f.read(), sys.argv[2]))
