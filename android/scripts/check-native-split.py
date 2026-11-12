#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0
"""Checks that splitting the native sources into files moved code without changing it.

    check-native-split.py BASE_REF CPP_DIR

Reads android_host.cpp and jni_bridge.cpp as they were at BASE_REF, and, in CPP_DIR now, what's left of them plus
every .cpp and .h that didn't exist at BASE_REF. Each is cut into top-level items: a function, a global, a type or a
macro, each with the comment block right above it, or a comment block standing alone. Before and after must hold the
same items, each exactly as many times, with these only ignored: the SPDX licence header; #include, #pragma once, using and Log_SetChannel
lines; namespace and extern "C" lines; a leading static or inline (a template's, after its head); and, in the new files, extern declarations and
function prototypes (what shares the moved state). Also checked: each NATIVE_LIBRARY_FUNCTION once, before and after;
the items a file took from one original in that original's order (so globals are still built in theirs); and each
file's log channel, its original's. Exits 0 when all hold, else prints what differs and exits 1.
"""

import collections
import os
import re
import subprocess
import sys

ORIGINALS = ("android_host.cpp", "jni_bridge.cpp")

_IGNORED_LINE = re.compile(r"^(#include\b|#pragma once\b|using\b|Log_SetChannel\()")
_WRAPPER_OPEN = re.compile(r'^(namespace(\s+[\w:]+)?\s*\{|extern "C"\s*\{)\s*(//.*)?$')
_WRAPPER_CLOSE = re.compile(r"^\}\s*(//.*)?$")
_CHANNEL = re.compile(r"^Log_SetChannel\((\w+)\);")
_NATIVE = re.compile(r"NATIVE_LIBRARY_FUNCTION\(\s*[\w:<>* ]+,\s*(\w+)\s*\)")
_LOGGING = re.compile(r"\b(ERROR|WARNING|INFO|DEV|VERBOSE|DEBUG)_LOG\(")


class Item:
    def __init__(self, text: str, file: str, index: int):
        self.text = text
        self.file = file
        self.index = index
        self.key = _normalise(text)

    @property
    def name(self) -> str:
        code = [line for line in self.text.split("\n") if line.strip() and not line.lstrip().startswith(("//", "/*", "*"))]
        return (code[0] if code else self.text.split("\n")[0]).strip()[:100]


def _normalise(text: str) -> str:
    lines = text.split("\n")
    for i, line in enumerate(lines):
        if line.strip() and not line.lstrip().startswith(("//", "/*", "*")):
            lines[i] = re.sub(r"^(static|inline)\s+", "", line)
            # A template's linkage is on the line after its template<...> head.
            if line.startswith("template") and i + 1 < len(lines):
                lines[i + 1] = re.sub(r"^(static|inline)\s+", "", lines[i + 1])
            break
    return "\n".join(lines)


def _depth_change(line: str, state: dict) -> int:
    """How much [line] opens braces and parentheses, outside strings, characters and comments; [state] carries a block
    comment open across lines."""
    depth = 0
    i = 0
    while i < len(line):
        c = line[i]
        if state["block"]:
            if line.startswith("*/", i):
                state["block"] = False
                i += 2
                continue
            i += 1
            continue
        if line.startswith("/*", i):
            state["block"] = True
            i += 2
            continue
        if line.startswith("//", i):
            break
        if c in "\"'":
            quote = c
            i += 1
            while i < len(line) and line[i] != quote:
                i += 2 if line[i] == "\\" else 1
            i += 1
            continue
        if c in "{(":
            depth += 1
        elif c in "})":
            depth -= 1
        i += 1
    return depth


def parse(text: str, file: str):
    """[file]'s top-level items, its log channel (or None), and whether it logs."""
    items = []
    channel = None
    comment = []
    lines = text.split("\n")
    i = 0

    def flush_comment():
        # The licence header, which every file has, isn't moved code.
        if comment and not all("SPDX-" in line for line in comment):
            items.append(Item("\n".join(comment), file, len(items)))
        comment.clear()

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            flush_comment()
            i += 1
            continue
        if line[0].isspace():
            raise SystemExit(f"{file}:{i + 1}: indented line outside an item: {line!r}")
        match = _CHANNEL.match(line)
        if match:
            channel = match.group(1)
        if _IGNORED_LINE.match(line) or _WRAPPER_OPEN.match(stripped) or _WRAPPER_CLOSE.match(stripped):
            flush_comment()
            i += 1
            continue
        if line.startswith("//"):
            comment.append(line)
            i += 1
            continue
        if line.startswith("/*"):
            while True:
                comment.append(lines[i])
                if "*/" in lines[i]:
                    break
                i += 1
            i += 1
            continue
        # An item: a macro to its last continued line, else up to where its braces and parentheses close on a line
        # ending in ; or }.
        body = []
        if line.startswith("#"):
            while True:
                body.append(lines[i])
                if not lines[i].endswith("\\"):
                    break
                i += 1
            i += 1
        else:
            depth = 0
            state = {"block": False}
            while True:
                body.append(lines[i])
                depth += _depth_change(lines[i], state)
                code = re.sub(r"//.*$", "", lines[i]).rstrip()
                if depth == 0 and not state["block"] and (code.endswith(";") or code.endswith("}")):
                    break
                # A macro call standing as a statement (a hotkey list's lines): it ends where the next line starts at
                # column 0 with anything but the { a function's signature goes on to,
                # or the file ends.
                following = lines[i + 1] if i + 1 < len(lines) else ""
                if depth == 0 and code.endswith(")") and following[:1] not in (" ", "\t", "{"):
                    break
                i += 1
                if i >= len(lines):
                    raise SystemExit(f"{file}: an item doesn't end: {body[0]!r}")
            i += 1
        items.append(Item("\n".join(comment + body), file, len(items)))
        comment.clear()
    flush_comment()
    return items, channel, bool(_LOGGING.search(text))


def _is_declaration(item: Item) -> bool:
    """What shares moved state in a new file: an extern declaration or a function prototype."""
    code = "\n".join(line for line in item.key.split("\n") if not line.lstrip().startswith("//"))
    return code.startswith("extern ") or ("{" not in code and code.rstrip().endswith(");"))


def _natives(items):
    return collections.Counter(n for item in items for n in _NATIVE.findall(item.text) if n not in ("ret", "name"))


def _git(repo: str, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True).stdout


def main(argv, repo: str = None, originals=ORIGINALS) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    base, cpp_dir = argv
    cpp_dir = os.path.abspath(cpp_dir)
    repo = repo or _git(cpp_dir, "rev-parse", "--show-toplevel").strip()
    rel = os.path.relpath(cpp_dir, repo)
    at_base = set(_git(repo, "ls-tree", "--name-only", f"{base}:{rel}").split())

    before, channels = [], {}
    for name in originals:
        items, channel, _ = parse(_git(repo, "show", f"{base}:{rel}/{name}"), name)
        before += items
        channels[name] = channel
    origin = {}
    for item in before:
        origin.setdefault(item.key, []).append(item)

    want = collections.Counter(item.key for item in before)
    after, problems = [], []
    split = [n for n in sorted(os.listdir(cpp_dir)) if n.endswith((".cpp", ".h")) and (n in originals or n not in at_base)]
    for name in split:
        with open(os.path.join(cpp_dir, name)) as f:
            items, channel, logs = parse(f.read(), name)
        new = name not in at_base
        # A declaration added to share moved state is ignored; one the originals had (a forward declaration moved to
        # a header) counts as the item it is.
        kept = [item for item in items if not (new and _is_declaration(item) and item.key not in want)]
        after += kept
        sources = {i.file for k in kept for i in origin.get(k.key, [])}
        if logs and channel is None and name.endswith(".cpp"):
            problems.append(f"{name}: logs, but sets no log channel")
        for source in sources:
            if channel is not None and channel != channels[source]:
                problems.append(f"{name}: log channel {channel}, where {source}'s is {channels[source]}")
            # In its original's order, so globals are built in it.
            indices = [min(i.index for i in origin[k.key] if i.file == source) for k in kept if any(i.file == source for i in origin.get(k.key, []))]
            if indices != sorted(indices):
                problems.append(f"{name}: what it took from {source} isn't in {source}'s order")

    have = collections.Counter(item.key for item in after)
    names = {item.key: item.name for item in before + after}
    for key in want.keys() | have.keys():
        if want[key] > have[key]:
            problems.append(f"missing ({want[key] - have[key]}): {names[key]}")
        elif have[key] > want[key]:
            where = sorted({item.file for item in after if item.key == key})
            problems.append(f"changed or doubled ({have[key] - want[key]}), in {', '.join(where)}: {names[key]}")
    for native, count in (_natives(before) - _natives(after) + (_natives(after) - _natives(before))).items():
        problems.append(f"native {native} differs in count ({count})")
    for native, count in _natives(after).items():
        if count != 1:
            problems.append(f"native {native} defined {count} times")

    for problem in sorted(set(problems)):
        print(problem)
    if problems:
        return 1
    print(f"Clean: {len(before)} items from {', '.join(originals)} in {len(split)} files.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
