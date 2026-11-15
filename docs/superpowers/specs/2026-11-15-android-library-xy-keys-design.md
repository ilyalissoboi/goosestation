# X and Y away from a game, on the library: design

A bug found while checking the `Navigator` refactor on the AYN Thor: on the library, Y on a toolbar button sent the
whole app to the background (`ActivityTaskManager: moveTaskToBack`, no crash).

## Why

**A gamepad key that the app leaves unhandled comes back as a different key.** When the window doesn't consume a key,
Android's input dispatcher sends the *fallback* key that the device's key character map gives for it. The library
uses X and Y only on a game tile (`gameKeys`, X for Saves and Y for Game settings). Anywhere else, Compose returns
false and the fallback runs:

| Where the key comes from | Map | X becomes | Y becomes |
|---|---|---|---|
| `adb shell input keyevent` (the virtual keyboard) | `Virtual.kcm` | `DPAD_CENTER`: presses the focused button | `BACK`: nothing on the library takes it, so the activity goes to the background |
| The Thor's own pad ("Odin Controller") | the Thor's `Generic.kcm` | `DEL` | `SPACE`: Compose 1.12's `clickable` takes Space as a click, so it presses the focused button |

So with `adb`, Y leaves the app and X presses the button. On the real pad, Y presses the button and X sends a Delete.
Neither is what the button hints say: away from a tile, the foot shows no X or Y at all.

**A game without a key (a PSF) has the same hole.** `gameKeys` returns false for Y when the game has no settings,
though its KDoc says Y "does nothing here", so Y falls back there too.

The other buttons were checked against both maps and behave as intended: A falls back to `DPAD_CENTER`, which is how A
presses anything at all (Compose doesn't take `BUTTON_A` itself). B falls back to `BACK`, which leaves the app from
the library when there's no search or keyboard to close, as `bWithoutASearchIsLeftAlone` pins. Select is already the
library's. Start and the stick clicks fall back to `DPAD_CENTER` and act as A. Mode falls back to `HOME`. L1, R1, L2
and R2 have no fallback.

## Decided

- **X and Y only** (your answer). On the library screen they do nothing anywhere except on a game tile, which keeps
  Saves and Game settings. A, B, Select, Start, the stick clicks and Mode stay as they are.
- **Both key-down and key-up are consumed**, as `gameKeys` does: the dispatcher starts a fallback from an unhandled
  key-down.
- **One place:** the library's own `onKeyEvent` on its column, which already takes Select, B and Back from anything in
  the screen. It runs after the focused element's handlers, so a tile's `gameKeys` still gets X and Y first. A game
  without a key passes Y up to it, and it consumes it.

## Tests

In `LibraryScreenTest`, whose outer `Box` records the keys the screen leaves to the activity (`outerKeys`):

- X and Y on a toolbar button (Settings) reach nothing outside the screen, open nothing, and leave the focus where it
  was.
- The same on the search field. The search stays empty.
- The same on Clear search. The search stays as typed.
- Y on a game without a key (`aBlankKeyHasNoGameSettings`) reaches nothing outside the screen either.
- The existing tile tests (X opens Saves, Y opens Game settings) and B's tests pass unchanged.

On the Thor, over `adb`: Y and X on the toolbar leave the app in front, with the focus where it was.

## Not doing

- **The overlays over the library:** the sort list, the long press's choice and What's new. They are shared components
  (`ChoiceList`, `OverlayPanel`) drawn by the app's overlay host, so they belong with the rest of the app.
- **The other screens** (settings, Saves, the memory card editor). Y falls back there too. On those screens, `BACK`
  goes back a page and `SPACE` presses the focused row. That is a follow-up of its own, probably in `MainActivity`.
- Start, the stick clicks, and the first-Down-after-Settings report. That report didn't reproduce on the Thor in
  three tries, and it has a different cause: `DPAD_DOWN` has no fallback.

## Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`, nor from its closed Android app.
- ktlint, and the repo's KDoc style.
