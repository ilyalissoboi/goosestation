# Android in-game menu, part 1: the core menu — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the paused game's menu grows from Resume and Quit into the core menu: save and load state in a two-panel
slot list, a resume state on Exit game with "Resume where you left off?" at the next start, fast forward, analog
mode, screenshot and reset console. Fast forward, analog mode and screenshot also get Select-chord hotkeys on the
Controllers page. A shared `KeyHints` footer and an app-drawn on-screen message layer come with it.

**Architecture:** the game's menu state lives in a new `InGameMenu` class beside `ControllersPage`. It calls a new
`GameCoreApi`, whose calls block on the CPU thread and are made from a new `gameDispatcher`, never from the main
thread. `GameMenu` (replacing `GamePrompt`) draws the menu panel and the slot panel from that state.

The core's `Host::AddOSDMessage` family is routed to the app on Android, through one `#ifdef __ANDROID__` hook in
`imgui_manager.cpp`. Kotlin rewrites the keyed messages for part 1's actions into the design's wording, drops the
save and load ones (the app says those itself), and draws everything in `OsdLayer`. So a menu row and its hotkey
show the same message.

Hotkeys are the core's own chord bindings (`Android-<n>/Button109 & Android-<n>/Button<k>`), fired by
`InputManager`, which already cancels the shorter bindings a completed chord shares. Android only stops Select's own
press reaching the game before the chord completes (`SelectChord`, in front of the core), and sends a held Select
together with the next button in one batch.

**Tech Stack:** C++20, CMake, Android NDK, JNI, Kotlin, coroutines, Jetpack Compose (BOM 2026.09.00), Material 3,
GoogleTest, JUnit 4, kotlinx-coroutines-test, AndroidX Test (orchestrator), Compose UI tests.

**Spec:** [docs/superpowers/specs/2026-10-31-android-in-game-menu-design.md](../specs/2026-10-31-android-in-game-menu-design.md),
"Part 1" in full. Its *(writer's call)* items are settled. It follows Stage 1 (Plans 1–3, PRs #23, #25, #29–#31) and
the design-system refresh (#32).

**Choices made with the user on 2026-10-31** (where the design left them open):
- **Select's own press**, on a pad with a hotkey bound, is held back. It goes to the game on its release, together with
  the next button pressed (one batch), or once it has been held for 500 ms, whichever comes first. On a pad with no
  hotkey, Select goes as it comes. A chord after 500 ms still fires; the game has seen Select by then, and the core
  cancels it as the chord completes. This was chosen over "only on release" and over resolving chords in Kotlin.
- **Slot screenshots** cross JNI as an `ArgbImage(width, height, IntArray)`, which Kotlin turns into a `Bitmap`. This
  was chosen over a native `Bitmap` and over PNGs in the cache.
- **The menu's core calls block** on the CPU thread and return their result, like `getPadBindings`. Callers make them
  on `gameDispatcher`. Callbacks remain for what the core starts itself: OSD messages, fast forward changing, the
  boot. This departs from the design's wording ("results come back through NativeCallbacks"), on the user's say.
- **The last slot used** is kept per serial in the app's `SharedPreferences` (`lastSlot.<serial>`), on this device.

**Calls made while writing this plan** *(writer's call; revisit in review)*:
- **Message rules** (`CoreMessages.translate`, Task 2), by the core's key:

  | Core key | Shown |
  | --- | --- |
  | `save_state`, `load_state` | nothing (the app posts its own) |
  | `system_reset` | "Console reset" |
  | `analog_mode_toggle_0`, `Controller0AnalogMode` | "Analog mode on" / "Analog mode off"; "locked" in the text: "The game keeps the controller in analog mode." / "… in digital mode." |
  | `ScreenshotSaver_*` | "Saving…" text: nothing; "Saved…": "Screenshot saved"; "Failed…": "Couldn't save the screenshot" |
  | anything else | the core's text, icon characters stripped (natively, `ImGuiManager::StripIconCharacters`) |

  Android has no translations, so the core's texts are its English source strings, and matching them is stable.
- **Durations:** the app's messages last 3 s. The core's keep their own durations (errors stay 15 s). At most 4 show;
  a fifth drops the oldest.
- **The memory card message's icon** shows its first frame only: nothing but fading messages moves during a game.
- **Opened while the game still boots**, the menu's game rows (Save state to Reset console) are disabled, and the
  details line says "Starting…". Resume and Exit game work.
- **Focus can't leave** the slot panel (to the menu beside it) or the resume prompt (to the library under it):
  `focusProperties { onExit = { cancelFocusChange() } }` on their focus group. B is the way out.
- **Save times:** "Today · 14:05", "Yesterday · 09:30", else "28 Oct 2026 · 21:17" (`d MMM yyyy`, English).
- **Exit game with no serial** (a PS-EXE, the BIOS shell) saves no resume state, silently: none could be offered.
- **The analog row** presses the core's `Analog` button (`SetBindState(Analog, 1)` then `0`), as the hotkey does, so
  the "locked by the game" case behaves the same both ways.
- **A hotkey capture** takes buttons only, never Select (109) or Start (108). "Reset to defaults" also clears the
  pad's hotkeys (they're unassigned by default).
- **The core's atomic temp files** for save states (`savestates/<name>.sav.XXXXXX`) are never mirrored. A forced push
  (the app going to the background mid-save) could otherwise copy one.
- **A pad connected during a game** gets its Select held back from the next game on. Chords are read as a game starts.

## Global Constraints

- The design system is binding ([docs/design-system.md](../../design-system.md), `docs/design-system/tokens.json`):
  - tokens only: no `Color(0x…)` and no font sizes in screens;
  - existing components first (`ActionButton`, `Notice`, `Eyebrow`, `LabelValue`);
  - `focusRing` on everything activatable, focus on open, 4 dp of padding for the ring in clipping containers;
  - no `AlertDialog`, `Dialog`, `Popup`, `DropdownMenu` or `ModalBottomSheet` (`checkNoWindowDialogs` enforces it);
  - at most one `beak` fill per screen;
  - every new screen goes into `FocusAuditTest` and `OrientationLayoutTest`, and gets a key-driven screen test.
- Writing: sentence case, buttons are verbs, no apologies or exclamation marks, details joined with " · ", save
  times in 24-hour form. The design's wording is used exactly where it gives one.
- Motion: messages fade out over 160 ms, and nothing else moves during a game. With animations off
  (`ValueAnimator.areAnimatorsEnabled()` false), they just go.
- Core calls run on the CPU thread (`EmuThread::RunOnThread`), never on the UI thread. `GameCoreApi` calls are made on
  `AppController.gameDispatcher` (`Dispatchers.IO.limitedParallelism(1)`), apart from `coreDispatcher`, so a library
  scan never delays the menu. `NativeCallbacks` post to the main thread and never wait for it.
- JNI: class and method IDs are cached at `JNI_OnLoad`, local references are deleted in loops, and a Java exception
  becomes an error return (Plan 2's rules).
- No core behaviour changes for desktop. Android-only code is behind `__ANDROID__`, in `src/util/android_*`, or in
  `android/`. After every task that touches `src/`, `cmake --build build` succeeds with no new warnings in changed
  files, and `build/bin/common-tests` passes.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- New files start with `// SPDX-FileCopyrightText: 2026 GooseStation contributors` and
  `// SPDX-License-Identifier: GPL-3.0`.
- Devices: the arm64 emulator (`goose-arm64`, `adb -s emulator-5554`) for every task. The AYN Thor
  (`adb -s 64dc3c35`) only after the user says go. The Thor's pad has no Mode button, and its D-pad is a hat: word
  hands-on steps with Back, Select + Start, and the D-pad.
- Commits: the user reviews every commit message in chat before it's committed. Work on
  `claude/android-in-game-menu` (the design and this plan are on it). One commit per task, in the log's style
  (`Core: …`, `Util: …`, `Android: …`, `Docs: …`), a bullet body, and the `Co-Authored-By` line. Push as
  `ilyalissoboi` (`GH_TOKEN=$(gh auth token --user ilyalissoboi)`, with the credential-helper pattern in the user's
  memory note).
- Commands: `cd android && ./gradlew testDebugUnitTest` (JVM), `ANDROID_SERIAL=emulator-5554 ./gradlew
  connectedDebugAndroidTest` (instrumented; one class with
  `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`), `./gradlew check`, and
  `android/scripts/run-native-tests.sh` on `goose-arm64`.

## Review Focus

1. **A save refused by the core** (a memory card write pending: "Cannot save state while memory card is being saved.",
   or a full disk). The slot panel stays open with a `Danger` notice saying why, the game stays paused, and nothing is
   remembered as the last slot. (Task 4 `aFailedSaveKeepsTheMenuOpenWithTheReason`.)
2. **Select in games, with a hotkey bound.**
   - A tap reaches the game, held down 100 ms.
   - A held Select reaches it after 500 ms (a map screen).
   - Select + X fires the hotkey, and the game sees neither press.
   - Select + Start still opens the menu, and the game never sees Select.

   (Task 7 `SelectChordTest`, `PadKeysTest`; Task 9 checks.)
3. **Exit game when the resume state can't be saved** (no serial, disk full). The game still exits. With a reason, the
   library says "Couldn't save your place: <reason>", and the next start offers nothing new. (Task 6
   `GameStartsTest.aGameWithoutASerialStartsAtOnce`; Task 9 check with a read-only `savestates`.)
4. **A state from another build, or a damaged one.** The row still shows Saved and its time, without a picture. Loading
   it shows the reason in the panel, and the game stays where it was. A damaged resume state at boot returns to the
   library with the reason, and is offered again next time. (Task 4 `aStateWithoutAPictureStillShowsAsSaved`,
   `aFailedLoadKeepsTheGameWhereItWas`; Task 9 check.)
5. **B and Back pressed and held across the layers.** B closes the confirmation first, then the slot panel (focusing
   its menu row), then the menu (resuming). Each acts on a key-up whose key-down it saw, so the press that opened a
   layer doesn't close it, and nothing pressed in the menu reaches the game. (Task 5 `bStepsOutOneLayerAtATime`,
   `aHeldBDoesNothingOnTheLayerItOpened`.)

## Files

| File | Responsibility | Task |
| --- | --- | --- |
| `ui/components/KeyHints.kt` (new) | The button-hint footer | 1 |
| `ui/theme/Theme.kt` | `osd`, `osdInk` colours; `type.osd` | 2 |
| `src/util/imgui_manager.h/.cpp` | The Android OSD hook | 2 |
| `android/app/src/cpp/android_host.*`, `jni_bridge.cpp` | Hook → `onOsdMessage`; fast forward polling; the game calls; hotkeys | 2, 3, 6, 7, 8 |
| `core/ArgbImage.kt` (new) | Pixels from native | 2 |
| `app/CoreMessages.kt`, `app/OsdMessages.kt` (new) | Message rules; the shown messages | 2 |
| `ui/OsdLayer.kt` (new) | Messages and the fast-forward marker over the game | 2 |
| `app/GameCoreApi.kt` (new) | The game's core calls, faked in tests | 3 |
| `mirror/DataLayout.kt` | Keeps the core's save-state temp files | 3 |
| `app/InGameMenu.kt`, `app/LastSlots.kt`, `ui/SaveTimes.kt` (new) | Menu state and actions; last slot; time labels | 4 |
| `ui/GameMenu.kt` (from `GamePrompt.kt`), `ui/SlotPanel.kt` (new) | The two panels | 5 |
| `app/GameStarts.kt`, `ui/ResumePrompt.kt` (new) | Resume where you left off | 6 |
| `input/SelectChord.kt` (new), `PadKeys.kt`, `MenuTrigger.kt`, `ControllerInput.kt` | Holding Select back | 7 |
| `src/util/android_input_keys.h/.cpp` | Chord binding and chord capture helpers | 8 |
| `app/ControllersPage.kt`, `ui/ControllersScreen.kt` | The Hotkeys section | 8 |
| `docs/android.md`, `docs/design-system.md` | Docs | 9 |

`app/AppController.kt` and `MainActivity.kt` change in Tasks 2–8 to wire each piece in. Paths under `ui/`, `app/`,
`core/`, `input/` and `mirror/` are in `android/app/src/main/java/io/github/ilyalissoboi/goosestation/`; their tests are
under `android/app/src/test/…` (JVM) and `android/app/src/androidTest/…` (instrumented), in the same packages.

---
### Task 1: `KeyHints`, the button-hint footer

**Files:**
- Create: `ui/components/KeyHints.kt`
- Test: `androidTest/…/ui/KeyHintsTest.kt`
- Modify: `docs/design-system.md` (Components: a `KeyHints` section; "Not built yet" loses the button-hint footer)

**Interfaces:**
- Produces:
  - `data class KeyHint(val glyph: String, val action: String)`
  - `object PadGlyphs { val confirm: String; val back: String }`: `BindingLabels.label("Button96")` and
    `BindingLabels.label("Button97")` ("A", "B"), so the glyphs follow the pad's labels as the Controllers page shows
    them.
  - `@Composable fun KeyHints(hints: List<KeyHint>, modifier: Modifier = Modifier)`: a `FlowRow`, 16 dp between hints
    and 8 dp between rows. Each hint is a key chip (the glyph in `GooseStationTheme.type.key`, `ink`, inside a 1 dp
    `lineStrong` border at `GsShapes.small`, at least 24 × 24 dp, 4 dp across), then, 8 dp on, the action in
    `typography.bodyMedium`, `inkMuted`. Nothing in it is focusable or clickable. The chip's semantics merge into the
    hint, read as "A: Select".
  - KDoc: what it shows, what the caller provides, that it takes no input.

- [ ] **Step 1: Write the failing tests** in `KeyHintsTest`:
  - `itShowsEachGlyphAndAction`: `KeyHints(listOf(KeyHint(PadGlyphs.confirm, "Select"), KeyHint(PadGlyphs.back,
    "Back")))` → nodes with text "A", "Select", "B", "Back".
  - `nothingInItTakesFocus`: inside a `Column` with a focused `ActionButton("Resume")`, D-pad down and up keep
    "Resume" focused, and `onAllNodes(isFocusable())` has one node.
  - `itWrapsOnANarrowScreen`: at 200 dp wide, with three hints, the third hint's top is below the first's.
- [ ] **Step 2: Run them to see them fail** (`KeyHints` doesn't exist).
- [ ] **Step 3: Implement** `KeyHints`, `KeyHint` and `PadGlyphs` as above.
- [ ] **Step 4: Run** `KeyHintsTest` → PASS, and `./gradlew ktlintCheck`.
- [ ] **Step 5: Docs.** Add `### KeyHints (KeyHints.kt)` to the Components section, with a usage snippet and the
  rules (glyphs from `PadGlyphs`, at a panel's foot, never focusable). Remove "the button-hint footer (`KeyHints`)
  naming each controller button" from "Not built yet".
- [ ] **Step 6: Commit** (message shown to the user first): `Android: KeyHints, the button-hint footer`.

### Task 2: the on-screen message layer

**Files:**
- Modify: `src/util/imgui_manager.h` and `src/util/imgui_manager.cpp`
- Modify: `android/app/src/cpp/android_host.h/.cpp`, `jni_bridge.cpp`
- Create: `core/ArgbImage.kt`, `app/CoreMessages.kt`, `app/OsdMessages.kt`, `ui/OsdLayer.kt`
- Modify: `core/NativeCallbacks.kt`, `ui/theme/Theme.kt`, `ui/GameScreen.kt`, `app/AppController.kt`,
  `MainActivity.kt`
- Test: `test/…/app/CoreMessagesTest.kt`, `test/…/app/OsdMessagesTest.kt`, `androidTest/…/ui/OsdLayerTest.kt`

**Interfaces:**
- Produces (C++, `imgui_manager.h`, `namespace Host`, under `#ifdef __ANDROID__`):
  `void OnOSDMessagePosted(std::string_view key, std::string_view message, float duration, const OSDImage* image);`,
  documented as "Android: the app draws on-screen messages. Each message posted, and each keyed removal (an empty
  message, duration 0), goes here instead of to the ImGui OSD."
  - In `ImGuiManager::PostOSDMessage`, after the `s_show_osd_messages` check, under `#ifdef __ANDROID__`: call it with
    `image ? &*image : nullptr`, then return.
  - In `Host::RemoveKeyedOSDMessage`, after its check: call it with `(key, {}, 0.0f, nullptr)`, then return.
  - Desktop compiles exactly as before.
- Produces (C++, Android host): `Host::OnOSDMessagePosted` calls
  `AndroidHost::NotifyOsdMessage(key, ImGuiManager::StripIconCharacters(message), duration, image)`.
  - The image goes as an `ArgbImage` of its first frame (`width × frame_height`).
  - Each RGBA8 pixel `0xAABBGGRR` becomes ARGB `0xAARRGGBB`: swap the R and B bytes, keep alpha.
- Produces (C++, Android host): fast forward watching. `Host::FrameDone()` compares `System::IsFastForwardEnabled()`
  with `s_fast_forward_reported`. On a change it stores the new value and calls
  `NotifyFastForwardChanged(bool)`. `Host::OnSystemDestroyed()` resets it to false, notifying if it was true.
- Produces (Kotlin):
  - `class ArgbImage(val width: Int, val height: Int, val pixels: IntArray)` in `core/`, made by native code.
    Constructor `(II[I)V`, cached at `JNI_OnLoad`.
  - `NativeCallbacks`:
    - `fun onOsdMessage(key: String, text: String, seconds: Float, icon: ArgbImage?) {}`: `seconds == 0f` removes
      `key`'s message.
    - `fun onFastForwardChanged(enabled: Boolean) {}`.
  - `object CoreMessages { fun translate(key: String, text: String): String? }`: the table under "Calls made while
    writing this plan". Null means show nothing.
  - `data class OsdMessage(val id: Long, val key: String?, val text: String, val icon: ArgbImage?, val untilMs: Long)`
  - `class OsdMessages(private val clock: () -> Long)`, main thread only:
    - `var shown: List<OsdMessage>` (Compose state, `private set`);
    - `fun post(text: String, key: String? = null, seconds: Float = APP_SECONDS, icon: ArgbImage? = null)`: a
      message with a key already shown replaces it in place, with a new `untilMs`; others go last. Over `MAX_SHOWN`
      (4), the oldest goes.
    - `fun remove(key: String)`, `fun expire()` (drops those past `untilMs`), `fun nextExpiry(): Long?`, `fun clear()`;
    - `companion object { const val APP_SECONDS = 3f; const val MAX_SHOWN = 4 }`.
  - `GsColors.osd = Color(0xD10A0C10)` and `GsColors.osdInk = Color(0xFFF3F1EC)` (from `tokens.json`), and
    `GsType.osd` (the mono family, 16/20, weight 600).
  - `@Composable fun OsdLayer(messages: List<OsdMessage>, fastForward: Boolean, nextExpiry: Long?, onExpire: () -> Unit)`:
    - messages stacked top left, 16 dp inside `WindowInsets.safeDrawing`, 8 dp apart;
    - each is the icon (if any, 20 dp, `FilterQuality.None`), then the text in `type.osd`, `osdInk`, on `osd` at
      `GsShapes.small`, padded 12 × 8 dp;
    - a non-scrolling `LazyColumn` keyed by `id`, using `animateItem(fadeInSpec = null, placementSpec = null,
      fadeOutSpec = tween(160))`, with `fadeOutSpec = null` when `ValueAnimator.areAnimatorsEnabled()` is false;
    - `LaunchedEffect(nextExpiry)` waits until then and calls `onExpire`;
    - with `fastForward`, the `GsIcons.fastForward` marker shows top right (24 dp, `osdInk`, on an `osd` chip with
      8 dp padding, `contentDescription` "Fast forward on");
    - nothing in it is focusable.
- Produces (`AppController`):
  - `val osd = OsdMessages(SystemClock::uptimeMillis)`;
  - `var fastForward by mutableStateOf(false)`, `private set`;
  - `onOsdMessage` posts to the main thread, then does `osd.remove(key)` for `seconds == 0f`, else
    `CoreMessages.translate(key, text)?.let { osd.post(it, key.ifEmpty { null }, seconds, icon) }`;
  - `onFastForwardChanged` sets `fastForward`;
  - `onGameStopped` does `osd.clear()` and `fastForward = false`.
- `GameScreen` takes `osd: List<OsdMessage>`, `fastForward`, `osdExpiry` and `onOsdExpire`, and draws `OsdLayer` over
  the `SurfaceView`, under the menu.

- [ ] **Step 1: Write the failing JVM tests.**
  - `CoreMessagesTest`, one test per row of the table:
    - `translate("save_state", "State saved to 'X_3.sav'.")` is null, and so is `load_state`'s;
    - `translate("system_reset", "System reset.")` is "Console reset";
    - `translate("analog_mode_toggle_0", "Controller 1 switched to analog mode.")` is "Analog mode on", and the
      digital text gives "Analog mode off";
    - `translate("Controller0AnalogMode", "Controller 1 is locked to digital mode by the game.")` is "The game keeps
      the controller in digital mode.";
    - `translate("ScreenshotSaver_/x.png", "Saving screenshot to 'x.png'.")` is null;
      `"Saved screenshot to 'x.png'."` gives "Screenshot saved", and `"Failed to save screenshot to 'x.png'."` gives
      "Couldn't save the screenshot";
    - `translate("", "Controller connected.")` is the text unchanged.
  - `OsdMessagesTest`, with a fake clock:
    - `aMessageLastsItsSeconds`: post at 0, `expire()` at 2 999 keeps it, at 3 000 drops it;
    - `aKeyReplacesInPlace`: post "a" (key k), then "b", then "c" (key k) → texts `["c", "b"]`, and "c"'s
      `untilMs` is from its own post;
    - `removeDropsByKey`;
    - `aFifthDropsTheOldest`;
    - `nextExpiryIsTheSoonest`, and null when empty.
- [ ] **Step 2: Run them to see them fail.** `./gradlew testDebugUnitTest --tests '*CoreMessagesTest' --tests
  '*OsdMessagesTest'`.
- [ ] **Step 3: Implement** `CoreMessages`, `OsdMessages` and `ArgbImage` → the JVM tests PASS.
- [ ] **Step 4: Write `OsdLayerTest`** (instrumented):
  - `messagesShowTopLeftInOrder`: two messages → both texts shown, the second below the first, and the first's left
    edge within 32 dp of the screen's;
  - `theMarkerShowsWhileFastForwardIsOn`: the "Fast forward on" description exists only with `fastForward = true`;
  - `nothingIsFocusable`;
  - `expiryCallsBack`: `nextExpiry` 200 ms ahead → `onExpire` is called (`mainClock.advanceTimeBy(300)`).

  Implement `OsdLayer` and the tokens → PASS.
- [ ] **Step 5: The native side.** Add the hook, `NotifyOsdMessage`, `NotifyFastForwardChanged`, the `ArgbImage`
  conversion and the callback method IDs (`onOsdMessage`
  `(Ljava/lang/String;Ljava/lang/String;FLio/github/ilyalissoboi/goosestation/core/ArgbImage;)V`, and
  `onFastForwardChanged` `(Z)V`). Desktop: `cmake --build build` and `build/bin/common-tests` → green, with no change
  in behaviour.
- [ ] **Step 6: Wire it in** (`AppController`, `GameScreen`, `MainActivity`). On `goose-arm64`, start padtest,
  press Back and resume:
  - the memory card message (or, with none, any core message in the log as `OSD: …`) shows in the app's style, top
    left, and fades;
  - the core's ImGui OSD shows no message, and its FPS overlay (Graphics → Show FPS) still draws.
- [ ] **Step 7: Run** `./gradlew check` and `connectedDebugAndroidTest` → PASS.
- [ ] **Step 8: Docs.** In `docs/design-system.md`:
  - the `osd` type row's Android column becomes `GooseStationTheme.type.osd`;
  - a new `### OsdLayer (ui/OsdLayer.kt)` entry under "Library and game screens": where messages sit, their timing, the
    marker, the reduce-motion rule, and that core messages are routed and rewritten (point to `CoreMessages`);
  - "Not built yet" loses "on-screen messages over the game in the system's style".
- [ ] **Step 9: Commit** (message shown first): `Android: an on-screen message layer, with the core's messages routed
  to it` (it touches `src/util`, behind `__ANDROID__`; say so in the body).

### Task 3: the game's core calls

**Files:**
- Modify: `android/app/src/cpp/android_host.h/.cpp`, `jni_bridge.cpp`, `core/NativeLibrary.kt`,
  `core/NativeCallbacks.kt`, `app/AppController.kt`, `MainActivity.kt`
- Create: `app/GameCoreApi.kt`; `test/…/app/FakeGameCore.kt`
- Modify: `mirror/DataLayout.kt`
- Test: `androidTest/…/core/GameCallsTest.kt`, `test/…/mirror/DataLayoutTest.kt`

**Interfaces:**
- Produces (`NativeLibrary`). Each runs on the CPU thread. The first five block, so call them off the main thread;
  the last four queue and return.
  - `external fun getSaveStateTimes(serial: String): LongArray`: `SaveSlots.COUNT + 1` entries, `[0]` the resume state
    and `[n]` slot n, each the file's modified time in Unix seconds (`System::GetSaveStateInfo`), or 0 for none. All
    zeros before `initialize` or for a blank serial.
  - `external fun getSaveStateScreenshot(serial: String, slot: Int): ArgbImage?`: the screenshot inside the state
    (`System::GetExtendedSaveStateInfo` on `System::GetGameSaveStateFileName(serial, slot)`), opaque (alpha forced to
    0xFF). Null if there's no file, or no picture, or it can't be read.
  - `external fun saveState(slot: Int): String?`: saves the running game to `slot` (1 to 10), with
    `backup_existing_save = false`. Null when saved, else the reason (`Error::GetDescription()`). "No game is running."
    without one, and "This game has no serial, so it can't have save states." with a blank serial.
  - `external fun loadState(slot: Int): String?`: loads `slot` (`SaveSlots.RESUME` for the resume state), with
    `save_undo_state = false`. Null when loaded, else the reason. The same errors.
  - `external fun getAnalogMode(): Int`: `-1` if Port 1's controller isn't an `AnalogController` (or no game),
    `0` digital, `1` analog.
  - `external fun resetGame()`: `System::ResetSystem()`.
  - `external fun setFastForward(enabled: Boolean)`: `System::SetFastForwardEnabled`.
  - `external fun toggleAnalogMode()`: on an `AnalogController` at port 0,
    `SetBindState(static_cast<u32>(AnalogController::Button::Analog), 1.0f)` then `0.0f`.
  - `external fun saveScreenshot()`: `System::SaveScreenshot()`.
- Produces (`NativeLibrary`, changed): `startGame(path: String, resumeSerial: String?)`: with a serial, it boots
  from that serial's resume state (`SystemBootParameters.save_state`). `shutdownGame(saveResumeState: Boolean)`:
  - when true, a game runs, `g_settings.save_state_on_exit` is set and the serial isn't blank, it first calls
    `System::SaveResumeState`;
  - if that fails, it calls `NotifyError("Couldn't save your place: <reason>")`;
  - then it calls `ShutdownSystem(false)`, as before.

  `NativeCallbacks.onGameStarted(title: String, serial: String)` gains the serial (`System::GetGameSerial()`). Its
  method ID at `JNI_OnLoad` becomes `(Ljava/lang/String;Ljava/lang/String;)V`.
- Produces (Kotlin, `app/GameCoreApi.kt`):
  - `object SaveSlots { const val RESUME = -1; const val COUNT = 10 }`;
  - `interface GameCoreApi` with exactly the nine calls above, under the same names. Its KDoc: every call is made on
    `gameDispatcher`;
  - `object NativeGameCoreApi : GameCoreApi`, which delegates to `NativeLibrary`;
  - `FakeGameCore(calls: MutableList<String>)` for the JVM tests: settable `times`, `screenshots`, `saveError`,
    `loadError` and `analogMode`, and each call logged as `"saveState 3"` and so on.
- Produces (`AppController`): `private val gameDispatcher = Dispatchers.IO.limitedParallelism(1)`. Callers updated
  for the new `startGame` and `shutdownGame` signatures: `leaveGame` passes `saveResumeState` as its caller says,
  and the eject path passes false. Task 6 makes Exit game pass true.
- Produces (`DataLayout`): `kept(path)` is also true for the core's atomic temp files of save states, matching
  `Regex("""^(.*/)?savestates/[^/]+\.sav\.[A-Za-z0-9]{6}$""")`. Match the layout's real savestates folder if it's
  configured elsewhere: check the folder part against `folders`.

- [ ] **Step 1: Write the failing tests.**
  - `DataLayoutTest.theCoresSaveStateTempFilesStayOnTheDevice`: `DEFAULT.mirrored("savestates/SCUS-94900_1.sav")` is
    true; `"savestates/SCUS-94900_1.sav.a1B2c3"` is false, and isn't `stray`. `"memcards/x.mcd.a1B2c3"` is mirrored,
    as before.
  - `GameCallsTest` (instrumented, after `initialize` on a fresh working copy, as `SettingsAccessTest` does):
    - `noSavesReadsAsZeros`: `getSaveStateTimes("SLUS-00000")` has 11 zeros;
    - `aStateFileIsSeen`: write any bytes to `savestates/SLUS-00000_3.sav` and `SLUS-00000_resume.sav` → `[3]` and `[0]`
      are within 5 s of now, and the rest are 0;
    - `aDamagedStateHasNoPicture`: `getSaveStateScreenshot("SLUS-00000", 3)` on those bytes is null, with no crash;
    - `withoutAGameTheCallsSaySo`: `saveState(1)` and `loadState(1)` return "No game is running.", and
      `getAnalogMode()` is -1.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement** the native calls, the JNI functions (`ArgbImage` is Task 2's), `GameCoreApi`, the fake,
  `gameDispatcher` and the `DataLayout` rule. One pixel conversion helper serves Tasks 2 and 3.
- [ ] **Step 4: Run** `GameCallsTest`, `DataLayoutTest`, `./gradlew check` → PASS. Desktop build and
  `common-tests` → green.
- [ ] **Step 5: Commit** (message shown first): `Android: the core calls for save states, reset, fast forward, analog
  mode and screenshots`.

### Task 4: the menu's state and actions (`InGameMenu`)

**Files:**
- Create: `app/InGameMenu.kt`, `app/LastSlots.kt`, `ui/SaveTimes.kt`
- Modify: `storage/FolderPrefs.kt` (`SharedPreferencesStore(context, name: String = "folders")`),
  `app/AppController.kt`
- Test: `test/…/app/InGameMenuTest.kt`, `test/…/app/LastSlotsTest.kt`, `test/…/ui/SaveTimesTest.kt`

**Interfaces:**
- Consumes: `GameCoreApi`, `SaveSlots`, `ArgbImage` (Task 3); `OsdMessages.post` (Task 2).
- Produces:
  - `enum class SlotMode { Save, Load }`
  - `data class SlotEntry(val slot: Int, val savedAt: Long?, val picture: ArgbImage? = null)`: `savedAt` is in Unix
    seconds, null for an empty slot.
  - `data class SlotPanel(val mode: SlotMode, val entries: List<SlotEntry>? = null, val focus: Int? = null,
    val replacing: Int? = null, val busy: Boolean = false, val error: String? = null)`: `entries` is null while
    the times are read.
  - `data class GameMenuState(val booted: Boolean = false, val fastForward: Boolean = false,
    val analogMode: Boolean? = null, val slots: SlotPanel? = null, val confirmingReset: Boolean = false)`:
    `analogMode` null hides the row.
  - `interface GameMenuActions`: `resume()`, `openSlots(mode: SlotMode)`, `closeSlots()`, `pickSlot(slot: Int)`,
    `confirmReplace()`, `cancelReplace()`, `toggleFastForward()`, `toggleAnalogMode()`, `screenshot()`, `askReset()`,
    `confirmReset()`, `cancelReset()`, `exit()`.
  - `class InGameMenu(scope: CoroutineScope, game: CoroutineDispatcher, api: GameCoreApi, lastSlots: LastSlotStore,
    onMessage: (String) -> Unit, onResume: () -> Unit, onExit: () -> Unit) : GameMenuActions`, main thread only.
    It has `var state: GameMenuState` (Compose state, `private set`), and these hooks:
    - `gameStarted(serial: String)`: `booted`, `fastForward` false, and the analog mode read;
    - `gameStopped()`: back to `GameMenuState()`;
    - `opened()`: no panel, no confirmation, and the analog mode read again if booted;
    - `fastForwardChanged(enabled: Boolean)`.
  - `interface LastSlotStore { fun get(serial: String): Int?; fun set(serial: String, slot: Int) }`. It blocks: call
    it on `game`.
  - `class LastSlots(store: KeyValueStore) : LastSlotStore`, with the key `lastSlot.<serial>` in
    `SharedPreferences("game")`.
  - `object SaveTimes { fun label(savedAt: Long, now: ZonedDateTime): String }`: "Today · 14:05",
    "Yesterday · 09:30", else "28 Oct 2026 · 21:17".
- Behaviour (each action does nothing unless `booted` and the serial isn't blank; picks do nothing while `busy`):
  - `openSlots(Save)`: entries are slots 1–10. `focus` is the last slot if it's 1–10, else 1.
  - `openSlots(Load)`: entries are the resume state first (when `times[0] != 0`), then 1–10. `focus` is the last slot if
    it's filled, else the newest filled one, else null.
  - After the times, each filled entry's screenshot is read, one at a time, and set as it arrives. A generation count
    drops results for a panel that was closed or reopened meanwhile.
  - `pickSlot` in Save: a filled slot sets `replacing`; an empty one saves. In Load: a filled one loads, and an empty one
    does nothing.
  - A save or load sets `busy`, then calls `api` on `game`. On success: `lastSlots.set`, `slots = null`, then
    `onMessage("Saved to slot N")`, "Loaded slot N" or "Loaded your resume state", then `onResume()`. On failure:
    `busy = false` and `error` is "Couldn't save to slot N: <reason>", "Couldn't load slot N: <reason>" or
    "Couldn't load your resume state: <reason>". The menu stays open, and nothing is remembered.
  - `toggleFastForward()`: `api.setFastForward(!fastForward)`, sets `fastForward` at once (the callback confirms it),
    then `onResume()`. `toggleAnalogMode()` and `screenshot()` call `api`, then `onResume()`; the core's message says
    what happened.
  - `askReset()` sets `confirmingReset`. `confirmReset()` calls `api.resetGame()`, clears it, then `onResume()`.
- `AppController` gains `val gameMenu: InGameMenu`, with `onMessage = osd::post`, `onResume = ::resumeFromPrompt` and
  `onExit = ::quitFromPrompt`. It calls `gameStarted` in `onGameStarted` (with the new serial), `gameStopped` in
  `onGameStopped`, `opened()` in `openPrompt`, and `fastForwardChanged` from `onFastForwardChanged`.

- [ ] **Step 1: Write the failing tests** (`runTest`, a `StandardTestDispatcher`, `FakeGameCore`, an in-memory
  `LastSlotStore`, and messages and resumes logged to the fake's list), in `InGameMenuTest`:
  - `saveFocusesTheLastSlotElseSlot1`: last 4 → focus 4; none → 1; last `RESUME` → 1;
  - `loadListsTheResumeStateFirstAndFocusesTheNewest`: times `[t0, 0, 0, t3, …]` with t3 > t0 and no last slot →
    entries `[-1, 1, …, 10]`, focus 3;
  - `loadWithNoSavesHasNothingToFocus`: 10 entries, all empty, focus null;
  - `picturesArriveAfterTheList`: entries show with `picture == null` first, then each filled one's picture;
  - `aStateWithoutAPictureStillShowsAsSaved` (Review Focus 4): the screenshot is null, and `savedAt` is still set;
  - `aClosedPanelDropsLateResults`: close before the pictures land → `slots` stays null;
  - `anEmptySlotSavesAtOnce`: `saveState 2`, then the message "Saved to slot 2", then a resume, and last = 2;
  - `aFilledSlotAsksFirst`: `replacing = 3`, and no save. `confirmReplace` saves; `cancelReplace` clears it, with no
    call;
  - `aFailedSaveKeepsTheMenuOpenWithTheReason` (Review Focus 1): `saveError` "Cannot save state while memory card is
    being saved." → `error` "Couldn't save to slot 2: Cannot save state while memory card is being saved.", no resume,
    no message, and last unchanged;
  - `aFailedLoadKeepsTheGameWhereItWas`: the same for a load;
  - `loadingTheResumeStateSaysSo`: "Loaded your resume state";
  - `picksWaitWhileBusy`: two picks before the save returns → one `saveState`;
  - `nothingRunsBeforeTheBoot`: before `gameStarted`, `openSlots` and the toggles make no call;
  - `fastForwardTogglesAndResumes`, `resetAsksThenResets`, `analogModeShowsOnlyForTheDualShock` (`getAnalogMode`
    -1 → null; 1 → true).

  `LastSlotsTest`: round trip, a missing serial gives null, and a garbled value gives null.

  `SaveTimesTest`: today, yesterday, older, across midnight, all in a fixed zone.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement** `InGameMenu`, `LastSlots`, `SaveTimes`, and the `AppController` wiring.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest` → PASS.
- [ ] **Step 5: Commit** (message shown first): `Android: the in-game menu's state, slots and actions`.

### Task 5: the menu and slot panels (`GameMenu`)

**Files:**
- Rename: `ui/GamePrompt.kt` → `ui/GameMenu.kt`, and `androidTest/…/ui/GamePromptTest.kt` →
  `androidTest/…/ui/GameMenuTest.kt` (`git mv`, then edit)
- Create: `ui/SlotPanel.kt`
- Modify: `ui/GameScreen.kt`, `MainActivity.kt`, `androidTest/…/ui/FocusAuditTest.kt`,
  `androidTest/…/ui/OrientationLayoutTest.kt`

**Interfaces:**
- Consumes: `GameMenuState`, `SlotPanel`, `SlotEntry`, `SlotMode`, `GameMenuActions` (Task 4); `KeyHints`,
  `PadGlyphs` (Task 1); `SaveTimes` (Task 4).
- Produces: `@Composable fun GameMenu(state: GameMenuState, actions: GameMenuActions, game: GameEntry? = null,
  now: ZonedDateTime = ZonedDateTime.now())`. `GameScreen` takes `menuOpen`, `menu: GameMenuState` and
  `actions: GameMenuActions` in place of `promptOpen`, `onResume` and `onQuit`.
- Layout:
  - Over a `scrim`, the menu panel: `surface`, `GsShapes.large`, 300 dp wide, 32 dp from the left, top and bottom
    edges, 24 dp padding.
  - In it, `Eyebrow("Paused")`, then the title and `gameDetails`. While `!booted`, the details line is "Starting…".
  - Then the rows, as `MenuItem` has them, with an optional value at the row's end in `bodyLarge`, `inkMuted`:
    1. Resume `play`
    2. Save state `memoryCard`
    3. Load state `memoryCard`
    4. Fast forward `fastForward`, "On"/"Off"
    5. Analog mode `gamepad`, "On"/"Off", only when `analogMode != null`
    6. Screenshot `camera`
    7. a 1 dp `line` divider
    8. Reset console `rewind`
    9. Exit game `power`
  - Rows 2–8 are `enabled = state.booted` (unfocusable when not).
  - `KeyHints(listOf(KeyHint(PadGlyphs.confirm, "Select"), KeyHint(PadGlyphs.back, "Back")))` at the foot.
  - The rows scroll in a column with 4 dp of padding for the ring, and the hints stay at the foot.
- While `state.slots != null`:
  - the menu's Save or Load row is current: `beakSoft` fill, `Modifier.semantics { selected = true }`;
  - `SlotPanel` shows to its right, 16 dp away, filling the rest up to 32 dp from the right, top and bottom edges;
  - under 720 dp of width (`BoxWithConstraints`), the slot panel takes the menu panel's place instead.
- `SlotPanel` (`surface`, `GsShapes.large`, 24 dp padding):
  - the title "Save state" or "Load state" (`headlineSmall`);
  - `Notice(Danger)` with `error`, when set; a line "Saving…" or "Loading…" while `busy`;
  - in Load with every entry empty, the line "No saves yet for this game.";
  - a `LazyColumn` (4 dp content padding) of slot rows, then `KeyHints` ("Save" or "Load" for A, "Back" for B) at the
    foot.
  - A slot row is `focusRing`ed, with the picture at 112 × 84 dp on `sunken`, `GsShapes.small`, `ContentScale.Fit`.
    The picture is `remember(entry.picture) { Bitmap.createBitmap(pixels, w, h, ARGB_8888).asImageBitmap() }`; an
    empty slot, or one still without a picture, shows the `memoryCard` icon in `inkFaint` instead.
  - Beside the picture: the name "Slot N" or "Resume" (`titleMedium`), the status "Saved" or "Empty" (`bodyMedium`,
    `inkMuted`), and the time `SaveTimes.label(savedAt, now)` in `type.mono`, when saved.
  - In Load, empty rows are `enabled = false`. On opening, `entry.focus` takes the focus (scrolled into view), else
    the first focusable row, else the panel itself (a `focusable()` title, so B still reaches it).
- `replacing != null`: in the panel, in place of the list, the text "Replace the save in slot N?" (`headlineSmall`) and
  a `FlowRow` with **Replace** (`Danger`) and **Cancel** (`Ghost`, focused).
- `confirmingReset`: in the menu panel, in place of the rows, "Reset the console? Progress since your last save is
  lost." with **Reset** (`Danger`) and **Cancel** (`Ghost`, focused).
- Keys, carried over from `GamePrompt` (a `down` set: act on a key-up whose key-down this layer saw):
  - B and Back go through `BackHandler`s, innermost first: the confirmation → `cancelReplace`/`cancelReset`; the slot
    panel → `closeSlots()`, then the Save or Load row is focused again; the menu → `resume()`.
  - Mode resumes from any layer.
  - Focus can't leave the slot panel or a confirmation (`focusGroup()` with
    `focusProperties { onExit = { cancelFocusChange() } }`).
- `MainActivity` passes `AppController.gameMenu.state` and `AppController.gameMenu`. `openPrompt`, `promptOpen` and
  `resumeFromPrompt` keep their names. `quitFromPrompt` is `exit()`'s target.

- [ ] **Step 1: Write the failing tests** in `GameMenuTest`, keeping `GamePromptTest`'s (renamed for the new rows), with
  a recording `GameMenuActions` fake and the state in a `mutableStateOf` the fake updates:
  - `rowsAreInOrderAndResumeIsFocused`, and Analog mode is absent with `analogMode = null`;
  - `fastForwardShowsItsValue`;
  - `gameRowsWaitForTheBoot`: `booted = false` → Save state is `assertIsNotEnabled()`, "Starting…" shows, and D-pad down
    from Resume reaches Exit game;
  - `saveStateOpensThePanelOnTheFocusedSlot`: `slots = SlotPanel(Save, entries, focus = 3)` → "Slot 3" focused, and the
    Save state row `assertIsSelected()`;
  - `emptySlotsInLoadAreSkipped`: from the Resume entry, D-pad down lands on the next filled slot;
  - `noSavesSaysSo`;
  - `bStepsOutOneLayerAtATime` (Review Focus 5): `replacing = 3` → B calls `cancelReplace`; then B calls `closeSlots`,
    and "Save state" is focused; then B calls `resume`;
  - `aHeldBDoesNothingOnTheLayerItOpened`: a B key-down sent before the panel shows, then its key-up → no call;
  - `replacingFocusesCancel`, `resetFocusesCancel`, and `anErrorShowsInThePanel`;
  - `focusStaysInTheSlotPanel`: D-pad left from a slot keeps a slot focused;
  - `aNarrowScreenShowsOnePanel`: at 411 × 891 dp with slots open, "Resume" isn't displayed and "Slot 1" is;
  - `theHintsNameAAndB`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement** `GameMenu`, `SlotPanel`, and the `GameScreen` and `MainActivity` wiring.
- [ ] **Step 4: The system's tests.**
  - `FocusAuditTest`: `gameMenu()` (replacing `gamePrompt()`), `gameMenuWhileBooting()`, `gameMenuSaveSlots()`,
    `gameMenuLoadWithNoSaves()`, `gameMenuReplacing()`, `gameMenuResetting()`.
  - `OrientationLayoutTest`: `theGameMenuAndItsSlots`, at every size and text scale: the menu, then Save slots with a
    long game title. Every slot is reached with the D-pad and scrolled into view, and no text is cut.
- [ ] **Step 5: Run** `connectedDebugAndroidTest` and `./gradlew check` → PASS.
- [ ] **Step 6: Check on `goose-arm64`** with padtest. Back opens the menu.
  - Save state → Slot 1 → back in the game, with "Saved to slot 1".
  - Save state again → Slot 1 → "Replace the save in slot 1?" → Replace → resumed.
  - Load state → Slot 1 → "Loaded slot 1".
  - Fast forward → the marker shows and the speed rises (Show speed on); Fast forward again → the marker goes.
  - Screenshot → "Screenshot saved", and a PNG in `files/data/screenshots`.
  - Reset console → Reset → "Console reset".
  - Slot pictures appear after the rows; the menu never stalls (no `Choreographer` "Skipped … frames" over 30 in
    logcat).
- [ ] **Step 7: Commit** (message shown first): `Android: the in-game menu, with save and load state in a slot panel`.

### Task 6: the resume state

**Files:**
- Create: `app/GameStarts.kt`, `ui/ResumePrompt.kt`
- Modify: `app/AppController.kt`, `MainActivity.kt`, `androidTest/…/ui/FocusAuditTest.kt`
- Test: `test/…/app/GameStartsTest.kt`, `androidTest/…/ui/ResumePromptTest.kt`

**Interfaces:**
- Consumes: `GameCoreApi.getSaveStateTimes`, `getSaveStateScreenshot`, `SaveSlots.RESUME`, `ArgbImage` (Task 3);
  `SaveTimes` (Task 4).
- Produces:
  - `data class ResumeOffer(val game: GameEntry, val savedAt: Long, val picture: ArgbImage?)`
  - `class GameStarts(scope: CoroutineScope, game: CoroutineDispatcher, api: GameCoreApi,
    start: (GameEntry, String?) -> Unit)`, main thread only:
    - `var offer: ResumeOffer?` (Compose state, `private set`);
    - `fun play(entry: GameEntry)`: with a blank serial, `start(entry, null)` at once. Otherwise it reads the times on
      `game`: with no resume state, `start(entry, null)`; with one, it reads the picture and sets `offer`. A `play`
      while one is being checked, or while `offer` is set, is ignored.
    - `fun resume()`: `start(game, serial)`; `fun startOver()`: `start(game, null)`; `fun cancel()`. Each clears
      `offer`.
  - `@Composable fun ResumePrompt(offer: ResumeOffer, onResume: () -> Unit, onStartOver: () -> Unit,
    onCancel: () -> Unit, now: ZonedDateTime = ZonedDateTime.now())`, drawn over the library:
    - a `scrim` that swallows taps, and a centred `surface` panel at `GsShapes.large`, at most 420 dp wide;
    - "Resume where you left off?" (`headlineSmall`), then the game's title;
    - the picture at 4:3, 224 × 168 dp on `sunken` (the `memoryCard` icon without one), and the time in `type.mono`;
    - a `FlowRow` of **Resume** (`Primary`, focused) and **Start over** (`Secondary`);
    - B and Back cancel (a `BackHandler`, key-up on a press seen here), and focus can't leave the panel.
- `AppController`:
  - `val gameStarts = GameStarts(scope, gameDispatcher, NativeGameCoreApi, ::startGameNow)`;
  - `startGame(game)` keeps its guards, then calls `gameStarts.play(game)`;
  - the old body becomes `private fun startGameNow(game: GameEntry, resumeSerial: String?)`, ending in
    `NativeLibrary.startGame(game.path, resumeSerial)`;
  - `quitFromPrompt` calls `leaveGame(saveResumeState = true)`, and every other `leaveGame` passes false.
- `MainActivity`: on `Screen.Library`, `Box { LibraryScreen(…); gameStarts.offer?.let { ResumePrompt(…) } }`.

- [ ] **Step 1: Write the failing tests.**
  - `GameStartsTest`:
    - `noResumeStateStartsAtOnce`;
    - `aResumeStateIsOffered` (with its time and picture);
    - `resumeBootsFromIt` (`start(entry, serial)`);
    - `startOverBootsNormally`;
    - `cancelStartsNothing`;
    - `aGameWithoutASerialStartsAtOnce` (Review Focus 3), with no `api` call;
    - `aSecondPlayWhileCheckingIsIgnored`.
  - `ResumePromptTest`:
    - `resumeIsFocused`;
    - `startOverIsOneStepRight`;
    - `bCancels`;
    - `focusStaysInThePrompt`: over a focusable button, every D-pad direction leaves Resume or Start over focused;
    - `itShowsTheTime`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement.** Add `resumePrompt()` to `FocusAuditTest`, and `theResumePromptWithALongTitle` to
  `OrientationLayoutTest`: at every size and text scale the title wraps, nothing is cut, and both buttons are
  reached.
- [ ] **Step 4: Run** the JVM and instrumented suites and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`.**
  - Play padtest (a serial is needed: use a disc image with one, as in `docs/android.md`'s results), then Exit game.
    `files/data/savestates/<serial>_resume.sav` exists.
  - Play it again: the prompt shows with the picture and "Today · …".
    - Resume → the game is where it was left.
    - Exit, Play, Start over → a fresh boot. The resume file is still there.
    - Exit, Play, B → still in the library.
  - Make `savestates` read-only (`adb shell chmod 500 …/savestates`): Exit game → the library says "Couldn't save
    your place: …". Put it back.
  - Corrupt the resume file (truncate it to 100 bytes): Play → Resume → back in the library, with the reason in the
    message bar. The next Play offers it again.
- [ ] **Step 6: Commit** (message shown first): `Android: a resume state on Exit game, and "Resume where you left off?"`.

### Task 7: holding Select back for the chords (`SelectChord`)

**Files:**
- Create: `input/SelectChord.kt`
- Modify: `input/PadKeys.kt`, `input/MenuTrigger.kt`, `input/ControllerInput.kt`, `core/NativeLibrary.kt`,
  `android/app/src/cpp/jni_bridge.cpp`, `android_host.h/.cpp`
- Test: `test/…/input/SelectChordTest.kt`, `test/…/input/PadKeysTest.kt`, `test/…/input/MenuTriggerTest.kt`

**Interfaces:**
- Produces:
  - `MenuTrigger.isHolding(deviceId: Int): Boolean`: a press of the Select + Start pair is held back on that device.
  - `internal class SelectChord(private val holdMs: Long = 500)`. Pure; the caller gives the time.
    - `fun plan(deviceId: Int, presses: List<KeyPress>, chords: Boolean, canceled: Boolean, nowMs: Long): List<KeyPress>`:
      `presses` is what `MenuTrigger` would send for one key event, and the result is what to send in its place, in
      order.
    - `fun deadline(paired: (Int) -> Boolean): Long?`: the soonest hold's end among devices `paired` says false for.
    - `fun onTime(nowMs: Long, paired: (Int) -> Boolean): List<Int>`: the devices whose held Select goes to the game
      now. Their holds end.
    - `fun forget(deviceId: Int)`, `fun reset()` (the held Selects are dropped, never sent).
  - `PadKeys.onKey(…, selectChords: Boolean)`, a new parameter. `PadKeys.Tick` gains `val lateSelects: List<Int>`:
    the devices whose `SELECT` key-down to send now. `PadKeys.onTime` marks each one sent (`router.markSent`), so
    its key-up follows it to the core.
  - `ControllerInput`:
    - `var selectChordDescriptors: Set<String> = emptySet()`, main thread;
    - `onKeyEvent` passes `selectChords = devices[event.deviceId]?.descriptor in selectChordDescriptors`;
    - an `Outcome.send` with more than one press goes through `NativeLibrary.onKeys` in one call;
    - `onTimer` sends each `lateSelects` press.
  - `external fun onKeys(deviceId: Int, keyCodes: IntArray, pressed: BooleanArray)`, backed by
    `AndroidHost::QueueKeyEvents(s32 device_id, std::span<const std::pair<s32, bool>> keys)`. It queues them all under
    one `s_input_mutex` hold, so the CPU thread passes them on in one batch, with no frame between them. It has the same
    device and Back/Mode filtering as `QueueKeyEvent`.
- `SelectChord.plan`, for each press in order, on `deviceId`:
  1. `SELECT` down, with `chords`: hold it (no output), with `deadline = nowMs + holdMs`.
  2. `SELECT` up while held: if `canceled`, drop both (no output). Otherwise emit `SELECT` down, then `SELECT` up.
     `LateRelease` then keeps it down 100 ms, as for Start today.
  3. Another key down while Select is held: emit `SELECT` down, then that key's down, so the core's chord fires and
     cancels both pad presses, in one batch. The hold ends.
  4. Anything else: emit it unchanged.

  `onTime` passes over a device while `paired(deviceId)` (`MenuTrigger.isHolding`) is true: a Select + Start hold in
  progress never lets Select through. It then ends with the menu (`reset`) or with a key event (rules 2 and 3).
- `PadKeys.onKey` runs `MenuTrigger` as now, then `selectChord.plan(…)` on `result.send`, then `LateRelease.plan` on
  that. The existing "pressed and not in the send list → `router.markHeldBack`" check runs on the final list, so a held
  Select's rest goes to Compose if it's dropped. `releaseAll`, a completed menu hold and `forget` also reset or forget
  `SelectChord`.

- [ ] **Step 1: Write the failing tests.**
  - `SelectChordTest`:
    - `withoutChordsSelectGoesAtOnce`;
    - `aTapGoesOnRelease`: down at 0 → `[]`; up at 80 → `[S↓, S↑]`;
    - `aHeldSelectGoesAfter500ms`: `deadline` is 500; `onTime(499)` → `[]`; `onTime(500)` → `[device]`;
    - `aButtonGoesWithSelect`: S↓ at 0, X↓ at 120 → `[S↓, X↓]`; X↑ → `[X↑]`; S↑ → `[S↑]`;
    - `aCancelledSelectIsDropped`: S↓, then a cancelled S↑ → `[]`;
    - `noTimeoutWhilePaired`: `onTime(600, paired = { true })` → `[]`, and `deadline` is null;
    - `devicesAreSeparate`.
  - `PadKeysTest` (with `selectChords = true`):
    - `selectAndStartStillOpenTheMenuAndTheGameNeverSeesSelect`: S↓ at 0, Start↓ at 100 → nothing sent; `onTime(1100)`
      → `openMenu`, nothing sent; the S and Start key-ups go to Compose (`toCore` false);
    - `startTappedWhileSelectHeld`: S↓ 0, Start↓ 100, Start↑ 300 → sent `[S↓, Start↓]`, and Start↑ after 100 ms more;
    - `aChordSendsOneBatch`: S↓, X↓ → one `Outcome` with `send == [S↓, X↓]`;
    - `releaseAllDropsAHeldSelect`: S↓, `releaseAll()` → no S↓ ever sent, and S↑ goes to Compose;
    - `aSelectTapIsHeld100ms`: S↓ 0, S↑ 50 → S↓ now, S↑ in `Tick.releases` at 150.
  - `MenuTriggerTest.isHoldingFollowsThePair`.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement** `SelectChord`, the `PadKeys`/`MenuTrigger`/`ControllerInput` changes, and `onKeys` in
  native code.
- [ ] **Step 4: Run** `./gradlew testDebugUnitTest` and `./gradlew check` → PASS. The descriptor set stays empty until
  Task 8 fills it, so nothing changes in games yet.
- [ ] **Step 5: Commit** (message shown first): `Android: Select held back for its chords, and chord presses sent in
  one batch`.

### Task 8: the Hotkeys section

**Files:**
- Modify: `src/util/android_input_keys.h/.cpp`; test: `src/common-tests/android_input_keys_tests.cpp`
- Modify: `android/app/src/cpp/android_host.h/.cpp`, `jni_bridge.cpp`, `core/NativeLibrary.kt`, `app/CoreApi.kt`,
  `test/…/app/FakeCoreApi.kt`, `app/ControllersPage.kt`, `ui/ControllersScreen.kt`, `app/AppController.kt`
- Test: `test/…/app/ControllersPageTest.kt`, `androidTest/…/ui/ControllersScreenTest.kt`,
  `androidTest/…/core/ControllerBindingsTest.kt`, `androidTest/…/core/BindingCaptureTest.kt`,
  `androidTest/…/ui/OrientationLayoutTest.kt`

**Interfaces:**
- Produces (C++, `AndroidInputKeys`, pure, in common-tests):
  - `std::string SelectChord(u32 device_index, std::string_view button)`: `"Button109 & Android-<i>/<button>"`. This is
    the form `SetDeviceBinding` takes and `GetDeviceBinding` gives back.
  - `BindingCapture(u32 device_index, std::vector<u32> centered_axes = {}, bool select_chord = false)`: with
    `select_chord`, only a button's press is taken, never `KEYCODE_BUTTON_SELECT` or `KEYCODE_BUTTON_START`, and axes
    are passed over.
- Produces (C++, Android host). Each runs on the CPU thread and blocks, as the pad binding calls do:
  - `std::optional<std::vector<PadBinding>> GetHotkeyBindings(std::string descriptor)`: in this order,
    `ToggleFastForward` (`[Hotkeys]`), `Analog` (`[Pad1]`, only if Port 1's type has it), and `Screenshot`
    (`[Hotkeys]`). All are `Button`, with the device's binding via `GetDeviceBinding`.
  - `void SetHotkeyBinding(std::string descriptor, std::string name, std::string button)`:
    - only those three names, else a warning and nothing;
    - an empty `button` clears the device's binding, and any other becomes `SelectChord(index, button)` via
      `SetDeviceBinding`;
    - then it saves and calls `System::ReloadInputBindings()`.
  - `GetPadBindings` leaves out `Analog`; `SetPadBinding` still accepts it.
  - `ResetPadBindings` also drops the device's `[Hotkeys] ToggleFastForward` and `Screenshot` entries.
  - `StartBindingCapture(std::string descriptor, bool select_chord)`.
- Produces (Kotlin):
  - `NativeLibrary`: `getHotkeyBindings(descriptor): Array<PadBinding>?`,
    `setHotkeyBinding(descriptor: String, name: String, button: String?)`, and
    `startBindingCapture(descriptor: String, selectChord: Boolean)`. `CoreApi` and `FakeCoreApi` follow.
  - `ControllersState.hotkeys: List<PadBinding>? = null`, read with the bindings (same `readBindings` generation).
  - `ControllersPage.captureHotkey(name: String)`: as `capture`, but with `selectChord = true`. Its result goes to
    `setHotkeyBinding`. `clear` and `undo` act on whichever list has the name.
  - `ControllersScreen`, after the pad rows and before "Reset to defaults":
    - a "Hotkeys" heading (`headlineSmall`), then the line "Hold Select and press the button you set. They work while
      a game runs.";
    - one `BindingRow` per hotkey, labelled "Fast forward", "Analog mode" or "Screenshot" (by name, in Kotlin), its
      value `BindingLabels.label(binding)` ("Select + X", or "Not set");
    - while one is captured, its prompt is "Hold Select and press a button for <label>… Back cancels (<n>)".
  - `AppController.onGameStarted`: on `gameDispatcher`, the descriptors of `controllers.connected.value` whose
    `getHotkeyBindings` has any binding. Then, on main, `controllers.selectChordDescriptors` gets them.
    `onGameStopped` sets it to `emptySet()`.

- [ ] **Step 1: Write the failing tests.**
  - common-tests:
    - `TEST(AndroidInputKeys, SelectChordRoundTrip)`: `SetDeviceBinding({}, 2, SelectChord(2, "Button99"))` is
      `{"Android-2/Button109 & Android-2/Button99"}`, and `GetDeviceBinding` of that for 2 is
      `"Button109 & Android-2/Button99"`;
    - `TEST(AndroidInputKeys, ChordCaptureSkipsSelectStartAndAxes)`: Select, Start and an axis pushed fully give
      nullopt; then X gives `Button99`.
  - `ControllerBindingsTest`:
    - `aHotkeyIsAChordOnThatDevice`: `setHotkeyBinding(pad, "ToggleFastForward", "Button99")` → settings.ini's
      `[Hotkeys] ToggleFastForward` is `Android-0/Button109 & Android-0/Button99`, and another pad's entry is kept;
    - `hotkeysReadBack`: `getHotkeyBindings` gives the names in order, with that binding;
    - `analogLeavesThePadList`;
    - `resetClearsHotkeys`.
  - `BindingCaptureTest.aChordCaptureTakesTheButtonNotSelect`: `onKey(Select down)`, `onKey(X down)` →
    `onBindingCaptured("Button99")`.
  - `ControllersPageTest`:
    - `aHotkeyCaptureSetsTheHotkey`: the fake logs (in `FakeCoreApi`'s existing format) a capture started with
      `selectChord = true`, then `setHotkeyBinding` with `ToggleFastForward` and `Button99`;
    - `clearAndUndoWorkOnHotkeys`.
  - `ControllersScreenTest`:
    - `theHotkeysSectionFollowsThePad`: "Hotkeys", "Fast forward", "Select + X" shown, and the pad list has no
      "Analog";
    - `aHotkeyCapturePrompts`.
  - `OrientationLayoutTest.controllersWithTwoPadsAndEveryInput` reaches the Hotkeys rows too.
- [ ] **Step 2: Run them to see them fail.**
- [ ] **Step 3: Implement**, as listed.
- [ ] **Step 4: Run** desktop `cmake --build build` and `build/bin/common-tests`, `run-native-tests.sh`, the JVM and
  instrumented suites, and `./gradlew check` → PASS.
- [ ] **Step 5: Check on `goose-arm64`** (a gamepad from `adb shell input` won't do chords: use the emulator's
  connected pad, or leave this to Task 9 on the Thor).
  - Bind Fast forward to Select + X. In padtest, hold Select and press X: the marker shows, and padtest shows neither
    Select nor Square.
  - Tap Select: padtest shows it.
  - Hold Select for a second: padtest shows it held, from about 0.5 s.
  - Select + Start for a second: the menu opens, and padtest never shows Select.
- [ ] **Step 6: Commit** (message shown first): `Android: a Hotkeys section, with fast forward, analog mode and
  screenshot as Select chords` (touches `src/util/android_input_keys`).

### Task 9: docs, and the checks on the emulator and the Thor

**Files:**
- Modify: `docs/android.md`, `docs/design-system.md`, `docs/design-system/screenshots/pause.png`
- Create: `docs/design-system/screenshots/save-slots.png`

- [ ] **Step 1: `docs/android.md`.**
  - "Leaving a game" becomes "The in-game menu": the triggers (unchanged), the rows and what each does, the slot panel,
    the replace question, the resume state and its prompt, and the messages.
  - Under "Controllers": the Hotkeys section, Select chords, Select's 500 ms hold on a pad with a hotkey, and that
    Analog Toggle moved there.
  - Under "Known limits": a pad connected during a game holds Select back only from the next game; no Undo load; no
    global slots.
- [ ] **Step 2: `docs/design-system.md`.**
  - Under "Library and game screens", `GamePrompt` becomes `GameMenu` (the two panels, the current row in `beak-soft`,
    the narrow-screen rule, the confirmations in the panel), with `SlotPanel` and `ResumePrompt`.
  - "Not built yet" loses "save-state slots".
  - Refresh `pause.png` and add `save-slots.png` under "What it looks like", made as the others were (Compose
    rendered off a device with Robolectric's native graphics, at 960 × 540 dp). If that isn't to hand,
    `adb exec-out screencap -p` on `goose-arm64` at 960 × 540 dp, with padtest under the menu.
- [ ] **Step 3: Everything green:** `cmake --build build` and `build/bin/common-tests`, `./gradlew check`,
  `testDebugUnitTest`, `connectedDebugAndroidTest`, `run-native-tests.sh`, and `assembleDebug`.
- [ ] **Step 4: The emulator's full pass**, recorded under "Results on the emulator": Task 5 Step 6, Task 6 Step 5 and
  Task 8 Step 5 in one run. Also check:
  - a save state is mirrored to the data folder whole: its size in the folder equals the working copy's, after a
    push forced by Home during a save;
  - no `.sav.XXXXXX` file reaches the data folder.
- [ ] **Step 5: The Thor** (`adb -s 64dc3c35`), **only after the user says go.** Word the steps with Back,
  Select + Start and the D-pad (no Mode button; the D-pad is a hat). Record the results under "Results on the AYN
  Thor".
  - Every row driven with the pad alone: the slot panel, Replace and Cancel, Reset, the resume prompt.
  - The hotkeys: Select + a face button for each of the three. A game's own Select still works: a tap, and a hold on a
    map screen.
  - Nothing pressed in the menu reaches the game.
  - A held direction or button doesn't stick after Resume.
- [ ] **Step 6: Commit** (message shown first): `Docs: the in-game menu, hotkeys and on-screen messages`.
- [ ] **Step 7: Finish the branch** (`superpowers:finishing-a-development-branch`). Push `claude/android-in-game-menu`
  as `ilyalissoboi` and open the PR to `master`, then request the whole-branch review
  (`superpowers:requesting-code-review`).
