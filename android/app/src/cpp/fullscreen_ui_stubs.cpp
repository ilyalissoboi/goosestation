// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

// The fullscreen UI (Big Picture mode) is compiled out on Android, where the app has its own UI. These no-op
// definitions stand in for what the rest of the core still calls: nothing is ever initialised or open.

#include "core/fullscreen_ui.h"

#include "util/imgui_fullscreen.h"

#include "common/small_string.h"

bool FullscreenUI::Initialize()
{
  return false;
}

bool FullscreenUI::IsInitialized()
{
  return false;
}

bool FullscreenUI::HasActiveWindow()
{
  return false;
}

void FullscreenUI::CheckForConfigChanges(const Settings& old_settings)
{
}

void FullscreenUI::OnSystemStarted()
{
}

void FullscreenUI::OnSystemPaused()
{
}

void FullscreenUI::OnSystemResumed()
{
}

void FullscreenUI::OnSystemDestroyed()
{
}

void FullscreenUI::Shutdown()
{
}

void FullscreenUI::Render()
{
}

// Footer help texts for ImGuiFullscreen's dialogs; fullscreen_ui.cpp defines them on the other platforms.

void ImGuiFullscreen::GetChoiceDialogHelpText(SmallStringBase& dest)
{
  dest.clear();
}

void ImGuiFullscreen::GetFileSelectorHelpText(SmallStringBase& dest)
{
  dest.clear();
}

void ImGuiFullscreen::GetInputDialogHelpText(SmallStringBase& dest)
{
  dest.clear();
}
