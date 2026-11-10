<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# The game database

GooseStation's game database is two files in `data/resources/`, both GPLv3 and inherited from the base we forked:

- **`gamedb.yaml`**: one entry per game, with its name, metadata, compatibility rating, and the fixes and settings
  the core applies when the game runs.
- **`discdb.yaml`**: one entry per disc, with each track's size and MD5, so a dump can be checked against known
  discs (the desktop app's game properties, *Verify*).

The database grows from **our own testing and public sources such as redump.org**, never from DuckStation's
database after commit `a5bf1ba5cee7` (the fork point), which isn't free. Every addition must be traceable to one of
these sources. A weekly job proposes redump.org's new discs ([The redump import](#the-redump-import)); fixes found
in testing are added by hand ([Adding a fix from testing](#adding-a-fix-from-testing)).

## How the core finds an entry

The core never reads a printed serial. It names a disc after its boot executable (`SCES_123.45` becomes
`SCES-12345`, `System::GetGameDetailsFromImage`), or, for a disc without one, after a hash of the executable
(`HASH-…`). That name is looked up in `gamedb.yaml`:

- An entry **without `codes`** is found by its key.
- An entry **with `codes`** is found by each of them, **and not by its key** unless the key is also in `codes`. Use
  `codes` for a game released under several serials.
- If two entries claim the same serial, whichever loads first wins and the other is never found. The check (below)
  rejects such clashes, except the 14 the base came with, listed in `scripts/gamedb/known-clashes.txt`.

`discdb.yaml` is looked up the other way round: by track MD5. Several discs sharing a track (the same audio on
two regions' discs) is normal.

## The files' layout

Both files keep one fixed layout, which yamllint (`extras/yamllint-config.yaml`) and `scripts/gamedb/gamedb_check.py`
enforce: two-space indentation, keys at the start of a line, strings in double quotes, no blank lines, one newline at
the end. Keep new entries in that layout; the check rejects lines it doesn't recognise rather than guess.

### `gamedb.yaml`

```yaml
SLPM-87395:
  name: "Chrono Cross (Japan) (Disc 1)"
  discSet:
    name: "Chrono Cross (Japan)"
    serials:
      - SLPM-87395
      - SLPM-87396
  compatibility:
    rating: NoIssues
  controllers:
    - AnalogController
    - DigitalController
  traits:
    - ForceRecompilerICache
    - DisableWidescreen
  settings:
    dmaMaxSliceTicks: 30 # Very sensitive to DMA timing, otherwise polygon flicker.
    dmaHaltTicks: 100 # CPU needs to run significantly faster than DMA.
  codes:
    - SLPM-87395
    - HASH-753EB8647043CE02
    - SLPS-02777
    - SLPS-91464
  metadata:
    publisher: "Squaresoft"
    developer: "Squaresoft"
    releaseDate: "1999-10-21"
    genre: "Tactical RPG"
    languages:
      - Japanese
    minPlayers: 1
    maxPlayers: 1
    minBlocks: 1
    maxBlocks: 1
    vibration: false
    multitap: false
    linkCable: false
```

An entry from the file as shipped. Comments after a value are fine; say why a setting is there.

| Field | Meaning |
| --- | --- |
| `name` | The game's name; redump's style, "Title (Region) (Notes)", for new entries. |
| `codes` | The serials (or `HASH-…` ids) this entry is found by; see above. |
| `compatibility` | `rating` (`Unknown`, `DoesntBoot`, `CrashesInIntro`, `CrashesInGame`, `GraphicalAudioIssues`, `NoIssues`), `versionTested`, and optional `comments`. |
| `traits` | Fixes the core forces on for the game, by name: `ForceInterpreter`, `ForceSoftwareRenderer`, `ForceSoftwareRendererForReadbacks`, `ForceRoundTextureCoordinates`, `ForceAccurateBlending`, `ForceInterlacing`, `DisableAutoAnalogMode`, `DisableTrueColor`, `DisableUpscaling`, `DisableTextureFiltering`, `DisableSpriteTextureFiltering`, `DisableScaledDithering`, `DisableForceNTSCTimings`, `DisableWidescreen`, the `DisablePGXP…` and `ForcePGXP…` family, `ForceRecompilerMemoryExceptions`, `ForceRecompilerICache`, `ForceRecompilerLUTFastmem`, `IsLibCryptProtected` (the full list is `s_trait_names` in `src/core/game_database.cpp`). |
| `libcrypt` | `true` if the disc has LibCrypt protection (it then needs its SBI file). |
| `settings` | Per-game values: display offsets and crop/deinterlacing modes, DMA timing (`dmaMaxSliceTicks`, `dmaHaltTicks`), GPU FIFO and run-ahead, PGXP tolerance and depth threshold, line detection. See `ParseYamlEntry`. |
| `discSet` | A multi-disc game's set name and its discs' serials, in order. |
| `controllers` | The controller types the game supports, from `Controller` type names. |
| `metadata` | Publisher, developer, `releaseDate` (`YYYY-MM-DD`), genre, languages, players, memory card blocks, and whether it has vibration, multitap and link cable support. |

### `discdb.yaml`

```yaml
SLPS-02110:
  name: "'99 Koushien (Japan)"
  trackData:
    - tracks:
        - size: 314812848
          md5: "7bdc7092aef04c6bec7e78cdfe3d9a81"
        - size: 16800336
          md5: "0e56bd53edeccf372fe863b3e88afc30"
    - version: "Rev 1"
      tracks:
        - size: 314812848
          md5: "…"
```

The key is the disc's serial in the core's form; `name` is redump's name for it. `trackData` has one track set per
version of the disc (a `version` names any but the first), and each track is a `.bin`'s size in bytes and MD5, in
the cue sheet's order. A few older entries quote the size (`size: '244939632'`); new ones don't.

## Adding a fix from testing

1. Find the game's entry by the serial the core gives it (the desktop game list's Serial column; `HASH-…` for a
   disc without a serial). If there is none, add one at the end of `gamedb.yaml` with at least its `name`.
2. Add the trait, setting or `compatibility` change, and nothing else in the same commit.
3. Open one pull request per game saying **the game, what was seen, and on what** (device or OS, renderer, the
   build's version), and how the change fixes it. Fixes are only ever from our own testing or a public, attributable
   source; say which.

**Rating compatibility:** set `rating` to what you saw on a full play-through or as far as you got (say which in
`comments`), and `versionTested` to `"GooseStation "` followed by `git describe`'s output for the build you tested (the version the app's About shows)
(`"GooseStation 0.1-604-g9c4c4d0e6"`). The prefix keeps our ratings apart from the base's, whose versions have
the same shape (`"0.1-1308-g622e50fa"`); a rating without it came with the base and hasn't been rechecked.

**Before pushing**, run the checks the pull request will run:

```bash
python3 scripts/gamedb/gamedb_check.py
```

```bash
yamllint -c extras/yamllint-config.yaml -s data/resources/gamedb.yaml data/resources/discdb.yaml
```

`gamedb-lint.yml` runs both, and the scripts' tests, on every pull request that touches the files or
`scripts/gamedb/`. The core's tests (`common-tests`, `GameDatabaseShippedFiles.*`) also load both files through
`GameDatabase` where they're built.

## The redump import

redump.org publishes the PS1 discs it has verified, with each track's MD5, as a public list.
`.github/workflows/gamedb-import.yml` runs every Monday at 05:23 UTC, and by hand from the Actions tab
(*GameDB redump import*, *Run workflow*). It:

1. downloads `http://redump.org/datfile/psx/serial,version` (the list with serials and versions; HTTPS first, plain
   HTTP if that fails), once, as `GooseStation gamedb import`;
2. checks the list's shape: a zip holding one `.dat` of at most 64 MiB, a Logiqx datafile named "Sony - PlayStation"
   whose disc count matches its header and is at least 10,900, a date as its version, valid tracks, and serials.
   Anything else stops the job with a message and opens nothing;
3. runs `scripts/gamedb/redump_import.py` on `master`, which adds **only discs we don't know**, and **never changes
   an existing entry**:
   - a disc is known when its tracks (sizes and MD5s, in order) equal one of `discdb.yaml`'s track sets, whatever
     serial redump prints for it;
   - a new disc gets a `discdb.yaml` entry under its **first serial in the core's form** (four letters, dash, five
     digits; `SLPM 86770` is read as `SLPM-86770`), and, if that serial is neither a key nor a code in `gamedb.yaml`,
     a name-only `gamedb.yaml` entry with redump's name;
   - entries are appended at the end of each file, sorted by name, so the diff is only additions;
4. runs yamllint and the check on the result;
5. pushes the branch `gamedb/redump-import` (rebuilt from `master` each time) and opens one pull request,
   "GameDB: redump import <list date>", or updates the open one, then starts `gamedb-lint.yml` on it (a pull
   request made with the job's own token starts no other workflows). If nothing is new, it closes an open import
   pull request; if the files are the same as last run's, it leaves the branch and pull request alone.

It never merges. The pull request's description has the counts, a table by category and the added serials, then
**what wasn't added**, for a later decision: discs with no serial (only an executable hash could key them, which
redump's list doesn't have), discs whose serials aren't in the core's form (`SLUS-01041GH`, `DTL-S1234`), and new
discs whose serial `discdb.yaml` already has for other tracks (adding them would change an existing entry). Over
60,000 characters the lists are cut short; the run's summary page has them in full.

**Reviewing an import:** check a few added entries against redump.org's pages, and that the diff only adds. To
reject a disc, add its redump name or serial to `scripts/gamedb/redump-skip.txt` **on `master`** (in its own
pull request, or in the import's before merging it): each run rebuilds the import branch from `master`, so edits
made only on the import branch last until the next run. Closing the import pull request without a skip-list entry
proposes the same discs again once the list or `master` changes.

**Running it by hand** on a downloaded list (keep the download in a folder of its own; it is untrusted data):

```bash
python3 -I scripts/gamedb/redump_import.py --list /path/to/list.zip --dry-run --summary /tmp/summary.md
```

Without `--dry-run` it appends to the files in `data/resources/` (or `--resources DIR`). The scripts' tests:

```bash
python3 -m unittest discover -s scripts/gamedb/tests -t scripts/gamedb
```

**The one-time repository setting:** the job's token may open pull requests only once GitHub Actions is allowed
to: *Settings → Actions → General → Workflow permissions → Allow GitHub Actions to create and approve pull
requests*. The default workflow permissions can stay read-only; the import job asks for `contents`,
`pull-requests` and `actions` write for itself only. Until the setting is on, the job pushes its branch and then
fails at opening the pull request.
