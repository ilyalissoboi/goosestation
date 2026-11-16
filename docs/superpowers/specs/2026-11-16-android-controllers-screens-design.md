# The controllers screens: the page's state and actions, not their fields

Batch P of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding 3.5's
controllers row). It is what 3.5 has left.

## Why

The Controllers page's four screens take the page's state one field at a time:

| Screen | Parameters |
|---|---|
| `PortScreen` | 27 |
| `MacroScreen` | 22 |
| `ControllersScreen` (the hub) | 18 |
| `HotkeysScreen` | 15 |

*(The review counted 28, 21 and 19; these are today's.)*

`MainActivity` spells all of them out in about 120 lines (`SettingsPageContent`'s `Screen.Controllers` branch), and works
some of them out there too:

- the controllers on a port, `state.controllers.filter { (state.ports[it.descriptor] ?: 0) == view.pad }`, twice;
- a port's name, `state.offered.firstOrNull { it.value == view.pad }?.label ?: Pads.label(view.pad)`, twice;
- whether the inputs show as glyphs, `state.bindingsType in GLYPH_TYPES`;
- `undoFor = state.undo?.name` three times, and `triggersFor`/`hotkeysFor` as `bindingsFor` only once the list is read;
- the macro row a port's page focuses, from `state.cameFrom`.

`ControllersPage` already filters the controllers on a port the same way, in its private `candidates()`, so the rule is
written twice.

The screens repeat the capture's frame too: `BackHandler(enabled = capturing != null)`, the focus put back on the first
row whenever the page loses it, `controllers.firstOrNull { it.descriptor == selected } ?: controllers.firstOrNull()`,
`labels[d] ?: name` seven times across the screens and `ControllerPicker`, and three prompts that each end "… Back cancels ($secondsLeft)".

`AppController` passes six of the page's actions through (`selectController`, `captureBinding`, `captureHotkey`,
`cancelCapture`, `clearBinding`, `resetBindings`), only for `MainActivity`.

Post-processing solved this shape already: `PostProcessingScreen(model, packs, onBack)` takes one
`PostProcessingModel`, which the page implements and the tests fake.

The goal is that **a controllers screen takes the page's state and its actions**, the rules that read the state are
written once beside it, and the capture's frame is written once.

## Goals

- **A `ControllersActions` interface** in `app/`, which `ControllersPage` implements: the actions the screens call.
- **Each screen takes `state: ControllersState` and `actions: ControllersActions`**, plus `onBack`; the hub also the
  touch controls' settings, which aren't the page's.
- **One `ControllersPageScreen` dispatcher** in `ui/`, which picks the view's screen. `MainActivity`'s branch becomes a
  call to it.
- **The rules that read the state are `ControllersState`'s own**, with JVM tests, and `ControllersPage` uses them too.
- **The capture's frame is written once:** a `BindingsPage` and a `CaptureBindingRow`.
- **`AppController`'s six pass-throughs go.**

## Non-goals

- **No behaviour change.** No row, wording, focus or capture changes. Each of the three prompts keeps its words
  ("Press the new button for …", "Press the button for …", "Hold Select and press a button for …"); only their shared
  end is written once.
- **No change to `ControllersPage`'s logic:** what it reads, when, the capture, the undo, or `ControllersState`'s fields.
- **No change to `BindingRow`, `ControllerPicker`** or the design system.
- **A game's own controllers page (`GameControllersScreen`) stays as it is.** It edits a game's file, not this page.
- **The touch controls stay `AppController.touchControls`'.** The hub keeps `touch` and `onTouch`.

## Anti-goals

- **A screen that takes the page itself (`ControllersPage`)**, which would tie the UI to its coroutines and make the
  tests build one. The screens see the state and the interface only.
- **A rule worked out in a composable that the page also needs**, or worked out in `MainActivity`. Each is
  `ControllersState`'s, once.
- **Tests that only check a call happened.** The screen tests already pin what the player sees; they keep doing that,
  through the state and a fake.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`ControllersActions`** (`app/ControllersPage.kt`), the page's own method names, so it implements them as they are:

  ```kotlin
  interface ControllersActions {
      fun select(descriptor: String)
      fun capture(name: String)
      fun captureHotkey(name: String)
      fun cancelCapture()
      fun clear(name: String)
      fun undo()
      fun dropUndo()
      fun reset()
      fun setPort(descriptor: String, pad: Int)
      fun setRumble(descriptor: String, percent: Int)
      fun setMultitap(values: SettingValues)
      fun openPort(pad: Int)
      fun openMacro(pad: Int, macro: Int)
      fun openHotkeys()
      fun setType(pad: Int, type: String)
      fun changePort(values: SettingValues)
      fun changeMacro(values: SettingValues)
  }
  ```

  `open`, `close`, `back`, `reloadShown` and `onBindingCaptured` stay off it: they're `AppController`'s.
- **`ControllersState`'s rules**, as members or extensions beside it:
  - `covered`: the controllers the view covers: on a port's or a macro's view, those on its pad; else all. This is
    `candidates()`, which the page then calls with the state it has.
  - `controller`: the one the view shows: the selected one among `covered`, or the first.
  - `label(controller)`: its `labels` entry, or its name.
  - `portLabel(pad)`: the offered port's label, or `Pads.label(pad)`.
  - `glyphs`: whether the bindings' type shows its inputs as glyphs. `GLYPH_TYPES` moves beside it.
  - `undoFor`: the name an Undo shows on, `undo?.name`.
  - `macroFocus`: the macro a port's page focuses as it comes back, from `cameFrom`.
- **The screens:** `ControllersScreen(state, actions, onBack, touch, onTouch)`, `PortScreen(state, actions, onBack)`,
  `MacroScreen(state, actions, onBack)`, `HotkeysScreen(state, actions, onBack)`. A port's and a macro's take their pad
  and macro from `state.view`. `PortScreen` shows the loading page itself while the port's bindings are unread, as
  `MainActivity` does now.
- **`ControllersPageScreen(state, actions, onBack, touch, onTouch)`** (`ui/`): one exhaustive `when (state.view)`.
- **`BindingsPage(title, state, actions, onBack, focus, content)`** (`ui/BindingRow.kt`): `SettingsPage`, the
  `BackHandler` that cancels a capture, and the column that asks for [focus] whenever nothing in the page has it.
  `focus` gives the requester to focus, so a port's page can give its macro row.
- **`CaptureBindingRow(binding, ask, state, actions, …)`**: a `BindingRow` whose prompt is `"$ask… Back cancels
  ($secondsLeft)"` while it's captured, and whose Undo, Clear and drop go to `actions`. A hotkey's capture is
  `captureHotkey`; a macro's trigger shows as "Trigger" but captures its own name.
- **`MainActivity`:** `Screen.Controllers -> ControllersPageScreen(AppController.controllersPage.state,
  AppController.controllersPage, controllersBack, …touch)`.

## Tests to write

**JVM (`ControllersPageTest`, or a `ControllersStateTest`):**

1. **`covered`** on the hub and the hotkeys (all), on a port and on a macro (those on its pad, a controller with no
   port read counting as on pad 0).
2. **`controller`**: the selected one when covered; the first covered when the selected one isn't; none with none.
3. **`label` and `portLabel`**: a numbered label wins over the name; an offered port's label, and a lost port's.
4. **`glyphs`**: the digital and analog types; another type; no type read.
5. **`macroFocus`**: back from a macro of this port; from the hub; on opening.

The page's existing tests (43) must pass unchanged, now that `candidates()` is `covered`.

**Instrumented:** the four screens' tests (`PortScreenTest`, `MacroScreenTest`, `HotkeysScreenTest`,
`ControllersScreenTest`) and the screens' cases in `FocusAuditTest` and `OrientationLayoutTest` build a
`ControllersState` and a recording fake of `ControllersActions`, in place of the fields and lambdas. Their cases and
assertions stay. One new case: **`ControllersPageScreen` shows each view's screen** (the hub, a port, a macro, the
hotkeys).

## Constraints

- Kotlin, with the project's ktlint rules and KDoc style.
- **Every check stays green:** `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`, with warnings as errors; and the quick tier on the test emulators, which runs the four screens'
  tests and the focus audit.
- **On a device.** On the AYN Thor's debug build, with your OK, from the library's Settings: the Controllers hub, Port
  1's page with its bindings, a macro and back to its row, the hotkeys, and back to the hub's rows. One capture
  started and cancelled with Back, so nothing is bound. Nothing set, cleared or reset.
- **Commits:** the state's rules and the interface, with their JVM tests; then the screens, `BindingsPage`,
  `CaptureBindingRow`, the dispatcher, `MainActivity`, `AppController` and the instrumented tests, in one, since every
  screen's signature changes with its callers; then the review's status section.

## Left to the builder

- Whether the state's rules are members of `ControllersState` or extensions in a file beside it.
- The fake's shape, as long as the tests keep asserting what they assert now.
- Whether `ControllersScreen`'s `RUMBLE_SCALE` and the touch scales stay where they are.
