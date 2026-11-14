#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""The toolchain versions from android/gradle/libs.versions.toml, where Gradle reads them too, so they're set once.

  toolchain-versions.py              # NDK_VERSION=..., CMAKE_VERSION=..., BUILD_TOOLS_VERSION=..., EMUSYNC_VERSION=...
                                     # (CI: >> "$GITHUB_ENV")
  toolchain-versions.py buildTools   # one [versions] entry's value

A missing entry fails, naming it. --catalog PATH reads another catalog (for the tests). Python 3.11 or later.
"""

import os
import sys
import tomllib

CI_LINES = [
    ("NDK_VERSION", "ndk"),
    ("CMAKE_VERSION", "cmake"),
    ("BUILD_TOOLS_VERSION", "buildTools"),
    ("EMUSYNC_VERSION", "emusync"),
]


def main(args):
    catalog = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gradle", "libs.versions.toml")
    if len(args) >= 2 and args[0] == "--catalog":
        catalog = args[1]
        args = args[2:]
    with open(catalog, "rb") as f:
        versions = tomllib.load(f).get("versions", {})

    def value(key):
        if key not in versions:
            sys.exit(f"libs.versions.toml has no [versions] {key}")
        return versions[key]

    if args:
        print(value(args[0]))
    else:
        lines = [f"{name}={value(key)}" for name, key in CI_LINES]
        print("\n".join(lines))


if __name__ == "__main__":
    main(sys.argv[1:])
