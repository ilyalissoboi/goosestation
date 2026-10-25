# Memory Card Rename and Save Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roadmap item 11: rename saves (title and internal file name) in the Qt memory card editor, and show the
written save's own icon in the "saved" OSD message.

**Architecture:** Pure logic (Shift-JIS encoding, name rules, matching written blocks to a save) lives in files with
no `core/system.h` dependency so `common-tests` can build them, as `memcard_sync_ui.cpp` does. The card image gains two
small write operations, the Qt editor gains a rename dialog, `MemoryCard` records which blocks a game changed, and
the ImGui OSD gains an optional animated image per message.

**Tech Stack:** C++20, Qt 6 widgets, Dear ImGui OSD (`src/util/imgui_manager.cpp`), GoogleTest (`src/common-tests`).

**Spec:** The design was agreed in chat on 2026-10-25 and is summarised in [Design](#design) below. Approaches chosen:
A1 (core helpers + core card ops), B1 (invert the existing Shift-JIS table), C1 (sync warning whenever sync is on),
I1 (image support in plain OSD messages), W1 (track written blocks during writes).

## Design

**Rename (Qt editor only).** Selecting a save that isn't deleted enables **Rename File...**. A dialog has two fields,
Title and File name, filled with the current values. Only a field the user changed is written; the other keeps its
bytes exactly.
- Title: stored as Shift-JIS for every region. Printable ASCII becomes full-width (`A` → `Ａ`, space → U+3000), other
  characters go through the inverted decoding table, and a title that can't be encoded or exceeds 64 bytes is refused.
- File name: hard limits are refused (empty, over 20 characters, characters outside 0x20–0x7E, `*` or `?`, same name
  as another non-deleted save on the card). A name not matching `B` + region (`A`/`E`/`I`) + product code gets a
  warning that the game may not find or load the save. When memory card sync is on, the same confirmation also says
  other devices will see the old save deleted and a new one added. The user confirms before it's applied.
- The change marks the card dirty and reaches the file on Save, like every other editor change.

**Save icon.** When a game's writes can be attributed to exactly one save, the "saved" OSD message shows that save's
icon (animated, 0.25 s per frame) in place of the SD-card glyph and reads "Saved '{title}' to '{card}'." Otherwise
(deleted save, format, writes spanning saves, directory-only writes, no icon) it stays as today. Errors keep the glyph.
Qt and Big Picture share the OSD, so both get it.

**Out of scope:** icon extraction, multitap, a Big Picture memory card editor.

## Global Constraints

- Clean room: nothing from DuckStation after `a5bf1ba5cee7`.
- New files start with `// SPDX-FileCopyrightText: 2026 GooseStation contributors` and
  `// SPDX-License-Identifier: GPL-3.0`.
- Every new `.cpp`/`.h` is added to CMake **and** the matching `.vcxproj` and `.vcxproj.filters` (Windows builds use
  the solution).
- User-visible strings go through `tr()` (Qt) or `TRANSLATE_FS` (core).
- File name limits: 1–20 characters, each in 0x20–0x7E, not `*` or `?`, unique among non-deleted saves on the card.
- Title limit: 64 bytes of Shift-JIS, zero-padded.
- Icon animation: 0.25 s per frame.
- Commits: two feature commits (after Task 4 and after Task 7). Show each commit message in chat for review before
  committing. Message style follows the log, e.g. `Memory card editor: rename saves`.

## Review Focus

1. **Multi-block saves:** renaming must change only the first directory frame (the only one holding the name) and
   refresh its checksum; the save must still list with all its blocks after Save and reopen. (Task 4, manual check.)
2. **Only one field changed:** renaming just the file name must leave a Japanese or oddly encoded title byte-for-byte
   unchanged, and the reverse. (Task 4, manual check with a Japanese save.)
3. **Titles the editor can't show exactly:** the decoder simplifies some characters (`ー` shows as `-`, `・` as a
   space), so re-encoding an edited title would silently change them. Editing such a title must warn first.
   (Corrected after the final review; `MemoryCardHelpers.IsTitleShownExactly` test.)
4. **Two saves in a row:** the second "saved" message replaces the first under the same key; the old texture must be
   recycled and the new icon shown, and a following message without an image must drop the image. (Task 6, manual.)
5. **Loading a save state:** the written-blocks mask is not in save states and must reset on load, so the next save
   message falls back to the glyph instead of naming a wrong save. (Task 7 step.)

---

### Task 1: UTF-8 → Shift-JIS encoder

**Files:**
- Modify: `src/util/shiftjis.h`, `src/util/shiftjis.cpp`
- Create: `src/common-tests/shiftjis_tests.cpp`
- Modify: `src/common-tests/CMakeLists.txt`, `src/common-tests/common-tests.vcxproj`,
  `src/common-tests/common-tests.vcxproj.filters` (add the test file and `../util/shiftjis.cpp`)

**Interfaces:**
- Produces: `std::optional<std::string> utf82sjis(std::string_view input);` (needs `<optional>`, `<string>`,
  `<string_view>` in the header).

- [ ] **Step 1: Write the failing tests** in `shiftjis_tests.cpp` (suite `ShiftJIS`). Decode with a helper that copies
  the bytes into a buffer with two trailing NULs, calls `sjis2utf8`, and frees the result.

```cpp
TEST(ShiftJIS, EncodesAsciiFullWidth)
{
  ASSERT_EQ(utf82sjis("Ab1"), std::string("\x82\x60\x82\x82\x82\x50"));
  ASSERT_EQ(utf82sjis(" "), std::string("\x81\x40"));
}
TEST(ShiftJIS, EncodesJapanese) { ASSERT_EQ(utf82sjis("セーブ"), std::string("\x83\x5A\x81\x5B\x83\x75")); }
TEST(ShiftJIS, RejectsUnencodable)
{
  ASSERT_FALSE(utf82sjis("\xF0\x9F\x98\x80").has_value()); // emoji
  ASSERT_FALSE(utf82sjis("\xC3\xA9").has_value());         // é
  ASSERT_FALSE(utf82sjis("\xEF\xBF\xBD").has_value());     // U+FFFD
  ASSERT_FALSE(utf82sjis("\xC3").has_value());             // truncated UTF-8
}
TEST(ShiftJIS, RoundTripsLettersDigitsSpace) { ASSERT_EQ(Decode(*utf82sjis("FINAL FANTASY VII 2")), "FINAL FANTASY VII 2"); }
TEST(ShiftJIS, EncodingIsStableForAllPrintableAscii)
{
  // for every c in 0x20..0x7E: encode succeeds, and encode(decode(encode(c))) == encode(c)
}
TEST(ShiftJIS, EmptyInput) { ASSERT_EQ(utf82sjis(""), std::string()); }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cmake --build build --target common-tests && build/bin/common-tests --gtest_filter='ShiftJIS.*'`
Expected: build error, `utf82sjis` not declared.

- [ ] **Step 3: Implement `utf82sjis`** in `shiftjis.cpp`. Build the inverse of `shiftJIS_convTable` once (function
  static): Unicode → Shift-JIS code, skipping slots that decode to U+0020 except the real 0x20, and keeping the lowest
  code when a code point appears twice. Per code point: ASCII 0x21–0x7E maps to U+FF01–U+FF5E first and space to
  U+3000; if that full-width form has no code, use the ASCII character's own entry; anything else unmapped, or invalid
  UTF-8, returns `std::nullopt`. Two-byte codes are written high byte first.

- [ ] **Step 4: Run to verify they pass** — same command, expected: all `ShiftJIS.*` PASS.

### Task 2: Rename rules

**Files:**
- Create: `src/core/memory_card_helpers.h`, `src/core/memory_card_helpers.cpp` (no `system.h`; depends on
  `util/shiftjis.h` and `common/types.h` only)
- Modify: `src/core/CMakeLists.txt`, `src/core/core.vcxproj`, `src/core/core.vcxproj.filters`
- Create: `src/common-tests/memory_card_helpers_tests.cpp`; add it and `../core/memory_card_helpers.cpp` to the three
  common-tests build files

**Interfaces:**
- Consumes: `utf82sjis` (Task 1).
- Produces, in `namespace MemoryCardHelpers`:
  - `inline constexpr u32 FILE_NAME_MAX_LENGTH = 20;` `inline constexpr u32 TITLE_SIZE = 64;`
  - `enum class FileNameError { None, Empty, TooLong, InvalidCharacter, Duplicate };`
  - `FileNameError CheckFileName(std::string_view name, std::span<const std::string_view> other_names);`
  - `bool IsStandardFileName(std::string_view name);`
  - `enum class TitleError { None, Unencodable, TooLong };`
  - `TitleError EncodeTitle(std::string_view utf8, std::array<u8, TITLE_SIZE>* out);` (zero-padded; `*out` untouched
    on error)

- [ ] **Step 1: Write the failing tests** (suite `MemoryCardHelpers`):
  - `CheckFileName`: `"BASLUS-00067DRACULA"` → None; a 20-character name → None; `""` → Empty; 21 characters →
    TooLong; `"BASLUS-00067\x01"`, `"BASLUS*"`, `"BAD?"`, `"\xC3\xA9"` → InvalidCharacter; `"BASLUS-00067"` with
    `other_names = {"BASLUS-00067"}` → Duplicate; `"my save"` → None (spaces allowed).
  - `IsStandardFileName`: true for `"BASLUS-00067DRACULA"`, `"BESCES-00344"`, `"BISLPS-00700"`, `"BISLPM_86034"`;
    false for `"BXSLUS-00067"`, `"SLUS-00067"`, `"BASLUS-0006"`, `"my save"`.
  - `EncodeTitle`: `"CRASH"` → None, bytes `82 62 82 71 82 60 82 72 82 67` then 54 zeros; 32 letters → None (exactly
    64 bytes); 33 letters → TooLong; `"\xF0\x9F\x98\x80"` → Unencodable; `""` → None, all zeros.

- [ ] **Step 2: Run to verify they fail** — `build/bin/common-tests --gtest_filter='MemoryCardHelpers.*'` after
  building; expected: build error, header missing.

- [ ] **Step 3: Implement.** Standard pattern: `B`, one of `A`/`E`/`I`, four uppercase letters, `-` or `_`, five
  digits, then anything (up to the length limit).

- [ ] **Step 4: Run to verify they pass** — expected: all `MemoryCardHelpers.*` and `ShiftJIS.*` PASS.

### Task 3: Card image rename operations

**Files:**
- Modify: `src/core/memory_card_image.h`, `src/core/memory_card_image.cpp`

**Interfaces:**
- Produces:
  - `bool SetFileName(DataArray* data, const FileInfo& fi, std::string_view filename);` writes the first directory
    frame (`fi.first_block`) only: zero the 21-byte field, copy the name, `UpdateChecksum`. Returns false if
    `fi.deleted` or the name is over 20 characters.
  - `void SetTitle(DataArray* data, const FileInfo& fi, std::span<const u8, 64> title_sjis);` copies into the save's
    `TitleFrame::title` (block `fi.first_block`, frame 0).

`memory_card_image.cpp` pulls in `system.h`, so it can't build in `common-tests`; this task is verified through Task 4
(TDD skipped here on purpose, with the rules themselves already tested in Task 2).

- [ ] **Step 1: Implement both functions.**
- [ ] **Step 2: Build** — `cmake --build build`; expected: success.

### Task 4: Rename dialog in the Qt editor

**Files:**
- Modify: `src/duckstation-qt/memorycardeditorwindow.h`, `src/duckstation-qt/memorycardeditorwindow.cpp`

**Interfaces:**
- Consumes: Task 2's `CheckFileName`, `IsStandardFileName`, `EncodeTitle`; Task 3's `SetFileName`, `SetTitle`;
  `MemcardSync::SECTION` and `Host::GetBaseBoolSettingValue(MemcardSync::SECTION, "Enabled", false)`.
- Produces: `QPushButton* m_renameFile;` and slot `void doRenameFile();`.

- [ ] **Step 1: Add the button** `tr("Rename File...")` to `centerButtonBox` after Undelete File, enabled in
  `updateButtonState()` when `has_selection && !is_deleted`, connected to `doRenameFile`.

- [ ] **Step 2: Build `doRenameFile()`**: a `QDialog` made in code with a `QFormLayout` (`tr("Title:")`,
  `tr("File name:")` with `setMaxLength(20)`) and OK/Cancel. On OK, validate; on any refusal show
  `QMessageBox::warning` and reopen the dialog with the typed values. A field counts as changed only if its text differs
  from `QString::fromStdString(fi->title)` / `fi->filename`. When the file name changed and either warning applies,
  show one `QMessageBox::question` with the applicable paragraphs and "Rename anyway?"; No returns to the dialog. Then
  apply, `setCardDirty`, `updateCardTable`, `clearSelection`, `updateButtonState`. Exact copy (all `tr()`, `%1` old
  name, `%2` new name):
  - Empty: "Enter a file name."
  - TooLong: "File names can be at most 20 characters."
  - InvalidCharacter: "File names can only use ASCII letters, numbers, spaces and punctuation, except * and ?."
  - Duplicate: "This card already has a save named \"%2\"."
  - Title Unencodable: "The title has characters that can't be stored on a memory card."
  - Title TooLong: "The title is too long. It can be up to 32 full-width characters."
  - Unusual name: "\"%2\" doesn't follow the usual pattern for save names: B, a region letter, then the game's
    product code, such as BASLUS-00067. The game may no longer find or load this save."
  - Sync on: "Memory card sync is on. Other devices will see the save \"%1\" deleted and a new save \"%2\" added. The
    history of \"%1\" stays available under its old name."

- [ ] **Step 3: Build and run the tests** — `cmake --build build && build/bin/common-tests`; expected: all PASS.

- [ ] **Step 4: Manual check in the app** (`build/bin/GooseStation.app`, Tools > Memory Card Editor):
  rename a title to `Test セーブ` and a file name to `BASLUS-00067TEST`, Save, close and reopen the editor: both
  show. Rename a multi-block save; after reopening it still lists with the same block count. On a Japanese save,
  change only the file name; the title shows unchanged. Try `BAD*`, a duplicate name, a 33-letter title, and `my save`
  (warning), each with the expected message. With sync on, the warning includes the sync paragraph.

- [ ] **Step 5: Commit** — show the message in chat first, then:

```bash
git add src/util/shiftjis.* src/core/memory_card_helpers.* src/core/memory_card_image.* src/core/CMakeLists.txt \
  src/core/core.vcxproj* src/common-tests src/duckstation-qt/memorycardeditorwindow.* docs/superpowers
git commit
```

### Task 5: Matching written blocks to a save

**Files:**
- Modify: `src/core/memory_card_helpers.h/.cpp`, `src/common-tests/memory_card_helpers_tests.cpp`
- Modify: `src/core/memory_card_image.h/.cpp` (new `FileInfo` field)

**Interfaces:**
- Produces:
  - `std::optional<size_t> MemoryCardHelpers::FindWrittenSave(std::span<const u16> save_block_masks, u16 written_blocks);`
    Bit *n* is block *n*; block 0 is the directory. With `data = written_blocks & ~1`: no data bits → `nullopt`;
    otherwise the index of the only save whose mask overlaps `data` and covers all of it, else `nullopt`.
  - `u16 FileInfo::block_mask;` set in `EnumerateFiles` from the save's block chain.

- [ ] **Step 1: Write the failing test** `MemoryCardHelpers.FindWrittenSave` with masks `{0b0110, 0b1000}`:
  `0b0111` → 0; `0b1001` → 1; `0b0001` → nullopt; `0b1010` → nullopt; `0b10000` → nullopt; `0` → nullopt;
  `0b0010` → 0.
- [ ] **Step 2: Run to verify it fails** (build error).
- [ ] **Step 3: Implement `FindWrittenSave` and fill `block_mask`** in `EnumerateFiles`.
- [ ] **Step 4: Run to verify it passes** — all `MemoryCardHelpers.*` PASS.

### Task 6: Images in OSD messages

**Files:**
- Modify: `src/util/imgui_manager.h`, `src/util/imgui_manager.cpp`

**Interfaces:**
- Produces, in `namespace Host`:

```cpp
struct OSDImage
{
  std::vector<u32> pixels; // RGBA8, frames stacked vertically
  u32 width;
  u32 frame_height;
  u32 frame_count;
  float frame_duration; // seconds per frame
};
void AddImageOSDMessage(std::string key, OSDImage image, std::string message, float duration = 2.0f);
```

- [ ] **Step 1: Carry the image.** `OSDMessage` gains `std::optional<Host::OSDImage> image` and
  `std::unique_ptr<GPUTexture> texture`. When `AcquirePendingOSDMessages` replaces a keyed message, it moves over the
  new image (or none) and recycles the old texture with `g_gpu_device->RecycleTexture`. Erasing an expired message and
  `ClearOSDMessages` recycle textures too. `AddImageOSDMessage` returns early when OSD messages are off, as
  `AddKeyedOSDMessage` does.
- [ ] **Step 2: Draw it.** In `DrawOSDMessages`, create the texture on first draw with `g_gpu_device->FetchTexture`
  (RGBA8, `width` × `frame_height * frame_count`); if that fails, log once and draw the text alone. The icon is a
  square of side `font->FontSize` at the text's top-left, followed by the width of two spaces, then the text; the box
  grows by that much. The frame is `floor(time_passed / frame_duration) % frame_count`, chosen by UV.
- [ ] **Step 3: Build** — `cmake --build build`; expected: success.

### Task 7: Show the save's icon when a game saves

**Files:**
- Modify: `src/core/memory_card.h`, `src/core/memory_card.cpp`
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: `FindWrittenSave`, `FileInfo::block_mask` (Task 5); `Host::AddImageOSDMessage`, `Host::OSDImage`
  (Task 6).
- Produces: `u16 m_written_blocks = 0;` in `MemoryCard` (not serialized).

- [ ] **Step 1: Record written blocks.** In `State::WriteData`, when the byte differs, set bit
  `m_address / MemoryCardImage::FRAMES_PER_BLOCK`. Reset the mask in `Reset()`, after `DoState` loads, and in the
  state copy (line ~74).
- [ ] **Step 2: Use it in `SaveIfChanged`.** Take and clear the mask at the start. On success with
  `display_osd_message`: enumerate non-deleted saves, pass their masks to `FindWrittenSave`; if a save is found and has
  icon frames, post `AddImageOSDMessage` under the same `osd_key` with the frames stacked, `frame_duration` 0.25, the
  text `TRANSLATE_FS("OSDMessage", "Saved '{}' to '{}'.")` with the title (the file name if the title is empty) and
  the card's file name, and `Host::OSD_QUICK_DURATION`. Otherwise keep the existing glyph message. Errors are unchanged.
- [ ] **Step 3: Build and run the tests** — `cmake --build build && build/bin/common-tests`; expected: all PASS.
- [ ] **Step 4: Manual check in the app:** save in a game; the message shows the save's icon, animated for a
  multi-frame icon, and the title. Save twice quickly: one message, current icon. Delete a save from a game's own menu:
  glyph message. Load a save state, then save: icon or glyph, never another save's icon. Same in Big Picture.
- [ ] **Step 5: Update the roadmap.** Mark item 11 done with this PR's number, as items 9 and 12 are written, saying
  what shipped. Move multitap memory cards in the UI to the roadmap's Later section; note icon extraction was dropped.
- [ ] **Step 6: Commit** — show the message in chat first, then commit Tasks 5–7 with the roadmap.
