<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# PGXP improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With PGXP on (it stays off by default), unaligned loads and stores keep precision in the interpreter and both
recompilers, lines get precise endpoints, the bugs found around PGXP are fixed, Crash Bandicoot's timing difference is
explained, rewind and runahead re-run frames exactly, and library games found broken get database traits. With PGXP
off, nothing changes.

**Architecture:**
- **Unaligned access, half-word by half-word.** Two new hooks, `CPU::PGXP::CPU_LWx(instr, addr, rtVal)` and
  `CPU::PGXP::CPU_SWx(instr, addr, memVal)`, take the *unaligned* address and the *merged* word (the value the
  instruction wrote to the register or to memory). From `instr.op` and `addr & 3` they know which bits of the merged
  word were kept and which were moved, and by how much. Each 16-bit half of the result takes the matching component (x
  for the low half, y for the high) from its source when the half comes whole from one half of one source and that
  source half still matches the native bits; otherwise the component is invalid. Z survives only a whole-word move
  (LWR at offset 0, LWL at offset 3, and the SWR/SWL equivalents). Sources are checked half by half against the bits of
  the merged word, as `ValidateAndLoadMem16` does, so **a load no longer clears the memory word's precision** (today
  `CPU_LW` validates the memory entry against the merged value and wipes it). The interpreter, the old recompiler and
  NewRec (x64 and AArch64) call the new hooks; today's `CPU_LW`/`CPU_SW` are unchanged for aligned access.
- **Lines.** `CPU::PGXP::GetPreciseLineVertices` looks every vertex of a line or polyline up; the primitive gets precise
  positions only when **every** vertex had precise X, Y and Z from memory (that is, came from the GTE), otherwise all
  native. 2D lines (menus, Vagrant Story's speech bubbles) have no Z and stay native. The line command's vertices carry
  float positions for drawing next to the native integers that culling and dirty rectangles keep using. Polylines keep
  their words' source addresses in `GPU::m_polyline_addresses` beside `m_blit_buffer`.
- **Memory states carry the shadow.** `MemorySaveState` gains `pgxp_data`, a copy of PGXP's register shadows and the
  memory shadow for the RAM in use (`g_ram_mask`) and the scratchpad, about 10.5 MB at 2 MB RAM. Saved and restored
  only with PGXP on; a state saved without it resets PGXP on load. Full save states and files are unchanged.
- **Fixes.** The vertex cache's lookup bound becomes `sy <= 1023` (the cache itself asserts 1023, and the GTE clamps SY
  to −1024…1023, so 1013 is a typo). The database's `DisablePGXPOn2DPolygons` handler writes the `settings` it is
  given. Big Picture's Vertex Cache row gets `false` as its default; its Geometry Tolerance and Depth Clear Threshold
  rows pass `pgxp_enabled` as `enabled` instead of as `multiplier` (with PGXP off they show 0 and write ∞ today), and
  Depth Clear Threshold writes `PGXPDepthClearThreshold`. `Settings::MovePGXPDepthThresholdOutOfDepthBuffer` repairs
  inis where Big Picture wrote a number into `PGXPDepthBuffer`.

**Tech Stack:** C++20 (`src/core`), gtest (`src/common-tests`), Dear ImGui (Big Picture), Kotlin comments (Android),
`goosestation-regtest` (Mac: Metal, MoltenVK, OpenGL; `pcsx2-win`: D3D11/D3D12/Vulkan), ffmpeg (frame differences),
an instrumented `.debug` test on the Thor (scratch, never committed).

**Spec:** `docs/superpowers/specs/2026-11-27-pgxp-improvements-design.md`.

## Decisions (made with the user on 2026-11-27: 1 A, 2 A, 3 A, 4 A, 5 A, native execution)

1. **How much an unaligned move keeps.**
   - **A. Half-words that move whole; Z only on whole-word moves (recommended).** Covers the common unaligned copy at
     offset 2 (LWR+LWL, SWR+SWL) completely, and the whole-word cases with Z. A vertex assembled from two words gets
     precise X and Y but no Z, so it never borrows another vertex's depth for texture correction. Pro: nothing can come
     out worse than integers. Con: such vertices lose perspective correction (as today).
   - B. As A, and Z taken from the low half's source. Pro: more texture correction. Con: a Z from a different vertex
     warps textures, an anti-goal risk.
   - C. Only stop loads clearing memory, and keep whole-word moves. Pro: smallest. Con: the offset-2 copy, the case
     that matters, stays imprecise.
2. **Which lines get precision.**
   - **A. All vertices or none, and only with precise X, Y and Z from memory (recommended).** Pro: 2D lines can't
     change; no cracks between a polyline's segments. Con: a 3D line with one game-built endpoint stays integer.
   - B. Per vertex on X and Y, as polygons do, with Disable on 2D Polygons applying. Pro: more lines precise. Con: 2D
     lines built by the game in CPU mode could shift, and the 2D switch is named for polygons.
3. **Rewind and runahead.**
   - **A. Copy the shadow into each memory state (recommended).** Exact: re-run frames see what the first run saw.
     Con: about 10.5 MB more per state with PGXP on (rewind's memory estimate shows it), and a copy each way per
     runahead frame (well under a millisecond on the Mac; measured on the Thor in Task 10).
   - B. Reset the shadow on every memory-state load. Pro: free. Con: re-run frames start imprecise, so runahead shows
     integer geometry for a frame in 30 fps games, and it isn't "the same as the first run".
   - C. Journal or dirty-page deltas. Pro: exact and cheap per frame. Con: the most code, in a hot path.
4. **NewRec's AArch32 and RISC-V backends** (built nowhere here, not in CI, no shipped platform uses them).
   - **A. Leave them calling today's `CPU_LW`/`CPU_SW` (recommended).** They keep today's behaviour exactly. Con: they
     don't get the gain.
   - B. Make the same change blind. Con: unbuilt, untested code generation.
5. **The bad `PGXPDepthBuffer` value Big Picture wrote.**
   - **A. Migrate it (recommended):** a number in `[GPU] PGXPDepthBuffer` moves to `PGXPDepthClearThreshold` (unless
     that is already set) and the depth buffer reads off, as it does today. Base settings in `QtHost::MigrateSettings`,
     game settings where `MoveOldVSyncSwitch` runs (`system.cpp:1505, 1516`). Android never had the row.
   - B. Only fix the row. Con: the threshold the player chose stays lost, and the junk value stays in the ini.

Execution: native, in one session.

## Global Constraints

- PGXP stays off by default; its sub-options' defaults and the database's PGXP traits are unchanged.
- **Nothing changes with PGXP off**: every regression-test frame identical to master's. Every new path sits behind
  `g_settings.gpu_pgxp_enable` (or the existing `pgxp_mode >= PGXPMode::Memory` in the interpreter).
- Clean room: nothing from DuckStation after `a5bf1ba5cee7`; the PCSX-R and Beetle PSX PGXP code for ideas only, none
  copied.
- No slower: PGXP on (memory and CPU mode) no more than 5% slower than today's PGXP at unlimited speed, on the Mac,
  Windows and the Thor.
- Thor: adb `64dc3c35`, the `.debug` app and `/sdcard/GooseTest` only; never the release app or the user's data
  folder; the measurement test stays out of the repository; clean up afterwards.
- Library copies go to scratch folders only. Remove `/tmp/goose-pgxp` and `/tmp/goose-upscale` at the end.
- Database traits only from our own testing (`docs/gamedb.md`, "Adding a fix from testing").
- Every commit: its message shown to the user after it's made; ends with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. One PR, body ending with
  `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Regtest on the Mac: `DYLD_LIBRARY_PATH=/Users/ilya.lissoboi/work/goosestation-deps/lib`, a throwaway `HOME`, read
  `<dumpdir>/<game>/goosestation.log`; the metallib from an older worktree.

## Review Focus

1. **An LWR+LWL pair whose second half sits in the first's load-delay slot, under each recompiler** → the same
   precise result as the interpreter (the kept bits come from the load-delay value). Pinned by Task 5/6's
   cross-backend frame comparison on a scene that uses it, and by Task 4's test `LwlInLoadDelayKeepsFirstHalf`.
2. **A save state loaded in the middle of a polyline** → that polyline draws native, no crash or misread (the address
   list is shorter than the blit buffer). Task 7's test `PolylineWithMissingAddressesIsNative`.
3. **PGXP switched on between a rewind save and its load** → the shadow resets, the load succeeds. Task 8's test
   `StateWithoutShadowResets`.
4. **8 MB RAM (`Console/Enable8MBRAM`)** → the shadow copy follows `g_ram_mask`, not 2 MB. Task 8's test
   `ShadowCopyFollowsRamSize`.
5. **CPU mode with unaligned access** → the same hooks run, and frames match across backends. Task 5/6's comparison
   runs with `-pgxp-cpu` as well as `-pgxp`.

---

### Task 0: Baselines and a survey (no code committed)

**Files:** scratch only (`/tmp/goose-pgxp`, a scratch patch in a throwaway worktree of `master`).

- [ ] **Step 1: Build master's regtest** in a detached worktree of `origin/master` (`/tmp/goose-pgxp/master`), Mac
  RelWithDebInfo, with the metallib copied in.
- [ ] **Step 2: Survey with a scratch counter.** In that worktree only, count per frame (a) LWL/LWR/SWL/SWR calls whose
  merged word overlaps a memory or register half that was precise, by `addr & 3`, and (b) lines and polylines whose
  vertices all have precise XYZ. Log them every 60 frames. Run Crash Bandicoot (title and demo, 9,000 frames), Ace
  Combat 3 (attract, 9,000), Vagrant Story (`SLUS-01040_resume.sav`, `_1`, `_2`, 3,600 each), PGXP on, memory and CPU
  mode. Expected: a table of games × counts; pick the scenes with the most unaligned and line hits as Task 10's
  wobble scenes. If no test game uses them, say so and survey the library on `pcsx2-win` the same way.
- [ ] **Step 3: Baseline frames from master**, every 60 frames, 1× and 4×, Metal: PGXP off; PGXP on (memory); PGXP on
  (CPU); each with `-cpu Interpreter`, `CachedInterpreter`, `Recompiler`, `NewRec`. Expected: hash lists in
  `/tmp/goose-pgxp/baseline/`. Note which backends already match each other with PGXP on; Tasks 5 and 6 must keep
  every match that exists today.
- [ ] **Step 4: Re-run check on master.** Scratch regtest patch: at frame F save a memory state
  (`System::SaveMemoryState`), hash the next 120 frames, load it, re-run and hash again; report mismatching frames.
  Run with PGXP off (expected: identical) and PGXP on (expected: some mismatches, confirming the design's concern;
  if none, note it — Task 8 still makes it exact).

### Task 1: The PGXP test fixture, and the vertex cache's bound

**Files:**
- Create: `src/common-tests/pgxp_tests.cpp` (add to `src/common-tests/CMakeLists.txt` and the `.vcxproj`/`.filters`)
- Modify: `src/core/cpu_pgxp.cpp:582`

**Interfaces:**
- Produces: `class PGXPTest : public ::testing::Test` in `pgxp_tests.cpp` — `SetUp` saves and sets
  `Bus::g_ram_mask = Bus::RAM_2MB_MASK`, `g_settings.gpu_pgxp_enable = true`, `gpu_pgxp_vertex_cache = true`,
  `gpu_pgxp_tolerance = -1`, calls `CPU::PGXP::Initialize()`; `TearDown` calls `Shutdown()` and restores both. Helpers
  `void StorePreciseVertex(u32 addr, float x, float y, float z)` (`GTE_RTPS(x, y, z, native)` then `CPU_SWC2` of
  register 14 with the native value, which truncates x and y) and `struct Found { float x, y, w; bool z_valid; };
  Found Lookup(u32 addr, u32 value)` (wraps `GetPreciseVertex` with the native position from `value` and offsets 0;
  native results have whole x and y, precise ones the test's fractions). Native packing: `u32 Pack(s16 x, s16 y)`.

- [ ] **Step 1: Write the failing test** `PGXPTest.VertexCacheFindsBottomRows`: `GTE_RTPS(5.25f, 1020.5f, 100.0f,
  Pack(5, 1020))`; `Lookup(0x3000, Pack(5, 1020))` (an empty shadow word, so the cache is asked) has x 5.25,
  y 1020.5. Also `PGXPTest.StoreThenLookupIsPrecise`: `StorePreciseVertex(0x1000, 10.25f, 20.5f, 300.0f)` →
  `Lookup(0x1000, Pack(10, 20))` is `{10.25, 20.5, 300/32768, true}`.
- [ ] **Step 2: Run** `build/bin/common-tests --gtest_filter='PGXPTest.*'`. Expected: `VertexCacheFindsBottomRows`
  FAILS (falls back to 5, 1020); `StoreThenLookupIsPrecise` passes.
- [ ] **Step 3: Change the bound** in `GetCachedVertex` to `sy <= 1023`.
- [ ] **Step 4: Run** the filter again. Expected: PASS.
- [ ] **Step 5: Commit** (`PGXP: the vertex cache finds vertices in its last ten rows`).

### Task 2: The database's 2D-polygons trait writes the settings it builds

**Files:** Modify `src/core/game_database.cpp:705`; Test `src/common-tests/game_database_tests.cpp`.

- [ ] **Step 1: Failing test** `GameDatabasePGXP.DisableOn2DPolygonsAppliesToTheGivenSettings`: an `Entry` with
  `Trait::DisablePGXPOn2DPolygons`; `Settings settings` with `gpu_pgxp_enable = true`, `gpu_pgxp_disable_2d = false`;
  `g_settings.gpu_pgxp_disable_2d = false` (saved and restored); `entry.ApplySettings(settings, false)` →
  `settings.gpu_pgxp_disable_2d` true, `g_settings.gpu_pgxp_disable_2d` false.
- [ ] **Step 2: Run** `--gtest_filter='GameDatabasePGXP.*'`. Expected: FAIL on `settings`.
- [ ] **Step 3: Write `settings.gpu_pgxp_disable_2d = true`.**
- [ ] **Step 4: Run.** Expected: PASS; the whole `GameDatabase*` set passes.
- [ ] **Step 5: Commit** (`Game database: Disable PGXP on 2D polygons applies to the game's settings`).

### Task 3: Big Picture's PGXP rows, and the value they wrote

**Files:**
- Modify: `src/core/fullscreen_ui.cpp:5023-5041`; `src/core/settings.h` (near `MoveOldVSyncSwitch`, 464);
  `src/core/settings.cpp` (after 1573); `src/duckstation-qt/qthost.cpp:725-729`; `src/core/system.cpp:1505, 1516`;
  `android/app/src/main/java/io/github/ilyalissoboi/goosestation/settings/GraphicsSettings.kt:58, 62` (comments).
- Test: `src/common-tests/settings_pgxp_tests.cpp` (new, added to the test project).

**Interfaces:**
- Produces: `static bool Settings::MovePGXPDepthThresholdOutOfDepthBuffer(SettingsInterface& si);` — true when it
  changed `si`.

- [ ] **Step 1: Failing tests** (with `MemorySettingsInterface`):
  - `SettingsPGXP.NumberInDepthBufferMovesToThreshold`: `PGXPDepthBuffer = "450.000000"`, no threshold → returns true;
    `PGXPDepthClearThreshold` reads 450; `PGXPDepthBuffer` reads `false` (as a bool, present).
  - `SettingsPGXP.ExistingThresholdIsKept`: buffer `"450.000000"`, threshold `"120"` → threshold stays 120, buffer false.
  - `SettingsPGXP.BoolDepthBufferIsLeftAlone`: `"true"` → returns false, unchanged; absent → returns false.
- [ ] **Step 2: Run** `--gtest_filter='SettingsPGXP.*'`. Expected: build failure (no such function).
- [ ] **Step 3: Implement** with `StringUtil::FromChars<bool>` failing and `FromChars<float>` succeeding as the test
  for "a number"; call it from `QtHost::MigrateSettings` (saving when true) and beside both `MoveOldVSyncSwitch(…, true)`
  calls in `system.cpp`.
- [ ] **Step 4: Fix the rows** (mechanical, no test: Big Picture rows aren't unit-testable): Vertex Cache passes
  `false, pgxp_enabled`; Geometry Tolerance and Depth Clear Threshold pass `"%.1f", 1.0f, pgxp_enabled`; Depth Clear
  Threshold's key is `"PGXPDepthClearThreshold"`. Update the two Kotlin comments to say what each row stores, without
  the Big Picture remarks.
- [ ] **Step 5: Run** the filter and build `goosestation-qt`. Expected: PASS, builds. Open Big Picture's Graphics page
  on the Mac with PGXP off and on: Tolerance shows −1.0 and Depth Clear Threshold 300.0 both times, greyed with PGXP
  off; editing the threshold writes `PGXPDepthClearThreshold` to the scratch `HOME`'s ini.
- [ ] **Step 6: Commit** (`Big Picture: PGXP's vertex cache, tolerance and depth threshold rows read and write their own settings`).

### Task 4: Unaligned loads and stores keep precision (PGXP and the interpreter)

**Files:** Modify `src/core/cpu_pgxp.h` (after `CPU_SW`), `src/core/cpu_pgxp.cpp` (after `CPU_SW`, 695),
`src/core/cpu_core.cpp:1600-1601, 1687-1688`; Test `src/common-tests/pgxp_tests.cpp`.

**Interfaces:**
- Consumes: Task 1's fixture.
- Produces: `void CPU::PGXP::CPU_LWx(Instruction instr, u32 addr, u32 rtVal);` (`addr` unaligned, `rtVal` the merged
  register value) and `void CPU::PGXP::CPU_SWx(Instruction instr, u32 addr, u32 memVal);` (`addr` unaligned, `memVal`
  the merged memory word). `instr.op` is `lwl`/`lwr` or `swl`/`swr`.

- [ ] **Step 1: Failing tests.** A test helper merges as `cpu_core.cpp:1585-1596` and `1675-1685` do. Words:
  A at `0x1000` = vertex (10.25, 20.5, z 300), B at `0x1004` = (30.75, 40.125, z 500).
  - `LwrLwlAtOffsetTwoKeepsBothHalves`: LWR from `0x1002`, then LWL from `0x1005` into the same register, then
    `CPU_SW` to `0x2000` → `Lookup(0x2000, Pack(20, 30))` has x 20.5, y 30.75, and **no valid Z** (Decision 1A:
    `GetPreciseVertex` returns false, x and y still precise).
  - `WholeWordMovesKeepZ`: LWR from `0x1000` (offset 0), `CPU_SW` to `0x2000` → `{10.25, 20.5, 300/32768}` with valid
    Z; same for LWL from `0x1003`.
  - `StraddledHalvesAreNative`: LWR from `0x1001` → the stored copy's lookup falls back to integers.
  - `LoadsLeaveMemoryPrecise`: after LWL from `0x1004` (offset 0, only one byte moved), `Lookup(0x1004, B)` is still
    precise (today it is cleared).
  - `SwrSwlAtOffsetTwoStoreBothHalves`: a register holding (12.5, 34.25) (from `CPU_LW` of a precise word) stored
    with SWR to `0x1002` and SWL to `0x1005` → A looks up as (10.25, 12.5), B as (34.25, 40.125), X and Y precise.
  - `MismatchedSourceIsNative`: A's native word changed behind PGXP's back (the merged value's moved half differs from
    the shadow) → that half is native.
  - `LwlInLoadDelayKeepsFirstHalf` (Review Focus 1): LWR then LWL where the kept bits are the LWR result (the
    load-delay case) → same as `LwrLwlAtOffsetTwoKeepsBothHalves`.
- [ ] **Step 2: Run** `--gtest_filter='PGXPTest.*'`. Expected: build failure (no `CPU_LWx`/`CPU_SWx`).
- [ ] **Step 3: Implement** `CPU_LWx` and `CPU_SWx` per the Architecture paragraph: one static helper that, given the
  kept source (validated half by half against the kept bits), the moved source (validated half by half against the
  moved bits), `addr & 3` and the direction, builds the result's x, y and flags; Z and all flags copied only when the
  result is the moved source whole. Loads write `g_state.pgxp_gpr[rt]` (value = `rtVal`); stores write the memory
  entry for `addr & ~3` (value = `memVal`), with `VALID_LOWZ | VALID_HIGHZ` set as `WriteMem` does when Z is kept.
  A memory half that fails validation has its flag cleared, as `ValidateAndLoadMem16` does.
- [ ] **Step 4: Wire the interpreter**: `cpu_core.cpp:1600-1601` calls `CPU_LWx(inst, addr, new_value)`; `1687-1688`
  calls `CPU_SWx(inst, addr, new_value)` (the unaligned `addr`).
- [ ] **Step 5: Run** the tests. Expected: PASS. Then regtest with `-cpu Interpreter` on Task 0's scenes: PGXP off
  frames identical to the baseline; PGXP on frames differ only where Task 0 counted unaligned hits.
- [ ] **Step 6: Commit** (`PGXP: unaligned loads and stores keep the precise halves they move`).

### Task 5: The old recompiler calls the new hooks

**Files:** Modify `src/core/cpu_recompiler_code_generator.cpp:1693, 1746-1747, 1777, 1809-1810`.

- [ ] **Step 1: The failing check**: regtest on the Task 0 scene with the most unaligned hits, PGXP on (memory and
  CPU), `-cpu Recompiler` vs `-cpu Interpreter`, frame hashes compared. Expected: differ (the recompiler still calls
  `CPU_LW`/`CPU_SW`), where they matched on master.
- [ ] **Step 2: Implement**: in `Compile_LoadLeftRight` and `Compile_StoreLeftRight`, keep the unaligned address in
  a `Value` until the PGXP call (only when `g_settings.gpu_pgxp_enable`), and call `PGXP::CPU_LWx` / `PGXP::CPU_SWx`
  with it and the merged value.
- [ ] **Step 3: Run** the comparison again. Expected: Recompiler == Interpreter wherever master had them equal, PGXP
  memory and CPU mode; PGXP off frames identical to the baseline.
- [ ] **Step 4: Commit** (`Recompiler: unaligned loads and stores give PGXP the unaligned address`).

### Task 6: NewRec (x64 and AArch64) calls the new hooks

**Files:** Modify `src/core/cpu_newrec_compiler_x64.cpp:29, 1617-1626, 1757-1817` and the matching `Compile_lwx` /
`Compile_swx` in `src/core/cpu_newrec_compiler_aarch64.cpp`. AArch32 and RISC-V unchanged (Decision 4A), each with a
one-line comment that they keep `CPU_LW`/`CPU_SW`.

- [ ] **Step 1: The failing check** as Task 5 with `-cpu NewRec` on the Mac (AArch64). Expected: differs.
- [ ] **Step 2: Implement**: `Compile_lwx` passes `addr` unmasked to `PGXP::CPU_LWx`; `Compile_swx` stores through a
  masked copy of `addr` and passes the unmasked one to `PGXP::CPU_SWx`. Drop "LWL etc" from the `PGXP TODO` comment.
- [ ] **Step 3: Run** on the Mac (AArch64) and on `pcsx2-win` (x64; build there). Expected: NewRec == Interpreter
  wherever master had them equal, memory and CPU mode; PGXP off identical to the baseline on both machines.
- [ ] **Step 4: Commit** (`NewRec: unaligned loads and stores give PGXP the unaligned address on x64 and AArch64`).

### Task 7: Lines get precise endpoints

**Files:**
- Modify: `src/core/cpu_pgxp.h/.cpp` (after `GetPreciseVertex`); `src/core/gpu_thread_commands.h:156-160`;
  `src/core/gpu.h:623` (new member); `src/core/gpu_commands.cpp:86, 96, 447`, `gpu.cpp:233, 1243`, `:573`
  (fill and clear the addresses with the blit buffer); `src/core/gpu_hw_frontend.cpp:584-700`;
  `src/core/gpu_hw.cpp:2756-2796`.
- Test: `src/common-tests/pgxp_tests.cpp`.

**Interfaces:**
- Produces: `bool CPU::PGXP::GetPreciseLineVertices(u32 count, const u32* addrs, const u32* values, const s32* native_xy,
  s32 offset_x, s32 offset_y, float* out_xy);` — `native_xy`/`out_xy` hold `count` x, y pairs (native includes the
  drawing offset); true and precise positions when every vertex has precise X, Y and Z from memory, else false and
  `out_xy` = native. `addrs == nullptr` means no addresses (native). `GPUThreadHWDrawLineCommand::Vertex` becomes
  `{ s32 x, y; float fx, fy; u32 color; }`. `std::vector<u32> GPU::m_polyline_addresses`.

- [ ] **Step 1: Failing tests**: `LineWithPreciseEndsIsPrecise` (two `StorePreciseVertex` words → both precise, true);
  `LineWithOneGameBuiltEndIsNative` (one end stored by `CPU_SW` from a register with X and Y but no Z → false, both
  native); `PolylineIsAllOrNothing` (three precise, one not → all native); `PolylineWithMissingAddressesIsNative`
  (`addrs == nullptr` → native, Review Focus 2).
- [ ] **Step 2: Run.** Expected: build failure.
- [ ] **Step 3: Implement** the lookup; the frontend pops lines with `m_fifo.Pop()` to keep each position's address,
  fills `fx, fy` from the lookup when PGXP is on (from the integers otherwise); polylines push each word's address
  into `m_polyline_addresses` when PGXP is on and pass it only when its size matches `m_blit_buffer`'s; `GPU_HW::DrawLine`
  keeps the integer rectangle for culling and `AddDrawnRectangle` and draws with `GSVector4(fx0, fy0, fx1, fy1)`. The
  addresses aren't saved in states.
- [ ] **Step 4: Run** the tests, then regtest: PGXP off identical to the baseline (lines draw from the same floats);
  PGXP on, frames differ only on Task 0's line scenes; Vagrant Story's speech bubbles identical to the baseline.
- [ ] **Step 5: Commit** (`PGXP: lines and polylines from the GTE get precise endpoints`).

### Task 8: Rewind and runahead re-run frames exactly

**Files:** Modify `src/core/cpu_pgxp.h/.cpp`; `src/core/system.cpp:136-142, 5129-5135, 5175-5210`; Test
`src/common-tests/pgxp_tests.cpp`.

**Interfaces:**
- Produces: `size_t CPU::PGXP::GetMemoryStateSize();` (register shadows + `(g_ram_mask + 1) / 4 + SCRATCHPAD_SIZE / 4`
  entries); `void CPU::PGXP::SaveMemoryState(std::span<u8> dst);`; `void CPU::PGXP::LoadMemoryState(std::span<const u8>
  src);` (size must match, else `Reset()`). `MemorySaveState::pgxp_data` (`DynamicHeapArray<u8>`).

- [ ] **Step 1: Failing tests**: `MemoryStateRestoresShadow` (store a vertex, save, store a different one at the same
  address, change `g_state.pgxp_gpr[4]`, load → the first vertex and register again); `StateWithoutShadowResets`
  (`LoadMemoryState({})` → lookups native; Review Focus 3); `ShadowCopyFollowsRamSize` (with `RAM_8MB_MASK`, the size
  grows by 6 MB / 4 entries and a vertex at `0x700000` round-trips; Review Focus 4).
- [ ] **Step 2: Run.** Expected: build failure.
- [ ] **Step 3: Implement**, and in `System::SaveMemoryState` fill `pgxp_data` when PGXP is on (clear it when off);
  in `LoadMemoryState`, after `DoState`, restore it when PGXP is on (reset when it's empty); add
  `GetMemoryStateSize()` × saves to `CalculateRewindMemoryUsage`'s RAM figure when PGXP is on.
- [ ] **Step 4: Run** the tests, then Task 0's re-run check on this branch. Expected: PGXP off and on (memory and CPU
  mode), re-run frames identical to the first run on every test scene.
- [ ] **Step 5: Commit** (`Rewind and runahead: memory states keep PGXP's precise values`).

### Task 9: Crash Bandicoot's timing difference

**Files:** none unless a cause other than precise culling turns up (then `superpowers:systematic-debugging` and a fix
with its own test and commit).

- [ ] **Step 1: Run** Crash's title and demo (9,000 frames) on this branch with a scratch log of a hash of main RAM
  every 60 frames (pictures can't be compared: PGXP moves pixels on purpose; RAM holds the game's own state): PGXP
  off; PGXP on; PGXP on with `GPU/PGXPCulling=false`; the same in CPU mode. Expected if precise culling is the cause:
  on-without-culling RAM hashes equal PGXP off's at every frame, and on's diverge (note the first frame). Repeat on
  Ace Combat 3 and a Vagrant Story state.
- [ ] **Step 2: Record** the result for the PR. Any other cause: fix it, test it, commit it.
- [ ] **Step 3: Games play correctly:** Crash's demo and Ace Combat 3's attract run 18,000 frames with PGXP on without
  a hang (frames keep changing), and each Vagrant Story state loads and plays 3,600 frames.

### Task 10: The library pass, the database, and the numbers

**Files:** `data/resources/gamedb.yaml` (only for games found broken); scratch for everything else.

- [ ] **Step 1: Ask the user for the save states** (Xenogears, Final Fantasy VIII, Legend of Dragoon, Armored Core,
  Crash 2 at 3D gameplay); copy them to scratch.
- [ ] **Step 2: Library pass on `pcsx2-win`** (the PowerShell driver from the faster-loading pass): every disc 9,000
  frames, hashes every 60, D3D11 at 1×: master PGXP off, branch PGXP off (must be identical, the anti-goal), master
  PGXP on, branch PGXP on, branch PGXP on CPU mode. Pair master-on/branch-on frames that differ, tile them, and look
  at each pair: worse with the branch = a regression to fix before going on. Games broken with PGXP on on master
  *and* the branch (2D or 2.5D scenes wrong, missing polygons, misaligned text, a hang) get the matching trait in
  `gamedb.yaml` per `docs/gamedb.md` (the build, what was seen, how the trait fixes it, checked by a re-run with it).
  Commit traits as `Game database: <trait> for <games>, from testing with PGXP on`.
- [ ] **Step 3: Wobble numbers**: on Task 0's scenes and the user's states with a still camera, mean MSE between
  consecutive frames (ffmpeg `psnr` filter) over 300 frames, master PGXP on vs branch PGXP on, at 1× and 4×. Success:
  lower on the branch wherever unaligned or line hits were counted, equal elsewhere.
- [ ] **Step 4: Speed**: unlimited speed (`-setting Main/EmulationSpeed=0`), interleaved runs (master, branch, branch,
  master), PGXP memory and CPU mode, on the Mac (Recompiler and NewRec) and `pcsx2-win`; on the Thor, the
  `.debug` app's measurement test at unlimited speed (per-second FPS line), plus PGXP with runahead 1 for Decision 3's
  cost. Success: within 5% of master.

### Task 11: Every tier, the roadmap, the PR

**Files:** `docs/roadmap.md` (item 26 and Later).

- [ ] **Step 1: Native tests** `build/bin/common-tests` (Mac) — all pass; Windows build of `common-tests` and the
  regtest — pass.
- [ ] **Step 2: Android**: `./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest ktlintCheck
  checkNoWindowDialogs`; the quick tier after Task 4 and Task 7 and the full tier now, each from a detached worktree
  (`git worktree add --detach /tmp/goose-pgxp-tier <commit>`, emusync AAR and `local.properties` copied in,
  `GOOSE_TEST_DATA=~/work/gt-tier-data`); read the result XMLs. Native tests on the emulator with
  `ANDROID_SERIAL=emulator-5580`.
- [ ] **Step 3: Roadmap**: item 26 says what was done (unaligned access, lines, the fixes, memory states, the culling
  finding, the traits) and what was dropped (CPU mode's untracked GTE ops, rectangles); Later gains "The PGXP depth
  buffer's rework (item 26, deferred on 2026-11-27)" with what was measured. Commit (`Docs: roadmap item 26 done`).
- [ ] **Step 4: Clean up** the Thor (`.debug.test` uninstalled, `/sdcard/GooseTest` removed), the scratch worktrees,
  `/tmp/goose-pgxp`, `/tmp/goose-upscale`.
- [ ] **Step 5: Push and open the PR** with the before-and-after numbers (wobble, speed, re-run check, the Crash
  finding, the library pass), then `superpowers:requesting-code-review`. Playing and watching for wobble are the
  user's checks.
