<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Game database: redump import and upkeep — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A weekly-and-manual workflow downloads redump.org's PS1 list, adds only the discs we don't know to
`discdb.yaml` (and a name-only `gamedb.yaml` entry where the serial is unknown), and opens or updates one PR for
review; a check, a core test and a guide keep the database sound as it grows.

**Architecture:** Two stdlib-only Python 3 scripts in `scripts/gamedb/`. `gamedb_check.py` reads both files with a
strict line reader (the files' fixed layout; any line it doesn't understand is an error, so a reformatted file fails
loudly instead of being misread) and checks keys and codes; `redump_import.py` reads the list's zip, plans the import
against what the reader found, appends new entries as text at the end of each file (so the diff is additions only),
and writes the PR's description. `gamedb-import.yml` runs them, then yamllint and the check, and pushes a fixed branch
and PR with the workflow's own token. `common-tests` gains a test that loads the shipped files through
`GameDatabase`, with the host stubs able to read a resource directory a test names.

**Tech Stack:** Python 3.10+ (stdlib only: `zipfile`, `xml.etree.ElementTree`, `unittest`), GitHub Actions with `gh`,
yamllint, C++20 / GoogleTest (`common-tests`).

**Spec:** `docs/superpowers/specs/2026-11-10-gamedb-redump-import-design.md`

## What the code and the real list showed (checked 2026-11-10)

These change or pin details the design left open:

- **The default list has no serials.** `http://redump.org/datfile/psx/` serves a dat without `<serial>`; the import
  must download `http://redump.org/datfile/psx/serial,version` (same zip name, `Sony - PlayStation - Datfile (10914)
  (2026-06-15 11-55-46).zip`, one `.dat` inside, `<serial>` and `<version>` per game). The newest list is still
  2026-06-15. The reader rejects a list in which no disc has a serial.
- **134 discs are added from that list, not about 126.** 10,452 discs match a `discdb.yaml` track set exactly (bin
  MD5s in order); 371 share no track with it and 89 share only some (audio tracks, mostly), 2 have every track known
  but spread over several entries. The design's rule (new = no full match) makes all 462 new. Of those: 267 have no
  serial, 21 have none in the core's form, 40 have a serial `discdb.yaml` already has, and **134 are added** (80 with
  no known track, 54 with some) — Demos 70, Games 56, Multimedia 4, and 1 each of Preproduction, Add-Ons,
  Applications, Educational. No two share a serial; 5 carry a `<version>`. 42 of the 134 serials are already
  `gamedb.yaml` keys (37) or codes (5), so **92 name-only `gamedb.yaml` entries** are added. (The earlier "about 126"
  was 371 minus the 245 without serials, before odd and known serials were taken out.)
- **The shipped `gamedb.yaml` already has clashes.** 13 serials are `codes` in two or more entries (one,
  `SLPS-02356`, in 35) and 4 keys are also other entries' codes. Existing entries never change, so the check takes a
  committed baseline, `scripts/gamedb/known-clashes.txt`, and fails only on clashes not in it.
- **yamllint's default rules already reject duplicate keys** (`key-duplicates`), so the check's own work is the
  clashes across entries; it still checks duplicate keys, since it reads the files itself.
- **An entry with `codes` is found only by its codes**, not its key (`ParseYamlCodes`, `game_database.cpp:1317-1355`);
  34 entries' keys aren't in their own codes. The import's "neither a key nor a code" rule is therefore safe as
  written.
- **`discdb.yaml` track sets** are `    - tracks:` or `    - version: "Rev 1"` + `      tracks:`, each track
  `        - size: N` + `          md5: "<32 hex>"`; names are double-quoted, no file has a backslash or an inner `"`.
  Both files are ASCII, LF, end in one newline; the base already appended later entries at the end, out of name order.
- **Dispatching `gamedb-lint.yml` needs `actions: write`** on the import job, besides `contents: write` and
  `pull-requests: write`.
- **The host stubs return no resources**, and `GameDatabase` writes `gamedb.cache` into `EmuFolders::Cache`, so the
  core test needs a resource directory in the stubs and a temporary cache folder.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, its later database included. The import uses only
  redump.org's public list.
- The import **adds only what's missing and never changes an existing entry**; both files keep their order and
  formatting, so the diff shows only additions.
- Serials in the core's form only: four capital letters, dash, five digits; a spaced serial is normalised
  (`SLPM 86770` → `SLPM-86770`); a disc's first serial in that form is used.
- The workflow uses only the repository's own token; no secrets. One download a week, User-Agent
  `GooseStation gamedb import (+https://github.com/ilyalissoboi/goosestation)`.
- The job never merges. Nothing new, nothing opened.
- Scripts read the downloaded list as untrusted data: run with `python3 -I`, list in its own directory, size-capped.
- Every new file carries the SPDX header (`2026 GooseStation contributors`, `GPL-3.0`) in its comment style.
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A list that downloads but is wrong** (the serial-less variant, an HTML error page saved as `.zip`, a truncated
   zip, a dat with fewer discs) must stop the job with a message and open nothing. Tests in Task 2.
2. **Re-running on an unmerged PR** must produce the same branch content (deterministic order: by redump name), not
   stack a second copy of the entries. Test in Task 3 (`test_plan_is_stable`), plus the workflow always building the
   branch fresh from `master` (Task 5).
3. **A name that YAML would misread** (`"`, `\`, `: `, leading `'`, `#`) must round-trip as the same string. Test in
   Task 4 (`test_name_escaping`).
4. **A PR description over GitHub's 65,536-character limit** (a future list with thousands of new discs) must still
   open the PR, with the lists cut short and the full summary in the job's summary. Test in Task 4
   (`test_summary_truncates`).
5. **A reviewer's rejection** must stick: a disc in the skip list (by name, by printed serial, or by core serial) is
   counted as skipped and not added, and the guide says to edit the skip list on `master`, since each run rebuilds the
   PR branch. Test in Task 3 (`test_skip_list`).

---

## File structure

- `scripts/gamedb/gamedb_check.py` — strict readers for both files (`parse_gamedb`, `parse_discdb`), the clash
  check, and the check's CLI. Imported by the import script.
- `scripts/gamedb/known-clashes.txt` — the baseline: serials that clash in the shipped files today.
- `scripts/gamedb/redump_import.py` — list reader and shape check, serial normalisation, the import plan, writing
  entries, the summary, the CLI.
- `scripts/gamedb/redump-skip.txt` — the skip list (header comment only at first).
- `scripts/gamedb/tests/test_gamedb_check.py`, `scripts/gamedb/tests/test_redump_import.py` — `unittest` suites on
  small hand-made files and lists (built in the tests with `zipfile`/strings; no fixtures from redump).
- `.github/workflows/gamedb-lint.yml` — also runs the script tests and the check.
- `.github/workflows/gamedb-import.yml` — the weekly import.
- `src/common-tests/core_host_stubs.{h,cpp}`, `src/common-tests/game_database_tests.cpp`,
  `src/common-tests/CMakeLists.txt` — the core test.
- `docs/gamedb.md` (new), `docs/roadmap.md` (item 18).

Scripts run with `-I` don't get their own directory on `sys.path`, so `redump_import.py` inserts
`Path(__file__).resolve().parent` itself before importing `gamedb_check`. Tests run with
`python3 -m unittest discover -s scripts/gamedb/tests -t scripts/gamedb`.

---

### Task 1: The database reader and check

**Files:**
- Create: `scripts/gamedb/gamedb_check.py`, `scripts/gamedb/known-clashes.txt`,
  `scripts/gamedb/tests/test_gamedb_check.py`
- Modify: `.github/workflows/gamedb-lint.yml`

**Interfaces:**
- Produces:
  - `class FormatError(Exception)` — message includes the file name and 1-based line.
  - `@dataclass(frozen=True) GameEntry(key: str, line: int, codes: tuple[str, ...])`
  - `@dataclass(frozen=True) DiscEntry(key: str, line: int, name: str, track_sets: tuple[tuple[tuple[int, str], ...], ...])`
    — each track set a tuple of `(size, md5)` in file order.
  - `parse_gamedb(text: str, path: str = "gamedb.yaml") -> list[GameEntry]`
  - `parse_discdb(text: str, path: str = "discdb.yaml") -> list[DiscEntry]`
  - `find_problems(games: list[GameEntry], discs: list[DiscEntry], known_clashes: set[str]) -> list[str]`
  - `read_known_clashes(path: Path) -> set[str]` — one serial per line, `#` comments and blanks ignored.
  - CLI: `gamedb_check.py [--resources DIR] [--known-clashes FILE]` (defaults: the repo's `data/resources` and
    `scripts/gamedb/known-clashes.txt`, found from `__file__`); prints each problem and exits 1, or prints
    `gamedb check: <n> games, <m> discs, no new clashes (<k> known)` and exits 0.

Reader rules. `gamedb.yaml`: a top-level line is `<key>:` with no leading space and no `#`; every other non-empty line
starts with two spaces; `  codes:` must be followed only by `    - <code>` lines, and any other `  codes` form is a
`FormatError`. `discdb.yaml`: each entry is exactly `  name: "<name>"`, `  trackData:`, then track sets as described
above; any other line is a `FormatError`. A blank line or a missing final newline is a `FormatError` in both.

Problems: a key twice in either file (`duplicate key <key> (lines a, b)`); a code in two or more `gamedb.yaml` entries
(`code <code> is in <k1>, <k2>`); a key that is another entry's code (`key <key> is also a code of <k2>`). A serial
in `known_clashes` raises no clash problem; duplicate keys are never allowed.

- [ ] **Step 1: Write the failing tests** in `test_gamedb_check.py`:
  - `test_parse_gamedb_keys_and_codes` — a two-entry text, one with `codes: [A, B]` in block form, one with
    `controllers` and `metadata`; asserts keys, lines (1-based) and codes.
  - `test_parse_gamedb_rejects_inline_codes` — `  codes: [SLUS-00001]` raises `FormatError` naming line 3.
  - `test_parse_discdb_track_sets` — an entry with `- tracks:` and one with `- version: "Rev 1"` then `tracks:`;
    asserts `name` and `track_sets == (((443733024, "0aa2…be"),),)`, and two sets for the second.
  - `test_parse_discdb_rejects_unknown_line` — a `  crc: "…"` line raises `FormatError`.
  - `test_parse_rejects_missing_final_newline` (both readers).
  - `test_duplicate_key`, `test_code_in_two_entries`, `test_key_is_other_entrys_code` — each yields exactly one
    problem with the serial in it.
  - `test_known_clash_is_not_a_problem` — the same input as `test_code_in_two_entries` with that code in
    `known_clashes` yields `[]`; a duplicate key in `known_clashes` is still a problem.
  - `test_shared_track_hashes_are_fine` — two discdb entries with the same MD5 yield `[]`.
  - `test_shipped_files_pass` — runs `find_problems` on the repository's two files with `known-clashes.txt`; `[]`.
- [ ] **Step 2: Run them and see them fail** —
  `python3 -m unittest discover -s scripts/gamedb/tests -t scripts/gamedb` → `ModuleNotFoundError: gamedb_check`.
- [ ] **Step 3: Implement `gamedb_check.py`** per the interfaces, then generate `known-clashes.txt` from the shipped
  files (one serial per line, sorted, under a header comment saying these clashes came with the base's database and
  are left as they are, since the import never changes existing entries). Expect 13 code clashes plus 4 key-is-code
  serials (some overlapping).
- [ ] **Step 4: Run the tests and the CLI** — the discover command above → `OK`;
  `python3 scripts/gamedb/gamedb_check.py` → `gamedb check: 10764 games, 10105 discs, no new clashes (<k> known)`.
- [ ] **Step 5: Add to `gamedb-lint.yml`** `scripts/gamedb/**` to both `paths` lists, and two steps after yamllint:
  `Test GameDB scripts` (the discover command) and `Check GameDB keys and codes` (`python3 scripts/gamedb/gamedb_check.py`).
- [ ] **Step 6: Commit** — `GameDB: a check for duplicate keys and clashing codes`.

### Task 2: Reading redump's list

**Files:**
- Create: `scripts/gamedb/redump_import.py`, `scripts/gamedb/tests/test_redump_import.py`

**Interfaces:**
- Produces:
  - `class ListShapeError(Exception)`
  - `@dataclass(frozen=True) Disc(name: str, category: str, serials: tuple[str, ...], version: str, tracks: tuple[tuple[int, str], ...])`
    — `serials` as printed, split on commas and stripped; `tracks` the non-`.cue` roms' `(size, md5)` in order;
    `version` `""` when absent.
  - `@dataclass(frozen=True) RedumpList(version: str, discs: tuple[Disc, ...])` — `version` the header's
    (`2026-06-15 11-55-46`).
  - `read_redump_zip(path: Path, min_discs: int = MIN_DISCS) -> RedumpList`; `MIN_DISCS = 10_900`,
    `MAX_DAT_BYTES = 64 * 1024 * 1024`.
  - `core_serial(printed: str) -> str | None` — `^[A-Z]{4}[ -]\d{5}$` → with a dash; anything else `None`.
  - `first_core_serial(disc: Disc) -> str | None`

Shape check, each failure a `ListShapeError` saying what was wrong: not a zip or a bad CRC; not exactly one member, or
it doesn't end in `.dat`; the member over `MAX_DAT_BYTES` (checked from the zip's header before reading); XML that
doesn't parse; root not `datafile`; header `name` not exactly `Sony - PlayStation`; the header `description`'s
`(<count>)` missing or not equal to the number of `<game>`s; fewer than `min_discs` games; a game with no non-cue rom,
or a rom whose `size` isn't digits or `md5` isn't 32 lowercase hex; no game with a serial at all ("this is the list
without serials; download /datfile/psx/serial,version").

- [ ] **Step 1: Write the failing tests**, with a helper `make_list(tmpdir, games, *, name="Sony - PlayStation",
  count=None, members=None) -> Path` that writes a zip of a small Logiqx dat:
  - `test_reads_discs` — two games (one with `<serial>SLPM 86770, SLPS 00222</serial>` and `<version>Rev 1</version>`,
    a cue and two bins); asserts `version`, names, `serials == ("SLPM 86770", "SLPS 00222")`, tracks without the cue.
  - `test_core_serial` — `SLPM 86770`→`SLPM-86770`, `SCES-02066`→same, `SLUS-01041GH`, `SLES-03428-GER`,
    `SLES-12345#`, `LSP-123456-1.1`, `DTL-S1234`, `slus-00001`, `SLPM  86770` → `None`.
  - `test_first_core_serial_skips_odd_ones` — `("SLUS-01041GH", "SLUS-01041")` → `SLUS-01041`.
  - `test_rejects_wrong_header_name`, `test_rejects_count_mismatch`, `test_rejects_too_few_discs`,
    `test_rejects_two_members`, `test_rejects_not_a_zip` (an HTML page written to `list.zip`),
    `test_rejects_truncated_zip` (the zip cut in half), `test_rejects_list_without_serials`,
    `test_rejects_bad_md5` — each raises `ListShapeError`.
  - Tests pass `min_discs=1`, except `test_rejects_too_few_discs`.
- [ ] **Step 2: Run them and see them fail** (`ModuleNotFoundError: redump_import`).
- [ ] **Step 3: Implement** the reader with `zipfile` and `xml.etree.ElementTree.fromstring` (Python's expat refuses
  external entities and caps entity expansion).
- [ ] **Step 4: Run the tests** → `OK`; then read the real list once:
  `python3 -I -c 'import sys; sys.path.insert(0,"scripts/gamedb"); import redump_import as r; l=r.read_redump_zip(r.Path(sys.argv[1])); print(l.version, len(l.discs))' /tmp/redump-dl.8zvgQA/list.zip`
  → `2026-06-15 11-55-46 10914`.
- [ ] **Step 5: Commit** — `GameDB: read and check redump's PS1 list`.

### Task 3: Planning the import

**Files:**
- Modify: `scripts/gamedb/redump_import.py`, `scripts/gamedb/tests/test_redump_import.py`
- Create: `scripts/gamedb/redump-skip.txt`

**Interfaces:**
- Consumes: `GameEntry`, `DiscEntry` (Task 1); `Disc`, `RedumpList`, `first_core_serial`, `core_serial` (Task 2).
- Produces:
  - `@dataclass(frozen=True) Added(serial: str, disc: Disc, gamedb: bool)` — `gamedb` true when a name-only
    `gamedb.yaml` entry is added too.
  - `@dataclass(frozen=True) ImportPlan(list_version: str, total: int, known: int, added: tuple[Added, ...],
    no_serial: tuple[Disc, ...], odd_serial: tuple[Disc, ...], known_serial: tuple[tuple[str, Disc], ...],
    skipped: tuple[Disc, ...])`
  - `read_skip_list(path: Path) -> set[str]` — one redump name or serial per line; `#` comments and blanks ignored;
    surrounding space stripped.
  - `plan_import(redump: RedumpList, games: list[GameEntry], discs: list[DiscEntry], skip: set[str]) -> ImportPlan`

Rules, in this order, for each disc sorted by redump name: **known** if its `tracks` equal one of `discdb.yaml`'s
track sets exactly (sizes and MD5s, in order) — counted, nothing else; **skipped** if its name, any printed serial,
or any printed serial's `core_serial` is in `skip`; **no_serial** if it has none; **odd_serial** if
`first_core_serial` is `None`; **known_serial** if that serial is a `discdb.yaml` key, or was already taken by an
earlier new disc in this run; else **added**, with `gamedb` true when the serial is neither a key nor a code in
`gamedb.yaml`.

- [ ] **Step 1: Write the failing tests** (`games`/`discs` built with the Task 1 dataclasses, lists as `RedumpList`
  values):
  - `test_known_disc_is_not_added` — a disc whose tracks equal an existing set is `known`, even with a new serial.
  - `test_partly_known_disc_is_added` — one track shared with an existing entry, one new → added.
  - `test_track_order_matters` — the same tracks in another order is not known.
  - `test_spaced_serial_added_normalised` — `SLPM 86770` → `Added("SLPM-86770", …, gamedb=True)`.
  - `test_gamedb_entry_only_when_unknown` — serial a `gamedb.yaml` key → `gamedb=False`; a code → `gamedb=False`.
  - `test_no_serial`, `test_odd_serial` (`SLUS-01041GH` only), `test_known_serial_new_tracks`,
    `test_two_new_discs_one_serial` (the second, by name order, goes to `known_serial`).
  - `test_skip_list` — skipped by exact name, by `SLPM 86770`, and by `SLPM-86770`; and `read_skip_list` on a file
    with comments and blanks.
  - `test_nothing_new` — every disc known → `added == ()`, `known == total`.
  - `test_plan_is_stable` — the same discs in reversed input order give an equal `ImportPlan`.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** `read_skip_list` and `plan_import`; create `redump-skip.txt` with a header comment
  (format, and "add what review rejected; edit this on master").
- [ ] **Step 4: Run the tests** → `OK`.
- [ ] **Step 5: Commit** — `GameDB: decide which redump discs are new`.

### Task 4: Writing the entries and the summary; the CLI

**Files:**
- Modify: `scripts/gamedb/redump_import.py`, `scripts/gamedb/tests/test_redump_import.py`

**Interfaces:**
- Consumes: `ImportPlan`, `Added` (Task 3); `parse_gamedb`, `parse_discdb`, `find_problems` (Task 1).
- Produces:
  - `yaml_quote(text: str) -> str` — a YAML double-quoted scalar; `\` → `\\`, `"` → `\"`; a control character raises
    `ListShapeError`.
  - `render_discdb_entry(added: Added) -> str`, `render_gamedb_entry(added: Added) -> str` — the files' exact layout
    (`render_gamedb_entry` is `<serial>:\n  name: "<name>"\n`); a disc with a `version` gets
    `    - version: "<version>"` + `      tracks:`.
  - `apply_plan(plan: ImportPlan, resources: Path) -> None` — appends to `discdb.yaml` and `gamedb.yaml`, in the
    plan's order; leaves both byte-identical when nothing is added.
  - `render_summary(plan: ImportPlan, limit: int | None = PR_BODY_LIMIT) -> str`; `PR_BODY_LIMIT = 60_000`.
  - `pr_title(plan: ImportPlan) -> str` → `GameDB: redump import 2026-06-15`.
  - `main(argv: list[str] | None = None) -> int` — `redump_import.py --list ZIP [--resources DIR] [--skip FILE]
    [--summary FILE] [--full-summary FILE] [--github-output FILE] [--dry-run]`. Prints
    `<list version>: <added> discs added (<g> gamedb.yaml entries), <known> known, <n> not added`. Writes
    `added=<n>` and `title=<pr_title>` to `--github-output`. Exit 0; `ListShapeError` or `FormatError` → message on
    stderr, exit 2. After writing (not on `--dry-run`), it re-reads both files and runs `find_problems`; any problem
    → exit 3.

Summary layout (Markdown): a first paragraph with the list version and the counts (`total`, `known`, added discs, new
`gamedb.yaml` entries, not added); a table of added discs by category; `### Added` — `` - `SERIAL` Name (Category) ``;
then `### Not added: no serial`, `### Not added: serial not in the core's form` (with the printed serials),
`### Not added: known serial, new tracks` (with the serial), `### Skipped (redump-skip.txt)`; an empty section is left
out. A last line says the list comes from redump.org, how to reject a disc (the skip list on `master`), and that the
job never merges. Over `limit`, the lists are cut and each cut list ends with `- …and <n> more (the run's summary has
the full list)`.

- [ ] **Step 1: Write the failing tests:**
  - `test_render_discdb_entry` and `test_render_discdb_entry_with_version` — exact expected strings.
  - `test_name_escaping` — names `A "B" C`, `C:\X`, `Key: value # not a comment`, `'quoted` rendered into a
    `gamedb.yaml` text and read back with `parse_gamedb`/`parse_discdb`, and the discdb name read back equals the
    original (`DiscEntry.name` is the unquoted, unescaped string).
  - `test_apply_plan_appends_only` — on a temp copy of two small files, the new text starts with the old text byte
    for byte, and the appended part equals the rendered entries.
  - `test_apply_nothing_leaves_files` — an empty plan leaves both files byte-identical.
  - `test_summary_sections` — counts line, category table, each list, empty sections omitted.
  - `test_summary_truncates` — 2,000 no-serial discs with `limit=5_000` → result ≤ 5,000 characters and ends its cut
    list with `…and` ; `limit=None` keeps all.
  - `test_main_dry_run` — on a temp resources copy and a made list: exit 0, files unchanged, summary written,
    `--github-output` has `added=1` and `title=GameDB: redump import 2026-06-15`.
  - `test_main_bad_list` — a non-zip → exit 2, files unchanged.
- [ ] **Step 2: Run them and see them fail.**
- [ ] **Step 3: Implement** per the interfaces. `DiscEntry.name` in Task 1's reader unescapes `\\` and `\"`, so the
  round-trip test holds.
- [ ] **Step 4: Run the tests** → `OK`; then a dry run on the real list (in a scratch copy of `data/resources`):
  `python3 -I scripts/gamedb/redump_import.py --list /tmp/redump-dl.8zvgQA/list.zip --resources <copy> --summary /tmp/gamedb-scratch/summary.md`
  → `2026-06-15 11-55-46: 134 discs added (92 gamedb.yaml entries), 10452 known, 328 not added`; then yamllint
  (installed in a venv under `/tmp`) and `gamedb_check.py --resources <copy>` pass, and `git diff --no-index --stat`
  shows only insertions.
- [ ] **Step 5: Commit** — `GameDB: add redump's new discs and describe them for review`.

### Task 5: The import workflow

**Files:**
- Create: `.github/workflows/gamedb-import.yml`

**Interfaces:**
- Consumes: `redump_import.py`'s CLI and outputs (Task 4), `gamedb_check.py` (Task 1), `gamedb-lint.yml` (Task 1).

Values: `on: schedule: cron '23 5 * * 1'` (Mondays 05:23 UTC) and `workflow_dispatch`; top-level `permissions: {}`;
`concurrency: gamedb-import`; one job, `if: github.repository == 'ilyalissoboi/goosestation'`, `runs-on:
ubuntu-24.04`, `timeout-minutes: 20`, `permissions: contents: write, pull-requests: write, actions: write`. Branch
`gamedb/redump-import`, base `master`. `GH_TOKEN: ${{ github.token }}`.

Steps:
1. Checkout `master` (`actions/checkout@v4.1.1`, as `gamedb-lint.yml` uses).
2. Download into `$RUNNER_TEMP/redump/` (a fresh directory): `curl --fail --silent --show-error --max-time 300
   --user-agent "$UA" -o list.zip https://redump.org/datfile/psx/serial,version`, and on failure the same over
   `http://`, logging which worked.
3. `python3 -I scripts/gamedb/redump_import.py --list … --summary $RUNNER_TEMP/pr-body.md --full-summary
   $GITHUB_STEP_SUMMARY --github-output $GITHUB_OUTPUT`.
4. Install yamllint (`apt-get install yamllint`, as the lint job does); run it on both files and run
   `python3 scripts/gamedb/gamedb_check.py`.
5. If `added == 0`: close an open PR from `gamedb/redump-import`, if any, with a comment that nothing is new any more
   (`gh pr list --head … --state open --json number`, `gh pr close --comment`); stop.
6. Else: `git switch -C gamedb/redump-import`, commit both files as `github-actions[bot]`
   (`41898283+github-actions[bot]@users.noreply.github.com`) with the title as message, `git push --force origin
   gamedb/redump-import`; `gh pr edit` the open PR's title and body, or `gh pr create --base master --head … --title
   … --body-file …`; then `gh workflow run gamedb-lint.yml --ref gamedb/redump-import`.

- [ ] **Step 1: Write the workflow.**
- [ ] **Step 2: Lint it** — `actionlint` if available (install with `brew install actionlint` only with the user's OK;
  otherwise yamllint with the repo's config) → no findings; read through each `run:` for quoting of `$UA` and paths.
- [ ] **Step 3: Rehearse steps 3–6 locally** against a throwaway clone with a local bare remote: run the CLI as step 3
  does, then the git commands of step 6 (without `gh`), and check the branch has one commit whose diff is only
  additions; run it twice and check the second push leaves the same tree.
- [ ] **Step 4: Commit** — `GameDB: a weekly workflow that proposes redump's new discs`.

### Task 6: A core test loading the shipped database

**Files:**
- Create: `src/common-tests/core_host_stubs.h`, `src/common-tests/game_database_tests.cpp`
- Modify: `src/common-tests/core_host_stubs.cpp:36-54`, `src/common-tests/CMakeLists.txt`

**Interfaces:**
- Produces: `namespace CoreHostStubs { void SetResourceDirectory(std::string directory); }` — empty (the default)
  keeps today's "no resources"; when set, `Host::ResourceFileExists`, `ReadResourceFile` and
  `ReadResourceFileToString` read `Path::Combine(directory, filename)` with `FileSystem`; the timestamp stays
  `std::nullopt`.
- CMake: `target_compile_definitions(common-tests PRIVATE
  GOOSESTATION_TEST_RESOURCES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../../data/resources")`, and the new test file in the
  sources list (CMake only, as `achievements_offline_store_tests.cpp` is).

- [ ] **Step 1: Write the failing test** `TEST(GameDatabase, LoadsShippedFiles)` in `game_database_tests.cpp`:
  `GTEST_SKIP()` when `GOOSESTATION_TEST_RESOURCES_DIR` doesn't exist (the Android device run); otherwise set the
  resource directory, set `EmuFolders::Cache` to a fresh temporary directory, `GameDatabase::Unload()`, then assert:
  `GetEntryForSerial("SLPS-01204")` non-null with title `'98 Koushien - Koukou Yakyuu Simulation (Japan)`;
  `GetEntryForSerial("SLPS-01311")` (a code, `gamedb.yaml:161`) non-null and its `serial` not `SLPS-01311`;
  `GetEntryForSerial("HASH-D05E80C0A958B7D0")` non-null; after `EnsureTrackHashesMapLoaded()`, the map's entry for
  MD5 `39a936ea7521157838d4e67b24f62f15` has serial `SLPS-01204`. Cleanup: `Unload()`, reset the resource directory
  and cache folder, remove the temporary directory.
- [ ] **Step 2: Build and see it fail** — configure the desktop build (`cmake -B build -G Ninja
  -DCMAKE_PREFIX_PATH=$HOME/work/goosestation-deps -DBUILD_TESTS=ON`, as earlier sessions did; in the background)
  and `cmake --build build --target common-tests` → fails to compile (`core_host_stubs.h` missing).
- [ ] **Step 3: Implement** the stub change and the CMake lines.
- [ ] **Step 4: Run** `build/bin/common-tests --gtest_filter='GameDatabase.*'` → `[  PASSED  ] 1 test`, then the
  whole suite → all pass.
- [ ] **Step 5: Commit** — `GameDB: a core test loads the shipped database`.

### Task 7: The guide, the roadmap and the dry run

**Files:**
- Create: `docs/gamedb.md`
- Modify: `docs/roadmap.md:54`

- [ ] **Step 1: Write `docs/gamedb.md`**: the two files and what each field means (from `ParseYamlEntry` and
  `LoadTrackHashes`: `name`, `codes`, `controllers`, `traits`, settings, `metadata`, `compatibility`, `trackData`;
  how a disc is identified by its boot executable or hash, and that an entry with `codes` is found only by them);
  adding a fix or trait from our testing (one PR per game saying the game, what was seen, and on what device or OS);
  rating compatibility (`rating` values from `GetCompatibilityRatingName`; `versionTested: "GooseStation <git describe>"`);
  allowed sources (our testing, redump.org, other public attributable sources; never DuckStation's database after
  `a5bf1ba5cee7`); the import (what it adds and lists, the schedule, running it by hand, the skip list on `master`,
  that each run rebuilds the PR branch so edits there don't last past a week, the shape check, the known-clashes
  baseline); the user's one-time setting (Settings → Actions → General → Workflow permissions: allow GitHub Actions
  to create and approve pull requests).
- [ ] **Step 2: Update roadmap item 18** with what's done (the import, the check, the guide) and what's left
  (keying the discs with no serial; metadata for new games; the first scheduled run).
- [ ] **Step 3: Full verification** — the script tests, `gamedb_check.py`, yamllint on both files (venv), the
  desktop `common-tests`, and Task 4's dry run on the real list; show the dry run's summary.
- [ ] **Step 4: Commit** — `Docs: the game database guide; roadmap item 18`, then push the branch.
