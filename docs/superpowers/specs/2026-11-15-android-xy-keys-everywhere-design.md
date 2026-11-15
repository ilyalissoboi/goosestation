# X and Y away from a game, everywhere: design

The follow-up to [the library's X and Y](2026-11-15-android-library-xy-keys-design.md) (PR #116), which fixed the
library screen only.

## Why

**Away from the library, X and Y still fall through to Android's fallback keys.** A gamepad key that the window leaves
unhandled comes back as the key its device's key character map gives for it: from `adb shell input keyevent`
(`Virtual.kcm`) Y becomes `BACK` and X `DPAD_CENTER`; on the AYN Thor's own pad ("Odin Controller", its `Generic.kcm`)
Y becomes `SPACE` and X `DEL`. Compose 1.12's `clickable` takes `SPACE` as a click.

Only two places in the app use X or Y: a game's tile on the library (X for Saves, Y for Game settings) and a save's row
in `SlotPanel` (X deletes). Everywhere else they fall through:

- the settings pages and their categories, Saves, the memory card editor, the setup screen;
- every overlay: `OverlayPanel`, `ChoiceList` (the sort list, the long press's choice), What's new, the resume prompt;
- the in-game menu, while it's open (the pads are Compose's then, not the core's).

There, Y over `adb` goes back a page (or leaves the app where nothing takes `BACK`), and X presses the focused row. On
the real pad, Y presses the focused row and X sends a Delete.

## Decided

- **App-wide, in `MainActivity.dispatchKeyEvent`** (your answer). After `super.dispatchKeyEvent` returns false, the
  activity takes X and Y, key-down and key-up, while the pads aren't the core's (`padInput` is false: away from a game,
  with the in-game menu open, outside a capture). The dispatcher starts a fallback only from a key-down that comes
  back unhandled, so this ends it.
- **Screens that use X or Y keep them.** Compose sees every key first: the activity only takes what came back
  unhandled, so the library's tile and `SlotPanel`'s X still work, as do any later ones.
- **Only X and Y.** A's `DPAD_CENTER` fallback is how A presses anything at all, and B's `BACK` is how B goes back (and
  leaves the app from the library, as intended). Select, Start, the stick clicks and Mode stay as they are.
- **Any source.** `KEYCODE_BUTTON_X` and `KEYCODE_BUTTON_Y` come only from pads, or from `adb`, whose events are the
  virtual keyboard's.
- **The rule is pure** (`LeftoverKeys`, in `input/`), so a JVM test pins it; the activity only asks it.
- **The library keeps its own catch** from PR #116. It's now covered twice, but its `outerKeys` tests pin that the
  screen itself keeps X and Y off the activity.

## Tests

`LeftoverKeysTest` (JVM):

- X and Y are taken away from the pads.
- With `padInput`, nothing is taken: in a game they're the core's, and a capture takes them as a binding.
- A, B, Select, Start, L1, R1, L2, R2, the stick clicks, Mode, the D-pad, `BACK`, `DPAD_CENTER`, `SPACE` and `DEL` are
  never taken.

The screens that use X and Y keep their own tests: `LibraryScreenTest`'s tile tests (X opens Saves, Y opens Game
settings) and `SlotPanel`'s X to delete. No Compose test reaches the activity's `dispatchKeyEvent`, so the wiring is a
device check.

On the Thor, over `adb` and on its own pad: X and Y on a settings page, on Saves (away from a save), in the memory card
editor, on the setup screen, in the sort list and in What's new leave the screen and the focus as they were; X on a
save in Saves still offers to delete it; in the in-game menu, they do nothing; in a game, X and Y still reach the game.

## Not doing

- Start, the stick clicks and Mode: their fallbacks act as A or Home, as intended.
- Keys other than X and Y with no use on a screen (L1, R1, L2, R2): they have no fallback.

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- ktlint, and the repo's KDoc style.
