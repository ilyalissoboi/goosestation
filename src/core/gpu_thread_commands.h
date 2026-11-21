// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/gpu_texture.h"
#include "util/gpu_thread_queue.h"

#include "common/types.h"

#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable : 4200) // zero-sized array in struct/union
#endif

/// The core's commands on the GPU thread's stream, after GPUThread's own.
enum class GPUThreadCommandType : u8
{
  UpdateSWDisplay = GPUThreadQueue::FIRST_USER_TYPE,
};

/// The software renderer's frame, copied out of VRAM by the emulation thread, for the GPU thread to upload and show.
struct GPUThreadUpdateSWDisplayCommand : public GPUThreadCommand
{
  enum Mode : u8
  {
    MODE_CLEAR,       // the display is off: nothing shown
    MODE_PROGRESSIVE, // the frame as it is
    MODE_INTERLACED,  // one field, deinterlaced
    MODE_VRAM,        // the whole of VRAM
  };

  u16 width;
  u16 height;
  u32 stride;
  GPUTexture::Format format;
  Mode mode;
  u8 field;
  bool chroma_smoothing; // 24-bit with chroma smoothing on
  u8 pixels[0];
};

namespace GPUThreadCommands {

/// The GPU thread's handler for the core's commands.
void Handle(const GPUThreadCommand* cmd);

} // namespace GPUThreadCommands

#ifdef _MSC_VER
#pragma warning(pop)
#endif
