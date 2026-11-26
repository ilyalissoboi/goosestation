<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# PGXP improvements — design

Roadmap item 26 (PGXP improvements, from the upstream gaps players notice most, `docs/upstream-features.md` tier 6).
Agreed in conversation on 2026-11-27. Calls made while writing this up, not yet confirmed, are marked *(writer's
call)*; calls the builder may make are marked *(builder's call)*.

## Intent

The player's picture: **wobbling geometry in 3D games** (polygons jittering, textures warping, seams opening),
especially upscaled *(the player's choice)*. PGXP removes most of it, for players who turn it on. This **fills the gaps
in PGXP that matter**: unaligned loads and stores, and lines, and fixes the bugs found around it, on desktop and Android
*(the player's choice)*. **PGXP stays off by default** *(the player's choice, 2026-11-27: turning it on by default was
considered and dropped)*. The depth buffer's rework goes to the roadmap's Later list *(the player's choice)*.

## What exists

From a map of the code (2026-11-27; line numbers as of `d342be57f`):

- **Settings** (`settings.h:134-142, 187-188, 311-313`; `settings.cpp:242-252, 563-573`), the same on every platform:
  `[GPU] PGXPEnable` false; `PGXPCulling` true; `PGXPTextureCorrection` true; `PGXPColorCorrection` false;
  `PGXPVertexCache` false; `PGXPCPU` false; `PGXPPreserveProjFP` false; `PGXPTolerance` -1; `PGXPDepthBuffer` false;
  `PGXPDisableOn2DPolygons` false; `PGXPDepthClearThreshold` 300. The software renderer and safe mode force PGXP off;
  PGXP off clears every sub-option (`settings.cpp:777, 798-821`). **No reason is recorded for it being off**; the help
  texts say "may not be compatible with all games", the depth buffer "low compatibility", CPU mode "high performance
  cost". Rows on Qt (`graphicssettingswidget.cpp:83-84, 171-197, 413-419, 507-539, 1029-1044`), Big Picture
  (`fullscreen_ui.cpp:4763-4771, 5000-5041`) and Android (`GraphicsSettings.kt:50-67`, `GraphicsScreen.kt:158-186`);
  hotkeys toggle PGXP, its depth and CPU mode (`hotkeys.cpp:434-566`).
- **Bugs found:** Big Picture's Vertex Cache row passes `pgxp_enabled` as its default rather than as "enabled"
  (`fullscreen_ui.cpp:5025`); its Depth Clear Threshold row writes `PGXPDepthBuffer` (5041; Android notes it,
  `GraphicsSettings.kt:58, 62`); the game database's `DisablePGXPOn2DPolygons` handler sets `g_settings` instead of
  the settings it's building (`game_database.cpp:705`); the vertex cache's lookup bound is `sy <= 1013` against 1023
  (`cpu_pgxp.cpp:582`), possibly a typo.
- **How it works:** a `PGXPValue` (float x, y, z, the native value, flags; `cpu_core.h:53-71`) shadows each aligned
  word of RAM and scratchpad (about 42 MB, `cpu_pgxp.cpp:36, 129-154, 261-277`), invalidated when the native value no
  longer matches. RTPS and RTPT record precise vertices (`gte.cpp:740-807`, `cpu_pgxp.cpp:456-467`), NCLIP uses them
  (`gte.cpp:852-857`), MTC2 to SXYP pushes the FIFO. Aligned LW/SW/LWC2/SWC2 copy values, LH/LHU/SH half-words, bytes
  invalidate (`cpu_pgxp.cpp:289-375, 548-568, 649-695`). The GPU looks each polygon vertex up by its source address
  (`gpu_hw_frontend.cpp:423-476`, `GetPreciseVertex`, `cpu_pgxp.cpp:604-647`) and falls back to integers when the
  value is invalid, mismatched or out of tolerance.
- **Gaps:**
  - **Unaligned loads and stores (LWL, LWR, SWL, SWR) throw precision away**: the interpreter passes the unaligned
    address and merged value (`cpu_core.cpp:1600-1601, 1687-1688`), the check fails and the memory entry's flags are
    cleared; the recompilers do the same (`cpu_recompiler_code_generator.cpp` ~1809-1811; NewRec's
    `// PGXP TODO: LWL etc, MFC0`, `cpu_newrec_compiler_x64.cpp:29-30`, and its lwx/swx paths, 1616-1625, 1801-1816).
  - **Lines** (`DispatchLine`, `gpu_hw_frontend.cpp` 584+) and **rectangles** (`DispatchRectangle`, 520+) are integer
    only.
  - In CPU mode, MVMVA, GPF/GPL and AVSZ aren't tracked, and NewRec has "TODO: PGXP for jalr"
    (`cpu_newrec_compiler.cpp:1196`); CPU mode hooks every ALU op (`cpu_core.cpp:996-1459`), floats with double
    intermediates (`cpu_pgxp.cpp:901-1200`).
- **The depth buffer** (linear `SZ/32768`, `gpu_hw_shadergen.cpp:111-130`; D32F or ROV; clears on an average-depth jump,
  `gpu_hw.cpp:2381-2398`; 2D, lines and sprites never written) is off by default and stays so.
- **The game database** (`game_database.cpp:607-706`, applied only with `Main/ApplyCompatibilitySettings` on,
  `system.cpp:1322`): `ForcePGXPCPUMode` 176 entries (Spyro, Metal Gear Solid, Croc, Grandia, Persona 2, Resident
  Evil 3, Breath of Fire III, Xenogears: "fixes jitter in character models", "improves wall texture wobble");
  `DisablePGXP` 30 (Doom, Final Doom, Hexen, Duke Nukem, Dark Forces: "2.5D, not beneficial, breaks rendering");
  `ForcePGXPVertexCache` 9; `DisablePGXPPreserveProjFP` 7 (Crash Team Racing); `DisablePGXPOn2DPolygons` 6 (Xenogears,
  "fixes misaligned text"); `DisablePGXPCulling` 5; `DisablePGXPColorCorrection` 3. On Android a game forced into CPU
  mode shows "increases system requirements… if too slow, disable PGXP" (673-677).
- **Measured, 2026-11-27** (Metal, 4×, regtest at `b180797ce`, scratch only): Vagrant Story from a saved state, PGXP
  on, draws Ashley's limbs and the railings cleaner and straighter than off; the depth buffer made no visible difference
  there. **Crash Bandicoot's title reached a different moment of its animation at the same frame number with PGXP on**:
  PGXP changes the emulation's timing there, cause not yet known.

## Goals

- **PGXP stays off by default**, with its sub-options' defaults and the game database's PGXP traits as today *(the
  player's choice)*. Everything below is for players who turn it on.
- **Unaligned loads and stores keep precision** *(the player's choice)*: LWL/LWR/SWL/SWR carry the precise values of
  the bytes they move, in the interpreter and both recompilers, instead of invalidating them.
- **Lines get PGXP precision** *(the player's choice)* where their endpoints came from precise vertices.
- **The bugs above are fixed** *(writer's call)*: Big Picture's Vertex Cache and Depth Clear Threshold rows, the
  database's `DisablePGXPOn2DPolygons` handler, and the vertex cache's bound if it's a typo.
- **Timing and precise culling** *(the player's choice, 2026-11-27)*: PGXP's precise NCLIP gives the game itself
  slightly different values when it decides which polygons face the camera (`gte.cpp:852-857`), which very likely
  explains Crash's timing difference; the builder confirms the cause. **That is accepted**: the game sees more accurate
  geometry, which is PGXP's point. It passes if games play correctly (no hangs, no wrong events, save states loading
  and playing on); frame-for-frame identical progress against PGXP off isn't required. Any other cause of a timing
  difference is fixed.
- **Re-running frames is consistent** *(writer's call)*: PGXP's shadow of memory isn't part of a memory state, so
  rewind and runahead can re-run frames from stale precise values (a full state load resets it, `system.cpp:2619-2622`).
  With PGXP on, re-running frames from a memory state gives the same result as the first run *(builder's call how)*.
- **The database** *(writer's call)*: a library game that testing finds broken with PGXP on gets the matching database
  trait, from our own testing only (as item 18's guide says); games outside the library aren't audited.

## Success

*(The player's choice: steadier 3D with PGXP on, nothing broken, games playing correctly; the measures are the writer's
calls.)*

- **Steadier 3D**: on the scenes where unaligned access or lines apply (found by the builder in the library's 3D games),
  with PGXP on, less frame-to-frame movement of static geometry and fewer seams than PGXP on today, at 1× and 4×
  *(builder's call how it's measured, for example the mean change between consecutive frames over a still camera)*.
- **Nothing broken**: with PGXP off, every regression-test frame identical to master's; with PGXP on, no library game
  that looked right with today's PGXP now worse (2D games, 2.5D scenes, menus and text included).
- **Games play correctly** with PGXP on: no hangs or wrong events; save states made before load and play on; rewind
  and runahead consistent.
- **No slower**: PGXP on (memory mode and CPU mode) no more than 5% slower than today's PGXP at unlimited speed, on the
  Mac, Windows and the Thor *(writer's call)*.

## Non-goals

- Turning PGXP on by default (considered and dropped, 2026-11-27).
- The depth buffer's rework, or turning it on by default (to Later).
- PGXP for sprites and rectangles, or for the GTE operations CPU mode doesn't track.
- Per-game tuning beyond the database's existing traits.

## Anti-goals

*(The player's choice.)*

- **A game that looked right with today's PGXP now broken** (2.5D games, misaligned text or menus, missing polygons).
- **A game behaving wrongly** with PGXP on (hangs, wrong events, save states, rewind or runahead going astray).
- **Anything changed with PGXP off.**
- Anything from DuckStation's code after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation after `a5bf1ba5cee7`; its commit titles and user documentation only. **Other
  PGXP implementations for ideas only** *(the player's choice)*: the original public PCSX-R patch and Beetle PSX's, for
  how they handle unaligned access and lines; GooseStation's own code, none copied.
- **One stage, one PR** *(the player's choice)*; `docs/roadmap.md` (item 26, and the depth buffer's rework added to
  Later) updated.
- **Games** *(the player's choice)*: what runs without input (Crash Bandicoot's title and demo, Ace Combat 3's attract
  flights, Vagrant Story's saved states in `~/work/goose-test-data/savestates/`), and save states the user makes at 3D
  gameplay in library games with visible wobble (Xenogears, Final Fantasy VIII, Legend of Dragoon, Armored Core, Crash 2)
  before the before-and-after runs. The library is on the Windows machine (SSH `pcsx2-win`, `F:\\games\\emulated\\psx\\`,
  US BIOS `C:\\Users\\Ilya\\Documents\\DuckStation\\bios\\SCPH1001.BIN`); copies go to scratch folders only, never the
  Thor's data folder.
- **Tests**: native tests for the unaligned load/store tracking and the line path (precise values in, precise values
  out) and the settings migration; the regression tests' frames with PGXP off identical to master's; Android's quick
  tier while building and the full tier before the PR.
- **The Thor** *(the player's choice)*: the building session may measure there whenever it's connected (adb
  `64dc3c35`), with the `.debug` app and scratch data in `/sdcard/GooseTest` only, never the release app (the plain ID,
  never uninstalled) or the user's data folder, and cleans up afterwards; the measurement test stays out of the
  repository. Playing and watching for wobble are the user's checks.
- **Desktop**: the Mac here (regtest, Metal, MoltenVK, OpenGL); Windows over SSH.
- The user reviews every commit message.

## Left to the builder

How unaligned access and lines are tracked, how the wobble is measured, how re-running frames is kept consistent,
confirming the cause of Crash's timing difference, and the order of implementation.
