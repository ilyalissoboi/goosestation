# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

"""Tests for toolchain-versions.py: python3 -m unittest discover -s android/scripts/tests -p 'test_*.py'"""

import os
import subprocess
import sys
import tempfile
import unittest

_SCRIPT = os.path.join(os.path.dirname(__file__), "..", "toolchain-versions.py")

CATALOG = """\
[versions]
agp = "9.4.1"
ndk = "30.0.16248370"
cmake = "4.1.2"
buildTools = "37.0.0"
emusync = "0.3.0"
emusyncAarSha256 = "964c"

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
"""


def run(*args):
    return subprocess.run([sys.executable, _SCRIPT, *args], capture_output=True, text=True)


class ToolchainVersionsTest(unittest.TestCase):
    def catalog(self, text):
        folder = tempfile.mkdtemp()
        path = os.path.join(folder, "libs.versions.toml")
        with open(path, "w") as f:
            f.write(text)
        return path

    def test_the_ci_lines(self):
        result = run("--catalog", self.catalog(CATALOG))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(
            "NDK_VERSION=30.0.16248370\nCMAKE_VERSION=4.1.2\nBUILD_TOOLS_VERSION=37.0.0\nEMUSYNC_VERSION=0.3.0\n",
            result.stdout,
        )

    def test_one_key(self):
        result = run("--catalog", self.catalog(CATALOG), "buildTools")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("37.0.0\n", result.stdout)

    def test_a_missing_key_fails_naming_it(self):
        result = run("--catalog", self.catalog(CATALOG.replace('cmake = "4.1.2"\n', "")))
        self.assertEqual(1, result.returncode)
        self.assertIn("[versions] cmake", result.stderr)
        self.assertEqual("", result.stdout)

    def test_the_real_catalog(self):
        result = run()
        self.assertEqual(0, result.returncode, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(
            ["NDK_VERSION", "CMAKE_VERSION", "BUILD_TOOLS_VERSION", "EMUSYNC_VERSION"],
            [line.split("=")[0] for line in lines],
        )
        for line in lines:
            self.assertNotEqual("", line.split("=", 1)[1], line)


if __name__ == "__main__":
    unittest.main()
