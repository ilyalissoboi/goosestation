<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# The GooseStation design system

How GooseStation looks and behaves, and how to build a new screen or control that fits. Read this before adding UI to
any frontend.

GooseStation is played at a desk with a mouse, on a sofa with a pad, on a TV with a remote, and on a phone with
thumbs. The design system makes those four feel like one app. Its look comes from the goose: white low-poly facets
with an orange beak, on the dark water of a night pond.

## Where things are

| What | Where |
| --- | --- |
| The values (colours in both themes, type, spacing, radii, sizes), machine-readable | [`docs/design-system/tokens.json`](design-system/tokens.json) |
| The UI icons, as SVG | [`docs/design-system/icons/`](design-system/icons/) |
| The PlayStation button glyphs, as SVG, one per core input name | [`docs/design-system/pad-glyphs/`](design-system/pad-glyphs/) |
| The goose mark, app icon and one-colour mark, as SVG | [`docs/design-system/logos/`](design-system/logos/) |
| The full goose illustration | [`data/resources/images/goosestation.png`](../data/resources/images/goosestation.png) |
| The Android theme, icons, mark | `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/theme/` |
| The Android components | `android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/components/` |
| The bundled fonts and their licences | `android/app/src/main/res/font/`, `android/app/src/main/assets/licenses/` |
| `GsIcons.kt`'s generator | [`scripts/branding/generate-gsicons.py`](../scripts/branding/generate-gsicons.py) |
| The Android launcher icon's generator | [`scripts/branding/generate-android-icon.py`](../scripts/branding/generate-android-icon.py) |

The design system is also published as a browsable page with live previews of every component, in both themes:
<https://claude.ai/artifact/BZV2gRHHmrSCzRmyWcrqwU> (ask a maintainer for access). `tokens.json` and the SVGs here are
the same values and files.

**What uses it today.** The Android app implements the dark theme and the components below. The desktop app (Qt)
and Big Picture (Dear ImGui) still have DuckStation's look; bringing them over is open work, and the tokens are the
source for it. When a value changes, change `tokens.json` and the implementations together.

## What it looks like

The Android app's screens, in [`docs/design-system/screenshots/`](design-system/screenshots/): rendered from the
Compose code, off a device, at a handheld's 960 x 540 dp in landscape (the AYN Thor's) and a phone's 411 x 891 dp in
portrait. The Controllers page, with its PlayStation glyphs, is the page's composable alone at 960 x 540 dp too,
rendered on the emulator. The pause menu and its slot panel are from the emulator, a phone in landscape (914 x 411
dp), over Ace Combat 3. The orange outline is the focus, where a controller is.

| | |
| --- | --- |
| ![The library as a grid, with a warning notice](design-system/screenshots/library-grid.png) | ![The library as a list, scanning](design-system/screenshots/library-list.png) |
| ![The settings hub](design-system/screenshots/settings.png) | ![The Graphics page: cyclers and a toggle](design-system/screenshots/graphics.png) |
| ![The BIOS page](design-system/screenshots/bios.png) | ![The Controllers page](design-system/screenshots/controllers.png) |
| ![Setup's welcome](design-system/screenshots/setup.png) | ![The pause menu over a game](design-system/screenshots/pause.png) |
| ![The Load state slot panel beside the pause menu](design-system/screenshots/save-slots.png) | |

![The library on a phone, in portrait](design-system/screenshots/library-phone.png)

## Principles

1. **The game is the hero.** Chrome stays dark, quiet and out of the way. Colour goes on the one thing to do next and
   on what needs attention, never on decoration.
2. **Every input is first-class.** Anything a mouse can do, a D-pad, a remote and a thumb can do too. Focus is always
   visible.
3. **Facets, not curves.** Corners are crisp: 4, 8 or at most 16 dp. No pills, no circles: switches are square, and
   round icons are octagons.
4. **Say it plainly.** Short words, sentence case, the truth about what happened and what to do next.
5. **Readable from across the room.** A hyperlegible text face, sizes that scale by 1.5 on a TV, and every text colour
   at 4.5:1 or more on the grounds it's used on.

## Colour

Name colours by role, never by value. Two themes share the names: **Dark** (the default, and the only one Android
has) and **Light** (for the desktop app, later). Values are in `tokens.json`; the dark ones are below.

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| `ground` | `#0e1116` | `#f3f1ec` | Every screen's background. |
| `surface` | `#161a21` | `#fbfaf7` | Groups, cards, the pause menu's panel. |
| `raised` | `#1f242d` | `#ffffff` | Secondary buttons; a focused or hovered row. |
| `sunken` | `#0a0c10` | `#e9e6df` | Wells: fields, toggle tracks, the cycler, a cover before its art. |
| `line` | `#2c323d` | `#dcd8cf` | Hairlines that divide. Never the only edge of a control. |
| `line-strong` | `#6a7383` | `#858072` | Edges that must be seen: toggles, the cycler, fields. 3:1 or more. |
| `ink` | `#f3f1ec` | `#14171c` | Text and icons people must read. |
| `ink-muted` | `#a7aebb` | `#525966` | Secondary text: notes, details, values at rest. |
| `ink-faint` | `#6b7381` | `#8a909a` | Disabled labels and placeholder glyphs only. |
| `beak` | `#ff7a1a` | `#f26a0f` | The one accent: the primary button, an on toggle, progress. |
| `beak-hover` | `#ff9447` | `#e0600a` | `beak` pressed or hovered. |
| `beak-ink` | `#ff7a1a` | `#a64100` | Orange as text or a thin mark: icons of the focused item. |
| `on-beak` | `#1a0b00` | `#1a0b00` | Text and icons on `beak`. Never white on orange. |
| `beak-soft` | `#3a2312` | `#fde3cf` | A quiet orange tint for a current item. Text on it stays `ink`. |
| `focus` | = `beak-ink` | = `beak-ink` | The focus ring. |
| `info` / `info-soft` | `#7aa7ff` / `#1a2742` | `#2b57c4` / `#dfe8fb` | Activity: syncing, scanning. |
| `success` / `success-soft` | `#3cc9b4` / `#12302d` | `#0b6e64` / `#d5efeb` | Done and safe. Teal, so it never relies on hue to differ from danger. |
| `warning` / `warning-soft` | `#f5c542` / `#3a3014` | `#7a5800` / `#f7ecc8` | Needs a look, nothing lost. |
| `danger` / `danger-soft` | `#ff6b81` / `#3d1820` | `#b01e3a` / `#f9dde2` | Failed, or destroys something. |
| `ps-triangle`, `ps-circle`, `ps-cross`, `ps-square` | `#3fd6a6`, `#ff6b6b`, `#7aa7ff`, `#f28ad6` | `#0b7a5a`, `#c42b2b`, `#2b57c4`, `#a8338e` | The PlayStation face symbols in pad glyphs only, 3:1 or more on `sunken`. |
| `scrim` | 72% black | 48% ink | Dims what's under a dialog or the pause menu. |
| `viewport`, `osd`, `osd-ink` | | | Over the game picture: the same in both themes. |
| `touch`, `touch-pressed`, `touch-line` | 14%, 34% and 40% `ink` | the same | The on-screen touch controls over the game: a button's fill at rest, held, and its edge and labels. |

Rules:

- **One accent.** `beak` fills at most one or two things on a screen: the primary button, an on toggle. If a screen
  has more orange than that, something is wrong.
- **Text on orange is `on-beak`**, near-black, in both themes.
- **Grounds step up in lightness:** `sunken`, then `ground`, `surface`, `raised`. Depth comes from these steps, not
  shadows. Only things that float (dialogs, the pause menu) cast `shadow-overlay`.
- **Status always has an icon shape and a word**, never colour alone (see Icons).
- Each token's note in `tokens.json` lists the contrast it was checked at. A new pair of colours must reach 4.5:1 for
  text (3:1 for text 24 px and up, control edges, icons and the focus ring) in both themes.

## Type

Three families, all under the SIL Open Font License, from Google Fonts. Bundle the files in each app; don't rely on
the system's fonts.

- **Chakra Petch** (`display`): titles and key glyphs. Its clipped corners are the facets in letter form. 20 px and up,
  plus `eyebrow` and `key`. Never for paragraphs.
- **Atkinson Hyperlegible Next** (`text`): everything people read or press. Built so similar letters can't be mixed
  up at a distance.
- **Atkinson Hyperlegible Mono** (`mono`): serials, timestamps, paths, counters, with tabular figures.

| Style | Family, size / line, weight | Use | Android |
| --- | --- | --- | --- |
| `display` | display 40/44, 600 | One per screen at most: a hero title. | `typography.displaySmall` |
| `title-1` | display 28/32, 600 | Screen titles. | `typography.headlineMedium` |
| `title-2` | display 20/24, 600 | Sections, dialogs, a game's name, a setup step. | `typography.headlineSmall`, `titleLarge` |
| `eyebrow` | display 12/16, 600, +1 tracking, capitals | A small label over what it names. | `Eyebrow(text)` |
| `key` | display 12/16, 700 | A button's glyph in a key chip. | `GooseStationTheme.type.key` |
| `label` | text 16/20, 600 | Anything pressed or picked. | `typography.titleMedium` |
| `label-sm` | text 14/18, 600 | Badges, a cycler's value. | `typography.titleSmall`, `labelLarge` |
| `body-lg` | text 18/26, 400 | Lead copy in setup and empty states. | (not used yet) |
| `body` | text 16/24, 400 | Reading text. | `typography.bodyLarge` |
| `body-sm` | text 14/20, 400 | Notes under a label, a game's details. | `typography.bodyMedium` |
| `mono` | mono 14/20, 500 | Serials, times, versions. | `GooseStationTheme.type.mono` |
| `osd` | mono 16/20, 600 | Messages over the game. | `GooseStationTheme.type.osd` |

Write text in sentence case even where it shows in capitals: `Eyebrow("Paused")` shows "PAUSED".

**On a TV** (Big Picture, Android TV), multiply every type size, control height and the focus width by 1.5, and pad
screens with 48 dp inside the overscan-safe area.

## Space, size and shape

- **A 4 dp grid:** 4, 8, 12, 16, 24, 32, 48, 64 (`space-1` to `space-8`). Rows and buttons pad 16 or 24 at the sides,
  screens 32 (48 on a TV), sections sit 12 to 24 apart. Android uses these as `dp` literals.
- **Controls** are at least 48 dp tall on touch screens and TVs, which is also the smallest touch target; 40 dp is
  for pointer-only desktop controls.
- **Radii:** `radius-sm` 4 dp for small, tight things (badges, toggles, fields, covers), `radius-md` 8 dp for
  buttons, rows, notices and cards, `radius-lg` 16 dp for dialogs, the pause menu and sheets. Nothing rounder. In
  Compose: `GsShapes.small`, `.medium`, `.large`, also set as `MaterialTheme.shapes`.
- **Layouts must work at 360 dp wide with text at 1.3×**, in portrait and landscape. A label and its value share a
  line when they fit and stack when they don't; `LabelValue` does this for settings rows.
- **Motion** is brief and only where it explains something: focus moves and toggles at 120 ms, dialogs fading in at
  160 ms. Nothing moves while a game runs except on-screen messages fading. Honour the system's reduce-motion setting.

## Focus and input

Every screen must work with a D-pad alone. That's tested (see Checking a new screen).

- **Something always has focus.** When a screen opens, focus the thing the player most likely wants: the first row,
  the last game played, Resume. When the focused element goes away, move focus to the nearest sensible one.
- **The focus ring** is a 2 dp `focus` outline drawn 2 dp outside the element, at the element's radius. Rows also
  lift to `raised` under it. A game tile rings its cover, not the whole tile.
- **Because the ring is outside the element, give it room:** a scrolling or lazy container that clips needs at least
  4 dp of content padding around focusable items, or the ring is cut. `focusRing` draws its element above its
  siblings while focused, so a neighbour's fill doesn't cover the ring.
- **Scrolling keeps the focus in the middle,** as on consoles: once a list scrolls, the focused element stays at its
  centre, and the list stops at its ends, with the title showing at the top and the last element's ring at the
  bottom. `GooseStationTheme` provides this for every scrolling and lazy container; don't override it.
- **A, Enter, the D-pad centre or a tap activate.** Only an A *pressed* on the element counts, not the release of one
  held from the previous screen. `focusRing` does this.
- **B and Back go back** (from the innermost thing that takes Back first, such as a dialog).
- **Left and right step a value** on a cycler or number row; elsewhere they move focus sideways.
- **Nothing can be clicked that can't be focused.** A tap-only part, such as a cycler's arrows, uses
  `pointerInput` rather than `clickable`, so it isn't a separate focus stop, and the row it's in carries the action.

## Components

Each is in `ui/components/` unless noted. Use them before writing a new control.

### `ActionButton`

```kotlin
ActionButton("Pick folder", onClick = ::pick, icon = GsIcons.folder)
ActionButton("Next", onClick = ::next, enabled = ready, kind = ButtonKind.Primary)
ActionButton("Back", onClick = ::back, kind = ButtonKind.Ghost)
```

A command: a label (a verb, sentence case), an optional leading icon, at least 48 dp tall with 24 dp sides.

- `Primary` (`beak` fill, `on-beak` label): the one thing the screen is for. Once a screen.
- `Secondary` (the default; `raised`, a `line` edge, `ink` label): every other command.
- `Ghost` (no fill): Back, Cancel, Remove, low-stakes commands beside a primary.
- `Danger` (secondary with a `danger` label): deletes or overwrites. Ask first, or offer Undo afterwards.
- Disabled: `ink-faint` on `surface`, and it can't take focus. Say nearby why it's disabled.
- Buttons in a row sit 8 dp apart; use a `FlowRow` so they wrap on a narrow screen.

### `Modifier.focusRing` and `Modifier.focusIndicator` (`FocusRing.kt`)

```kotlin
Row(Modifier.fillMaxWidth().focusRing(role = Role.Button, onClick = ::open).padding(16.dp, 12.dp)) { … }
```

`focusRing` makes anything focusable and activatable the GooseStation way: the ring, the `raised` lift
(`focusedFill`, or `null` for none), A on key-up, no focus while disabled. Put it before the padding, so the ring and
the touch target cover the whole element. For a ring on part of an element, pass `ring = false` and
`onFocusChange`, and draw `Modifier.focusIndicator(focused, colors.focus, shape)` on the part, as `GameTile` does.

### `Notice` and `Banner` (`Banner.kt`)

```kotlin
Notice(NoticeStatus.Warning) {
    Text("Memory card sync is offline", style = MaterialTheme.typography.titleMedium, color = colors.ink)
}
Banner(BannerModel("Can't read your BIOS folder.", listOf("Pick again" to ::repick)))
```

A message in the page, on its status's `-soft` ground, with the status icon: `Info` (a square, or pass
`icon = GsIcons.sync` for activity), `Success` (a ticked square), `Warning` (a triangle), `Danger` (an octagon). A
`Banner` is a warning notice with up to two secondary actions. Notices go at the top of the content, most serious
first, and leave when their cause does. A notice never holds a primary button. `MessageBar` in `LibraryScreen.kt` is
the app's passing status line, an info or danger notice.

### `SyncStatusBadge` (`SyncStatusBadge.kt`)

```kotlin
SyncStatusBadge(SyncBadge.Conflicts(2), onClick = ::openConflicts)
```

Memory card sync's state in the library's top bar, while sync is on: the status icon at `icon-sm` (16 dp) in the
status colour and a word in `label-sm` `ink`, `space-1` apart, on the status's `-soft` ground at `radius-sm`. Synced
is `success` (a ticked square), Syncing… `info` (`sync`), Offline `warning` (`cloud-off`), N conflicts and Needs setup
`warning` (a triangle), Sync failed `danger` (an octagon), so each reads without colour. It's focusable, with the touch
height, and opens what it's about: the conflicts, or Settings > Memory cards. `SyncBadges.of()` picks the badge;
it's never Synced while nothing syncs.

### Settings: `SettingsPage`, `CyclerRow`, `NumberRow`, `SwitchRow`, `NavRow`, `SettingsSection` (`SettingsRows.kt`)

```kotlin
SettingsPage("Graphics", onBack = onBack) {
    SettingsSection("Rendering")
    CyclerRow("Renderer", choices, settings.renderer, onSelect = { … })
    NumberRow("Internal resolution", settings.scale, scale, onChange = { … }, note = "Higher is sharper.")
    SwitchRow("Show FPS", settings.showFps, onToggle = { … })
}
```

- `SettingsPage`: the title in `title-1`, the rows in a scrolling column, B and Back going back. It keeps one choice
  list open at a time; a tap outside an open list closes it and still does what it would have.
- `CyclerRow`: a setting with a few values, shown between two arrows. Left and right step and stop at the ends; a tap
  on an arrow steps its way. Each step is the change. A, Enter or a tap on the row open its choice list. A
  `Choice`'s `note` shows under the row while it's the current value. Values that aren't among the choices show as
  themselves. For more than about eight values use a `NumberRow`; for values that need explaining, make a page of
  their own.
- `NumberRow`: a number, or a long ladder of values (speeds, sizes, counts), shown between two arrows as its
  `NumberScale` labels it. Left and right step by the scale and stop at its ends, and a held key repeats; a tap on an
  arrow steps. A, Enter or a tap open the choice list of the scale's common values, with the current value among
  them. A value off the scale shows as itself, and a step from it goes to the nearest one on the scale.
- `SettingsSection`: a page's section heading, in `eyebrow` `ink-muted`, named as desktop names the section. It's a
  heading for screen readers and never a focus stop. Split a long page into sections rather than into pages.
- A disabled row (`enabled = false` on `CyclerRow`, `NumberRow` and `SwitchRow`) is dimmed to `ink-faint`, with no
  beak on its toggle, and the focus passes it by. Its note says why ("Off in hardcore mode"). A row that does nothing
  in the current state (a renderer's own options under another renderer) is left out instead.
- The choice list: a `CyclerRow`'s choices, open in the page under its label line (never a window), on `sunken` at
  `radius-md` with a `line-strong` edge. Each choice is a 48 dp row with its label in `label` and its note in
  `body-sm` `ink-muted`; the current one is on `beak-soft` with a `check` in `beak-ink`. The focus starts on the
  current choice and stays in the list; up and down move it, left and right do nothing. Nothing changes until A on a
  choice, which picks it and closes the list; B, Back or a tap on the row close it unchanged. The focus goes back to
  the row, and the arrows are faint while it's open.
- `SwitchRow`: on/off, a square toggle and no word. The state is in the row's semantics (`toggleableState`), so
  screen readers say it and tests check it with `assertIsOn()`.
- `NavRow`: an entry that opens a page, with a summary of what's in it and a chevron. Disabled, its summary says why.
- A row's `note` explains a consequence ("Higher is sharper and slower.") or a fallback. Never repeat the label.
- **Rows for a game** (a page of a game's own settings, its values a game's, `SettingValues.isGame`): the page
  shows the game's title as an `Eyebrow` over its own, and every row can use the global setting. The shared rows
  (`Switch`, `Names`, `Number`, `Choices` in `ui/PageRows.kt`) do it themselves: a choice row's first choice is
  "Use global (<the global value>)", selected while the game's file doesn't set it; an on/off row becomes a
  `CyclerRow` of "Use global (On)", On and Off; a `NumberRow` (given `global` and `onUseGlobal`) shows "Use global
  (…)" while it follows it, Right making the global value the game's own and Left from the scale's lowest value going
  back, with "Use global" first in its list. Choosing it deletes the key. A row the game sets has a mark, "Set for
  this game" in `label-sm` `beak-ink`, before its note, even when its value is the global one: it no longer follows
  it. A row that writes several keys uses the global setting as a whole and is marked when the game sets any. In a
  running game, a setting the core only takes at the next start adds "Applies when the game next starts." to its
  note (`NextStart`).
- **An ordered list a page edits** (the post-processing chains' stages): each item a `NavRow` numbered in order
  ("1. crt-lottes"), with an "Add …" `NavRow` after the last, which opens a page of what can be added. An item's page
  starts with its `ActionButton`s (Move up, Move down, Remove; one that can't act is disabled, and after a move the
  focus stays on the button pressed while it still can), then its own rows. Remove doesn't ask, as desktop's doesn't;
  back on the list, the focus is on the item after it, or on "Add …".
  When an item has only actions and no rows of its own (the post-processing favourites), A opens an `OverlayPanel`
  over the list instead, as a memory card save's does: its name and summary, its first action focused, then the
  rest, Delete (`Danger`) last. A delete there asks first, and a question or a name form takes the panel's place;
  the list's last row is then the one that adds ("Save as favourite"), and B goes back a step, to the item's row.
- `TextFieldRow`: a text setting, its label in `label-sm` over a 48 dp `sunken` well with a `line-strong` edge at
  `radius-sm`, the focus ring around the well. Focus doesn't open the keyboard, so a D-pad passes through; A, the
  D-pad centre or a tap open it, and up and down leave the field as on any row. A password is the secure field:
  dots, and never offered to a keyboard to learn. It takes a `TextFieldState`.

### `KeyHints` (`KeyHints.kt`)

```kotlin
KeyHints(listOf(KeyHint(PadGlyphs.confirm, "Select"), KeyHint(PadGlyphs.back, "Back")))
```

The button-hint footer: each button's glyph in a key chip (`key` type, a `line-strong` edge at `radius-sm`), then the
action it takes, in a row that wraps on a narrow screen. It goes at the foot of a panel, such as the pause menu.

- Name the buttons with `PadGlyphs`, which labels them as the Controllers page does ("A" for `BUTTON_A`).
- Actions are short verbs, in sentence case: Select, Back, Save.
- It takes no input: nothing in it is focusable, and each hint reads as "A: Select".
- `KeyChip` is the chip alone, for a glyph shown elsewhere, such as `PanelTabs`.

### `PanelTabs` (`PanelTabs.kt`)

A panel's tabs: the L1 chip, the tabs' labels in `label-sm` (the current one on `beak-soft` in `ink`, the others
`ink-muted`), then R1. The panel switches them on L1 and R1 from anywhere in it, and a tap on a label selects it.
Nothing in it takes focus, so the D-pad stays on the panel's rows. `KeyHints` says "L1 R1 Switch".

### `OverlayPanel` (`OverlayPanel.kt`)

```kotlin
OverlayPanel(onBack = ::close) { Text("Erase every save on Shared card 1?", …); FlowRow { … } }
```

A small panel over a screen, drawn in the app's window, never a window dialog: a `scrim` over the whole window, past
the system bars, that takes every tap, and a centred `surface` panel at `radius-lg`, at most 420 dp wide, 24 dp
inside, in the safe area, that scrolls (as little as it must, keeping those 24 dp beside what the focus brings into
view, so its last button never sits against its edge). The focus can't leave it, and the caller focuses what it holds. B and Back
call `onBack` on the key-up of a press that began in it. It scrolls the least that shows the focus, not to the middle:
a focused text field asks to show its cursor as well as itself, which centring can't do. A question in it puts its
destructive answer first and the focus on the safe one.

### `ProgressBar` (`ProgressBar.kt`)

```kotlin
ProgressBar(fraction = done.toFloat() / total)          // how far a job has gone
ProgressBar(fraction = null, phase = bytes % sweep / sweep) // its length unknown: a third of a bar, moved by the job
```

A 6 dp `beak` bar on `sunken`, `radius-sm`, the full width it's given, for a job with a step to say: it goes under the
step's line ("Downloading lola-retro… 45%"), with the job's Cancel after it. While the job's size isn't known, a third
of a bar sits at `phase` along it, which the caller moves as the job does (the bytes so far, say), never on a clock: a
screen with one still settles, and the tests can wait for it. Never focusable; TalkBack reads it as a progress bar.

### `Eyebrow` (`Eyebrow.kt`)

A small capitalised label over what it names: "Paused" over a game's title, "Step 1 of 3" over a setup step.

### Library and game screens (`ui/`)

- `GameTile`: the cover in a square on `sunken`, fitted whole, never cropped; the disc icon, title and serial until
  the art loads; then the title (2 lines) and region · play time. Tiles are at least 152 dp wide, 16 dp apart across
  and 24 down.
- `GameListRow`: a 48 dp cover thumbnail, the title, then region · serial · play time.
- `GameSettingsScreen`: a game's own settings hub, a `SettingsPage` titled "Game settings" under the game's title as
  an `Eyebrow`: the serial and region in `body` `ink-muted`, what its file sets in `body` `ink`, a `NavRow` per page
  (its summary the page's count, "2 set for this game", or what's on it), then Copy global settings (`Secondary`)
  and Clear settings (`Danger`). Each asks in an `OverlayPanel` (`GameQuestionPanel`): the question, then its answer
  (`Danger`) and Cancel (`Ghost`, focused); the focus goes back to the button once it closes. In a game it takes the
  menu's place over the paused game.
- The library's foot: `KeyHints` "A Play" and "Y Game settings", while it has games. Y, or a long press, on a game
  opens its settings.
- `GameMenu`: the pause menu. A `scrim` over the game, a `surface` panel at `radius-lg`, 300 dp wide, 32 dp from the
  edges: "Paused" over the game's title and details, then menu rows (an icon, a label and any value at the end, or
  under the label when they don't fit; focused, the icon turns `beak-ink`; disabled, all `ink-faint`), a `line`
  divider before Reset console, and `KeyHints` at the foot. The row whose panel is open is current: `beak-soft`, its icon `beak-ink`. A question (Reset console)
  takes the rows' place in the panel: the question in `title-2`, then a `Danger` button and Cancel (`Ghost`, focused).
  A multi-disc game has Change disc (`disc`, its value "Disc 1 of 2") after Load state; Patch codes (`code`) follows
  Screenshot, then Achievements (`trophy`, its value the progress, "14 of 40", or why it's off), then Settings
  (`settings`, the game's own). In hardcore mode,
  Load state, Patch codes and Fast forward are disabled with "Off in hardcore mode" as their value. One side panel
  shows at a time.
  Exit game's question about replacing the resume state offers Replace (`Danger`) and Keep it (`Secondary`, focused),
  since both exit.
  B steps out one layer at a time, back to the row it came from.
- `SaveSlotPanel`: the slot list, a second `surface` panel beside the menu, filling the rest of the screen (on a
  screen under 720 dp wide, the menu narrows to 240 dp while it shows); on a narrow portrait screen, below it, the
  menu keeping the top 40%. Each slot row shows its screenshot at 4:3 (112 x 84
  dp, 64 x 48 dp in a row under 240 dp wide) on `sunken`, or the `memoryCard` icon in `ink-faint`; the slot's name in
  `label`; Saved or Empty, with the disc for a multi-disc game ("Saved · Disc 2"); and the time in `mono`. Rows
  that can't be picked are disabled; Load lists only the slots that hold a save, then the states still under the
  game's other discs, under an `Eyebrow("Other saves")`. `PanelTabs` (This game, Shared; L1 R1) head the list, inside
  its scroll, so a short screen keeps the rows its height; a shared row names the game it holds under the slot's name,
  in `body` `ink`, two lines at most. In a list under 150 dp tall, rows are compact (the 64 x 48 dp picture, the game
  on one line, the time on Saved's line). Replacing a save asks in the
  panel, as the menu does. The focus can't leave the panel.
- `DiscPanel`: Change disc, a side panel framed as `SaveSlotPanel`: a 48 dp row per disc, "Disc N" in `label` with the
  disc's title (or its serial) under it in `body-sm` `ink-muted`; the disc in the drive on `beak-soft` with "Current"
  at its end, and focused as it opens. A `Danger` notice says why a disc didn't go in. `KeyHints`: A Insert, B Back.
- `PatchCodePanel`: Patch codes, a side panel framed the same way: each group's `Eyebrow`, then its codes as
  `SwitchRow`s with their notes (each two lines at most, then an ellipsis, so no row outgrows the panel), or, for a
  code applied by hand, a row with Apply at its end. "Applied: <name>" shows
  as a `Success` notice and a failure as `Danger`, in the panel, which stays open. With no codes it says so and names
  the file to add, in `mono`.
- `AchievementsPanel`: RetroAchievements, a side panel framed as `SaveSlotPanel`, with `PanelTabs` when the game has
  both achievements and leaderboards. The Achievements tab: "14 of 40 unlocked" and the points, a 4 dp `beak` bar,
  then each section under its `Eyebrow`, each achievement a row (focusable, with nothing to activate) with its 48 dp
  badge on `sunken`, its title in `label` (`ink`, or `ink-muted` while locked), its description in `body-sm`, when it
  was unlocked or its progress with a small bar, and its points in `mono` at the end. In a list under 200 dp tall,
  each row shows its title and one line. The Leaderboards tab: a row per leaderboard, A opening it; open, its title in
  `title-2`, the entry count, then Top and Around you (one list where they meet), each entry its rank and score in
  `mono` and the user, the player's own on `beak-soft` and focused; "Loading…", or a `Danger` notice when it can't be
  fetched. B steps back from a leaderboard to its row, then to the menu.
- `MemcardEditorScreen`: Settings → Memory cards → Manage cards. "Memory cards" in `title-1` with the unsaved
  changes under it ("1 change not saved"), and **Discard** and **Save** (`Primary`) at the end; then two cards side by
  side, each a `surface` panel at `radius-md` edged `line-strong` while it holds the focus (`line` otherwise). A card's
  picker row has its name and "N of 15 blocks free", "No card yet" or "Can't read this card"; A opens the choice list
  of every card. Each save is a row: its icon's first frame at 32 dp on `sunken` (nearest-neighbour), its title in
  `label` with its size under it, and its file name in `mono` on a line of its own, the row's width; a deleted save is
  "Deleted · <title>", all in `ink-faint`. Under the saves, **Import save**, **Import card** and **Format** (`Danger`).
  L1 and R1 move to the other card, as Left and Right do at a card's edge; under 720 dp wide one card shows, with
  both names as `PanelTabs`. The page scrolls as one, `KeyHints` fixed at its foot (A Actions, B Back, L1 R1 Other
  card). A on a save opens its `OverlayPanel` (Copy to <the other card> first and focused, Rename…, Export…,
  Delete; on a deleted one, Undelete and Delete permanently); questions take the panel's place, and the rename form
  is Title and File name as `TextFieldRow`s, the rule broken as a `Danger` notice, Rename and Cancel. When it closes,
  the focus goes back to what had it.
- `ResumePrompt`: "Resume where you left off?" over the library, a centred `surface` panel at `radius-lg`: the game's
  title, the resume state's screenshot (224 x 168 dp) and time, then Resume (`Primary`, focused) and Start over. The
  `scrim` takes every tap, and the focus can't leave the panel. When hardcore mode would start, it gives the game's
  title, says the game starts from the beginning with the resume state kept, and offers only Start.
- `OsdLayer`: the messages over the running game, the app's and the core's, at the top left, oldest first, at most
  four. Each is `osd` type in `osd-ink` on the `osd` ground at `radius-sm`, with the core's icon (a memory card's,
  say) when it has one, showing its first frame only. The app's messages last 3 s, and the core's keep their own time.
  Each fades out over 160 ms, the only thing that moves during a game; with animations off, it just goes. While fast
  forward is on, its marker (the `fastForward` icon on `osd`) shows at the top right. On Android the core's messages
  are routed here, not to its own OSD: `CoreMessages` puts those for the in-game menu's actions in the app's words
  and drops those the app says itself. The core's FPS and speed overlay is unchanged.
  RetroAchievements adds two things: an unlock card at the top centre, at most 420 dp wide (the badge at 48 dp,
  "Achievement unlocked" or "Set mastered" as an eyebrow in `beak-ink`, the title in `label`, the text in `body-sm`,
  the points in `mono`, on `osd` at `radius-md`), one at a time, 5 s each (10 s for a mastery), fading as the messages
  do, and waiting while the menu is open; and the live indicators at the bottom right, 8 dp apart on `osd` at
  `radius-sm`: a leaderboard timer in `mono`, a challenge's 32 dp badge, the progress pop-up's badge and count for 3 s.
  The indicators hide while the menu is open.

### `TouchOverlay` (`ui/TouchOverlay.kt`)

The on-screen PS1 pad over a running game, for a phone or tablet with no controller: the D-pad, the face diamond, L1,
L2, R1, R2, Select, Start and a menu button, and the two sticks with L3 and R3 when Port 1 is analog. Where each sits
is `TouchLayout` (`input/`), from the agreed mockups
([docs/superpowers/specs/2026-11-08-android-touch-controls-mockup.html](superpowers/specs/2026-11-08-android-touch-controls-mockup.html)):
in landscape in the bars beside the picture, in portrait under the picture, which moves to the top.

- **Shapes:** octagons for the face buttons, sticks (ring and knob), the menu button, L3 and R3; `radius-md` tabs for
  the D-pad's arms, the shoulders, Select and Start. Fills `touch` at rest and `touch-pressed` held, a 1.5 dp
  `touch-line` edge; labels (L1, SELECT, …) in `labelMedium` and `touch-line`; the D-pad's arrowheads and the menu's
  `GsIcons.pause` in `touch-line`. The player's Opacity setting fades all of it.
- **Face buttons:** a symmetric diamond, four buttons of one size at one distance from its centre, mirrored by the
  D-pad on the other side at the same height. Their glyphs are `PsGlyph`'s face symbols without plates, in their
  `ps-*` colours, each scaled to one drawn size (40% of the button, stroke included, at one stroke weight) and
  centred in its button (`TouchGlyphs`); never font characters.
- **Size:** `touch-button`, 50 dp, is a face button at Size 100% in the phone layout (the D-pad's arms 40 by 54).
  The layout grows with the screen and the Size setting, and shrinks as far as it must: **every touch area is at least
  48 dp each way** and none overlaps another's, even where the drawn shape is smaller (a shoulder's area runs into
  the gaps around it). Where the bars beside the picture are too narrow, the controls are drawn over the picture.
  The D-pad and the face diamond reach the screen's edges, so they're kept from Android's edge gestures
  (`systemGestureExclusion`): a thumb sliding inward plays, rather than going Back.
- **Touch only:** it shows only in a game, never under the menu, and in Automatic only while no controller is
  connected, so the controller-first rules hold everywhere else. Nothing in it is focusable or `clickable`, and
  screen readers don't hear it. It never covers the app's own screens, which keep their own touch handling.
- Tests: `TouchLayoutTest` (sizes, overlaps, symmetry over phones and tablets), `TouchGlyphsTest`, `TouchOverlayTest`
  (injected touches), `OrientationLayoutTest.theTouchControls`.

### `GooseMark` (`ui/theme/GooseMark.kt`)

The goose mark, drawn in code from `logos/goose-mark.svg`'s polygons. Full colour, for dark grounds only. It sits
beside a screen's title on the library and setup screens.

## Pad glyphs

Port 1's inputs on the Controllers page are shown as the PlayStation controller's buttons, not their names: `PsGlyph(name,
contentDescription)` in `PsGlyph.kt`, drawn as in [`docs/design-system/pad-glyphs/`](design-system/pad-glyphs/), one
SVG per core input name (`Triangle`, `LLeft`, …).

- **The icon style, 24 units tall:** 2-unit strokes, square caps, mitred corners, the octagon for a round button.
- **Face buttons:** an octagon on `sunken` with a `line-strong` edge, the symbol inside in its `ps-*` colour (the
  circle is a true circle: it's the symbol, not a housing). **D-pad:** the plus in `ink`, the pressed arm filled.
  **Sticks:** the octagon, L or R in the middle and an arrowhead for the direction; **L3 and R3** add a pressed-in
  inner octagon. **Shoulder buttons:** a tab on `sunken` with the label inside. **Select and Start:** a bar and a
  right-pointing triangle, with their word in capitals.
- Labels are the `text` family, bold. Glyphs show 32 dp tall and grow with the font scale; widths follow the
  drawings (24, 30, 54 for Start, 60 for Select).
- Screen readers hear the input's name ("Left Stick Left"), never the labels.
- Only on Port 1's rows, and only for a digital pad or DualShock; any other type, or a name without a glyph, keeps
  its words. The player's own controller's buttons are always words.

## Icons

- **The UI icons** (`docs/design-system/icons/`) are drawn for GooseStation on a 24 px grid: 2 px strokes, square
  caps, mitred corners, and octagons wherever an icon would use a circle. In Compose they're `GsIcons.play`,
  `GsIcons.memoryCard` and so on, used through `Icon(…, tint = …)` at 16, 20 or 24 dp.
- **Colour them by context:** `ink-muted` at rest, `beak-ink` when focused or current, the status colour inside a
  notice. They're always paired with a word unless the meaning is universal (close, settings) and the element has an
  accessible name.
- **Status icons differ in shape** (square, ticked square, triangle, octagon) so they read without colour.
- **To add an icon:** draw it in the same style as an SVG in `docs/design-system/icons/`, using only `<polygon>`,
  `<polyline>`, `<rect>` and `<path>` with absolute `M`, `L`, `H` and `V`, stroked `#14171c`. Then run
  `python scripts/branding/generate-gsicons.py`, which rewrites `GsIcons.kt` from the folder. Don't edit `GsIcons.kt`
  by hand.
- **The goose:** `goosestation.png` is the hero illustration (about box, setup welcome, store pages), dark grounds
  only. `logos/goose-mark.svg` is the same goose cropped to head, neck and beak, for small sizes;
  `goose-app-icon.svg` is the mark on a dark tile, the app icon and the mark to use on light grounds;
  `goose-mark-mono.svg` is the one-colour silhouette for Android notification and themed icons and the macOS menu
  bar. Never recolour or redraw the goose. The wordmark is the name in Chakra Petch 600 beside the mark; there's no
  logotype file.
- **The Android launcher icon** is an adaptive icon made from the goose mark, in vector layers on the 108 dp canvas
  (`res/mipmap-anydpi/ic_launcher.xml`): the `surface`-dark background, the mark in full colour as the foreground, its
  head and beak inside the 66 dp safe circle and its neck running off the bottom edge, so the goose rises out of
  whatever shape the launcher masks it to; and the silhouette as the monochrome layer, which Android 13 and up tint
  for themed icons. With `minSdk` 29, vectors cover every supported version, so there are no PNGs per density. It's
  generated from `logos/goose-mark.svg` and `goose-mark-mono.svg` by
  `python scripts/branding/generate-android-icon.py`, which also checks the safe zone; change the mark there, not
  the XML. The desktop icons (`scripts/branding/generate-icons.py`) still use the full goose.
- **No emoji** and no decorative illustrations in the interface. The only pictures are the players' covers and
  screenshots.

## Writing

- **Sentence case** everywhere, titles and buttons included. The name is **GooseStation**: one word, two capitals.
- **Talk to the player as *you*.** The app calls itself GooseStation, never *I* or *we*.
- **Buttons are verbs:** Play, Resume, Add folder, Pick folder, Sync now. Never "OK" when the button can say what it
  does.
- **Say what's true, then the way forward:** "Memory card sync is offline. Your saves stay on this device and sync
  when you're back online." No apologies, no blame, no exclamation marks.
- **Show progress as a present-tense line ending in the ellipsis character (…):** "Scanning your games folders…".
- **Join details with " · "** ("NTSC-U · SCUS-94900 · 3h 12m") and leave out a part that's blank.
- **Numbers:** play time as "3h 12m"; save times in 24-hour form; serials as printed. Use the hardware's own words:
  PS1, BIOS, disc, memory card, save state, controller; NTSC-U, NTSC-J, PAL.

## Building a new screen or control

1. **Look for a component first.** Combine `SettingsPage`, rows, `ActionButton` and `Notice` before writing a new
   control.
2. **Wrap the screen in `GooseStationTheme`** (the activity already does) and take colours from
   `GooseStationTheme.colors` or `MaterialTheme.colorScheme`, type from `MaterialTheme.typography` or
   `GooseStationTheme.type`, shapes from `GsShapes`. No `Color(0x…)` literals and no font sizes in screens.
3. **Make every activatable thing `focusRing`ed**, focus something when the screen opens, and leave 4 dp for the
   ring in clipping containers.
4. **Give it the touch size** (48 dp tall at least) and let it wrap: `FlowRow` for button rows, `LabelValue` for a
   label and a value.
5. **A new component** that others will reuse goes in `ui/components/`, with a KDoc saying what it shows, what the
   caller provides and how it responds to each input. Add it to this document.
6. **A new value** (a colour, a size) goes in `tokens.json` first, with a usage note and, for a colour, its contrast on
   the grounds it's used on in both themes. Then add it to `GsColors` or the right place in `Theme.kt`.

### Checking a new screen

The instrumented tests (`android/app/src/androidTest/…/ui/`) hold every screen to the system:

- `FocusAuditTest`, through `assertControllerReady()`: something has focus, and everything clickable can be focused.
  Add the new screen to it.
- `OrientationLayoutTest`: at phone and handheld sizes, portrait and landscape, at text 1.0× and 1.3×, no text is cut
  off, broken mid-word, overlapping or off-screen, and every focusable is reached with the D-pad and scrolled into
  view. Add settings pages and anything with long, user-supplied text to it.
- A screen test of its own, driving it with keys (`pressKey(Key.DirectionDown)`, `Key.Enter`, `Key.ButtonB`) and
  checking what's focused, as `SettingsRowsTest` does.

See [docs/android.md](android.md) for running them.

## Not built yet

The design system defines these, and the published page previews them, but no app has them yet: a light theme on
Android; a badge for region; the
desktop nav rail; and the whole desktop app and Big Picture. Build them from the published page's previews and the
tokens.
