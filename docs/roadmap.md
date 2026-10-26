# GooseStation roadmap

What to build next, in order of priority, with what each item waits on. Written on 2026-10-24,
after the memory card sync (#2), the rebrand (#4) and the new logo (#5); last updated on 2026-10-25,
after SDL3 (#19) and the memory card editor additions (#20).

**Size** is a rough effort guess: **S** a day or two, **M** a week or two, **L** longer.
**After** names the items that must be finished first. Items from DuckStation's post-fork feature
list are tagged with their priority there ([upstream-features.md](upstream-features.md)); they are
built clean-room, from behaviour and our own design, never from DuckStation's code after
`a5bf1ba5cee7`.

## 0. Finish what is in flight (done)

| # | Item | Size | After |
|---|---|---|---|
| 1 | ~~Merge #5.~~ Merged; the Windows ARM64 build passes on `master`. | S | CI |
| 2 | ~~A real sync test.~~ Saves sync between Windows and macOS (after #8). Still unchecked: whether cards already open when sync starts sync from their next opening. | S | 1 |
| 3 | ~~Delete the merged branches.~~ Done. | S | 1 |

## 1. First public release

| # | Item | Size | After |
|---|---|---|---|
| 4 | ~~Release process.~~ Done (#9, #10): a version scheme (an annotated `0.1` tag, so builds read `0.1-N` from `git describe`), a release job that publishes by hand only (automatic releases would flood the history while development moves fast), and the updater checked against GooseStation's own releases (0.1-49 → 0.1-61). See [releasing.md](releasing.md). | M | 1 |
| 5 | ~~CJK fonts.~~ Done (#11): static Regular instances of Noto Sans JP/KR/SC, published in the `runtime-resources` release the app downloads them from (`scripts/make-cjk-fonts.py`). The Google Fonts variable fonts default to Thin, which is all FreeType renders. | S | 4 |
| 6 | Deferred to [Later](#later): registering with RetroAchievements. | S | none |
| 7 | ~~RetroAchievements login token out of settings.ini.~~ Done (#12) (upstream P1, security): kept in a separate secrets.ini settings layer, as PCSX2 does, so settings.ini can be shared; moved there from older settings. | S | none |
| 8 | macOS signing and notarization, so Gatekeeper does not block downloaded builds (optional; needs an Apple developer account). | M | 4 |

## 2. Memory card sync

| # | Item | Size | After |
|---|---|---|---|
| 9 | ~~A conflict screen.~~ Done (#13): when a save was changed on two devices, the player chooses which version to keep (Settings > Memory Cards > Resolve...); the card keeps this device's version until then, and the other stays in emusync's history. Big Picture has one too (item 12). | M | 2 |
| 10 | More platforms: emusync runtimes for Windows ARM64 and Linux aarch64 (today Windows x64, Linux x86_64 and macOS). Needs emusync releases for those targets first. | M | 1, emusync |
| 11 | ~~Memory card editor additions.~~ Done (#20, upstream P1): the editor renames saves, both the title (stored as Shift-JIS, typed text made full-width) and the file name the game looks for, with a warning when the new name doesn't follow the usual pattern or when sync is on (other devices see the old save deleted and a new one added). When a game saves, the message shows that save's icon, animated, and its title. Icon extraction was dropped as too narrow; multitap cards are in [Later](#later). | M | none |
| 12 | ~~Sync settings in Big Picture.~~ Done (#18): Settings > Memory Cards has the sync settings Qt has (the switch, storage, access key ID, secret access key and encryption passphrase, masked and never shown back, device name, status and Sync Now) and the conflict screen, one row per save with a choice of version. Secrets can be pasted (item 14). | M | 14 |

## 3. Platform and dependencies

| # | Item | Size | After |
|---|---|---|---|
| 13 | ~~Updated dependencies.~~ Done (#16): Qt 6.12 LTS (macOS 14.4+), SDL2 2.32.10, VS 2026 on the `windows-2025` runner, clang 21 on Linux, Xcode 16.4 on `macos-15` (`macos-14` is deprecated), and the Flatpak on the KDE 6.11 runtime. | M | 1 |
| 14 | ~~Text entry in Big Picture.~~ Done (#17), through the platforms' own keyboards rather than one of ours (upstream's on-screen keyboard is in [Later](#later)). Big Picture tells the host when a text field starts and stops being edited; under Steam (a Steam Deck in game mode, Big Picture mode) that shows Steam's keyboard, with the number pad for number fields, unless the field was opened with a physical keyboard. Ctrl+V (Cmd+V on macOS), and X (Square on PlayStation pads) in the text input dialog, paste the first line of the system clipboard. Still unchecked on a Steam Deck: whether Steam asks for its keyboard for a non-Steam shortcut, and whether the Flatpak opens `steam://` links without asking which app to use. Android answers the same hook with its system keyboard (item 16). | S | none |
| 15 | ~~SDL3.~~ Done (#19): SDL 3.4 replaces SDL2 as a drop-in (SDL2 gets fixes only now; support for new controllers lands in SDL3), for controller input and the SDL audio backend, with the same features, bindings and settings. Not motion sensors or the touchpad, which PS1 games have no use for. Still unchecked, for lack of a controller: hot-plugging, rumble and LEDs, DualShock 4/DualSense over Bluetooth with enhanced mode off and on, and Switch controllers' analog sticks and face-button bindings. | M | 13 |
| 16 | Android frontend, our own and written independently (not DuckStation's closed-source app, not RetroArch). The emusync Android library already exists. Text entry uses the system keyboard, through item 14's hook. Stage 1, Plan 1 (the emulator on Android) is done (#23); Plan 2 (storage, setup and the library: the folder picker, the data folder's mirror, and a cover grid) is in progress. | L | 12, 14 |

## 4. Upstream features (clean room)

| # | Item | Size | After |
|---|---|---|---|
| 17 | Slang shader presets (RetroArch `.slangp`) through librashader, as the sister projects do (upstream P1). | M | none |
| 18 | Game database: rebuilt from our own testing and public sources such as redump.org, never from DuckStation's later non-free data (upstream P1, ongoing). | M | none |
| 19 | Accuracy fixes, with regression tests, as games show problems (upstream P1, ongoing). | ongoing | none |
| 20 | Hardware texture cache with per-texture replacement (upstream P1). | L | 13 helps |

## Later

- Register GooseStation with RetroAchievements (item 6, low priority for now); until then its user agent
  may be treated as an unknown emulator and hardcore mode refused.
- Importing settings, memory cards and save states from an existing DuckStation install.
- Multitap memory cards in the UI (upstream P1, left out of item 11).
- A Discord application for GooseStation, so rich presence can be turned back on.
- An on-screen keyboard of our own in Big Picture (upstream P1). Every platform already has a way
  to type (item 14), so it would only help a desktop driven by a controller alone, outside Steam,
  such as a TV PC.
- Flathub publishing: needs screenshots, media mirroring and the `flatpak-builder-lint repo` check
  restored.
- The P2 items in [upstream-features.md](upstream-features.md): texture filters, display scaling,
  bezels, save state and game list improvements, cheat management, more controllers, achievement UI
  and the rest.

## Suggested order

1 → 2 → 4 → 5, with 7 and 9 alongside: a first release with sync tested for real. Then
12 and 14 lead to Android (16); 14 comes before 12, and gives Android's Big Picture screens the
system keyboard. With 12, 14 and 15 done, Android (16) is next. Items 17 and 18 block nothing and
can be picked up at any time.
