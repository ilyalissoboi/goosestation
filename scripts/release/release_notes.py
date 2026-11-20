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


def unwrap(text: str) -> str:
    out: list[str] = []
    joinable = False  # whether the last line out is prose a next line may continue
    in_fence = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("<!-- SPDX-"):
            continue
        if stripped.startswith("```"):
            in_fence = not in_fence
            out.append(line)
            joinable = False
        elif in_fence:
            out.append(line)
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
