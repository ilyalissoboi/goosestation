<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Game database: redump import and upkeep — design

Roadmap item 18. Agreed in conversation on 2026-11-10. Calls made while writing this up, not yet confirmed, are marked
*(writer's call)*; calls the builder may make are marked *(builder's call)*.

## Intent

GooseStation ships the base's game database untouched since the fork (`data/resources/gamedb.yaml`, about 10,700
games; `discdb.yaml`, about 10,100 discs; both GPLv3). It must grow from our own testing and public sources such as
redump.org, never from DuckStation's later, non-free database. This makes it **recognise more discs and stay
current** *(the player's choice)*: an **automated import from redump.org's public PS1 list** that **opens a pull
request for review** *(the player's choice)*, plus a guide and checks so per-game fixes from our own testing can be
added and kept.

## What exists

- **`gamedb.yaml`**: entries keyed by serial (`SLPS-03086:`), many with a `codes:` list of other serials, and some of
  executable-hash ids (`HASH-…`, `gamedb.yaml:3483`), that the lookup also goes through (`game_database.cpp:1317-1358,
  338-339`); whichever entry loads first owns a code. `name` is mostly redump-style ("Title (Region) (Demo)"; 611
  have no parenthesised part), and the file is roughly sorted by name. Fields:
  `controllers`, `traits`, settings, `metadata` (publisher, developer, release date, genre, languages, players,
  blocks, vibration, multitap, link cable), `compatibility` (`rating`, `versionTested`). Looked up by serial
  (`GameDatabase::GetEntryForSerial`, `GetEntryForDisc`, `GetEntryForGameDetails`, `src/core/game_database.h:108-110`).
- **`discdb.yaml`**: entries keyed by serial, with `name` and `trackData` (each track's `size` and `md5`), loaded into a
  track-hash map (`LoadTrackHashes`, `game_database.cpp:1367-1446`; a `std::multimap`, `game_database.h:140`) for
  checking a dump against known discs. **Shared track hashes are normal** (7,861 MD5s appear more than once, most
  across serials). Qt's dump check takes the first full match in file order (`gamesummarywidget.cpp:444-490`).
- **How the core identifies a disc:** from its boot executable's name (`SCES_123.45` → `SCES-12345`,
  `system.cpp:886-910`), or an executable hash when there's none; never from a printed serial. A disc without a
  serial can only be keyed by that hash, which redump's list doesn't have.
- **CI**: `gamedb-lint.yml` runs yamllint on both files (`extras/yamllint-config.yaml`). The core's tests
  (`src/common-tests`) aren't run in CI, and their host stubs return no resource files
  (`common-tests/core_host_stubs.cpp:36-54`).
- **The repository's Actions settings** don't let workflows create pull requests (default token permissions
  read-only, "Allow GitHub Actions to create and approve pull requests" off), and a pull request or push made with the
  workflow's own token starts no `pull_request` or `push` workflows (`workflow_dispatch` still works).
- **redump.org's PS1 list**, as checked on 2026-11-10:
  - `http://redump.org/datfile/psx/` (also `/serial,version`) serves `Sony - PlayStation - Datfile (<count>) (<date>).zip`
    to anyone, no login; `robots.txt` allows all. HTTPS failed from the development machine (possibly its TLS
    interception), so CI may need plain HTTP; no checksum is published.
  - Logiqx XML: per disc a `<game name>`, `<category>` (Games, Demos, Coverdiscs, Bonus Discs, Applications,
    Multimedia, Add-Ons, Educational, Preproduction, Video, Audio), `<serial>` (sometimes several, comma-separated,
    with a space: "SLPM 86770, SLPS 00222"), `<version>`, and each file's `<rom name size crc md5 sha1>` (the cue and
    each track's bin).
  - The 2026-06-15 list has 10,914 discs; **371 have no track in our `discdb.yaml`, and 245 of those have no serial**.
    Its serials come in many shapes besides the core's (`SLUS-01041GH`, `SLES-03428-GER`, `…#`, `…/A`, `LSP-…`), and
    38 of the new discs reuse a serial `discdb.yaml` already has for other tracks (mostly prototypes).

## Goals

### The import job

- **A workflow, `gamedb-import.yml`**, run **weekly on a schedule and by hand** *(the player's choice)*. It downloads
  the PS1 list, checks it's the expected shape (a zip holding one Logiqx `.dat` with the header's name "Sony -
  PlayStation" and at least as many discs as last time) *(writer's call)*, runs the import script, and when anything
  is new, **opens or updates one pull request** ("GameDB: redump import <list date>") on a fixed branch. It never
  merges. Nothing new, nothing opened.
- **The import script** (`scripts/gamedb/import-redump.py` *(builder's call on the place)*), also runnable by hand on a
  downloaded list:
  - **only new discs** are added: a redump disc whose tracks all match a `discdb.yaml` entry is known, whatever
    serials redump prints for it (no entries under extra serials of known discs, which recognise nothing new and
    could confuse the dump check) *(writer's call)*;
  - **adds only what's missing and never changes an existing entry** *(the player's choice)*: a new disc gets a
    `discdb.yaml` entry under its serial (name, tracks' sizes and MD5s, in the file's format); if that serial is
    neither a key nor a `codes` entry anywhere in `gamedb.yaml`, it also gets a name-only entry there, named as redump
    names it (so no new entry competes with an existing one for a code) *(writer's call)*;
  - **serials in the core's form only** *(writer's call)*: spaced ones are normalised (`SLPM 86770` → `SLPM-86770`),
    and a disc's first serial in the form four letters, dash, five digits is used; a disc with none in that form isn't
    added;
  - keeps both files in their existing order and formatting, so the diff shows only additions *(builder's call how)*;
  - writes the PR's description: counts by category and the added serials, then **what wasn't added, listed for a
    later decision** *(the player's choice for discs with no serial; writer's call for the rest)*: discs with no
    serial, discs whose serials aren't in the core's form, and new discs whose serial `discdb.yaml` already has for
    other tracks (adding them would change an existing entry);
  - reads a committed **skip list** (`scripts/gamedb/redump-skip.txt`, by redump name or serial): a reviewer adds what
    they rejected, so next week's run doesn't propose it again *(writer's call)*.
- **Which categories** *(writer's call)*: all of them, since the base already has demos, coverdiscs and preproduction
  discs; the PR's counts show them by category for review.

### Checks

- **A Python check** (in `gamedb-lint.yml` and the import job) that both files parse, that **no top-level key appears
  twice**, and that **no key in one entry is another entry's key or `codes` entry** *(writer's call)*. Shared track
  hashes aren't checked: they're normal. The core's test suite also gains a test loading the shipped files through
  `GameDatabase` *(writer's call)*, run where the tests are built (it isn't in CI today, and adding a core build there
  isn't worth it for this).
- **On the import's PR** *(writer's call)*: the job runs the checks itself before opening the PR, then starts
  `gamedb-lint.yml` on the PR branch with `workflow_dispatch` (which the workflow's token may do), so the result shows
  on the PR.
- **A one-time repository setting is the user's step:** allow GitHub Actions to create pull requests (Settings →
  Actions → General), with the import job's `permissions:` granting `contents: write` and `pull-requests: write`
  only to it. `docs/gamedb.md` says so.

### Per-game fixes from our own testing

- **A guide, `docs/gamedb.md`** *(writer's call)*: what each field means, how to add a fix or trait found in testing,
  how to rate compatibility (`rating`, and `versionTested` as "GooseStation " plus `git describe`'s version, so our
  ratings can't be taken for upstream's, which use the same number shape) *(writer's call)*, the sources allowed (our own
  testing, redump.org and other public sources) and never DuckStation's database after `a5bf1ba5cee7`, and how the
  import works. Each fix is its own PR saying the game, what was seen, and on what.

## Non-goals

- Keying discs without a serial (the 245); they're listed, not added.
- Changing existing entries from redump (renames, new tracks for a known disc).
- Metadata beyond the name for new games (publisher, genre and so on): later, from testing or other public sources.
- The cheat database (`chtdb.txt`).

## Anti-goals

- An import that changes or deletes an existing entry.
- Any data from DuckStation's later database, or from anywhere not public and attributable.
- A malformed or truncated download producing a PR (the shape check), or the job merging anything.
- A diff that rewrites the files' formatting, burying the additions.

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, its later database included. The import uses only
  redump.org's public list.
- The workflow uses only the repository's own token to open the PR; no secrets. It's polite to redump.org: one
  download a week, a User-Agent naming GooseStation and its repository *(writer's call)*.
- The script has tests on small hand-made lists (normalising serials, matching by hash, several serials, no serial,
  nothing new, a list in the wrong shape) *(writer's call)*.
- `docs/roadmap.md` (item 18) is updated. The user reviews every commit message.

## Left to the builder

The script's language and place, how it keeps the files' order and formatting, the PR branch's name, the exact shape
check, and the order of implementation.
