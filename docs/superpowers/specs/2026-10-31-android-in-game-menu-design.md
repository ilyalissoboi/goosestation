<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Android in-game menu — design

Roadmap item 16, the "In-game menu" stage that follows Stage 1 (see
[2026-10-25-android-frontend-design.md](2026-10-25-android-frontend-design.md), "Later stages"). Agreed in
conversation on 2026-10-31. Calls made while writing this up, not yet confirmed, are marked *(writer's call)*; calls
the builder may make are marked *(builder's call)*.

## Intent

Stage 1's pause menu ([`ui/GamePrompt.kt`](../../../android/app/src/main/java/io/github/ilyalissoboi/goosestation/ui/GamePrompt.kt))
offers only **Resume** and **Quit to library**. The player asked for an in-game menu with: load state, save state,
toggle fast forward, RetroAchievements achievements and leaderboards, exit game, change disc, toggle the controller's
analog mode, patch codes, save screenshot and reset console.

That list is built in **three parts**, each ending in something usable on the AYN Thor:

1. **The core menu (this document specifies it in full):** save and load state, fast forward, analog mode,
   screenshot, reset console and exit game, plus a resume state on exit and a Hotkeys section for the toggles.
2. **Discs and patch codes (outline only):** change disc, with a game's discs grouped in the library, and a patch-code
   screen per game.
3. **RetroAchievements (outline only):** networking on Android, sign-in, the achievements and leaderboards screens, and
   how hardcore mode limits the rest of the menu.

Every function in part 1 already exists in the core (`src/core/system.h`, `src/core/hotkeys.cpp`); what's missing is
the Android side: JNI calls, screens and bindings.

## Part 1: goals

### The menu

Back, or Select + Start held for one second, pauses the game and opens the menu, as today (see `docs/android.md`,
"Leaving a game"; the triggers, release-on-open and release-on-resume behaviour don't change). The menu panel shows
the "Paused" eyebrow, the game's title and details, then these rows, in this order:

1. **Resume** (focused when the menu opens)
2. **Save state**
3. **Load state**
4. **Fast forward**, with its current value, "On" or "Off", at the row's end
5. **Analog mode**, with "On" or "Off" (only when Port 1's controller type is the DualShock, `AnalogController`;
   hidden otherwise) *(writer's call)*
6. **Screenshot**
7. a hairline divider
8. **Reset console**
9. **Exit game** (renamed from "Quit to library")

Parts 2 and 3 add **Change disc** after Load state, and **Patch codes** and **Achievements** after Screenshot. Part 1
shows none of them *(writer's call: no greyed rows for unbuilt features)*.

Icons (from `GsIcons`): Resume `play`, Save state `saveState` and Load state `loadState` (a memory card with an arrow in or out), Fast forward `fastForward`, Analog mode
`gamepad`, Screenshot `camera`, Reset console `rewind`, Exit game `power`. *(Writer's call; a builder may draw a
better reset icon in the system's style.)*

### Layout: two panels

Chosen from mockups. The menu is a `surface` panel at the left of the screen (about 300 dp wide, 32 dp from the
edges), over the `scrim`ed game. **Save state** and **Load state** open a second, wider `surface` panel to its right,
filling the rest of the height, while the menu stays visible with its row marked as current (`beak-soft`) *(writer's
call)*. Focus moves into the slot panel; B or Back returns focus to the menu row it came from and closes the slot
panel.

On a screen too narrow for both panels side by side (under about 720 dp wide, e.g. a phone in portrait), the slot
panel opens below the menu panel instead: the menu keeps the top of the screen (at most 40% of its height, its rows
scrolling) and the slot panel takes the rest. B returns to the menu row, as on a wide screen (the player's choice,
2026-10-31, in place of the slot panel replacing the menu).

### The slot list

A scrolling list, one row per slot, each row showing:

- **the screenshot** saved in the state, at 4:3, about 112 × 84 dp, on `sunken`; an empty slot shows the
  `memoryCard` icon in `ink-faint` on `sunken` instead;
- **the slot's name**: "Slot 1" to "Slot 10", and "Resume" for the resume state;
- **its status**: "Saved" or "Empty";
- **its time**: the save's date and time in `mono` (Today/Yesterday, else the date, then 24-hour time, as the design
  system's Writing rules), only when saved.

The core keeps 10 slots per game (`System::PER_GAME_SAVE_STATE_SLOTS`), at `savestates/<serial>_<n>.sav`, with a
256 px screenshot inside each (`System::GetExtendedSaveStateInfo`). Global slots aren't offered *(writer's call:
per-game only)*.

- **Save state** lists Slot 1 to Slot 10 (not the resume state). **Load state** lists **Resume** first, when a resume
  state exists, then the slots that hold a save.
- When the list opens, the **slot last saved to or loaded from** in this game is focused; with none, Slot 1 (Save)
  or the newest save (Load) *(writer's call)*.
- **In Load state, empty slots** aren't listed: they can't be picked, and a list the focus skips through is
  confusing *(changed after the first device pass; it showed them disabled)*. Load state with no saves at all shows
  a line saying there are no saves yet; the menu's Load state row is still enabled.
- Reading the slots (and their screenshots) happens off the UI thread; rows show without a picture until it arrives,
  and the panel never waits for the core to draw.

### Saving

- Picking an **empty slot** saves at once.
- Picking a **filled slot** asks first, inside the slot panel (not a window dialog): "Replace the save in slot 3?",
  with **Replace** (`Danger`) and **Cancel** (`Ghost`, focused) *(writer's call on the wording and focus)*.
- After saving, the menu closes and the **game resumes**, with an on-screen message "Saved to slot 3".
- A failed save (disk full, the data folder unreachable) keeps the menu open and shows the reason in the slot panel as
  a `Danger` notice. The game doesn't resume.

### Loading

- Picking a slot loads it, closes the menu and resumes the game, now at the loaded point, with "Loaded slot 3"
  (or "Loaded your resume state").
- Loading needs no question: the core keeps an undo state (`System::CanUndoLoadState`), but part 1 offers no Undo
  load *(writer's call; it's a candidate for later)*.
- A failed load (a corrupt or incompatible state) keeps the game where it was, keeps the menu open, and shows the
  reason as a `Danger` notice.

### The resume state

- **Exit game** saves a resume state (`System::ShutdownSystem(true)`, which writes `<serial>_resume.sav`), then
  returns to the library. Today Android always shuts down with `false`.
- **When a resume state already exists**, Exit game asks first, in the menu: "Replace your saved place from <time>?",
  with Replace (`Danger`) and Keep it (`Secondary`, focused). Both exit; Keep it exits without saving, so the old
  resume state stays. B cancels and stays in the game *(added after the first device pass)*.
- The next time the player starts that game, before it boots, the app asks: **"Resume where you left off?"**, with
  the resume state's screenshot and time, and **Resume** (`Primary`, focused) and **Start over** (`Secondary`). Resume
  boots from the resume state (`SystemBootParameters.save_state`); Start over boots normally. B or Back cancels and
  stays in the library *(writer's call)*. Drawn in the app's window, controller-first, like the menu.
- "Start over" doesn't delete the resume state; the next Exit game replaces it *(writer's call)*.
- If the resume state can't be loaded at boot (corrupt, or from an incompatible build through a shared data folder),
  the app returns to the library with the reason in the message bar; the file is kept, so the next start offers
  Resume and Start over again *(writer's call)*.
- Other ways a game ends don't save one: a fatal error, the SD card removed, the app killed. A save that fails on
  Exit game shows "Couldn't save your place: <reason>" in the library's message bar, and the game still exits.
- The `Main/SaveStateOnExit` setting already exists (default on) and is honoured; there's no switch for it in part 1
  *(writer's call)*.

### Fast forward

- The row toggles fast forward and shows its value; picking it closes the menu and resumes the game *(writer's call:
  you turned it on to play faster)*.
- While it's on, the on-screen message layer shows the fast-forward marker (below).
- The speed is the existing `Main/FastForwardSpeed` setting (default 0, as fast as the device can go). No setting
  for it in part 1.
- It's off again whenever a game starts.

### Analog mode

- The row switches the DualShock's analog mode, exactly as the real controller's Analog button does (the core's
  `Analog` button on `AnalogController`), shows its value, and resumes the game *(writer's call)*, with "Analog mode on" or "Analog mode off".
- Shown only for the DualShock controller type.

### Screenshot

- The row saves a screenshot of the game picture without the menu (`System::SaveScreenshot`), then resumes the game,
  with "Screenshot saved". Screenshots go to the data folder's `screenshots` folder, which is mirrored.

### Reset console

- Asks first, inside the menu panel: "Reset the console? Progress since your last save is lost.", with **Reset**
  (`Danger`) and **Cancel** (`Ghost`, focused). Reset restarts the game (`System::ResetSystem`) and resumes it, with
  "Console reset".

### Hotkeys

A new **Hotkeys** section at the end of the Controllers page lets the player assign a controller button to:

- **Fast forward** (toggle: the core's `ToggleFastForward` hotkey),
- **Analog mode** (Port 1's `Analog` button binding), and
- **Screenshot** (the core's `Screenshot` hotkey).

**Hotkeys are chords: hold Select and press a button** (the player's choice). The Thor's pad has no spare button
(Back opens the menu, Home never reaches the app), so a single button would also press its game button. As a chord,
nothing is taken from the game:

- Each row's capture asks for a button to press **with Select** ("Hold Select and press a button…"), with the same
  5 s timeout, Clear and Undo as the pad rows. It stores the core's chord binding (`Android-0/Button109 &
  Android-0/Button<n>`, Select being `KEYCODE_BUTTON_SELECT`, 109). Select itself, Start (Select + Start is the
  menu's trigger) and Back can't be picked.
- **Select still reaches the game** when it's pressed and let go on its own. While Select is held, the press of a
  button bound in a hotkey chord fires the hotkey and doesn't reach the game; Select's own press is held back from
  the game until it's known not to be part of a chord, the way Select + Start's key-down is held back today
  (`input/KeyRouter.kt`, `input/MenuTrigger.kt`) *(writer's call: a Select tap goes to the game on release if no
  chord button came; the builder checks a game's Select still works, e.g. a map screen)*.
- **Unassigned by default.**
- **Hotkeys follow the selected controller**, like the pad rows: bindings are per device (`Android-<n>/…`), so each
  controller has its own *(writer's call)*.
- **The existing "Analog Toggle" row moves into Hotkeys** as "Analog mode", so one row edits `[Pad1] Analog`; it
  leaves the pad list *(writer's call)*. Like the others, it's a Select chord.
- Bindings live in `settings.ini` (`[Hotkeys] ToggleFastForward`, `[Hotkeys] Screenshot`, `[Pad1] Analog`), so they
  follow the data folder. A hotkey works only while a game runs and the menu is closed.

The section is named "Hotkeys" because more will join it (the player's choice of name).

### On-screen messages

Messages over the game are drawn by the app, in a new **on-screen message layer** in the design system's `osd`
style (the player's choice), not by the core's ImGui OSD:

- A Compose layer over the game's `SurfaceView`: messages in the `osd` type (Atkinson Hyperlegible Mono 16/20, 600)
  on the `osd` ground with `osd-ink` text, from `tokens.json`, top left, stacked, each fading out after about 3 s
  *(writer's call on position and time; the published preview wins if it differs)*. Motion follows the design
  system: messages fading are the only thing that moves while a game runs, and reduce-motion is honoured.
- The fast-forward marker is part of the same layer: the `fastForward` icon, top right, while fast forward is on.
- Part 1's messages: "Saved to slot N", "Loaded slot N", "Loaded your resume state", "Screenshot saved", "Analog
  mode on" / "Analog mode off", "Console reset". Wording follows the design system's Writing rules.
- **The core's own messages are turned off on Android** for these actions, so nothing shows twice. The core's other
  messages (the memory card save message with its icon, controller connected, errors) are routed to the same layer
  *(writer's call: route `Host::AddOSDMessage`/`AddIconOSDMessage` to the app on Android, so every message over the
  game has the one look; the core's FPS/speed overlay stays as it is)*.
- The layer is the design system's "on-screen messages over the game" item; add it to `docs/design-system.md` and
  out of "Not built yet".

### Button hints (`KeyHints`)

The design system lists a button-hint footer, `KeyHints`, as "not built yet". Part 1 builds it as a shared component
in `ui/components/`, from the design system's published preview and tokens (key chips: `key` type, a
`line-strong` edge, `radius-sm`), and adds it to `docs/design-system.md`'s Components section. It shows at the foot
of the menu panel and the slot panel: **A** Select, **B** Back (and on the slot panel, Save or Load for A). Which
glyph names A and B follows the pad's labels, as the Controllers page already shows them *(builder's call on how)*.

## Part 1: non-goals

- Change disc, patch codes and RetroAchievements (parts 2 and 3).
- Undo load state, rewind, frame step, global save slots, deleting or renaming saves, and save-state management
  outside a game (the "Saved data" stage).
- A settings switch for the resume state or the fast-forward speed.
- Touch controls.

## Anti-goals

Part 1 fails, even if every function works, if:

- a save, the resume state or a memory card is lost or overwritten without the player confirming it;
- the menu or the slot list can't be driven with the Thor's pad alone, or something focusable can't be reached;
- input pressed in the menu leaks into the game, or input held in the game sticks after the menu closes;
- the UI thread waits on the core (an ANR risk), for example while slots and their screenshots are read;
- it breaks the design system: colours, type and shapes outside the tokens, window-based dialogs, more than one
  `beak` fill per screen.

## Constraints

- The design system ([`docs/design-system.md`](../../design-system.md)) is binding: tokens only, `focusRing` on
  everything activatable, existing components first (`ActionButton`, `Notice`, `Eyebrow`, the menu rows), no window
  dialogs, focus on open, 4 dp room for the ring in clipping containers. New screens go into `FocusAuditTest` and
  `OrientationLayoutTest`, with a screen test of their own driven by keys.
- Core calls run on the CPU thread through the host's queue (`EmuThread::RunOnThread`), never on the UI thread; JNI
  follows the existing `NATIVE_LIBRARY_FUNCTION` pattern in `android/app/src/cpp/jni_bridge.cpp`, and results come
  back through `NativeCallbacks`, posted to the main thread.
- Save states and screenshots live in the working copy's `savestates` and `screenshots` folders, which the data
  folder's mirror already pushes (`mirror/DataLayout.kt`); a state written mid-push must not be mirrored half-written
  (the mirror's existing no-half-written-file rule covers it, but check it with a state's size).
- No core behaviour changes for desktop. Android-only code is behind `__ANDROID__` or in `android/`.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7` or its closed Android app.
- The user reviews every commit message before it's committed.

## Parts 2 and 3 (outline only)

**Part 2: discs and patch codes.**
- Change disc: for an M3U, switch between its discs (`System::SwitchMediaSubImage`); for separate disc files, the
  library groups a game's discs (today `getGameList` skips `DiscSet` entries and each disc is its own tile) and the
  menu offers the set's other discs (`System::InsertMedia`).
- Patch codes: a per-game screen listing the core's cheat and patch codes for the game, from `data/resources/chtdb.txt`
  and the player's own lists, switched on and off.

**Part 3: RetroAchievements.**
- Android has no HTTP downloader today (`src/util/http_downloader_android.cpp` returns nothing); part 3 needs one.
- Sign-in, the achievements list and leaderboards for the running game, and on-screen unlock messages.
- GooseStation isn't registered with RetroAchievements yet (roadmap item 6), so its user agent may be refused in
  hardcore mode; hardcore mode also disables loading states, patch codes and fast forward, which the menu must reflect.

## The published design-system page

`docs/design-system.md` points to a published page with previews of `KeyHints`, save-state slots and on-screen
messages, which needs a maintainer to grant access. A builder without access works from `tokens.json`, the design
system's text and this document; where the preview and this document differ, this document wins *(writer's call)*.

## Left to the builder

The module and file structure; how slot information and screenshots are passed across JNI (a bitmap, a PNG, a file
path); how the last-used slot is remembered (per game, in the app's preferences or `settings.ini`); the fast-forward
marker's exact drawing; animation timings within the design system's rules; and the order of implementation.
