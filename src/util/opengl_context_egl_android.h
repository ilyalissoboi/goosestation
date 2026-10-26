// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "opengl_context_egl.h"

/// EGL context on an ANativeWindow (WindowInfo::window_handle), or surfaceless while the app has no surface.
class ContextEGLAndroid final : public OpenGLContextEGL
{
public:
  ContextEGLAndroid(const WindowInfo& wi);
  ~ContextEGLAndroid() override;

  static std::unique_ptr<OpenGLContext> Create(const WindowInfo& wi, std::span<const Version> versions_to_try,
                                               Error* error);

  std::unique_ptr<OpenGLContext> CreateSharedContext(const WindowInfo& wi, Error* error) override;

protected:
  EGLDisplay GetPlatformDisplay(Error* error) override;
  EGLSurface CreatePlatformSurface(EGLConfig config, void* win, Error* error) override;
};
