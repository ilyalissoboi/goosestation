// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "gpu_thread_commands.h"
#include "gpu_sw.h"

#include "common/assert.h"

void GPUThreadCommands::Handle(const GPUThreadCommand* cmd)
{
  switch (static_cast<GPUThreadCommandType>(cmd->type))
  {
    case GPUThreadCommandType::UpdateSWDisplay:
      GPU_SW::UpdateDisplayOnGPUThread(static_cast<const GPUThreadUpdateSWDisplayCommand*>(cmd));
      break;

    default:
      UnreachableCode();
      break;
  }
}
