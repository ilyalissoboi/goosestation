# GooseStation roadmap

What to build next, in order of priority, with what each item waits on. Written on 2026-10-24,
after the memory card sync (#2), the rebrand (#4) and the new logo (#5).

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
| 4 | Release process: a version scheme (an annotated `0.1` tag, so builds read `0.1-N` from `git describe`), a release job that publishes by hand only (automatic releases would flood the history while development moves fast), and checking the updater against GooseStation's own releases. See [releasing.md](releasing.md). | M | 1 |
| 5 | CJK fonts: static Regular instances of Noto Sans JP/KR/SC, published in the `runtime-resources` release the app downloads them from (`scripts/make-cjk-fonts.py`). The Google Fonts variable fonts default to Thin, which is all FreeType renders. | S | 4 |
| 6 | Deferred to [Later](#later): registering with RetroAchievements. | S | none |
| 7 | RetroAchievements login token out of settings.ini (upstream P1, security): kept in the system's credential store through emusync, like the sync keys, and moved there from older settings. Builds without emusync (Windows ARM64 until item 10) still keep it in settings.ini. | S | none |
| 8 | macOS signing and notarization, so Gatekeeper does not block downloaded builds (optional; needs an Apple developer account). | M | 4 |

## 2. Memory card sync

| # | Item | Size | After |
|---|---|---|---|
| 9 | A conflict screen. Today the newest version wins and the other stays in emusync's history; let the player choose. | M | 2 |
| 10 | More platforms: emusync runtimes for Windows ARM64 and Linux aarch64 (today Windows x64, Linux x86_64 and macOS). Needs emusync releases for those targets first. | M | 1, emusync |
| 11 | Memory card editor additions and memory card improvements: renaming saves, extracting icons, the game's icon shown while saving (upstream P1). | M | none |
| 12 | Sync settings in Big Picture; only the Qt settings have them now. | M | 14 |

## 3. Platform and dependencies

| # | Item | Size | After |
|---|---|---|---|
| 13 | Updated dependencies: a newer Qt LTS and compilers (upstream P1). Also less exposed to the CI breakage seen with current runners. | M | 1 |
| 14 | On-screen keyboard for text entry with a controller (upstream P1); needed by item 12 and Android. | M | none |
| 15 | SDL3 input: better controller support, sensors, touchpad (upstream P1; also helps Android). | M | 13 |
| 16 | Android frontend, our own and written independently (not DuckStation's closed-source app, not RetroArch). The emusync Android library already exists. | L | 12, 14, 15 |

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
- A Discord application for GooseStation, so rich presence can be turned back on.
- Flathub publishing: needs screenshots, media mirroring and the `flatpak-builder-lint repo` check
  restored.
- The P2 items in [upstream-features.md](upstream-features.md): texture filters, display scaling,
  bezels, save state and game list improvements, cheat management, more controllers, achievement UI
  and the rest.

## Suggested order

1 → 2 → 4 → 5, with 7 and 9 alongside: a first release with sync tested for real. Then
13 → 15 and 14 → 12 → 16 lead to Android. Items 11, 17 and 18 block nothing and can be picked up at
any time.
