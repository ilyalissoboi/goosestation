# CoreEvents: the core's callbacks, out of AppController

Batch N of the Android code review's clean-ups (`docs/reviews/2026-11-10-android-code-review.md`, finding 3.1). It is
the last step of `AppController`'s split, after `DataSync` (#89), `GameSession` (#113), `FolderSettings` (#114) and
`Navigator` (#115).

## Why

`AppController` is 1,591 lines, and it is still the core's `NativeCallbacks`: 24 overrides, about 200 lines, at its
end. The core calls them on its own threads. `NativeCallbacks`' contract is that each one either posts to the main
thread and never waits for it, or, for the few the core needs an answer from, finishes on the core's thread before it
returns. Today that rule is spread across 24 bodies, and nothing checks it. One that touched the UI's state off the
main thread, or waited for the main thread while it waits for the core in `surfaceChanged`, would be a race or a
deadlock.

Two decisions sit in those bodies too, and no test runs them:

- **What an event becomes on screen:** an on-screen message removed or translated, an unknown hotkey dropped, an
  achievement notice shown as a card or as a message with its picture.
- **RetroAchievements' messages over the game:** "signed out" said once at the next game's start, and "couldn't reach
  RetroAchievements" or "offline" said once per game. Their two flags (`signInExpired`, `unreachableSaid`) are set
  from the callbacks and read by the game's start in `SessionHooks`.

The goal is that **which thread each event runs on, and these decisions, can be tested on the JVM**. With it,
`AppController` stops being the core's callbacks, and its class doc can say truthfully which thread touches what.

## Goals

- **A `CoreEvents` class** in `app/`, implementing `NativeCallbacks`: it posts each event to the main thread, or runs
  it on the core's thread, as today, and passes it on to the pieces through a narrow interface.
- **An `AchievementMessages` class** in `app/`, owning the two flags and the messages they decide.
- **JVM tests that pin today's behaviour**: `CoreEventsTest` and `AchievementMessagesTest`, with the cases below.
- **`AppController` no longer implements `NativeCallbacks`.** It registers a `CoreEvents`, and keeps no copy of the
  moved state.

## Non-goals

- **No behaviour change.** This is a pure move. A bug or a risk found on the way is noted, then fixed in its own commit
  after the move, or left for a later PR.
- **No change to `NativeCallbacks`, `NativeLibrary`, or the JNI side.** The core keeps calling the same 24 methods.
- **No change to `MainActivity`, any screen, or the pieces the events reach** (`GameSession`, `OsdMessages`,
  `AchievementOverlay`, the game menu, the controllers page and the rest).
- **`readSelectChords` stays in `AppController`.** The game's start calls it through `SessionHooks`; no callback does.

## Anti-goals

These would count as failure even if the app still works:

- **An event that changes thread.** One posted today must still be posted, and one run on the core's thread today must
  still finish there before the call returns: the login token and the sync secrets, which the core reads right after;
  the binding capture, the rumble and the sound, which hand off on their own.
- **A `CoreEvents` that reaches back into `AppController`**, or holds the pieces themselves.
- **Tests that only check the fakes were called.** Each test pins a decision: whether an event waited for the main
  thread, what the message says, or whether it was said at all.

## What moves

From `AppController.kt`, as of `origin/master` `9ed3bb188`.

**To `CoreEvents`:** all 24 `NativeCallbacks` overrides, with the decisions in them:

- **Posted to the main thread** (17): the game's start and stop, an error, an on-screen message, memory card sync's
  change, the sign-in's expiry, the queue's sent and refused counts, the achievements' change, a notice, a toast, an
  indicator, a leaderboard page, a downloaded badge, fast forward, Port 1's pad kind, and a save state hotkey;
- **On the core's thread** (7): the login token, the three sync secret calls, a captured binding, the rumble and a
  sound; and the hotkey's name is looked up there, before it posts;
- **the decisions:** an on-screen message with no time left removes its key, any other is translated
  (`CoreMessages`) or dropped; an unknown hotkey name is dropped before anything is posted; a notice is a card or a
  message (`AchievementNotices`), and a message's picture is decoded off the main thread, at 64 px.

**To `AchievementMessages`:** `signInExpired`, `unreachableSaid` and `UNREACHABLE`, with:

- the expiry's message at the next game's start, with what the queue keeps (from `SessionHooks.onStarted`);
- the reset of "unreachable said" as a game starts;
- on the achievements' change during a game: the game's status read off the main thread, then "couldn't reach" or
  "offline" said once, and not after the game has stopped.

## How it's shaped

These are my calls as the designer. A builder may adjust names, but not the shape.

- **`CoreEvents` constructor:**
  - `post`: how a block gets to the main thread (`mainHandler::post`). A test queues the blocks and runs them when it
    chooses, so it can see what ran before the call returned;
  - a `CoreEventTargets`;
  - the scope and the IO dispatcher, for a notice's picture.
- **`CoreEventTargets`:** what each event reaches. Main-thread methods and core-thread methods are kept apart in two
  interfaces, `MainEvents` and `CoreThreadEvents`, so a method's thread is in its type. *(My call: one interface with
  a comment per method would let a later change call a main-thread target from the core's thread without anything
  showing it.)*
- **`AchievementMessages` constructor:** the scope, the dispatcher the game's achievements are read on, a reader for
  the two core calls it makes (the queue's own count, the game's achievements), whether a game is running, and how a
  message is said (`osd.post`).
- **`AppController`** builds both, implements the two target interfaces over its pieces, and calls
  `NativeLibrary.setCallbacks(coreEvents)` where it called it with itself.
- **Threads.** Each class's KDoc says which of its functions run on which thread, and `AppController`'s class doc is
  rewritten to match.

## Tests to write (JVM)

**`CoreEventsTest`**, with a fake poster that queues blocks, and fake targets writing to one ordered log tagged with
whether the queue had been run:

1. **Every posted event** reaches nothing until the main thread runs, then reaches its target with its arguments.
2. **Every core-thread event** reaches its target before the call returns, and the secrets' answers come back.
3. **An on-screen message** with no time left removes its key; one the translation drops shows nothing; any other
   shows translated, with its key (none for an empty key), time and icon.
4. **A hotkey** with an unknown name posts nothing; a known one reaches the menu as its `StateHotkey`.
5. **A notice** that is a card goes to the overlay; one that is a message shows with its picture, decoded off the main
   thread, and without one when it has no path.
6. **The game's start and stop, and an error,** reach their targets in the order the core sent them.

**`AchievementMessagesTest`**, with a fake reader and a test scope:

1. **The sign-in's expiry** is said once, at the next game's start, with what the queue keeps, and not again.
2. **"Couldn't reach"** is said once per game, as the achievements change; **"offline"** likewise for a game started
   offline; neither when ready and online.
3. **Neither is said** without a game running, or when the game stops while its status is being read.
4. **A new game** may say it again.

## Constraints

- Kotlin, in `app/`, beside `AppController`, with the project's ktlint rules and KDoc style. Comments say why, and the
  ones in the moved code move with it.
- **Every check stays green:**
  - `assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck checkNoWindowDialogs`, with warnings as
    errors;
  - the quick tier on the test emulators, which runs the core's callbacks for real.
- **On a device.** On the AYN Thor's debug build, with your OK: a game started, its menu opened and closed, a save
  state hotkey, fast forward, and Exit game. The core's messages show over the game as before, and nothing crashes.
  None of these changes a setting; a hotkey that saves would write to a slot, so I'd use one that only picks a slot.
- **Commits:** the two classes, their interfaces and their tests first; then `AppController` moved onto them; then
  any follow-up fix; then the review's status section, which marks 3.1 done.

## Left to the builder

- File layout: `CoreEvents.kt` and `AchievementMessages.kt`, with the target interfaces beside `CoreEvents`.
- How the fakes are built, and the tests' names.

## What this ends

3.1 is done with this step: `AppController` is the wiring, the lifecycle and the cold start, with the pieces' hooks.
It should come out of this at about 1,450 lines. What remains open in the review is 3.2 (the sealed `SidePanel`),
3.5 (the controllers screens' parameters) and four rows of 4.2.
