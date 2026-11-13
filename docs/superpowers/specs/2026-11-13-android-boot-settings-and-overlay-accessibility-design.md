# Settings before a boot, sign-in off the queue, and overlays for TalkBack: design

Batch G of the Android code review (`docs/reviews/2026-11-10-android-code-review.md`): the two findings of 1.13 that the
status section still lists as open.

## Why

**The core queue is one line for slow work.** `AppController.coreDispatcher` (`Dispatchers.IO.limitedParallelism(1)`)
runs every settings read and write, library scans, `readCorePaths`, and `AchievementsPage.signIn`, one at a time. Two
things go wrong:
- **A slow sign-in leaves every page on "loading".** `signIn` waits for the server, and every settings read queued
  behind it waits too. The native sign-in itself runs on the core's emulation thread (`AchievementsBridge::SignIn`,
  `EmuThread::RunOnThread(…, true)`), and settings reads and writes don't use that thread. Only the Kotlin queue holds
  them back.
- **A game can boot on old settings.** `startGameNow` and `startGameFromStateNow` call `NativeLibrary.startGame*` at
  once. A settings write queued just before Play may not have run yet. The boot then reads the old values, and the
  write's `applySettings()` lands mid-game. `changeDataFolder` already drains the queue first, with
  `withContext(coreDispatcher) {}`.

**TalkBack can reach the screen under an overlay.**
- `OverlayPanel` (and everything built on it: `Question`, `QuestionPanel`, `ResumePrompt`, What's new, the slot and
  memory card questions) draws a scrim that blocks pointer input only.
- The library's sort list does the same with a tap-catcher.
- A screen reader's swipe moves through the screen underneath and can activate it.
- Nothing announces the overlay as a pane when it opens.

## Goal

- Sign-in doesn't hold up the pages.
- A game boots only after the settings writes asked for before it have landed.
- While an overlay shows, TalkBack reaches only the overlay, and announces it as it opens.

## Decided

- **Sign-in gets its own queue.** `AchievementsPage` runs `api.signIn` and the account read after it on a
  single-thread dispatcher of its own, not on the core queue. Every other achievements call stays on the core queue.
  This is safe because the native sign-in serialises itself on the emulation thread, as now. A sign-in that writes
  `Cheevos/Enabled` does it on the emulation thread under the settings lock, as now.
  - **What this fixes, and what it doesn't** *(my call)*: the pages' reads and writes no longer wait behind a sign-in.
  - **What it can't fix:** anything that needs the emulation thread still waits while the native sign-in holds it.
    That's `applySettings()` after a write, the account and queue reads, and a boot. It's a limit of the native
    side, written into `AchievementsPage`'s KDoc, not changed here.
- **A start waits for the settings writes asked for before it** (your answer), not for the whole core queue. A start
  during a library scan or a cover download doesn't wait for it.
  - **Tracked writes:** every settings write the app queues on the core queue goes through one `SettingsWrites`
    helper. It launches the write on the core queue, as now, and remembers its `Job` until it ends.
  - **Which writes:** the page models (`PageValues`, `SettingsPages`, `GameSettingsPages`, `ControllersPage`,
    `PostProcessingPage`, `AchievementsPage`'s settings, `MemcardSyncPage`) and anything else that writes settings or a
    game's file on that queue. The plan lists them.
  - **A start:** `startGameNow` and `startGameFromStateNow` wait for the writes outstanding at that moment
    (`joinAll`), **before** `beginGame`'s checks and its switch to the game screen. Then they run those checks and
    boot as now. *(My call: waiting first means nothing has changed screen yet, so nothing needs undoing if the start
    is refused; the checks then see the state after the wait.)*
  - **While a start waits:** the library stays as it is, and a second Play or a start from Saves is ignored, through
    a "starting" flag that `beginGame`'s refusals and the boot both clear. The wait is normally milliseconds.
- **Overlays draw into one host at the root** (your answer). An `OverlayHost` sits at the root of the app's content
  in `MainActivity`, above the screens:
  - While it shows anything, the screens under it are hidden from accessibility (`hideFromAccessibility()` on their
    container). The overlay is the only thing TalkBack reaches.
  - **`OverlayPanel`** draws its scrim and panel into the host, through a `CompositionLocal`, as `CyclerRow`'s lists
    use `LocalChoiceListHost`. Its call sites don't change. Without a host, as in a Compose test of a single screen, it
    draws in place, as now.
  - **The panel is a pane:** `paneTitle` set to its title, so TalkBack announces it as it opens. `OverlayPanel` takes
    `title: String`, and every caller passes the line it shows at the top (a question's text, "What's new in …",
    "Resume …?").
  - **The library's sort list** draws into the host too, as a pane titled "Sort by".
  - **The pause menu** doesn't move. It covers the game screen, whose only other content (the touch controls) is
    already hidden from accessibility. Its questions (reset, exit, replace the resume state, and the slot panel's
    switch and delete) are inline `Question`s inside the menu, not overlays. They stay as they are, so TalkBack can
    still reach the menu rows around them. *(My call: it's the pause menu, which this batch leaves alone. It's noted
    as a follow-up.)*
  - **Hosts nest** *(my call)*. A host is provided at the app's root, and another by `GameScreen` around the in-game
    settings. An overlay draws into the nearest one, so the overlays over the in-game settings (`GameQuestionPanel`,
    post-processing's `QuestionPanel`, `ShaderPacksPanel`, `FavouritePanel`) stay inside `GameScreen`'s key handler.
    The Mode key still resumes the game from them, and the nearest host hides just the in-game settings under them.
  - **Focus on open** *(my call)*: an overlay that asked for focus from a `LaunchedEffect` outside its content
    (`ResumePrompt`, `WhatsNewPanel`, `ShaderPacksPanel`) asks from inside it, as `GameChoicePanel` does. The host may
    draw the content a frame later, and a request before then would find nothing to focus.
  - **What doesn't change:** focus, keys, Back, the 160 ms fade, the shadow and the scrim. Focus moves into an overlay
    as it opens and can't leave it, B and Back close it, and the screen underneath keeps its state.

## Tests

- **JVM:**
  - a sign-in held open by a gated fake doesn't hold up a settings read on the core queue (`AchievementsPageTest`, or
    its equivalent);
  - **`SettingsWrites`:** a join waits for the writes outstanding when it's called, not for ones that start later;
  - **a start, with a gated write outstanding,** doesn't reach the boot until the write ends, and a second start
    meanwhile is ignored;
  - **a start during a gated non-write job on the core queue** (a stand-in for a scan) isn't held up.

  `AppController` is an `object` that calls `NativeLibrary` directly, so the start's waiting and checking move into a
  small testable class (`GameStartGate` or the like) that `AppController` calls.
- **Compose (`ui` package), a new `OverlayHostTest`:**
  - with an overlay shown, the screen underneath isn't in the accessibility tree (`onNodeWithText(...)` on the
    unmerged tree finds it hidden), and the overlay is;
  - the overlay's pane title is set;
  - closing it gives the screen back to accessibility;
  - focus and Back behave as now, which the existing `OverlayPanelTest`, `QuestionTest`-style classes,
    `ResumePromptTest` and `LibraryScreenTest` already cover.
- **By hand, with TalkBack on emulator-5580:** open a question over a settings page and swipe through it. Only the
  question's text and buttons are read. Ledger it.
- **The quick tier** on both test emulators, and the CI tasks.

## Constraints

- `docs/design-system.md` is binding. Nothing about how an overlay looks or moves changes.
- `checkNoWindowDialogs` stays green: no `Dialog` or `Popup` windows. The host is in the app's one window.
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.

## Not doing

- Moving any other slow work off the core queue, such as library scans.
- The pause menu into the host, or its inline questions as panes.
- Moving `applySettings()` or the account reads off the emulation thread.
- Any other 1.13 item.

## Left to the builder

- How the host keeps more than one overlay. A stack, newest on top, is the obvious way: a question over a page with
  a list open is rare, but a `ResumePrompt` over a library with its sort list open may not be.
- The name and placement of the sign-in dispatcher.
- The order of commits. A sensible default: sign-in, then the drain, then the host.
