<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android library sort and search — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Android library sorts (Big Picture's keys and order) and searches (title, file name, serial), under a
compact top bar of 48 dp icon buttons with labels on focus and long press.

**Architecture:** Native code adds each game's file size, uncompressed size and type to `GameEntry`. Kotlin does the
rest: pure, JVM-tested `library/` code sorts exactly as `FullscreenUI::PopulateGameListEntryList` does, filters and
finds match ranges, and words the labels. `AppController` keeps the sort (read from and written to `[Main]
FullscreenUIGameSort` / `FullscreenUIGameSortReverse`) and the search text (cleared when a game starts). New
components (`IconActionButton` with `ActionLabelStrip`, `SearchField`) make up a new `LibraryTopBar`. The sort list
is the design system's `ChoiceList`, drawn in the screen's own layout under the Sort button.

**Tech Stack:** C++20 JNI (`android/app/src/cpp/jni_bridge.cpp`), Kotlin, Jetpack Compose, JUnit 4 on the JVM,
AndroidX instrumented tests on the emulators, `scripts/branding/generate-gsicons.py`.

**Spec:** `docs/superpowers/specs/2026-11-08-android-library-sort-search-design.md` (mockup v3 beside it).

## Global Constraints

- Android-only code in `android/` (and the icon SVGs plus docs). No change to `src/`, desktop or Big Picture. Clean
  room: nothing from DuckStation after `a5bf1ba5cee7`, nor its closed Android app.
- `docs/design-system.md` is binding: controller-first, focus always visible, 48 dp targets, no window dialogs (no
  `Popup`, no `Dialog`; `checkNoWindowDialogs` enforces it), `KeyHints` at the foot, layouts work at 360 dp wide
  with text at 1.3×. Every new state goes into `FocusAuditTest` and `OrientationLayoutTest`, grid and list.
- Keys: `[Main] FullscreenUIGameSort` (0 Type, 1 Serial, 2 Title, 3 File title, 4 Time played, 5 Last played, 6 File
  size, 7 Uncompressed size) and `[Main] FullscreenUIGameSortReverse`. Android reads an absent sort as **2 (Title)**,
  an absent reverse as false, and a number outside 0–7 as Title (Big Picture's switch falls through to the title
  fallback). Both keys are written only when the player picks a different sort or order.
- Offered sorts, in this order: Title, Last played, Time played, Serial, File title, File size. Type and Uncompressed
  size are shown and applied only when the file has them.
- New icons follow the set: 24 px grid, 2 px square-capped strokes, mitred, `<polygon>`/`<polyline>`/`<rect>`/`<path>`
  with absolute M, L, H, V only, no fills, octagons for circles, stroked `#14171c`. `list.svg` doesn't change.
  `GsIcons.kt` is only ever regenerated, never edited.
- **Copy, exact** (the spec's, plus the plan's where marked *plan*):
  - Button labels and content descriptions: "Sort: <sort>", "Order: <direction>", "Show as a list" (grid mode) /
    "Show as a grid" (list mode), "Refresh the library", "Settings".
  - Sort names: "Title", "Last played", "Time played", "Serial", "File title", "File size", "Type", "Uncompressed
    size".
  - Directions (first / reversed): Title, Serial, File title "A to Z" / "Z to A"; Last played "Most recent first" /
    "Least recent first" *(plan)*; Time played "Least played first" / "Most played first" *(plan)*; File size and
    Uncompressed size "Smallest first" / "Largest first" *(plan)*; Type "Discs first" / "PSFs first" *(plan)*.
  - Count line: "48 games · Title, A–Z", "2 of 48 games · Title, A–Z", "48 games · Last played, most recent first";
    "1 game" for one *(plan)*. A to Z shortens to "A–Z", Z to A to "Z–A"; other directions are lower-cased.
  - Search: placeholder "Search your games" when the field is at least 200 dp wide, else "Search" *(plan)*; content
    description "Search your games"; the ✕'s "Clear search". No match: "No games match “zelda”." and the button
    "Clear search".
  - `KeyHints`: on a game "A Play", "X Saves", "Y Game settings", "Select Search", then "B Clear search" while
    searching; on a top-bar button or Clear search "A Choose", "Select Search" (+ "B Clear search"); on the search field
    "A Type" *(plan)* (+ "B Clear search"); with the sort list open "A Choose", "B Close".
- Tests use made-up games; emulators only (the Thor needs the user's go-ahead; no scratch cards in its data folder).
- Commit each task; messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, shown to the user
  after each commit.

Kotlin paths below are under `android/app/src/main/java/io/github/ilyalissoboi/goosestation/` (tests under
`android/app/src/test/java/…` and `android/app/src/androidTest/java/…`).

## Running the tests

- JVM: `cd android && ./gradlew -q testDebugUnitTest --tests '<Class>'`, then `./gradlew -q ktlintCheck`.
- One instrumented class: `ANDROID_SERIAL=<emulator> ./gradlew connectedDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.ilyalissoboi.goosestation.<pkg>.<Class>`.
- Tasks touching the UI or the bridge end with `android/scripts/run-instrumented-tests.sh quick`, read from the
  result XML under `app/build/outputs/androidTest-results/`, not Gradle's verdict (it can say SUCCESSFUL with nothing
  run). `OrientationLayoutTest` is `@LargeTest`, so quick skips it: run it as one class after Tasks 6–8.
- Before the PR (Task 9): `./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`, then
  `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full` from a detached worktree, in
  the background.

## Review Focus

1. **A sort value from desktop that Android doesn't offer** (0 Type, Big Picture's own default once desktop has
   written it; 7; or a stray 12). It must sort as Big Picture does, show by name ("Sort: Type"), be current in the list,
   and never be rewritten until the player picks. Tests: Task 2 (`typeSortsDiscsFirst`, `anUnknownValueSortsAsTitle`),
   Task 7 (`aDesktopOnlySortShowsByName`, `LibraryPrefsTest`).
2. **The games list replaced while sorted or searching** (a refresh, a data folder pull, coming back from a game). The
   focused game must stay focused and in view if it's still shown, never jumping to the top. Test: Task 8
   (`aNewListWhileSearchingKeepsTheFocusedGame`).
3. **Titles outside ASCII** (CJK, accented). Search ignores case for any letter and marks the right characters;
   sorting folds only ASCII case, as `strcasecmp` does, so "Ébc" sorts before "éab" as on desktop. Tests: Task 2
   (`caseFoldsOnlyAscii`), Task 3 (`ignoresCaseBeyondAscii`).
4. **A query of spaces, or with spaces round it.** It's trimmed; a blank query is no search (no count "of", B not
   taken, no "No games match"). Test: Task 3 (`aBlankQueryIsNoSearch`), Task 8 (`spacesAloneAreNoSearch`).
5. **B with no search** must not be taken, so Back works as before; **B while the sort list is open** closes the list
   and doesn't clear the search. Tests: Task 8 (`bWithoutASearchIsLeftAlone`), Task 7 (`bClosesTheSortListAndKeepsTheSearch`).

---

### Task 1: Sizes and type reach `GameEntry`

**Files:**
- Modify: `android/app/src/cpp/jni_bridge.cpp` (`getGameList` ~2259-2420; the constructor lookup ~914-918)
- Modify: `core/GameEntry.kt`
- Test: `android/app/src/androidTest/java/io/github/ilyalissoboi/goosestation/library/LibraryScanTest.kt`

**Interfaces:**
- Produces: `GameEntry.fileSize: Long = 0`, `GameEntry.uncompressedSize: Long = 0`, `GameEntry.typeOrder: Int = 0`
  (the core's `GameList::EntryType` as Big Picture sorts it: 0 disc, 2 PS-EXE, 3 playlist, 4 PSF), appended after
  `playlist`; the native constructor gains `fileSize: Long, uncompressedSize: Long, typeOrder: Int` at its end.

- [ ] **Step 1: Write the failing test** `sizesAndTypesReachKotlin` in `LibraryScanTest`. Add to `FILES` a name-grouped
  pair in the tree's root: `"Game D (Disc 1).cue"` / `"Game D (Disc 1) (Track 1).bin"` and the same for Disc 2 (each
  `bin()`), so `secondRefreshUsesTheCache`'s listed folders don't change. Assert:
  - `GAME C.PSEXE`: `fileSize == 2048`, `uncompressedSize == 2048`, `typeOrder == 2`.
  - `Game A.cue`: `typeOrder == 0`, `uncompressedSize == 300L * 2352`, `fileSize > 0`.
  - `Game B.m3u` (a playlist): `typeOrder == 3`, `uncompressedSize == 2L * 300 * 2352` (desktop sums a playlist's
    sub-images into its own entry).
  - Game D (one tile, two discs): `discs.size == 2`, `uncompressedSize == 2L * 300 * 2352`, `fileSize` equal to the
    sum of its two discs' sizes as each alone would report (assert `== 2 * gameA.fileSize`, the same cue and bin
    shape).
- [ ] **Step 2: Run it** (one class on an emulator). Expected: FAIL, unresolved `fileSize`.
- [ ] **Step 3: Implement.** In `getGameList`, copy `file_size`, `uncompressed_size` and `GetSortType()` into `File`
  under the lock. Per game: a **playlist** takes its playlist file's own values (the file at the game's path, as
  desktop lists the M3U as one entry with its sub-images' size summed); **any other game** sums its files' sizes, as
  desktop sums a disc set's (`game_list.cpp:919-920`), and takes its first file's type. Pass them through
  `NewObject`, and change the signature to
  `"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJLjava/lang/String;[Lio/github/ilyalissoboi/goosestation/core/GameDisc;ZJJI)V"`.
  In `GameEntry`, the three fields with defaults, the secondary constructor's three new trailing parameters, and the
  KDoc.
- [ ] **Step 4: Run** `LibraryScanTest` (all four plus the new one). Expected: PASS. Then `quick`, reading the XML.
- [ ] **Step 5: Commit** "Android: a game's file size, uncompressed size and type reach the library".

### Task 2: Big Picture's sort, in Kotlin

**Files:**
- Create: `library/LibrarySort.kt`, `library/GameFiles.kt`, `library/LibraryWords.kt`
- Test: `library/LibrarySortTest.kt`, `library/GameFilesTest.kt` (JVM)

**Interfaces:**
- Consumes: Task 1's `GameEntry` fields.
- Produces:
  - `enum class SortKey(val value: Int) { Type(0), Serial(1), Title(2), FileTitle(3), TimePlayed(4), LastPlayed(5),
    FileSize(6), UncompressedSize(7) }` with `companion fun of(value: Int): SortKey` (unknown → `Title`) and
    `val OFFERED: List<SortKey>` = Title, LastPlayed, TimePlayed, Serial, FileTitle, FileSize.
  - `data class LibrarySort(val key: SortKey = SortKey.Title, val reverse: Boolean = false)`.
  - `object LibraryOrder { fun sorted(games: List<GameEntry>, sort: LibrarySort): List<GameEntry> }`.
  - `object GameFiles { fun fileName(path: String): String; fun fileTitle(path: String): String }`.
  - `object LibraryWords { fun sortName(key: SortKey): String; fun direction(sort: LibrarySort): String;
    fun shortDirection(sort: LibrarySort): String }` (copy from Global Constraints).

- [ ] **Step 1: Write the failing tests.** `GameFilesTest`: `content://com.android.externalstorage.documents/tree/primary%3AGames/document/primary%3AGames%2FSet%2FGame%20B.m3u`
  → name `"Game B.m3u"`, title `"Game B"`; `/games/padtest.psexe` → `"padtest.psexe"` / `"padtest"`; a name without a
  dot is its own title; a `+` in the document id stays a `+`. `LibrarySortTest`, each on a few `GameEntry`s:
  - `titleAscendsByDefault`, and reversed descends.
  - `anUnknownValueSortsAsTitle`: `SortKey.of(12) == SortKey.Title`.
  - `lastPlayedIsMostRecentFirst`; reversed, least recent first.
  - `timePlayedAndSizesAscend`: TimePlayed, FileSize, UncompressedSize least first; reversed most first.
  - `serialComparesBytes`: `"SLUS-1"` before `"slus-0"` (case-sensitive, as `std::string`'s `<`).
  - `typeSortsDiscsFirst`: typeOrder 0, 2, 3, 4 in that order; reversed 4, 3, 2, 0.
  - `tiesFallBackToTitleReversedWithTheRest`: equal play times sort by title A–Z, and Z–A when reversed.
  - `fileTitleComparesOnlyUpToTheShorterName`: file titles "Crash" and "Crash 2" tie, so titles decide.
  - `caseFoldsOnlyAscii`: titles "apple" before "Banana"; "Ébc" before "éab" (`strcasecmp` folds ASCII only, bytes
    otherwise).
  - `wordsForEachSort`: `direction` and `shortDirection` for every key, both ways ("A to Z"/"A–Z", "Most recent
    first"/"most recent first").
  - `aThousandGamesSortInAFrame`: 1,000 generated games, every key both ways, after a warm-up the median sort under
    8 ms.
- [ ] **Step 2: Run them.** Expected: FAIL, unresolved names.
- [ ] **Step 3: Implement.** A comparator that mirrors `fullscreen_ui.cpp:7713-7790` case by case, with two private
  helpers: an ASCII-only case-folding compare over code points (UTF-8 byte order is code point order) with a
  "only up to the shorter" mode for File title, and a plain code point compare for serials. `sortedWith` is stable,
  so full ties keep the native title order. `GameFiles.fileName` decodes a `content://…/document/<id>` path's id as
  `DataFolderCheck.kt:150` does (move that decode to a shared internal function rather than copy it) and takes what
  follows its last `/`; any other path, what follows its last `/`. `fileTitle` drops from the last `.`.
- [ ] **Step 4: Run them** and `ktlintCheck`. Expected: PASS.
- [ ] **Step 5: Commit** "Android: the library's sort, as Big Picture sorts".

### Task 3: Search matching and the count line

**Files:**
- Create: `library/LibrarySearch.kt`; Modify: `library/LibraryWords.kt`
- Test: `library/LibrarySearchTest.kt` (JVM)

**Interfaces:**
- Consumes: `GameFiles.fileName`, `LibrarySort`, `LibraryWords`.
- Produces: `object LibrarySearch { fun query(text: CharSequence): String?` (trimmed; null when blank)
  `fun matches(game: GameEntry, query: String): Boolean; fun filter(games: List<GameEntry>, query: String?):
  List<GameEntry>; fun match(text: String, query: String?): IntRange? }` and `LibraryWords.countLine(shown: Int,
  total: Int, searching: Boolean, sort: LibrarySort): String`.

- [ ] **Step 1: Write the failing tests:**
  - `matchesTitleFileNameOrSerialIgnoringCase`: "BAND" finds "Crash Bandicoot"; "scus-949" its serial; "crash.cue"
    its file name; anywhere in each.
  - `matchesAnyDisc`: a two-disc game is found by its second disc's serial and its second disc's file name.
  - `aBlankQueryIsNoSearch`: `query("   ") == null`, `query("  crash ") == "crash"`, `filter(games, null) == games`.
  - `matchGivesTheRange`: `match("Crash Bandicoot", "band") == 6..9`; no match gives null.
  - `ignoresCaseBeyondAscii`: "ÄRGER" matches "ärger" at 0..4; a Japanese title matches its own substring.
  - `countLines`: "48 games · Title, A–Z", "2 of 48 games · Title, A–Z", "1 game · Title, A–Z", "48 games · Last
    played, most recent first".
  - `aThousandGamesFilterInAFrame`: 1,000 games, median `filter` under 4 ms after a warm-up.
- [ ] **Step 2: Run them.** Expected: FAIL.
- [ ] **Step 3: Implement.** `indexOf(query, ignoreCase = true)` over the title, the path's and each disc's file
  name, and the key and each disc's serial; it compares a character at a time, so ranges stay in the original text.
- [ ] **Step 4: Run them** and `ktlintCheck`. Expected: PASS.
- [ ] **Step 5: Commit** "Android: the library's search, by title, file name or serial".

### Task 4: The new icons

TDD skipped: the icons are drawings and `GsIcons.kt` is generated; checked by eye and by the build.

**Files:**
- Create: `docs/design-system/icons/sort.svg`, `order.svg`, `order-reversed.svg`, `list-bulleted.svg`, `refresh.svg`
- Regenerate: `ui/theme/GsIcons.kt` (`python3 scripts/branding/generate-gsicons.py`)
- Modify: `docs/design-system.md` (Icons)

**Interfaces:** Produces `GsIcons.sort`, `.order`, `.orderReversed`, `.listBulleted`, `.refresh`.

- [ ] **Step 1: Draw them** with the set's root attributes (copy `list.svg`'s `<svg …>`):
  - `sort`: `<path d="M4 6H20M4 12H15M4 18H10"/>`.
  - `order`: `<path d="M12 20V5M7 10 12 5 17 10"/>`; `order-reversed`: `<path d="M12 4V19M7 14 12 19 17 14"/>`.
  - `list-bulleted`: `<rect x="4" y="5" width="2" height="2"/>` at y 5, 11, 17, and `<path d="M10 6H20M10 12H20M10
    18H20"/>`.
  - `refresh`: one arrow turning clockwise round an octagon, a quarter open at the top right:
    `<polyline points="19,15 15,19 9,19 5,15 5,9 9,5 16,5"/>` and its head `<path d="M13 2 16 5 13 8"/>`.
- [ ] **Step 2: Look at them** beside `sync.svg` and `list.svg` (`qlmanage -t -s 96 -o /tmp/icons <svgs>`, then read
  the PNGs): each reads at 24 px, and Refresh's single arrow can't be taken for sync's two. Adjust the drawing, not the
  rules, if one doesn't.
- [ ] **Step 3: Regenerate** `GsIcons.kt`; `git diff --stat` shows only the five additions. `./gradlew -q
  assembleDebug` builds.
- [ ] **Step 4: Document** them in `design-system.md`'s Icons: the five names and what each is for, and the
  exception to the icons-with-words rule: an `IconActionButton` whose label shows on focus and long press and is its
  accessible name.
- [ ] **Step 5: Commit** "Design system: sort, order, bulleted list and refresh icons".

### Task 5: `IconActionButton`, its label, and `SearchField`

**Files:**
- Create: `ui/components/IconActionButton.kt`, `ui/components/SearchField.kt`
- Modify: `ui/components/SettingsRows.kt` (`DockedKeyboard` from private to internal), `ui/components/KeyHints.kt`
  (`PadGlyphs.select = BindingLabels.label("Button109")`)
- Test: `androidTest/…/ui/IconActionButtonTest.kt`, `androidTest/…/ui/SearchFieldTest.kt`

**Interfaces:**
- Produces:
  - `@Stable class ActionLabelHost` (which label shows, and where its button is in root coordinates);
    `@Composable fun rememberActionLabelHost(): ActionLabelHost`.
  - `@Composable fun IconActionButton(icon: ImageVector, label: String, onClick: () -> Unit, labels:
    ActionLabelHost, modifier: Modifier = Modifier, onFocusChange: (Boolean) -> Unit = {})`: 48 × 48 dp, `raised` on
    a `line` edge at `radius-md`, the icon 24 dp in `ink-muted`, `beak-ink` while focused; `focusRing`; content
    description and role Button; while focused, or for 2 s after a long press (a tap acts), it shows `label` through
    `labels`.
  - `@Composable fun ActionLabelStrip(labels: ActionLabelHost, modifier: Modifier = Modifier)`: one line high
    (`body-sm` plus 8 dp) whether or not a label shows; the label, `body-sm` `ink` on `raised` with a `line` edge at
    `radius-sm`, centred under its button and kept inside the strip. Not focusable; hidden from screen readers (the
    button carries the words).
  - `@Stable class SearchFocus { fun focusAndType() }` (focus the field and open the keyboard) and
    `@Composable fun rememberSearchFocus(): SearchFocus`.
  - `@Composable fun SearchField(state: TextFieldState, focus: SearchFocus, modifier: Modifier = Modifier,
    onKeyboardClosed: () -> Unit = {}, onDown: () -> Unit = {}, onFocusChange: (Boolean) -> Unit = {})`: a 48 dp
    `sunken` well with a `line-strong` edge at `radius-sm`, the `search` icon, the placeholder (copy), the ✕ while
    there's text. Focus doesn't open the keyboard; A or the D-pad centre do (as `TextFieldRow`); Up moves the focus
    up; Down calls `onDown`; the keyboard's action is Search, which hides it. `onKeyboardClosed` runs when the
    keyboard goes, by its action or the system's Back. The ✕ is a 48 dp `pointerInput` tap target, not a focus stop.
    It uses `DockedKeyboard`.

- [ ] **Step 1: Write the failing tests.** `IconActionButtonTest` (a host of three buttons in a `Row` over an
  `ActionLabelStrip`): `itIs48dpAndNamedByItsLabel`; `aFocusedButtonShowsItsLabelUnderIt` (label's top ≥ the
  button's bottom, its centre within 1 dp of the button's); `theLabelStaysInsideTheStrip` (the last button's long
  label ends inside it); `aLongPressShowsTheLabelAndATapActs` (`longClick()` shows it, no click counted; `click()`
  counts one); `theStripKeepsItsHeight` (same height with and without a label). `SearchFieldTest`:
  `upLeavesAndDownCallsOnDown`; `typingFillsTheState`; `theCrossClearsAndIsNotAFocusStop` (tap it: text cleared;
  `onAllNodes(isFocusable())` doesn't include it); `theSearchActionClosesTheKeyboard` (`performImeAction()` calls
  `onKeyboardClosed`); `focusAndTypeFocusesTheField`; `theWordsFitTheWidth` (180 dp wide shows "Search", 300 dp "Search
  your games").
- [ ] **Step 2: Run** both classes. Expected: FAIL, unresolved.
- [ ] **Step 3: Implement** to the interfaces above.
- [ ] **Step 4: Run** both classes and `ktlintCheck`. Expected: PASS.
- [ ] **Step 5: Commit** "Android: icon buttons with a label on focus, and a search field".

### Task 6: The compact top bar

**Files:**
- Create: `ui/LibraryTopBar.kt`
- Modify: `ui/LibraryScreen.kt` (the `FlowRow` bar at 129-164), `MainActivity.kt:451-467`
- Test: `test/…/ui/LibraryTopBarLayoutTest.kt` (JVM); update `androidTest/…/ui/LibraryScreenTest.kt`
  (`theToggleButtonHandsTheFocusToTheGame`, `upFromTheFirstRowReachesTheTopBar`), `OrientationLayoutTest`
  (`theLibrarysTopBar`, `…WithTheSyncBadge`), `FocusAuditTest` (library cases)

**Interfaces:**
- Consumes: Task 4's icons, Task 5's components, `LibraryWords`.
- Produces:
  - `enum class BarLayout { OneRow, SearchBelow, Stacked }` and `fun barLayout(width: Dp, title: Dp, trailing: Dp):
    BarLayout` (gaps 8 dp; the field needs `SEARCH_MIN = 120.dp`): OneRow when title + field + trailing fit; else
    SearchBelow when title + trailing fit; else Stacked (the title, with the badge if it fits, then the buttons on
    their own row).
  - `@Composable fun LibraryTopBar(gridView: Boolean, sort: LibrarySort, syncBadge: SyncBadge?, search:
    TextFieldState, searchFocus: SearchFocus, sortButton: FocusRequester, settingsButton: FocusRequester,
    onSort: () -> Unit, onOrder: () -> Unit, onToggleView: () -> Unit, onRefresh: () -> Unit, onOpenSettings: () -> Unit,
    onSyncBadge: () -> Unit, onSearchDown: () -> Unit, onSearchKeyboardClosed: () -> Unit, onFocusArea: (LibraryArea)
    -> Unit)`, where `enum class LibraryArea { Game, Bar, Search, ClearSearch }`. Order left to right: the goose and
    "Library", the field, the badge, Sort, Order (`order` / `orderReversed` by `sort.reverse`), View (`listBulleted`
    in grid mode, `grid` in list mode), Refresh (`refresh`), Settings. The `ActionLabelStrip` goes straight under
    the buttons' row, so in SearchBelow and Stacked it sits between the buttons and the field. A custom `Layout`
    measures the title and trailing groups' intrinsic widths to pick the `BarLayout`.
  - `LibraryScreen` gains `sort: LibrarySort = LibrarySort()`, `onSort: (LibrarySort) -> Unit = {}`, `search:
    TextFieldState = rememberTextFieldState()` (wired in Tasks 7–8; here Sort and Order do nothing yet).

- [ ] **Step 1: Write the failing tests.** `LibraryTopBarLayoutTest`: the Thor at 1.0 (576 dp, title 148, trailing
  272) is OneRow; with a badge (trailing 380) SearchBelow; 296 dp is Stacked. `LibraryScreenTest`:
  `theButtonsAreIconsNamedByTheirLabels` (content descriptions "Sort: Title", "Order: A to Z", "Show as a list",
  "Refresh the library", "Settings", each 48 dp); `theViewButtonShowsTheGridInListMode` ("Show as a grid");
  `upFromTheFirstGameReachesTheSearchThenTheButtons`; the two updated tests by content description.
  `OrientationLayoutTest.theLibrarysTopBar`: at every config, every button and the field reached and in view, labels
  shown on focus; the field on the buttons' row at 960×540 1.0×, under it at 640×360 1.3× and in portrait; no focus
  label overlapping the field, the count line or the first game (`theFocusLabelNeverCoversTheRowUnderIt`, folded into
  the same loop).
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement** `LibraryTopBar` and swap it into `LibraryScreen`; Settings keeps `settingsButton` for
  `focusSettings` and the no-games case.
- [ ] **Step 4: Run** `LibraryTopBarLayoutTest`, `LibraryScreenTest`, `FocusAuditTest`, `OrientationLayoutTest`.
  Expected: PASS. Then `quick`.
- [ ] **Step 5: Commit** "Android: the library's top bar as icon buttons, with a search field".

### Task 7: Sorting the library

**Files:**
- Create: `ui/LibrarySortList.kt`, `library/LibraryPrefs.kt`
- Modify: `ui/LibraryScreen.kt`, `app/AppController.kt` (`readGridView` at 1621 becomes `readLibraryView`, and its
  two callers; a sort state beside `gridView` at 345), `MainActivity.kt`
- Test: `test/…/library/LibraryPrefsTest.kt` (JVM, with `settings.MemoryCoreSettings`); `LibraryScreenTest`;
  `OrientationLayoutTest`; `FocusAuditTest`

**Interfaces:**
- Consumes: `LibrarySort`, `SortKey.OFFERED`, `LibraryOrder.sorted`, `LibraryWords`, `ChoiceList` and `Choice`
  (internal, same module).
- Produces:
  - `object LibraryPrefs { fun read(settings: CoreSettings): LibrarySort; fun write(settings: CoreSettings, sort:
    LibrarySort) }` (keys and defaults from Global Constraints).
  - `AppController.librarySort: LibrarySort` (state, private set) and `fun setLibrarySort(sort: LibrarySort)`: nothing
    when unchanged; else sets it and writes both keys on `coreDispatcher`, as `toggleView` does.
  - `@Composable internal fun LibrarySortList(sort: LibrarySort, anchor: Rect, onPick: (SortKey) -> Unit, onClose:
    () -> Unit)`: a `ChoiceList` of `OFFERED` plus `sort.key` at the end when it isn't offered, 240 dp wide (less on a
    narrower screen), its top 8 dp under the anchor, its right edge on the anchor's, kept inside the screen's padding,
    scrolling when taller than the room under the anchor. Under it, a transparent full-screen layer whose tap closes
    it (`pointerInput`). B or Back closes it, both halves taken.

- [ ] **Step 1: Write the failing tests.** `LibraryPrefsTest`: `absentKeysReadAsTitleAToZ`, `desktopValuesReadAsTheyAre`
  (0 → Type, 7 → UncompressedSize, reverse true), `writeSetsBothKeys`. `LibraryScreenTest`:
  - `sortOpensItsListWithTheCurrentChoiceFocused` (Title current and focused).
  - `pickingASortCallsOnSortAndFocusesSort` (Down, A: `onSort(LastPlayed, false)`; "Sort: Last played" focused).
  - `pickingTheCurrentSortChangesNothing` (no `onSort` call).
  - `bClosesTheSortListAndKeepsTheSearch` (with "game" typed: list closed, focus on Sort, text still "game").
  - `theSortListIsInTheScreen` (one root while it's open: `onAllNodes(isRoot()).assertCountEquals(1)`).
  - `aDesktopOnlySortShowsByName` (`sort = LibrarySort(SortKey.Type)`: "Sort: Type", "Order: Discs first", Type
    current and last in the list).
  - `orderReverses` (A on Order: `onSort(Title, true)`).
  - `theGamesFollowTheSort` (Last played puts the most recent first; the count line "30 games · Last played, most
    recent first").
  - `afterASortTheLastFocusedGameIsInView` (Game 25 focused, Up to Sort, pick Time played where Game 25 has the most:
    Game 25 displayed).
  - `OrientationLayoutTest.theLibrarysSortList` (each config: every choice reached and in view, the list inside the
    screen), `FocusAuditTest.librarySortList`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.** `LibraryScreen`'s root becomes a `Box` holding the column and, while open,
  `LibrarySortList` (the Sort button's bounds from `onGloballyPositioned`). `val sorted = remember(games, sort) {
  LibraryOrder.sorted(games, sort) }` feeds the grid and list. The count line ("…games · …") goes above the grid
  while there are games. After a pick or close, the Sort button takes the focus back and the grid or list scrolls to
  the last focused game (`GameFocus.focused`), if shown. `AppController` reads the sort in `readLibraryView`
  (so a data folder pull rereads it) and `MainActivity` passes `sort` and `onSort = AppController::setLibrarySort`.
- [ ] **Step 4: Run** the tests above and `ktlintCheck checkNoWindowDialogs`. Expected: PASS. Then `quick`.
- [ ] **Step 5: Commit** "Android: sort the library, in Big Picture's keys".

### Task 8: Searching the library, and hints that follow the focus

**Files:**
- Modify: `ui/LibraryScreen.kt`, `ui/GameTile.kt`, `ui/GameListRow.kt`, `app/AppController.kt` (`startGameNow` at
  1121), `MainActivity.kt`
- Test: `LibraryScreenTest`; `OrientationLayoutTest`; `FocusAuditTest`

**Interfaces:**
- Consumes: `LibrarySearch`, `LibraryWords.countLine`, `SearchFocus`, `LibraryArea`, `PadGlyphs.select`.
- Produces: `AppController.librarySearch: TextFieldState`, cleared once `startGameNow` passes its checks;
  `GameTile(…, query: String? = null)` and `GameListRow(…, query: String? = null)` marking a match (`beak-soft`
  behind, `beak-ink` text) in the title, and in the row's serial.

- [ ] **Step 1: Write the failing tests** in `LibraryScreenTest`:
  - `typingNarrowsTheGames`: "game 2" shows Game 02 and 20–29 only; count "11 of 30 games · Title, A–Z".
  - `selectJumpsToTheSearch`: from a game, Select: the field focused.
  - `downFromTheFieldGoesToTheFirstMatch`: "game 2", Down: Game 02 focused.
  - `bClearsTheSearchAndKeepsTheGame`: then B: 30 games, Game 02 still focused and displayed.
  - `bWithoutASearchIsLeftAlone`: B on a game reaches a test `onKeyEvent` wrapped round the screen.
  - `spacesAloneAreNoSearch`: "   ": 30 games, no "of", B left alone.
  - `noMatchSaysSoAndWaitsForTheKeyboard`: "zelda": "No games match “zelda”." shown, focus still on the field;
    `performImeAction()`: Clear search focused; A: the text empty and the field focused.
  - `downOnNoMatchGoesToClearSearch`.
  - `theFooterFollowsTheFocus`: the hint sets of Global Constraints on a game, a game while searching, Sort, the
    field, and the open sort list.
  - `aNewListWhileSearchingKeepsTheFocusedGame`: "game 2", Down to Game 02, the games replaced by a fresh copy plus
    one more: Game 02 still focused.
  - `aThousandGames`: 1,000 games, "game 99" types in with the count "11 of 1000 games · Title, A–Z" and Down reaching
    "Game 099".
  - `OrientationLayoutTest.theLibraryWhileSearching` (grid and list, each config: the marked title laid out, the
    no-match line and Clear search reached and in view); `FocusAuditTest.librarySearching`, `.libraryNoMatch`.
- [ ] **Step 2: Run** them. Expected: FAIL.
- [ ] **Step 3: Implement.** `val query = LibrarySearch.query(search.text)`; `shown = remember(sorted, query) {
  LibrarySearch.filter(sorted, query) }`. The screen's `onPreviewKeyEvent`: Select (key-up; both halves taken) calls
  `searchFocus.focusAndType()`; B or Back with a query clears it (both halves taken), else passes. The search
  field's `onDown` focuses the first match (`focus.pending` and a scroll to 0) or Clear search; its
  `onKeyboardClosed` focuses Clear search when nothing matches. When `shown` changes: a focused game still shown is
  scrolled to and keeps the focus; otherwise, while searching, the list goes to its top; otherwise to the last
  focused game. `KeyHints` come from the focus area (tracked through `onFocusArea` and each game's focus) and the
  sort list. `AppController.startGameNow` clears `librarySearch`; `MainActivity` passes it.
- [ ] **Step 4: Run** the tests above, all of `LibraryScreenTest`, `FocusAuditTest`, `OrientationLayoutTest`, and
  `ktlintCheck`. Expected: PASS. Then `quick`.
- [ ] **Step 5: Commit** "Android: search the library; the button hints follow the focus".

### Task 9: Docs, the full tier, and the branch

**Files:** Modify `docs/android.md` ("Running it": the bar, sort, search; a "Library sort and search (2026-11-08)"
entry under Results on the emulator), `docs/design-system.md` (`IconActionButton`, `ActionLabelStrip`,
`SearchField` under Components; the library section's bar, count line, sort list and hints), `docs/roadmap.md`
(item 16: library sort and search done, with the PR number once there is one).

- [ ] **Step 1: Write the docs** in the house style (plain words, what's true, no history of the work).
- [ ] **Step 2: Local CI:** `cd android && ./gradlew assembleDebug testDebugUnitTest ktlintCheck checkNoWindowDialogs`.
  Expected: BUILD SUCCESSFUL, the new JVM classes in `build/test-results`.
- [ ] **Step 3: Full tier** from a detached worktree at the branch head, in the background:
  `GOOSE_TEST_DATA=~/work/goose-test-data android/scripts/run-instrumented-tests.sh full`. Read every result XML:
  no failures, no errors, the new classes and methods present and run.
- [ ] **Step 4: Commit** "Docs: Android library sort and search", then push the branch to
  `origin claude/library-sort-search`.
- [ ] **Step 5:** `superpowers:finishing-a-development-branch`; a PR to `master` only when the user says so.
