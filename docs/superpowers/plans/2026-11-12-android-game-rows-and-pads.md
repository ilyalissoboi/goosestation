# Game-Aware Rows and Pads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put the multitap order and labels in one `Pads` object, and make the game-aware rows one implementation,
renamed `Game*`, whose grouped rows gain the next-start note and `enabled`.

**Architecture:**
- **`Pads`** (`settings/PortSettings.kt`) replaces `ControllersPage.PORT_ORDER`, `portLabel`,
  `GameControllers.pads` and `GameControllers.label`, and the `"Port ${pad + 1}"` fallbacks.
- **`PageRows.kt`** gets one private choice row and one private number row over a list of settings, with `value` and
  `write`. The six public rows are thin wrappers over them.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4, Compose UI tests (AndroidJUnit4), ktlint.

**Spec:** `docs/superpowers/specs/2026-11-12-android-game-rows-and-pads-design.md`

## Global Constraints

- **Single-setting rows behave exactly as before,** on both pages.
- **The grouped rows change only in two ways:** their note goes through `gameNote` over all their settings, and they
  take and pass on `enabled`.
- **The one visible change:** a pad the core doesn't offer is labelled `Pads.label(pad)`, so "Port 1B" where it said
  "Port 3". Ports 1 and 2 still say "Port 1" and "Port 2".
- **The new names:** `GameSwitch`, `GameNumber` (int and float), `GameNames`, `GameChoices`, `GameGroupChoices` and
  `GameGroupNumber`.
- **Untouched:** `PostProcessingScreen`'s switch.
- **`docs/design-system.md` is binding.** How a row looks doesn't change.
- **Clean room:** nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- **Checks:**
  - `./gradlew assembleDebug testDebugUnitTest ktlintCheck`;
  - single `ui` classes on emulator-5580 with `-Pgoose.orchestrator=false`, reading the XML;
  - at the end: the CI tasks, and the quick tier from a detached worktree with the emusync AAR copied in.
- **Commits** end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A single-setting row whose game-page behaviour moved** while being folded into the shared function. That covers
   the "Use global (…)" label, the mark, `followsGlobal`, `onUseGlobal`, and the note being `gameNote`'d on the game
   page only.
2. **`GameSwitch` on the global page** must still be a `SwitchRow`, not a three-way cycler.
3. **`GameNames`' offered list after dropping the second `offered()`:** a game's own value and the global one, each
   unnamed by the core, are still both offered.
4. **A label the core does give.** `state.offered`'s label still wins over `Pads.label` in `MainActivity` and
   `ControllersScreen`.
5. **`Pads.of(mode)` for an unknown mode** still gives `0, 1`, as `GameControllers.pads` does.

The tests for items 2, 3 and 5 are in the tasks below. Items 1 and 4 are covered by the existing tests running
unchanged.

---

### Task 1: `Pads`, test-first

**Files:**
- modify `settings/PortSettings.kt`, `app/ControllersPage.kt`, `app/GameSettingsPages.kt`, `MainActivity.kt`,
  `ui/ControllersScreen.kt` and `ui/GameControllersScreen.kt`;
- test: create `test/.../settings/PadsTest.kt`, and modify `androidTest/.../ui/ControllersScreenTest.kt`.

**Interfaces (produced):**

```kotlin
object Pads {
    val ORDER: List<Int>                          // 0, 2, 3, 4, 1, 5, 6, 7
    fun of(multitap: String): List<Int>           // today's GameControllers.pads
    fun label(pad: Int, multitap: String): String // today's GameControllers.label
    fun label(pad: Int): String                   // today's ControllersPage.portLabel
}
```

- [ ] **Step 1: Write `PadsTest`:**
  - `orderKeepsAPortsSlotsTogether`: `ORDER == listOf(0, 2, 3, 4, 1, 5, 6, 7)`;
  - `eachModeOffersItsPads`, by mode:
    - `"Disabled"` → `[0, 1]`;
    - `"Port1Only"` → `[0, 2, 3, 4, 1]`;
    - `"Port2Only"` → `[0, 1, 5, 6, 7]`;
    - `"BothPorts"` → `ORDER`;
    - `"Nonsense"` → `[0, 1]`;
  - `aTappedPortsPadsAreLettered`, for each mode and each pad in `of(mode)`:
    - `"BothPorts"` gives 0 → "Port 1A", 2 → "Port 1B", 4 → "Port 1D", 1 → "Port 2A" and 7 → "Port 2D";
    - `"Port1Only"` gives 1 → "Port 2";
    - `"Disabled"` gives 0 → "Port 1" and 1 → "Port 2";
  - `withoutTheModePortsOneAndTwoAreUnlettered`: `label(0) == "Port 1"`, `label(1) == "Port 2"`,
    `label(2) == "Port 1B"`, `label(4) == "Port 1D"`, `label(5) == "Port 2B"` and `label(7) == "Port 2D"`.
- [ ] **Step 2: Add to `ControllersScreenTest`** `aPortTheCoreDoesntOfferIsLabelledAsTheCoreWould`:
  `show(ports = mapOf(PAD.descriptor to 2), offered = OFFERED)`, where `OFFERED` lacks pad 2, then
  `assertFocusedHas("Port 1B")`.
- [ ] **Step 3: Run** `--tests '*PadsTest'`. Expected: it fails to compile. Then run `ControllersScreenTest` on the
  emulator. Expected: the new test fails, showing "Port 3".
- [ ] **Step 4: Write `Pads`** from `GameControllers.pads`/`label` and `portLabel`, as they are.
  - Move every caller onto it: `ControllersPage` (`PORT_ORDER`, `portLabel`), `GameSettingsPages` (`pads`),
    `GameControllersScreen` (`label`), and the three `"Port ${… + 1}"` fallbacks (`MainActivity` ×2,
    `ControllersScreen`), all with `Pads.label(pad)`.
  - Remove the old helpers.
  - Move any existing JVM test of them onto `PadsTest`.
- [ ] **Step 5: Run** the checks, then `ControllersScreenTest`, `GameControllersScreenTest` and `PortScreenTest` (if
  present) on emulator-5580. Expected: all pass.
- [ ] **Step 6: Commit:** "Android: the multitap pads' order and labels in one Pads object".

### Task 2: One implementation behind the game-aware rows, renamed

**Files:**
- modify `ui/PageRows.kt`, and every call site of the six rows (`ui/*Screen.kt`);
- test: `androidTest/.../ui/GameRowsTest.kt`.

**Interfaces (produced):** the six public rows, with today's parameters under the new names. Two rows gain
`enabled: Boolean = true`: `GameGroupChoices` (after `modifier`) and `GameGroupNumber` (after `modifier`). Both
`GameNumber`s keep no `enabled`, as today.

- [ ] **Step 1: Add to `GameRowsTest`** a `showRows(context, content)` helper. It sets the content with
  `LocalGameContext` and a `values` state, as `show` does. Then add these tests:
  - `aGroupedRowOfANextStartSettingSaysSoInARunningGame`: `GameGroupChoices` over
    `listOf(ConsoleSettings.REGION)`, and `GameGroupNumber` over `listOf(ConsoleSettings.RAM_8MB)` (as a 0/1 number),
    each show `NextStart.NOTE` while running;
  - `aDisabledGroupedRowCantBeChanged`: each, with `enabled = false`, ignores a press on its selected row; the value is
    unchanged;
  - `aSwitchOnTheGlobalPageIsOnOrOff`: `GameSwitch` with no game context is a switch, so pressing toggles it and no
    "Use global" shows (Review Focus 2);
  - `aGameNamesRowOffersTheGamesAndTheGlobalUnnamedValues`: a game's value "Odd" and the global "Other", neither named
    by the core, both appear in the list (Review Focus 3).
- [ ] **Step 2: Run** `GameRowsTest` on the emulator. Expected: it fails to compile, because of the new names and the
  `enabled` parameters.
- [ ] **Step 3: Write the shared rows.**
  - **`gameChoiceRow<T>(values, onChange, label, settings, choices, value, write, note, modifier, enabled)`:** on the
    global page a `CyclerRow`. On a game's page, today's game branch over all `settings`, with
    `note = gameNote(settings, note)` and the mark over all of them.
  - **`gameNumberRow(values, onChange, label, settings, scale, value, write, note, modifier, enabled)`:** today's
    `GroupNumber` body, with `gameNote` over the settings.
  - **`gameNote`** takes a list of settings (any one in `NextStart.settings`), and keeps an overload for one setting.
  - **The public rows** wrap these. A single-setting row passes `listOf(setting)`, `{ it[setting] }` and
    `{ v, x -> v.with(setting, x) }`.
  - **`GameSwitch`** keeps its global-page `SwitchRow` branch.
  - **`GameNames`** calls `offered()` once on each path, with the same result.
  - **Every call site** takes the new name.
- [ ] **Step 4: Run** the checks, then on emulator-5580, one at a time: `GameRowsTest`, `SettingsScreensTest`,
  `GameSettingsTest`, `PortScreenTest`, and every other `ui` class whose screen uses the rows
  (`grep -l "GameSwitch\|GameNumber\|GameNames\|GameChoices\|GameGroup" androidTest`). Expected: all pass.
- [ ] **Step 5: Commit:** "Android: one implementation behind the game-aware rows, renamed Game*".

### Task 3: Whole-branch checks

- [ ] **Step 1: Run** the CI tasks, and the quick tier from `git worktree add --detach /tmp/goose-rows HEAD`, with
  the emusync AAR copied in. Expected: all pass, none skipped beyond the BIOS tests.
