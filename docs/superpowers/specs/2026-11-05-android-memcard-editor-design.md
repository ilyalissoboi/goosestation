<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android memory card editor — design

Part of roadmap item 16 (the Android frontend's "Saved data" stage), after memory card sync (#41). Agreed in
conversation on 2026-11-05. Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls
the builder may make are marked *(builder's call)*.

## Intent

The desktop app has a memory card editor (`src/duckstation-qt/memorycardeditorwindow.cpp`, with item 11's renaming).
The Android app shows memory cards nowhere: they live in the working copy's `memcards/`, mirrored to the data folder
and synced through emusync, but the player can't see, copy, rename, back up or clear a save. This adds an editor with
**everything the desktop editor does** *(the player's choice)*, adapted for a controller, on the Thor's 960 × 540 dp
and smaller screens.

## What exists

- **The core's card image code** (`src/core/memory_card_image.h`) does every operation: `LoadFromFile`,
  `SaveToFile`, `Format`, `EnumerateFiles` (with deleted saves), `GetFreeBlockCount`, `ReadFile`, `WriteFile`,
  `DeleteFile`, `UndeleteFile`, `SetFileName`, `GetRawTitle`/`SetTitle`, `ImportCard`, `ExportSave`, `ImportSave`.
  Each save's `FileInfo` has its title, file name, size, blocks, deleted flag and icon frames.
- **Item 11's rename rules** (`src/core/memory_card_helpers.{h,cpp}`): file-name checks, the usual-pattern warning,
  title encoding to Shift-JIS with the title's own style, and "shown exactly" checks; the desktop dialog's wording.
- **Card files:** on Android nothing sets the card type, so the core's defaults apply: card 1 per game title
  (`PerGameTitle`), card 2 none. A game's card is `<sanitized title>_1.mcd` in `EmuFolders::MemoryCards`, and only
  exists once the game has booted (`MemoryCard::Open` formats a missing card). Shared cards (`shared_card_N.mcd`)
  only exist when a desktop `settings.ini` or a game's ini says so, or were copied from desktop; `[MemoryCards]
  Card1Path`/`Card2Path` may name other files in the data folder.
- **Sync:** `MemcardSync::OnCardWritten` acts only on cards a game has open (`s_cards`), so it does nothing for the
  editor. The route that works is the one the mirror uses (`WithCardHeld`: hold a session on the card, whose end
  snapshots it). The desktop editor doesn't notify sync at all.
- **The settings page** Settings → Memory cards (`ui/MemcardSyncScreen.kt`, #41) holds sync's settings.

## Goals

### Where it opens

**Settings → Memory cards → Manage cards** *(the player's choice)*, a `NavRow` on the existing Memory cards page
(with the number of cards as its summary *(writer's call)*). Like every settings page except Folders, it needs the core
and is **locked while a game runs**, so the editor never edits a card a game has open.

### The layout: two cards side by side

Chosen from mockups (option A, like desktop):

- The screen's title "Memory cards", the unsaved-changes count ("1 change not saved") beside it, and **Discard**
  (`Secondary`) and **Save** (`Primary`, the one `beak` fill; disabled with no changes) at the top right.
- **Two panes**, left and right, each a `surface` panel with:
  - **a card picker** at the top: the card's name and "N of 15 blocks free" (or "No card yet"); A opens a list (the
    design system's choice list, #34) of the cards below;
  - **the card's saves**, one row each: the save's icon (its first frame, *(writer's call)*: no animation), its
    title, its file name in `mono`, and its blocks ("1 block", "3 blocks"). **Deleted saves stay listed**, greyed
    (`ink-faint`, "Deleted · <title>"), and can be undeleted;
  - **the card's own actions** under its list: **Import save**, **Import card**, **Format** (on a "No card yet" card,
    Import save and Import card only).
- **L1 and R1** move between the panes (and so do Left and Right at a pane's edge *(writer's call)*); the focused
  pane is marked. `KeyHints` at the foot: **A** Actions, **B** Back, **L1/R1** Other card.
- On a screen too narrow for both panes (under about 720 dp wide), one pane shows at a time with the other's name as
  a tab, L1/R1 switching *(writer's call)*.
- Both panes may not show the same card; picking the other pane's card swaps them *(writer's call)*.

### Which cards

*(The player's choice after the builder check.)*

- **One card per game in the library**, at the path that game will use: `System::GetGameMemoryCardPath(serial, path,
  slot)` for each library game and slot that has a card type (new JNI), titled by the game. That includes games not
  played yet: a card that doesn't exist shows **"No card yet"** and is created (formatted, then written) the first
  time Save writes a save to it, so an imported or copied save is where the game will find it. A multi-disc game is
  one entry (part 2's grouping).
- **Plus the other card files that exist:** shared cards ("Shared card 1", …), cards named by `[MemoryCards]
  Card1Path`/`Card2Path` anywhere in the data folder *(writer's call)*, and any other `.mcd` in
  `EmuFolders::MemoryCards` (by its file name without `_N.mcd`).
- **No New card.** Cards from outside the data folder come in through **Import card** onto one of these.
- Match cards to games by computed path, never by parsing a file name (`Path::SanitizeFileName` is lossy). Don't use
  `GetGameMemoryCardPath`'s `Shared` branch to name shared cards (it passes `slot + 1`, `system.cpp:5430`).

### A save's actions

**A on a save** opens a small panel over the editor (drawn in the app's window, not a window dialog), titled with the
save's title, with *(the player's choice of actions)*:

- **Copy to <the other card's name>**, first and focused. Refused with the reason when the other card lacks free
  blocks ("Shared card 1 needs 3 free blocks; it has 1."). When the other card has a save of the same file name, it
  asks first: "Replace the save of the same name on Shared card 1?" (**Replace** `Danger`, **Cancel** `Ghost`,
  focused) *(writer's call)*.
- **Rename…**: the title and the file name, with item 11's rules, encoding and warnings (the same messages as the
  desktop dialog, through `memory_card_helpers`), using Android's keyboard.
- **Export…**: Android's file picker (`ACTION_CREATE_DOCUMENT`) to save the save as `.mcs` *(the player's choice)*.
- **Delete** (`Danger`), as on desktop *(writer's call)*: marks the save deleted (`DeleteFile(…, clear_sectors =
  false)`); it stays listed, greyed, and can be undeleted until its blocks are reused, also after Save. It asks first:
  "Delete <title>? You can undelete it while its blocks aren't reused."
- **On a deleted save:** **Undelete** (refused with the reason if its blocks were reused), and **Delete
  permanently** (`Danger`, `clear_sectors = true`), which asks first: "Erase <title> for good? It can't be
  undeleted."

B or Back closes the panel.

### A card's actions

- **Import save**: Android's file picker (`ACTION_OPEN_DOCUMENT`), any save format the core imports.
- **Import card**: the file picker; it **replaces everything on the card**, so it asks first ("Replace everything on
  <card> with <file>?").
- **Format**: asks first ("Erase every save on <card>?"), **Erase** `Danger`, **Cancel** focused.

All read and write through the picker's `content://` document *(the player's choice)*, never a path.

### Saving

- **Edits stay in the editor until Save** *(the player's choice)*: the panes edit copies of the cards in memory.
- **Save** writes each changed card to its file (the core's atomic write) and the data folder's mirror pushes it. It
  shows "Saved" in the page's message line.
- **Sync on Save** *(the player's choice)*: only **cards sync already knows** (cards a game has used on this device,
  or that came from another device) are synced, through the mirror's route (`WithCardHeld`), one change per card. Any
  other card stays on this device until a game uses it, so old or backup cards aren't uploaded to every device.
- **When sync is on**, Delete, Delete permanently, Format and Import card on a card sync knows add a line to their
  question: "Memory card sync is on: your other devices will see these saves removed." *(writer's call on the
  wording)*.
- **Discard** drops the edits (asks first if there are any).
- **Leaving with unsaved changes** (B, Back, or the system's Back) asks **Save**, **Discard** or **Keep editing**
  (focused) *(writer's call on the third)*. The app going to the background keeps the edits in memory; if the process
  dies they're lost, with no notice afterwards *(writer's call)*.
- A card file that changed under the editor (a sync pull or the mirror while it's open) is reloaded only if it has no
  edits; with edits, Save asks whether to overwrite it *(writer's call)*.

## Non-goals

- Editing while a game runs, or from the in-game menu.
- Opening card files from outside the data folder (Import card covers it), multitap cards, icon extraction (dropped
  from item 11).
- Changing the desktop editor.

## Anti-goals

- A save lost without the player confirming it: every destructive action asks, and nothing is written before Save.
- A card written while a game has it open.
- A card change that sync or the mirror misses.
- The UI thread waiting on card I/O or the core.
- Breaking the design system or controller-first rules: everything reachable with the D-pad, focus always visible,
  no window dialogs, one `beak` fill.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding; the editor and its panels go into
  `FocusAuditTest` and `OrientationLayoutTest` (`@LargeTest` where heavy), with screen tests driven by keys; run the
  quick tier while building and the full tier before the PR (`android/scripts/run-instrumented-tests.sh`).
- Card operations go through the core's `memory_card_image` and `memory_card_helpers` over JNI, on the CPU thread; the
  rename rules aren't reimplemented in Kotlin.
- Test cards are made by the tests (formatted cards with saves written by the core), never a player's real saves.
- No desktop behaviour change; clean room; the user reviews every commit message.

## Known groundwork

- The core reads `content://` but never writes through it: Export writes the `.mcs` to a temporary path and the app
  copies it to the picked document.
- `ImportSave` and `ImportCard` pick the format from the file name's extension, and a raw save takes its name from
  the file title: pass the picker's display name along with the bytes; `ImportSave` needs an in-memory variant.
- The rename messages live in the Qt editor's strings, not `memory_card_helpers` (only the error enums are shared):
  the Android wording is written for it, matching the desktop's meaning.

## Left to the builder

How cards and save icons cross JNI; how the in-memory copies are held; the panels' exact layout within the design
system; the order of implementation.
