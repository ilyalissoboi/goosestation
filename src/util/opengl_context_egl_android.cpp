// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "opengl_context_egl_android.h"

#include "common/error.h"
#include "common/log.h"

#include <android/native_window.h>

Log_SetChannel(OpenGLContext);

ContextEGLAndroid::ContextEGLAndroid(const WindowInfo& wi) : OpenGLContextEGL(wi)
{
}

ContextEGLAndroid::~ContextEGLAndroid() = default;

std::unique_ptr<OpenGLContext> ContextEGLAndroid::Create(const WindowInfo& wi, std::span<const Version> versions_to_try,
                                                         Error* error)
{
  std::unique_ptr<ContextEGLAndroid> context = std::make_unique<ContextEGLAndroid>(wi);
  if (!context->Initialize(versions_to_try, error))
    return nullptr;

  return context;
}

std::unique_ptr<OpenGLContext> ContextEGLAndroid::CreateSharedContext(const WindowInfo& wi, Error* error)
{
  std::unique_ptr<ContextEGLAndroid> context = std::make_unique<ContextEGLAndroid>(wi);
  context->m_display = m_display;

  if (!context->CreateContextAndSurface(m_version, m_context, false))
  {
    Error::SetStringView(error, "Failed to create context/surface");
    return nullptr;
  }

  return context;
}

EGLDisplay ContextEGLAndroid::GetPlatformDisplay(Error* error)
{
  // Android has a single display connection, the default one.
  EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (dpy == EGL_NO_DISPLAY)
  {
    const EGLint err = eglGetError();
    Error::SetStringFmt(error, "eglGetDisplay() failed: {} (0x{:X})", err, err);
  }

  return dpy;
}

EGLSurface ContextEGLAndroid::CreatePlatformSurface(EGLConfig config, void* win, Error* error)
{
  ANativeWindow* const window = static_cast<ANativeWindow*>(win);

  // Match the window's buffer format to the config, keeping the window's own size.
  EGLint format;
  if (eglGetConfigAttrib(m_display, config, EGL_NATIVE_VISUAL_ID, &format))
  {
    const int32_t res = ANativeWindow_setBuffersGeometry(window, 0, 0, format);
    if (res != 0)
      WARNING_LOG("ANativeWindow_setBuffersGeometry() failed: {}", res);
  }
  else
  {
    WARNING_LOG("eglGetConfigAttrib(EGL_NATIVE_VISUAL_ID) failed: 0x{:X}", eglGetError());
  }

  EGLSurface surface = eglCreateWindowSurface(m_display, config, window, nullptr);
  if (surface == EGL_NO_SURFACE)
  {
    const EGLint err = eglGetError();
    Error::SetStringFmt(error, "eglCreateWindowSurface() failed: {} (0x{:X})", err, err);
  }

  return surface;
}
