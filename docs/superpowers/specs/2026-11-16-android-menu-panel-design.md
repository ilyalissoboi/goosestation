# MenuPanel: the in-game menu's one side panel, as one field

Batch O of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, findings 3.2 and
1.7). It is what 3.2 has left.

## Why

The rest of 3.2 has landed since the review: `SlotQuestion` with `SlotPanel.idle` (#90), the save list shared with
Saves (`SaveListModel`, #90), and the codes and achievements panels as models of their own, each with its own
generation (#93). 1.7's bug was fixed with one `withSide()` helper (#82).

What's left is the shape 1.7 asked for. `GameMenuState` still has four nullable side panel fields, `slots`, `discs`,
`codes` and `cheevos`, and "one at a time" is kept by hand:

- `withSide()` nulls the other three, and every `open*` has to remember to call it. 1.7 was one `open*` that didn't.
- The UI decides which panel shows with the same four-way `when` in three places in `ui/GameMenu.kt`: B, the panel
  drawn, and the row the focus goes back to. A fifth panel would need all of them changed, and nothing would say so.
- `panelId` is still one counter that every panel's open and close bumps, though only the slot panel's work reads
  it.

The goal is that **one side panel at a time is in the type**, so no `open*` can break it, and the UI's choice is one
`when` the compiler checks.

## Goals

- **A sealed `MenuPanel`** in `app/`, one case per side panel, and **one `panel: MenuPanel?` field** in
  `GameMenuState` in place of the four.
- **The UI chooses by `when (state.panel)`**, exhaustive, in the three places.
- **The slot panel's work has its own generation**, bumped only as the slot panel opens or goes.
- **JVM tests** for the invariant, with the cases below.

## Non-goals

- **No behaviour change.** No panel opens, closes or says anything differently.
- **No change to the panels' own models** (`SaveListModel`, `PatchCodesModel`, `AchievementsPanelModel`) or their
  state classes (`SlotPanel`, `DiscChoice`, `CodePanel`, `AchievementsPanelState`).
- **No change to the panels' composables** or the design system. The `SidePanel` component (3.3) is unrelated, and
  keeps its name.
- **The game's own settings stay a flag.** They take the menu's place, not a side panel's, and `openSettings` already
  closes whatever panel is open.

## Anti-goals

- **Two panels representable at once**, through a second field, a default, or a `copy` that sets one without
  clearing the other.
- **A `when` over the panels with an `else`** in the UI, which would let a new panel compile without being handled.
- **Tests that only check the field was set.** Each pins what the player sees: which panel shows, what B does, and
  what a model that was closed does when its read lands.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **The type:**

  ```kotlin
  sealed interface MenuPanel {
      data class Slots(val slots: SlotPanel) : MenuPanel
      data class Discs(val choice: DiscChoice) : MenuPanel
      data class Codes(val codes: CodePanel) : MenuPanel
      data class Achievements(val cheevos: AchievementsPanelState) : MenuPanel
  }
  ```

  *(My call: `MenuPanel`, not the review's `SidePanel`, because `ui/components/SidePanel` is the frame composable
  from 3.3, and `GameMenu.kt` imports both.)*
- **`GameMenuState`** keeps `slots`, `discs`, `codes` and `cheevos` as **read-only properties** derived from `panel`.
  Reads stay as they are, so the 135 reads in `InGameMenuTest` and most of the UI don't change. Every write goes
  through `panel`.
- **`InGameMenu`:**
  - each `open*` sets `panel` to its case, closing the other panels' models first, as `withSide()` does now. `withSide`
    becomes `show(panel)`;
  - each model's callback writes its case only while that case is the one shown, or opens it. A model's late read
    for a panel that was replaced can't bring it back;
  - `panelId` becomes `slotsId`, bumped where the slot panel opens or goes: its open, its close, another panel taking
    its place, the menu opening, and the game's start and stop. *(Today every panel bumps it, but a slot panel's work
    can only be in flight while that panel shows, so the two give the same answers.)*
- **The UI (`ui/GameMenu.kt`):**
  - **B:** the slot panel's questions and work first, as now, then one `when (val panel = shown.panel)` for which
    panel to close and which row takes the focus;
  - **the panel drawn:** one `when` over `MenuPanel`;
  - **where the focus goes back:** `CameFrom` remembers the last `MenuPanel` case shown (with the slot panel's mode)
    instead of four flags.
- **`GameMenuTest`**'s fake actions write `panel` instead of the four fields.

## Tests to write (JVM, in `InGameMenuTest`)

1. **Each panel's open replaces any other:** for each pair, open one, then the other. Only the second shows, and the
   first's model is closed.
2. **A model's read that lands after its panel was replaced** doesn't bring the panel back: the codes' groups, the
   achievements' sections, and the slot lists.
3. **A slot save or load that fails after its panel was replaced** says so as a message, not in a panel that is gone.
   This repeats today's case, now that the generation is the slot panel's own.
4. **Closing a panel shows none**, and B's path (`closeSlots`, `closeDiscs`, `closeCodes`, `closeAchievements`)
   leaves no other panel under it.
5. **The menu opening, and the game's start and stop,** show no panel.

The existing tests, which read `slots`, `discs`, `codes` and `cheevos`, must pass unchanged.

## Constraints

- Kotlin, with the project's ktlint rules and KDoc style.
- **Every check stays green:** `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`, with warnings as errors; and the quick tier on the test emulators, whose `GameMenuTest`
  drives B, the panels and the focus.
- **On a device.** On the AYN Thor's debug build, with your OK, in a running game: Save state and Load state opened
  and closed with B, Change disc, Patch codes and Achievements likewise, and one panel opened from another's row. The
  focus should come back to each panel's row. Nothing is picked, saved or loaded.
- **Commits:** the type and `InGameMenu` with the new tests first; then the UI and `GameMenuTest`; then the review's
  status section.

## Left to the builder

- Whether `MenuPanel` lives in `InGameMenu.kt` beside the state classes, or a file of its own.
- The tests' names, and how they reach each pair.
