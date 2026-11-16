# The last of 4.2: focus set-up, one-message notices, the page header and the screens' padding

The last open items of the Android code review (`docs/reviews/2026-11-10-android-code-review.md`, finding 4.2,
"Cross-screen UI patterns"). The rest of 4.2 landed in #87: `Question`, `backKeys`, `tabKeys`, and `ResumePrompt` on
`OverlayPanel`.

## Why

Four patterns are still written by hand on each screen, and each copy has drifted a little:

- **Focus set-up.** `remember { FocusRequester() }` and `LaunchedEffect(Unit) { requestFocus() }` open 11 pages and the loading page.
  `remember { mutableMapOf<K, FocusRequester>() }` with `getOrPut` makes a requester per row in 6 places. Seven lines
  hold the focus when a page has nothing to press ("Connect a controller to set it up.", "Reading the settings…"),
  each a `Text` with `.focusable()` and no ring, against "Focus is always visible" (design-system.md, principle 2).
- **One-message notices** (a `Notice` whose content is one `Text`) come in three text styles: `bodyMedium` (8),
  `titleMedium` (5) and `bodyLarge` (1). The type table makes a notice's message reading text, `body`
  (`bodyLarge`): `titleMedium` is `label`, for what's pressed or picked, and `bodyMedium` is `body-sm`, for notes. None
  tells a screen reader it appeared.
- **`SettingsPage` and `LazySettingsPage`** each write the game's eyebrow, the title and its heading, `BackHandler`,
  and B and Back through the dispatcher.
- **The screens' padding,** `padding(horizontal = 32.dp, vertical = 20.dp)`, is written on 7 screens, and 20 dp isn't
  a step of the 4 dp grid (`space-1` to `space-8`: 4, 8, 12, 16, 24, 32, 48, 64).

## Goals

- **`rememberInitialFocus()`**, a requester the element it's on takes as the page opens, on the 11 pages and the loading page.
- **`rememberFocusRequesters<K>()`**, a requester per key, made the first time it's asked for, in the 5 places that
  only make and keep one. A sixth, the sync conflicts', fills its map as its rows compose, and stays.
- **`FocusHolderLine(text)`**, a line that holds the focus with nothing to press, with the focus ring, in the 7 places.
- **`Notice(status, text)`**, a one-message notice in `body` and `ink`, announced politely as it appears or changes,
  at the 14 one-message sites.
- **`PageHeader(title, subtitle)`** and one back set-up, shared by `SettingsPage` and `LazySettingsPage`.
- **`ScreenPadding`**, 32 dp at the sides and **24 dp** top and bottom (`space-5`), on the 7 screens; the settings
  rail's top padding follows, so its title stays level with the page's.

## Non-goals

- **No other change in behaviour.** Nothing gains or loses a focus stop, and every page opens on the same element.
- **`Banner` and `UpdateBanner`** keep their own content: a prompt with a note, and words that change with progress,
  which a live region would read out at every step.
- **The panels' titles that hold the focus** while a panel has nothing else to focus (Save state, Patch codes,
  Achievements, Saves) stay as they are. They are headings, focusable only for that moment, and their ring is a
  question for the panels, not this change.
- **Requester holders with more to them** stay: `GameFocus` (it forgets a game as it leaves), the card editor's
  `FocusMemory` (it remembers the last one), `SaveRowFocus` (a class the slot panel and Saves share), and the hub's
  port rows (made with each row, under `key`).
- **The design-system screenshots** aren't retaken for a 4 dp shift.

## Anti-goals

- **A holder line with no ring,** or one a screen reader reads as a button.
- **A live region on a notice whose text ticks** (a download's progress).
- **A screen keeping its own literal padding** beside `ScreenPadding`.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **Where:** the focus helpers in `ui/components/FocusRing.kt`, beside `focusStop`; `Notice(status, text)` in
  `ui/components/Banner.kt` beside `Notice`; `PageHeader` and `ScreenPadding` in `ui/components/SettingsRows.kt`
  beside the pages.
- **`rememberInitialFocus(): FocusRequester`** remembers a requester and asks for the focus once, in
  `LaunchedEffect(Unit)`, as the pages do now. A page that picks its first element by state (the game settings'
  entries, a memory-card sync row shown only first) keeps its own effect.
- **`rememberFocusRequesters<K>(): FocusRequesters<K>`**, a `@Stable` class with `operator fun get(key: K):
  FocusRequester`, made on first use and kept for the screen's life, as `getOrPut` does now.
- **`FocusHolderLine(text, modifier, requester)`**: `body` in `ink-muted` (as six of the seven are now), padded 16 dp
  at the sides and 8 dp down, holding the focus with `focusStop()`, so it shows the ring and the `raised` fill, and a screen
  reader reads it whole and not as a button. `requester` is the focus requester it carries, usually
  `rememberInitialFocus()`; `enabled = false` makes it no stop, for the port's line below its type row.
- **`Notice(status, text, modifier, icon)`**: the existing `Notice` with one `Text(text, bodyLarge, ink)` and
  `liveRegion = LiveRegionMode.Polite` on the notice. `MessageBar` becomes one call to it.
- **`PageHeader(title, subtitle)`**: the game's eyebrow (from `LocalGameContext`), the title in `headlineMedium` as a
  heading, and the subtitle in `bodyMedium` `ink-muted`, 8 dp apart. Both pages draw it; `LazySettingsPage` as its
  first item, under `LAZY_PAGE_HEADER` as now.
- **One back set-up:** a private `Modifier.pageBack(onBack)` composable modifier that registers the page's
  `BackHandler` and returns `backKeys { dispatcher?.onBackPressed() ?: onBack() }`. It stays called before the page's
  content, so a handler a page adds itself is newer and takes Back first, as now.
- **`ScreenPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp)`.** `LazySettingsPage` keeps the rows' 4 dp
  ring margin inside the list, so it pads `ScreenPadding` less 4 dp all round, as it pads 32 and 20 less 4 now.

## Tests

- **`FocusHolderLine`** (instrumented, in `ComponentsTest` or a test of its own): it takes the focus; focused, it
  shows the ring (the `focused` semantics the ring tests already read, or the drawn indicator); a screen reader node
  has its text and no `Role.Button`; `enabled = false` is no stop.
- **`Notice(status, text)`**: its node has `liveRegion` Polite and the text.
- **`rememberFocusRequesters`** (JVM or instrumented): the same key gives the same requester; another key another.
- **The pages that change** keep their own tests passing: the settings pages, the controllers pages, Saves, the slot
  and disc panels, the card editor, the focus audit and the orientation layouts. Each page still opens on the same
  element, which their tests and `FocusAuditTest` check.

## Constraints

- Kotlin, with the project's ktlint rules and KDoc style.
- **Every check stays green:** `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`, with warnings as errors; and the quick tier on the test emulators.
- **On a device.** On the AYN Thor's debug build, with your OK: a settings page opening on its first row, the
  Controllers page with no controller, if one can be disconnected (or a loading page), its holder line showing the
  ring, and the screens' new top padding level with the rail's title.
- **Commits,** one per part: this design; the focus helpers; the notices; the page header and back set-up; the
  padding; then the review's status section and `docs/design-system.md` (the components, and the screens' 24 dp).

## Left to the builder

- Whether `FocusRequesters` is a class or a typealias over a remembered map with an extension.
- The tests' names, and which test class each goes in.
