# SPDX-FileCopyrightText: 2026 GooseStation contributors
# SPDX-License-Identifier: GPL-3.0

import pathlib
import unittest

from release_notes import release_body, unwrap

REPO = pathlib.Path(__file__).resolve().parents[3]


class UnwrapTest(unittest.TestCase):
    def test_joins_a_wrapped_paragraph(self):
        self.assertEqual(unwrap("One line\nand the next.\n\nAnother."), "One line and the next.\n\nAnother.")

    def test_joins_a_list_item_continued_on_an_indented_line(self):
        self.assertEqual(unwrap("- first item\n  goes on\n- second"), "- first item goes on\n- second")

    def test_keeps_numbered_items_and_their_continuations(self):
        self.assertEqual(unwrap("1. one\n   more\n2. two"), "1. one more\n2. two")

    def test_keeps_headings_on_their_own_lines(self):
        self.assertEqual(unwrap("## Android\nA paragraph\nwrapped."), "## Android\nA paragraph wrapped.")

    def test_leaves_fenced_code_alone(self):
        text = "Run:\n\n```bash\ngit tag -a 0.2\ngit push origin 0.2\n```\n\nDone."
        self.assertEqual(unwrap(text), text)

    def test_drops_the_licence_header(self):
        text = "<!-- SPDX-FileCopyrightText: x -->\n<!-- SPDX-License-Identifier: GPL-3.0 -->\n\nNotes."
        self.assertEqual(unwrap(text), "Notes.")


class ReleaseBodyTest(unittest.TestCase):
    def test_ends_with_the_commit(self):
        self.assertEqual(release_body("Notes\nwrapped.\n", "abc123"), "Notes wrapped.\n\nBuilt from abc123.\n")

    def test_the_0_2_notes_have_one_line_per_paragraph_and_item(self):
        body = release_body((REPO / "docs/releases/0.2.md").read_text(), "abc123")
        for line in body.splitlines():
            self.assertFalse(line.startswith(" "), line)
        self.assertIn("- **Game database**: new discs from redump.org's public PS1 list.\n", body)
        self.assertTrue(body.startswith("GooseStation's first versioned release: everything since the fork"))


if __name__ == "__main__":
    unittest.main()
