// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "jni_classes.h"

#include "achievements_bridge.h"
#include "android_host.h"
#include "bios_import.h"
#include "content_bridge.h"
#include "cover_downloads_android.h"
#include "emu_thread.h"
#include "http_downloader_android.h"
#include "jni_util.h"
#include "memcard_editor_bridge.h"
#include "memcard_sync_bridge.h"
#include "core/game_database.h"
#include "core/game_list.h"
#include "core/memory_card_image.h"
#include "core/controller.h"
#include "core/host.h"
#include "core/settings.h"
#include "core/shader_packs.h"
#include "util/http_downloader.h"
#include "util/audio_stream.h"
#include "util/platform_misc.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/progress_callback.h"
#include "common/string_util.h"
#include <android/native_window_jni.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <iterator>
#include <mutex>
#include <optional>
#include <string>
#include <tuple>
#include <unordered_map>
#include <utility>
#include <vector>

using JNIUtil::ClearException;

using JNIUtil::FromJString;

using JNIUtil::ToJString;

extern "C" {

namespace {

// A check's or an install's progress, reported to the Kotlin PackProgress it was called with, on the calling thread.
class JavaPackProgress final : public ProgressCallback
{
public:
  JavaPackProgress(JNIEnv* env, jobject listener) : m_env(env), m_listener(listener)
  {
    jclass cls = env->GetObjectClass(listener);
    m_status = env->GetMethodID(cls, "status", "(Ljava/lang/String;)V");
    m_progress = env->GetMethodID(cls, "progress", "(JJ)V");
    m_cancelled = env->GetMethodID(cls, "cancelled", "()Z");
    env->DeleteLocalRef(cls);
    JNIUtil::ClearException(env);
  }

  bool IsCancelled() const override
  {
    if (!m_cancelled)
      return false;
    const jboolean cancelled = m_env->CallBooleanMethod(m_listener, m_cancelled);
    return !JNIUtil::ClearException(m_env) && cancelled == JNI_TRUE;
  }

  void SetStatusText(std::string_view text) override
  {
    ProgressCallback::SetStatusText(text);
    m_reported_percent = -1;
    if (!m_status)
      return;
    jstring jtext = JNIUtil::ToJString(m_env, text);
    if (jtext)
    {
      m_env->CallVoidMethod(m_listener, m_status, jtext);
      JNIUtil::ClearException(m_env);
      m_env->DeleteLocalRef(jtext);
    }
  }

  void SetProgressRange(u32 range) override
  {
    ProgressCallback::SetProgressRange(range);
    Report();
  }

  void SetProgressValue(u32 value) override
  {
    ProgressCallback::SetProgressValue(value);
    Report();
  }

private:
  // A known range is reported as its percentage changes (extracting thousands of files); bytes as they come.
  void Report()
  {
    if (!m_progress)
      return;
    if (m_progress_range > 1)
    {
      const s32 percent = static_cast<s32>((static_cast<u64>(m_progress_value) * 100) / m_progress_range);
      if (percent == m_reported_percent)
        return;
      m_reported_percent = percent;
    }
    m_env->CallVoidMethod(m_listener, m_progress, static_cast<jlong>(m_progress_value),
                          static_cast<jlong>((m_progress_range > 1) ? m_progress_range : 0));
    JNIUtil::ClearException(m_env);
  }

  JNIEnv* m_env;
  jobject m_listener;
  jmethodID m_status = nullptr;
  jmethodID m_progress = nullptr;
  jmethodID m_cancelled = nullptr;
  s32 m_reported_percent = -1;
};

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, getShaderPacks)(JNIEnv* env, jobject)
{
  const std::string& root = EmuFolders::GetShaderPacksFolder();
  std::vector<std::string> flat;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
  {
    const std::optional<ShaderPacks::InstalledPack> installed = ShaderPacks::ReadMarker(root, pack.id);
    flat.emplace_back(pack.id);
    flat.emplace_back(pack.name);
    flat.emplace_back(pack.description);
    flat.emplace_back(pack.licence);
    flat.emplace_back(pack.needs ? pack.needs : "");
    flat.emplace_back((installed.has_value() && !installed->version.empty()) ? installed->version : "");
    flat.emplace_back((installed.has_value() && installed->version.empty()) ? "1" : "0");
    flat.emplace_back(installed.has_value() ? ShaderPacks::FormatSize(installed->archive_size) : "");
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, checkShaderPacks)(JNIEnv* env, jobject, jobject listener)
{
  JavaPackProgress progress(env, listener);
  const std::unique_ptr<ShaderPacks::Transport> transport = ShaderPacks::CreateTransport();
  std::vector<std::string> flat;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
  {
    Error error;
    std::optional<ShaderPacks::ResolvedVersion> version;
    if (!transport)
      error.SetStringView(ShaderPacks::DescribeFailure(true, pack, HTTPDownloader::HTTP_STATUS_ERROR));
    else if (!progress.IsCancelled())
      version = ShaderPacks::ResolveLatest(pack, *transport, &progress, &error);
    flat.emplace_back(pack.id);
    flat.emplace_back(version.has_value() ? version->version : "");
    flat.emplace_back(version.has_value() ? ShaderPacks::FormatSize(version->size) : "");
    flat.emplace_back(version.has_value() ? "" : error.GetDescription());
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, installShaderPacks)(JNIEnv* env, jobject, jobjectArray jids, jobject listener)
{
  const std::vector<std::string> ids = JNIUtil::FromJStringArray(env, jids);

  JavaPackProgress progress(env, listener);
  std::vector<std::string> flat;
  const std::unique_ptr<ShaderPacks::Transport> transport = ShaderPacks::CreateTransport();
  if (!transport)
  {
    for (const std::string& id : ids)
    {
      const ShaderPacks::PackInfo* pack = ShaderPacks::FindPack(id);
      flat.insert(flat.end(), {id, "0", "0",
                               pack ? ShaderPacks::DescribeFailure(false, *pack, HTTPDownloader::HTTP_STATUS_ERROR) :
                                      std::string()});
    }
    return ToNonNullStringArray(env, flat);
  }

  for (const ShaderPacks::InstallResult& result :
       ShaderPacks::Install(EmuFolders::GetShaderPacksFolder(), ids, *transport, &progress))
  {
    flat.insert(flat.end(), {result.id, result.success ? "1" : "0", result.cancelled ? "1" : "0", result.message});
  }
  return ToNonNullStringArray(env, flat);
}

namespace {

// For ShaderPacksInstallTest only: the platform's transport, with GitHub's addresses moved to a server on the device.
class RebasedTransport final : public ShaderPacks::Transport
{
public:
  RebasedTransport(std::unique_ptr<ShaderPacks::Transport> inner, std::string base)
    : m_inner(std::move(inner)), m_base(std::move(base))
  {
  }

  s32 Get(const std::string& url, std::string* body, ProgressCallback* progress) override
  {
    return m_inner->Get(Rebase(url), body, progress);
  }

  s32 Download(const std::string& url, const std::string& path, u64 max_size, ProgressCallback* progress,
               Error* error) override
  {
    return m_inner->Download(Rebase(url), path, max_size, progress, error);
  }

private:
  std::string Rebase(const std::string& url) const
  {
    for (const auto& [from, to] : {std::pair<std::string_view, std::string_view>{"https://api.github.com/", "/api/"},
                                   std::pair<std::string_view, std::string_view>{"https://github.com/", "/gh/"}})
    {
      if (url.starts_with(from))
        return fmt::format("{}{}{}", m_base, to, url.substr(from.size()));
    }
    return url;
  }

  std::unique_ptr<ShaderPacks::Transport> m_inner;
  std::string m_base;
};

} // namespace

NATIVE_LIBRARY_FUNCTION(jobjectArray, shaderPacksTestInstall)(JNIEnv* env, jobject, jstring base, jobjectArray jids,
                                                              jobject listener)
{
  const std::vector<std::string> ids = JNIUtil::FromJStringArray(env, jids);

  JavaPackProgress progress(env, listener);
  std::vector<std::string> flat;
  if (std::unique_ptr<ShaderPacks::Transport> inner = ShaderPacks::CreateTransport())
  {
    RebasedTransport transport(std::move(inner), FromJString(env, base));
    for (const ShaderPacks::InstallResult& result :
         ShaderPacks::Install(EmuFolders::GetShaderPacksFolder(), ids, transport, &progress))
    {
      flat.insert(flat.end(), {result.id, result.success ? "1" : "0", result.cancelled ? "1" : "0", result.message});
    }
  }
  return ToNonNullStringArray(env, flat);
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, shaderPackMarkerFiles)(JNIEnv* env, jobject, jstring json)
{
  const std::optional<ShaderPacks::InstalledPack> marker = ShaderPacks::ParseMarker(FromJString(env, json));
  return marker.has_value() ? ToNonNullStringArray(env, marker->files) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, shaderPackIds)(JNIEnv* env, jobject)
{
  std::vector<std::string> ids;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
    ids.emplace_back(pack.id);
  return ToNonNullStringArray(env, ids);
}

NATIVE_LIBRARY_FUNCTION(jstring, getShadersFolder)(JNIEnv* env, jobject)
{
  const std::optional<std::string> folder = AndroidHost::GetShadersFolder();
  return folder.has_value() ? JNIUtil::ToJString(env, folder.value()) : nullptr;
}

NATIVE_LIBRARY_FUNCTION(jstring, removeShaderPack)(JNIEnv* env, jobject, jstring id)
{
  Error error;
  if (ShaderPacks::Uninstall(EmuFolders::GetShaderPacksFolder(), FromJString(env, id), &error))
    return nullptr;
  return JNIUtil::ToJString(env, error.GetDescription());
}

NATIVE_LIBRARY_FUNCTION(jobjectArray, describeShaderPack)(JNIEnv* env, jobject, jstring id, jstring latest)
{
  const std::optional<ShaderPacks::InstalledPack> installed =
    ShaderPacks::ReadMarker(EmuFolders::GetShaderPacksFolder(), FromJString(env, id));
  std::optional<ShaderPacks::ResolvedVersion> version;
  if (latest)
    version = ShaderPacks::ResolvedVersion{FromJString(env, latest), std::string(), 0};
  const ShaderPacks::PackStatus status = ShaderPacks::GetPackStatus(installed, version);
  static constexpr const char* STATES[] = {"NotInstalled", "Installed", "UpdateAvailable", "Incomplete"};
  return ToNonNullStringArray(env, {STATES[static_cast<size_t>(status.state)], ShaderPacks::DescribePackStatus(status)});
}

} // extern "C"
