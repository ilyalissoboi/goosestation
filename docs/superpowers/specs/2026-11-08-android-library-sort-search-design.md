<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android library sort and search — design

Part of roadmap item 16 (the Android frontend). Agreed in conversation on 2026-11-08, with mockups:
[2026-11-08-android-library-sort-search-mockup.html](2026-11-08-android-library-sort-search-mockup.html) (v3, the
one agreed). Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls the builder may
make are marked *(builder's call)*.

## Intent

The library shows every game in one fixed order, as a grid or a list, and gets harder to use as it grows. This adds
**sorting and searching** *(the player's choice; no filters)*, and makes the top bar **compact with icon buttons**
*(the player's choice)*, since adding sort and search to today's labelled buttons would make it too long.

## What exists

- **The library** (`ui/LibraryScreen.kt`, `ui/GameTile.kt`, `ui/GameListRow.kt`; `docs/android.md`, "Running it"):
  a cover grid or a list (`[UI] GameListGridView`), a top bar of buttons with an icon and a word (**List**/**Grid**,
  **Settings**, **Refresh**, the last drawn with `GsIcons.sync`) that wraps in a `FlowRow` (`LibraryScreen.kt:135-171`),
  the sync badge when sync is on, and banners. `OrientationLayoutTest` checks it at 640×360 (the Thor), 800×360 and
  360/411 dp portrait, at text 1.0× and 1.3× (`OrientationLayoutTest.kt:200-226`). A plays, X opens a game's Saves, Y its settings; a long
  press offers both by touch. The last game played is focused on return. Multi-disc games are one tile.
- **Big Picture's sort** (`fullscreen_ui.cpp:7687-7760, 8469-8482`): `[Main] FullscreenUIGameSort` (0 Type, 1
  Serial, 2 Title, 3 File title, 4 Time played, 5 Last played, 6 File size, 7 Uncompressed size; default 0) and
  `FullscreenUIGameSortReverse` (default off). Last played is most recent first unless reversed; the others ascend
  unless reversed; ties fall back to title, reversed with the rest (`fullscreen_ui.cpp:7787-7789`); File title
  compares only up to the shorter name (`:7739-7741`).
- **What reaches Kotlin** (`core/GameEntry.kt:17-27`, built at `jni_bridge.cpp:2398-2400`): path, title, key, region,
  play time, last played, cover, discs, playlist flag; **no file size, uncompressed size or entry type**. The native
  list arrives sorted by title (`jni_bridge.cpp:2367-2369`). Android groups discs itself (`AndroidDiscGroups`).
- **Icons** (`docs/design-system/icons/`, made into `GsIcons` by `generate-gsicons.py`, which takes stroke-only paths
  of M, L, H and V, and the design system draws octagons for circles, `design-system.md:495, 501`): `search`, `list`
  (three plain lines, also the memory card picker's, `MemcardEditorScreen.kt:423`), `grid`, `settings`, `sync` (also
  the sync badge's "Syncing…") and the rest; no sort, order or bulleted-list icon. Icons go with a word unless their
  meaning is universal (`design-system.md:498-499`).
- **The design system's choice list** opens in the page under its row, never a window (`design-system.md:261`;
  `ChoiceList.kt:105` is built for settings rows), and `checkNoWindowDialogs` bans `Popup`.

## Goals

### The top bar

*(The player's choice: icon buttons; the v3 mockup.)*

- **Left to right:** the logo and "Library"; the **search field** taking the remaining width; the sync badge (when
  sync is on, as today, with its word); then 48 dp **icon buttons**: **Sort**, **Order**, **View**, **Refresh**,
  **Settings**.
- **Icons:** Sort is new (three shortening lines); Order is new (an arrow, pointing up for the first direction and
  down when reversed); **View shows a bulleted list in grid mode** (it switches to the list) **and the grid in list
  mode** *(the player's choice)*: a new `list-bulleted` icon, its bullets small stroked squares, leaving `list.svg`
  (the memory card picker's) as it is; **Refresh is a new single arrow turning round an octagon**, so it can't be
  mistaken for the sync badge's two arrows *(writer's call; the mockup's round arc redrawn to the set's rules)*;
  Settings keeps its icon. New icons follow the set (24 px grid, 2 px square-capped strokes, M/L/H/V paths, no fills)
  and go into `docs/design-system/icons/` and `GsIcons`.
- **Labels:** the focused icon button shows a label under it with its name and value ("Sort: Title", "Order: A to Z",
  "Show as a list", "Refresh the library", "Settings"), and screen readers say the same (content description) *(writer's
  call)*. **By touch, a long press shows the same label** (a tap acts) *(writer's call)*. Touch targets stay 48 dp.
  The design system's rule on icons and words gains this exception: icon buttons with a label on focus and long press.
- **When the bar doesn't fit on one row** (the Thor's 640 dp at text 1.3×, phones in portrait, a sync badge with a
  long word), **the search field moves to its own full-width row under the bar**, which keeps the logo, "Library", the
  badge and the icon buttons *(writer's call)*. Up from the first game reaches the search row, Up again the buttons.
  The focus label never covers the row under it (it sits in the bar's own space or the gap) *(builder's call how)*.
- **The count line** under the bar always says the game count and the current sort ("48 games · Title, A–Z"), and
  while searching, the matches ("2 of 48 games · Title, A–Z") *(writer's call)*.

### Sort

- **Sort** opens a list under its button, the current choice marked, drawn inside the screen's layout like the design
  system's choice list (never a window or `Popup`), a new variant for a button rather than a settings row *(builder's
  call on the component)*. The choices *(writer's
  call: Big Picture's, less the two that mean little here)*: **Title** (Android's default when the key isn't set),
  **Last played**, **Time played**, **Serial**, **File title**, **File size**. Type and Uncompressed size are left
  out; a value from desktop that Android doesn't offer (0 Type, 7 Uncompressed size) is applied as desktop applies it
  and shown by its name, as settings pages show values from `settings.ini` *(writer's call)*. (The first mockup's
  "Region" isn't offered: Big Picture has no region sort, and the keys are shared.)
- **Order** reverses it, and means exactly what Big Picture's switch means, so both read the same keys the same way
  *(writer's call)*: Title, Serial and File title A to Z; Time played and File size least first; Last played most
  recent first; reversed, each the other way. Its label names the direction ("Order: Most recent first"). Ties fall
  back to title, reversed with the rest, and File title compares as Big Picture does, so the two order alike.
- **Sizes and types reach Kotlin:** `GameEntry` gains each game's file size (and uncompressed size and entry type,
  for desktop's values); a multi-disc game's are its discs' summed, as desktop sums a disc set's, and its type its
  first disc's *(writer's call)*.
- **Kept in Big Picture's keys** (`[Main] FullscreenUIGameSort`, `FullscreenUIGameSortReverse`) in `settings.ini`,
  so a data folder shared with desktop sorts the same in Big Picture *(writer's call)*. They're written only once the
  player changes the sort, as every setting is (`docs/android.md`); until then Android sorts by Title while Big
  Picture's own default is Type *(writer's call)*.
- After a sort change, the focus goes back to the Sort button, as the design system's choice list returns it, and the
  grid keeps the game last focused in view, so Down reaches it wherever it moved *(writer's call)*.

### Search

- **The search field** narrows the grid or list as you type, matching anywhere in the title, the file name or the
  serial, ignoring case *(writer's call)*; the matched part is marked (`beak-soft` behind it). The sort applies to the
  matches.
- **On a controller:** the field is focusable like any control; A on it opens Android's keyboard (as `TextFieldRow`
  does). **Select jumps to the field and opens the keyboard** from anywhere in the library, for a quick search. Down
  from the field goes to the first match. **B with the keyboard up closes it** (the system takes it first); **B with a
  search and no keyboard clears the search**, and so does the ✕ in the field *(writer's call)*.
- **`KeyHints` follow the focus**, as the mockup shows: on a game, A Play · X Saves · Y Game settings · Select Search
  (with B Clear search while there's a search); on a top-bar button, A Choose · Select Search; with the sort list
  open, A Choose · B Close *(writer's call)*.
- **No match:** "No games match "zelda"." with a **Clear search** button. It takes the focus only when the keyboard
  closes on no match (or on Down from the field), never while typing passes through a no-match state *(writer's
  call)*.
- **The search clears** when a game starts *(writer's call)*; the sort is kept. The game played stays focused on
  return.

### What doesn't change

- Multi-disc games stay one tile; the long press, X, Y, banners and the sync badge work as today.

## Non-goals

- Filters (region, played or unplayed, favourites), letter jumps, or saved searches.
- Searching anything but the library (settings, saves).
- Changing Big Picture or desktop.

## Anti-goals

- An icon button whose meaning the player has to guess: each has a label on focus and a screen-reader description.
- The Refresh icon and the sync badge looking alike.
- A sort that reads Big Picture's keys differently from Big Picture.
- Focus lost or jumping to the top on a sort change or as a search narrows.
- Breaking the design system or controller-first rules: everything reachable with the D-pad, focus always visible,
  48 dp targets.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding; the top bar, sort list, search
  field and empty state go into `FocusAuditTest` and `OrientationLayoutTest` (grid and list, phone and Thor sizes),
  with screen tests driven by keys; the quick tier while building and the full tier before the PR
  (`android/scripts/run-instrumented-tests.sh`).
- Sorting and searching run off the UI thread or fast enough not to drop frames on a large library (a test with a
  thousand entries) *(builder's call how)*.
- Android-only code in `android/`; no desktop change. Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor
  its closed Android app. `docs/android.md`, `docs/design-system.md` (the icon buttons and new icons) and the roadmap
  (item 16) are updated. The user reviews every commit message.

## Left to the builder

The icon button component and its label's exact look within the design system; how matches are computed and
highlighted; the order of implementation.
