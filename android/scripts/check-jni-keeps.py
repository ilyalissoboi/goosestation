#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Checks that R8 kept everything the app's native code looks up by name through JNI.

    check-jni-keeps.py SEEDS_TXT CPP_DIR

SEEDS_TXT is R8's list of what its keep rules kept (app/build/outputs/mapping/release/seeds.txt, after
assembleRelease); CPP_DIR is the JNI code (android/app/src/cpp). A class or member R8 removed or renamed makes the
lookup fail at run time: a launch that fails in JNI_OnLoad, or HTTP requests that all fail (both have happened).

Checked, with comments left out:
- each string literal that names one of the app's classes (io/github/ilyalissoboi/goosestation/...) must be kept;
- each Get(Static)MethodID and Get(Static)FieldID whose name and signature are both literals (a call may span lines,
  and adjacent literals are joined) must be a member R8 kept in one of the app's classes, as seeds.txt writes it, or
  one of FRAMEWORK_MEMBERS. A lookup of a framework class's member that isn't there fails: add it.
Names held in variables aren't checked here; JNI_OnLoad looks those up at launch, which the test tiers' release build
covers (run-instrumented-tests.sh).
"""

import os
import re
import sys

PACKAGE = "io/github/ilyalissoboi/goosestation/"

# Framework members the native code looks up by literal name, as seeds.txt would write them.
FRAMEWORK_MEMBERS = {
    "void run()",  # java.lang.Runnable, jni_classes.cpp
}

_PRIMITIVES = {
    "Z": "boolean",
    "B": "byte",
    "C": "char",
    "S": "short",
    "I": "int",
    "J": "long",
    "F": "float",
    "D": "double",
    "V": "void",
}

_CLASS_LITERAL = re.compile(r'"(' + re.escape(PACKAGE) + r'[\w/$]+)"')
_LOOKUP = re.compile(r"\bGet(?:Static)?(Method|Field)ID\s*\(")
_LITERALS = re.compile(r'\s*((?:"(?:[^"\\]|\\.)*"\s*)+)')


def strip_comments(text: str) -> str:
    """text with // and /* */ comments blanked (newlines kept, so line numbers hold), string literals untouched."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            i = n if end < 0 else end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            stop = n if end < 0 else end + 2
            out.append("".join(c if c == "\n" else " " for c in text[i:stop]))
            i = stop
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i : j + 1])
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def java_type(descriptor: str, i: int = 0):
    """The Java type of the JNI type descriptor starting at descriptor[i], and the index after it."""
    dims = 0
    while descriptor[i] == "[":
        dims += 1
        i += 1
    if descriptor[i] == "L":
        end = descriptor.index(";", i)
        name = descriptor[i + 1 : end].replace("/", ".")
        i = end + 1
    else:
        name = _PRIMITIVES[descriptor[i]]
        i += 1
    return name + "[]" * dims, i


def method_parts(signature: str):
    """The parameters (as one comma-joined string) and return type of a JNI method signature."""
    params = []
    i = 1
    while signature[i] != ")":
        t, i = java_type(signature, i)
        params.append(t)
    returns, _ = java_type(signature, i + 1)
    return ",".join(params), returns


def read_seeds(path: str):
    """The app's kept classes (dotted names), and their kept members as seeds.txt writes them (without the class)."""
    classes = set()
    members = set()
    prefix = PACKAGE.replace("/", ".")
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line.startswith(prefix):
                continue
            if ": " in line:
                cls, member = line.split(": ", 1)
                members.add(member)
                # A constructor is written as its class's simple name: kept as "<init>(params)" too.
                simple = cls.rsplit(".", 1)[-1]
                if member.startswith(simple + "("):
                    members.add("<init>" + member[len(simple) :])
            else:
                classes.add(line)
    return classes, members


def literal_args(code: str, start: int):
    """The string literals passed as the next two arguments from code[start] (just after a comma), or None."""
    values = []
    i = start
    for _ in range(2):
        m = _LITERALS.match(code, i)
        if not m:
            return None
        values.append("".join(re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))))
        i = m.end()
        if len(values) == 1:
            if i >= len(code) or code[i] != ",":
                return None
            i += 1
    return values


def describe(kind: str, name: str, signature: str) -> str:
    if kind == "Field":
        return f"{java_type(signature)[0]} {name}"
    params, returns = method_parts(signature)
    if name == "<init>":
        return f"<init>({params})"
    return f"{returns} {name}({params})"


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__.strip().splitlines()[2].strip())
        return 2
    seeds_path, cpp_dir = argv
    classes, members = read_seeds(seeds_path)
    misses = []
    checked_classes = set()
    checked_members = 0
    for root, _, files in os.walk(cpp_dir):
        for file in sorted(files):
            if not file.endswith((".cpp", ".h")):
                continue
            path = os.path.join(root, file)
            with open(path) as f:
                code = strip_comments(f.read())
            where = os.path.relpath(path, cpp_dir)

            def line_of(index):
                return code.count("\n", 0, index) + 1

            for m in _CLASS_LITERAL.finditer(code):
                name = m.group(1).replace("/", ".")
                checked_classes.add(name)
                if name not in classes:
                    misses.append(f"{where}:{line_of(m.start())}: {name} isn't kept by R8 (proguard-rules.pro).")

            for m in _LOOKUP.finditer(code):
                # The handle is the first argument; the name and signature follow it.
                depth = 0
                i = m.end()
                while i < len(code):
                    c = code[i]
                    if c in "([":
                        depth += 1
                    elif c in ")]":
                        if depth == 0:
                            break
                        depth -= 1
                    elif c == "," and depth == 0:
                        break
                    i += 1
                if i >= len(code) or code[i] != ",":
                    continue
                args = literal_args(code, i + 1)
                if args is None:
                    continue
                name, signature = args
                member = describe(m.group(1), name, signature)
                if member in FRAMEWORK_MEMBERS:
                    continue
                checked_members += 1
                if member in members:
                    continue
                shown = f"a constructor ({member[len('<init>(') : -1]})" if name == "<init>" else member
                misses.append(f"{where}:{line_of(m.start())}: {shown} isn't kept by R8 (proguard-rules.pro).")

    for miss in misses:
        print(miss)
    if misses:
        return 1
    print(f"JNI names: {len(checked_classes)} classes and {checked_members} members kept.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
