<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Patches

Patches are codes that change how a game looks or runs, not how it plays: **widescreen** (16:9, and 16:10 or 21:9
where a code is written for them), **frame rate** (60 fps and frame-rate uncaps) and **display mode** (NTSC mode on a
PAL disc, black bars removed). Everything else (infinite health, items, debug menus) is a cheat. GooseStation keeps
the two apart: patches have their own list for each game, always there, and cheats keep theirs.

## Using patches

### Where they are

- **Qt:** a game's properties have a **Patches** page, for a game that's running or not; while a game runs,
  **System › Patches…** opens it.
- **Big Picture:** the pause menu has **Patches**, and a game's settings have a Patches page.
- **Android:** the in-game menu's **Patches & cheats** opens a panel with two tabs, Patches first (L1 and R1 switch).

Patches don't need desktop's "Enable Cheats", which still gates cheats only. **In hardcore mode neither patches nor
cheats run**, and "Disable all enhancements" turns patches off too (it turns off the overclock frame-rate patches need).

### What a patch does when it's on

A patch sets what it needs **for the running game only, while it's on**: a widescreen patch sets the display's aspect
ratio (16:9, or the ratio its code is for) and turns the emulator's own widescreen rendering off, so the picture isn't
widened twice; a frame-rate patch runs the CPU overclock it needs (one measured on the library, or the one its code's
name gives), never lower than an overclock you set yourself. None of this is written into your settings: switch the
patch off, or end the game, and every setting is as it was. If you change one of those settings by hand while the
patch is on, yours wins until the patch is off. A frame-rate patch with no known overclock says it may need one.

### Switching one off

A patch whose code puts back what it wrote (it writes only with `A7`, "write if equal, with restore") switches off at
once. Most widescreen codes don't, so switching one of those off while the game runs asks: **Reset now**, or **at the
next start** (a reset, a disc change, the next boot). Until then the patch keeps running, with its 16:9 and
overclock, so the picture never comes out squashed. Switching a patch on is always at once.

### Choices

Where a game has alternatives for the same thing (the database's `Select Widescreen Type` groups, a game's several
frame-rate codes, or widescreen variants such as Vert-, ALT or a Rev 1 version), they show as one patch with a choice:
Off or one of them, never two at once. Cheats in `Select …` groups (levels, items) are left as they were.

### Patches you add

A player's file in the cheats folder can hold patches too: `cheats/<game title>.cht` (and on Android also
`cheats/<serial>.cht`). A code there is a patch when it's one the bundled database's marks name, when the newer
community format's patch-only keys mark it (below), or when its `.cht` entry has a `#patch` line. Qt's Patches page
**Import…** adds a file's codes to the game's `.cht`, each as a patch; the cheat manager's Import reads the newer
format too.

A code from the newer format can have **values**: a list of named options, or a range. The Patches page (and, for a
cheat, the cheat manager, Big Picture's Cheat List and Android's Cheats tab) shows the value to pick; the value goes into
the code's `?` digits.

## Reference

### The bundled marks

The bundled cheat database, `data/resources/chtdb.txt`, is shipped as it is. Beside it, `chtdb_patches.txt` names its
patch codes, one line per code and serial:

```
SLUS-01224<TAB>Widescreen 16-10<TAB>widescreen 16:10
SLUS-00255<TAB>Uncap Game at 60 FPS (160 Overclock for stable 60 FPS)<TAB>framerate overclock=160 choice=Frame rate
SCPS-10047<TAB>Select Widescreen Type\Widescreen Vert-<TAB>widescreen 16:9 choice=Select Widescreen Type
SCUS-94228<TAB>PAL2NTSC<TAB>displaymode
```

The code is named as the database has it (`Group\Name`, or the name alone). The kind is `widescreen` (with its ratio,
16:9 if none), `framerate` (with `overclock=<percent>` when one is known) or `displaymode`; `choice=<title>` joins a
game's codes of which only one can be on. The file is written by `scripts/generate_patch_marks.py`, which picks codes
by their names and holds every hand call: the names that look like patches but are cheats, the overclocks measured on
the library, and which codes are alternatives. Don't edit the file by hand: change the script and run it again.

### Where on/off is kept

- **Desktop:** in the game's own settings, `gamesettings/<serial>.ini`, section `[Patches]`: `On` and `Off` lists of
  code names (only where a code differs from its default, which is its `.cht` marker, `[*Name]`) and a `Value` list of
  `name<TAB>value`. When nothing is switched from its default, the section goes, and the file too if it's left empty.
  Never in the player's `.cht` files, nor the global settings.
- **Android:** in the app's file `cheats/<key>.patchcodes`, as for cheats, with an optional fourth field on each line
  for the value: `on|off<TAB>group<TAB>name[<TAB>value]`.

A code that was already on before it became a patch (from a `.cht` marker, or Android's file) stays on as a patch.

### The newer community format

GooseStation reads the newer community cheat format from its public description: a file of entries, each `[Name]`
(`[Group\Name]` to group it), then `Key = Value` lines, then the code's lines. `;` and `#` start comments.

| Key | Read as |
|---|---|
| `Type` | `Gameshark` (the default) or `Assembly`; assembly codes are listed but can't be switched on |
| `Activation` | `Manual`, `EndFrame` (the default), or `StartFrame`, applied at the end of each frame |
| `Author`, `Description` | shown with the code |
| `Option = Name:Value` | one of a list of values (repeat it); `0x` hex and `0b` binary values too |
| `OptionRange = min:max` | a number between them |
| `Ignore = true` | the entry is left out |
| `OverrideAspectRatio`, `OverrideCPUOverclock`, `DisableWidescreenRendering` | patch-only: they make the entry a patch, with those needs |
| other patch-only keys (`OverrideRAMSize`, `ForcePGXPPreserveProjectionPrecision`, …) | make the entry a patch; kept and ignored, with a note |
| anything else | kept and ignored, with a note |

A value goes into the code's `?` digits, shifted to where they are: `80001234 00??` with 7 writes `0007`, and
`80001234 ??00` with 5 writes `0500`. The `?` digits are in the value only, in one run per line; a value too wide for
them, outside its range or not among its options is refused, and a code whose `?` can't take a value is listed and
can't be switched on, with the reason. In our own `.cht` files these keys are kept as `#author=`, `#details=`,
`#option=`, `#optionrange=`, `#optionvalue=`, `#override=` and `#patch` lines, so a file survives Qt's editor and
export.
