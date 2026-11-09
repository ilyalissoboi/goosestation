<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android cover downloads — design

Part of roadmap item 16 (the Android frontend's "cover downloading"). Agreed in conversation on 2026-11-09. Calls
made while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may make are marked
*(builder's call)*.

## Intent

The library shows covers only when they're already in `covers/` in the data folder; nothing fetches them. This lets
the player download covers from inside the app, **from sources they choose, as on desktop** *(the player's choice)*,
with **one preset source** filled in that they can edit, paste over or remove *(the player's choice)*.

## What exists

- **The core's downloader** (`GameList::DownloadCovers`, `src/core/game_list.h:128-130`, `game_list.cpp:1363`):
  takes URL templates with `${title}`, `${filetitle}` or `${serial}` (refusing only when no template has one,
  `game_list.cpp:1369-1383`), goes through **every core entry** (each disc, playlists, disc sets, which Android's
  library hides, `jni_bridge.cpp:2296, 2382-2387`), skips entries that already have a cover
  (`GetCoverImagePathForEntry`), and saves new covers under `covers/` by title, or by serial when asked or when the
  title can't be a file name (`game_list.cpp:991-999`; with no serial that's `covers/.jpg`). Its progress counts URLs,
  its status text is fixed English, and it can't cancel a request in flight (the requests don't get the callback,
  `game_list.cpp:1446`; `http_downloader.cpp:14, 117`). Its path helpers (`GetCoverImagePath`,
  `GetNewCoverImagePathForEntry`) are what the library reads covers by.
- **Desktop's dialog** (`src/duckstation-qt/coverdownloaddialog.*`): one template per line, "Use Serial File Names",
  Start and Close; it saves nothing, and says "We do not host any cover images, the user must provide their own source
  for images."
- **Android:** the library shows `covers/` (mirrored with the data folder); its HTTP goes through `HttpBridge`
  (`android/app/src/cpp/http_downloader_android.cpp`, whole responses in memory, fine for covers); the settings hub
  has twelve pages ending with Advanced and Folders, locked while a game runs.
- **The preset source**, xlenore's PSX cover collection (`github.com/xlenore/psx-covers`), whose README gives the
  template for this kind of downloader: `https://raw.githubusercontent.com/xlenore/psx-covers/main/covers/default/${serial}.jpg`,
  with serial file names. The repository has no licence file.

## Goals

### The Covers page

*(The player's choice: a page in Settings, after Folders.)*

- **Sources:** a list of sources, **each a `TextFieldRow`** (one URL template per row, Android's keyboard and pasting),
  with **Add a source** after them and **Remove** on each, rather than one multi-line field, so the design system's
  single-line text rows and D-pad rules hold unchanged *(writer's call)*. A note under them lists the variables
  (`${serial}`, `${title}`, `${filetitle}`) and that GooseStation hosts no covers itself *(writer's call on the
  wording)*. Every source must hold at least one variable: one without says so on its row, and Download covers waits
  until it's fixed or removed ("Each source needs ${serial}, ${title} or ${filetitle}.") *(writer's call: stricter than
  the core, which fetches such a line as written for every game)*.
- **The preset** *(the player's choice)*: while no sources have ever been saved, the list holds the preset template
  above as its one source. Editing, pasting over or removing it saves the player's list, and an emptied list stays
  empty (the preset doesn't come back on its own) *(writer's call)*. **Restore the preset source** puts it back, asking first when that
  replaces the player's lines.
- **Save covers by serial:** a switch, on by default since the preset needs it *(writer's call)*; off saves by title,
  as desktop's default.
- **Download covers** *(the player's choice: only when asked)*: fetches covers for **the library's tiles** that show
  none, never replacing a cover already there *(writer's call: per tile, not per core entry, so discs 2 onward and
  hidden disc sets aren't fetched, and the counts are games)*. Each tile's cover is fetched for the entry the library
  reads its cover from (disc 1, or the playlist), trying the sources in order until one answers, and saved where the
  core's own path helpers put it, so desktop finds it too. With Save by serial on and no serial, it saves by title
  *(writer's call, never `covers/.jpg`)*.
- **Android runs the loop** rather than calling `GameList::DownloadCovers` as it is *(writer's call)*: it uses the
  core's path helpers and the host's HTTP downloader, passing the progress callback so **Cancel stops the request in
  flight** at once.
- While it runs, the page shows progress ("Downloading the cover for Crash Bandicoot … 12 of 48") and **Cancel**; at
  the end, a summary ("Found 31 covers. 17 games had none at these sources.") *(writer's call on the wording)*. With
  no network, it says so.
- **Leaving the page doesn't stop it** *(writer's call)*: the download belongs to the app while it's in front, the
  library shows covers as they land, and reopening the page shows its progress. Starting a game or the app going to
  the background cancels it, with the covers found so far kept.
- The library shows new covers as they land, or at the latest when the page closes *(builder's call)*; they reach
  the data folder through the mirror like any file.

### Where it's kept

- The sources and the switch are kept in `settings.ini` *(writer's call)*, in a section of their own desktop ignores
  (desktop's dialog saves nothing) *(builder's call on the key names)*, so they follow the data folder to another
  Android install.

## Non-goals

- Downloading automatically, after a scan or in the background *(the player's choice: only when asked)*.
- Replacing covers already present, picking a cover by hand, or 3D covers (a player can enter that template).
- Changing desktop's dialog.

## Anti-goals

- Anything sent to the network without the player pressing Download covers.
- A cover the player already has overwritten.
- The preset coming back after the player removed it.
- The UI thread waiting on downloads; a download that can't be cancelled.
- Breaking the design system or controller-first rules.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding; the page goes into
  `FocusAuditTest` and `OrientationLayoutTest`, with screen tests driven by keys; the quick tier while building and
  the full tier before the PR (`android/scripts/run-instrumented-tests.sh`).
- The download runs natively over JNI, off the UI thread, with the core's path helpers and HTTP downloader, rather
  than a second downloader in Kotlin *(writer's call)*. Shared code may gain small helpers, but desktop's behaviour
  (its dialog and `DownloadCovers`) doesn't change.
- The test emulators can't reach GitHub over HTTPS (a TLS-intercepting proxy here), so tests serve covers from a local
  server; a real download from the preset is the user's check on the Thor, with their go-ahead.
- Android-only code in `android/`; no desktop change. Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor
  its closed Android app. `docs/android.md` and the roadmap (item 16) are updated. The user reviews every commit
  message.

## Left to the builder

The page's exact layout within the design system; how progress and cancel cross JNI; the key names; when the
library reloads covers; the order of implementation.
