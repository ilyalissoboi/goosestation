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
    def __init__(self, text: str, file: str, index: int, path=(), group=None):
        self.text = text
        self.file = file
        self.index = index
        self.key = _normalise(text)
        # The namespaces it's in, outermost first: "ns:<name>", "ns:" for an anonymous one, or "C" for extern "C".
        self.path = tuple(path)
        # Whether a blank line (or a namespace line) parts it from the next item.
        self.gap_after = True
        # The comment-led group it belongs to (the first item's index), or None.
        self.group = group

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
    """[file]'s top-level items, its log channel (or None), whether it logs, and its using lines with their paths."""
    items = []
    usings = []
    channel = None
    comment = []
    path = []
    lines = text.split("\n")
    i = 0
    run_start = None  # the first item of the run the next item would join, if nothing parts them

    def add(item_text):
        nonlocal run_start
        item = Item(item_text, file, len(items), path)
        if run_start is not None and items and not items[-1].gap_after:
            item.group = run_start
        items.append(item)
        item.gap_after = False
        return item

    def gap():
        nonlocal run_start
        if items:
            items[-1].gap_after = True
        run_start = None

    def flush_comment():
        nonlocal run_start
        # The licence header, which every file has, isn't moved code.
        if comment and not all("SPDX-" in line for line in comment):
            add("\n".join(comment))
            run_start = items[-1].index
        comment.clear()

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if not stripped:
            flush_comment()
            gap()
            i += 1
            continue
        if line[0].isspace():
            raise SystemExit(f"{file}:{i + 1}: indented line outside an item: {line!r}")
        match = _CHANNEL.match(line)
        if match:
            channel = match.group(1)
        opening = _WRAPPER_OPEN.match(stripped)
        if opening or _WRAPPER_CLOSE.match(stripped):
            flush_comment()
            gap()
            if opening:
                path.append("C" if stripped.startswith("extern") else "ns:" + (opening.group(2) or "").strip())
            elif path:
                path.pop()
            i += 1
            continue
        if _IGNORED_LINE.match(line):
            if line.startswith("using"):
                usings.append((tuple(path), line.strip()))
            flush_comment()
            gap()
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
                # column 0 with anything but the { or ( a function's signature goes on to, or the file ends.
                following = lines[i + 1] if i + 1 < len(lines) else ""
                if depth == 0 and code.endswith(")") and following[:1] not in (" ", "\t", "{", "("):
                    break
                i += 1
                if i >= len(lines):
                    raise SystemExit(f"{file}: an item doesn't end: {body[0]!r}")
            i += 1
        led = bool(comment)
        add("\n".join(comment + body))
        if led:
            run_start = items[-1].index
        elif run_start is None:
            run_start = items[-1].index
        comment.clear()
    flush_comment()
    if items:
        items[-1].gap_after = True
    # A group is a run of items with no gap between them that a comment leads; other runs are no group.
    for item in items:
        if item.group is not None and not items[item.group].text.lstrip().startswith(("//", "/*")):
            item.group = None
    for item in items:
        if any(other.group == item.index for other in items) and item.text.lstrip().startswith(("//", "/*")):
            item.group = item.index
    return items, channel, bool(_LOGGING.search(text)), usings


def _flat(item: Item) -> str:
    return " ".join(
        re.sub(r"\s*//.*$", "", line).strip() for line in item.key.split("\n") if not line.lstrip().startswith("//")
    ).strip()


def _signature(item: Item):
    """A function definition's head, as a prototype of it would read; None for anything else."""
    flat = _flat(item)
    if "{" not in flat:
        return None
    head = flat.split("{")[0].strip()
    if "=" in head.split("(")[0] or not head.endswith(")"):
        return None
    head = re.sub(r"\b[\w]+::(?=\w+\s*\()", "", head)  # AndroidHost::Name( reads as Name( where it's declared
    return head + ";"


def _is_declaration(item: Item, signatures) -> bool:
    """What the split adds to share moved state: an extern declaration with no value, or a prototype of a function
    defined in the split's files."""
    flat = _flat(item)
    if flat.startswith("extern "):
        return not re.search(r'extern\s+"C"|[={(]', flat)
    return flat in signatures


def _natives(items):
    return collections.Counter(n for item in items for n in _NATIVE.findall(item.text) if n not in ("ret", "name"))


def _path_fits(before, after) -> bool:
    """Whether an item at [after] is where it was at [before], or there with an anonymous namespace left."""
    if before == after:
        return True
    kept = tuple(p for p in before if p != "ns:")
    return after == kept or all(p in before for p in after) and [p for p in before if p != "ns:" or p in after] == list(after)


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

    before, channels, using_lines = [], {}, set()
    for name in originals:
        items, channel, _, usings = parse(_git(repo, "show", f"{base}:{rel}/{name}"), name)
        before += items
        channels[name] = channel
        using_lines |= set(usings)
    origin = {}
    for item in before:
        origin.setdefault(item.key, []).append(item)

    want = collections.Counter(item.key for item in before)
    split = [n for n in sorted(os.listdir(cpp_dir)) if n.endswith((".cpp", ".h")) and (n in originals or n not in at_base)]
    parsed = {}
    for name in split:
        with open(os.path.join(cpp_dir, name)) as f:
            parsed[name] = parse(f.read(), name)
    signatures = {sig for items, _, _, _ in parsed.values() for sig in map(_signature, items) if sig}

    after, problems, ignored = [], [], []
    for name in split:
        items, channel, logs, usings = parsed[name]
        new = name not in at_base
        # A declaration added to share moved state is ignored, in a new header only; one the originals had (a forward
        # declaration moved to a header) counts as the item it is.
        kept = []
        for item in items:
            if new and name.endswith(".h") and item.key not in want and _is_declaration(item, signatures):
                ignored.append(f"{name}: {item.name}")
            else:
                kept.append(item)
        after += kept
        for path, line in usings:
            if (path, line) not in using_lines:
                problems.append(f"{name}: a using line the originals don't have there: {line}")
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
        for item in kept:
            # In the namespace it was in, or there out of an anonymous one.
            if item.key in origin and not any(_path_fits(o.path, item.path) for o in origin[item.key]):
                problems.append(f"{name}: in another namespace than it was ({'/'.join(item.path) or 'global'}): {item.name}")
        # Neighbours that were neighbours: no blank line added or taken away between them.
        for a, b in zip(kept, kept[1:]):
            for oa in origin.get(a.key, []):
                ob = [o for o in origin.get(b.key, []) if o.file == oa.file and o.index == oa.index + 1]
                if ob and a.gap_after != oa.gap_after:
                    problems.append(f"{name}: a blank line {'added' if a.gap_after else 'taken away'} after: {a.name}")

    # A group a comment leads stays whole, in one file, its items together and in order.
    where = collections.defaultdict(list)
    for item in after:
        for o in origin.get(item.key, []):
            where[(o.file, o.index)].append(item)
    for o in before:
        if o.group is None or o.group != o.index:
            continue
        members = [m for m in before if m.file == o.file and m.group == o.index]
        placed = [where.get((m.file, m.index), [None])[0] for m in members]
        files = {p.file for p in placed if p}
        if len(files) != 1 or any(p is None for p in placed):
            problems.append(f"the group under '{o.name}' is split across {', '.join(sorted(files))}")
            continue
        indices = [p.index for p in placed]
        if indices != list(range(indices[0], indices[0] + len(indices))) or any(p.gap_after for p in placed[:-1]):
            problems.append(f"the group under '{o.name}' isn't together in {next(iter(files))}")

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

    for line in ignored:
        print(f"ignored, a declaration the split added: {line}")
    for problem in sorted(set(problems)):
        print(problem)
    if problems:
        return 1
    print(f"Clean: {len(before)} items from {', '.join(originals)} in {len(split)} files ({len(ignored)} declarations added).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
