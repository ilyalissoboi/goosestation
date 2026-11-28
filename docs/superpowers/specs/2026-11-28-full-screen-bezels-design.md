<!-- SPDX-FileCopyrightText: 2026 GooseStation contributors -->
<!-- SPDX-License-Identifier: GPL-3.0 -->

# Full-screen bezels — design

Roadmap item 30 (border overlays, from the upstream gaps players notice most, `docs/upstream-features.md` tier 9),
reshaped in conversation on 2026-11-28. Calls made while writing this up, not yet confirmed, are marked *(writer's
call)*; calls the builder may make are marked *(builder's call)*.

## Intent

A 4:3 game on a 16:9 screen (the AYN Thor's, most desktops') leaves black bars at the sides. libretro's slang pack,
which the app already downloads (roadmap item 17), has about 1,500 bezel presets made to fill them (TV frames, rooms,
reflections), and the light ones hold full speed on the Thor. But **GooseStation gives a slang stage only the game
picture's 4:3 rectangle to draw on**, so every bezel is squeezed into it: the bars stay black and the game shrinks.
This **lets bezel presets draw over the whole screen** *(the player's choice)*, as they're made to, on desktop (Qt and
Big Picture) and Android, rather than adding a second image-overlay system.

## Measured on the Thor (2026-11-28)

Vagrant Story (USA) from its saved state, Vulkan, 3×, a 1920×1080 surface, `.debug` build at `cf8ff5665`, libretro's
`slang-shaders` (shallow clone, 2026-11-28) unpacked into the test's shaders folder; a throwaway instrumented test,
scratch data only.

| Preset | Speed | GPU busy | Picture |
|---|---|---|---|
| none | 60.0 VPS | 5% | |
| `bezel/Mega_Bezel/Presets/MBZ__5__POTATO.slangp` | 60.0 (min 59.9) | 44% | a TV frame, inside the 4:3 area |
| `bezel/uborder/uborder-bezel-reflections.slangp` | 60.0 | 16% | a CRT TV in a room with posters, inside the 4:3 area |
| `bezel/koko-aio/koko-aio-ng.slangp` | 60.0 | 26% | **solid white** |
| `bezel/Mega_Bezel/Presets/MBZ__3__STD.slangp` | **2.5 (min 1.9)** | 103% | (not captured) |

Every bezel was drawn inside the 4:3 picture (x 254–1660 of 1920), the side bars black. A structural estimate from
each preset's passes (`#reference` chains resolved; each pass's output size at 1080p summed) ranks them in the same
order but not to scale: Potato 10 passes, 2.3× a 1080p screen of pixels drawn, 7 textures; koko-aio 17, 2.1×, 6;
Mega Bezel standard 30, 9.0×, 24; Smooth-Advanced 48, 13.4×, 24; uborder 1 pass at 1.0× (its base preset may add more;
the quick parser didn't follow it). Standard costs about 50 times Potato on the Thor, not 4: per-pass work varies far
more than pixel counts.

## What exists

From the code (2026-11-28; line numbers as of `cf8ff5665`):

- **The display chain** (`PostProcessing::DisplayChain`) runs in `GPUPresenter` (`gpu_presenter.cpp:359-380, 495-507`)
  into a target of the window's (or a screenshot's) full size, with `display_rect` (the game picture's rectangle) as
  `final_rect`; its first stage may read the game's own frame at the console's resolution
  (`FirstStageReadsGamePicture`, `postprocessing.cpp:475`). It's off while surfaceless (so the regression runner, which
  is surfaceless, never runs post-processing).
- **A slang stage** (`postprocessing_shader_slang.cpp:384+`) draws into `visible.out` from `Slang::CropToTarget`
  (:397), **the picture's rectangle, cropped to the target**: librashader's viewport is the game picture, not the
  screen. That is what squeezes bezels.
- **Slang presets** are named by their path under the shaders folder or the downloaded packs' folder
  (`FindPreset`, `ResolvePresetPath`, `postprocessing_shader_slang.cpp:56-82`); the browser lists them by name only
  (`postprocessing.cpp:225-231`), on Qt, Big Picture and Android (roadmap item 17's stage 2).
- **Stages' options** live in `PostProcessing/StageN` sections (`ShaderName` and the preset's parameters).

## Goals

- **A bezel stage draws over the whole screen** *(the player's choice)*: its librashader viewport is the whole target
  (window, or screenshot), and the game picture reaches it as the source, so the preset places the picture and draws
  the bezel around it, as in other frontends. librashader takes only a viewport, a rotation and the game's aspect ratio
  (its frame options, `aspect_ratio` and rotation, which every glue file passes as `nullptr` today); GooseStation
  passes the game's aspect ratio and rotation there.
- **The preset decides where the game goes** *(the player's choice)*: each family places the picture with its own
  parameters (Mega Bezel `HSM_ASPECT_RATIO_MODE`, `HSM_INT_SCALE_MODE`, `HSM_NON_INTEGER_SCALE` at about 83% of the
  screen's height by default; uborder `fr_aspect_x/y`, `fr_i_scaling`; koko-aio `ASPECT_X/Y`), which the stage's
  settings already show and the player can change. While a bezel stage draws over the whole screen, GooseStation's own
  display placement (integer scaling, alignment, stretch) doesn't apply to it, and those rows say so on Qt, Big Picture
  and Android *(writer's call on the wording)*. Mega Bezel's smaller default picture is accepted; raising its scale
  parameter enlarges it.
- **A game that isn't 4:3 falls back** *(the player's choice)*: when the picture's aspect isn't 4:3 (item 28's
  widescreen patches, a 16:9 aspect setting, other ratios), a bezel stage draws into the picture as today, since most
  families ignore the aspect they're given and would squeeze the picture (and a 16:9 picture on a 16:9 screen leaves no
  bars). Presets known to honour the aspect they're given (koko-aio reads `OriginalAspect`) may still draw over the
  whole screen *(builder's call which, by testing)*. The OSD, Big Picture and the achievement overlays still draw on top
  *(writer's call)*; screenshots that include post-processing include the bezel *(writer's call)*.
- **Which stages are bezels** *(the player's choice)*: automatic for presets under a pack's `bezel/` folder, or that
  declare themselves as bezels *(builder's call how a preset declares it, if any convention exists in the pack)*, plus
  a per-stage switch, **"Draw over the whole screen"**, on Qt, Big Picture and Android, overriding the automatic choice
  either way (a new key in the stage's section *(builder's call)*). Every other stage keeps drawing into the picture as
  today.
- **Where a bezel stage can sit** *(writer's call)*: anywhere in the display chain; stages after it work on the whole
  screen it drew; stages before it work on the picture as today, and the bezel reads their result as its source.
- **A cost label in the shader browser** *(the player's choice)*: "light", "heavy" or "very heavy" beside each slang
  preset (all of them, not only bezels *(writer's call)*), estimated from its structure: passes, the pixels they draw
  at the screen's size, their textures (`#reference` chains followed), with thresholds set from the measurements above
  so Potato and uborder read light and the full Mega Bezel very heavy *(builder's call on the formula)*. Worked out
  when the browser lists presets, cheaply (or cached), never by running them. **No warnings while playing**
  *(the player's choice)*.
- **koko-aio's white output** is investigated; if the cause is ours (a missing semantic, a texture format, the
  viewport), it's fixed *(writer's call)*.

## Success

*(The player's choice of anti-goals; the measures are the writer's calls.)*

- On the Thor and on desktop (Metal, MoltenVK, OpenGL on the Mac; D3D11, D3D12, Vulkan, OpenGL on Windows), Mega Bezel
  Potato and uborder fill the whole 16:9 screen with the game where the preset places it; the Thor holds 60 VPS with
  each.
- Non-bezel slang stages (CRT, filters) and the GLSL and ReShade stages draw frame-for-frame as today, with and without
  integer scaling, cropping and rotation.
- The picture is never stretched, cut off or misplaced by a bezel: at 4:3 it's where the preset's own settings put
  it; at other aspect ratios the stage falls back to drawing into the picture (or, for a preset that honours the
  aspect, the picture keeps its ratio); with cropping and a rotated screen likewise *(builder's call on what a bezel
  does where the preset can't place a rotated picture: drawing into the picture as today is acceptable)*.
- The browser's labels: Potato and uborder light, Mega Bezel standard and Smooth-Advanced very heavy.
- No cost when no bezel stage is in the chain.

## Non-goals

- Images or presets of our own; a separate image-overlay system; per-game bezel art downloads.
- Runtime performance warnings (decided against).
- Making the full Mega Bezel presets fast on the Thor.

## Anti-goals

*(The player's choice.)*

- **A non-bezel effect drawing differently than today.**
- **The game picture stretched, cut off or misplaced** by a bezel: away from where the preset's settings put it, or
  squeezed at an aspect other than 4:3.
- **The OSD, Big Picture or screenshots covered or drawn wrongly.**
- **A bezel stage costing anything when it isn't in use.**
- Anything from DuckStation's code after `a5bf1ba5cee7`.

## Constraints

- **Clean room**: nothing from DuckStation after `a5bf1ba5cee7`. libretro's presets are run as downloaded, never
  shipped by us.
- **One stage, one PR** *(writer's call)*; `docs/roadmap.md` (item 30, reworded to what's built) updated, and item 29's
  "won't implement" (already on this branch) goes in with it.
- **Tests**: native tests for the viewport rule (which stages draw over the whole screen, the 4:3 fallback, the aspect
  ratio and rotation passed to librashader) and the cost label (passes and pixel totals from hand-written presets,
  `#reference` chains); frames compared on desktop with a surface (the regression runner is surfaceless, so a
  windowed or offscreen check *(builder's call how)*); Android's quick tier while building and the full tier before
  the PR.
- **The Thor** *(writer's call, the rule of the last items)*: the building session may measure there whenever it's
  connected (adb `64dc3c35`), with the `.debug` app and scratch data in `/sdcard/GooseTest` only, never the release
  app (the plain ID, never uninstalled) or the user's data folder, and cleans up afterwards. Judging how bezels look is
  the user's check.
- **Desktop**: the Mac here; Windows over SSH on the user's machine.
- The user reviews every commit message.

## Left to the builder

How the viewport, aspect ratio and rotation reach librashader, how a bezel is recognised beyond its folder, the cost
formula and its thresholds, the stage switch's key, what a bezel does with a rotated picture, why koko-aio is white,
and the order of implementation.

## Built (2026-11-29)

What changed from the above while building (plan `docs/superpowers/plans/2026-11-28-full-screen-bezels.md`):

- **No preset family honours the aspect as shipped.** Drawn on a 1920×1080 screen with a 16:9 picture, koko-aio-ng
  and scanline-classic both place a 4:3 picture by their own parameters (koko-aio reads the aspect only with its
  `ASPECT_X` set negative), so every bezel falls back into the picture when it isn't 4:3.
- **"4:3" is within 5%**, not 2%: Auto's aspect with the default overscan crop is 1.306 for Vagrant Story and moves
  between 1.306 and 1.312 for padtest.
- **koko-aio's white** was ours: it writes alpha 0, and the stage copied the preset's alpha into the window, where
  captures (and Android's surface) keep it. The result is drawn opaque now.
- **Metal lost whole frames** of heavier presets (crt-guest-advanced came out empty): librashader's per-frame objects
  were freed before the GPU used them; it now encodes into a command buffer that retains them.
- **The Thor** (Vagrant Story, Vulkan, 1920×1080, `.debug` build at `d662efebd`): none 59.7 VPS, GPU 5%; Potato 59.7,
  55%; uborder 59.8, 21%; koko-aio 59.8, 28%; Mega Bezel standard 1.9 (min 0.9), 102%.
