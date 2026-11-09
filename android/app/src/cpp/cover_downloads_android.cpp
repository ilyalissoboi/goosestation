// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "cover_downloads_android.h"
#include "android_host.h"
#include "jni_util.h"

#include "core/game_list.h"
#include "core/host.h"
#include "core/settings.h"
#include "util/http_downloader.h"

#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/progress_callback.h"

#include <memory>

Log_SetChannel(CoverDownloads);

namespace {

// The Kotlin CoverProgress a download reports to, on the thread it was called on.
class Listener
{
public:
  Listener(JNIEnv* env, jobject listener) : m_env(env), m_listener(listener)
  {
    jclass cls = env->GetObjectClass(listener);
    m_game = env->GetMethodID(cls, "game", "(IILjava/lang/String;)V");
    m_found = env->GetMethodID(cls, "found", "(Ljava/lang/String;Ljava/lang/String;)V");
    m_cancelled = env->GetMethodID(cls, "cancelled", "()Z");
    env->DeleteLocalRef(cls);
    JNIUtil::ClearException(env);
  }

  bool Cancelled() const
  {
    if (!m_cancelled)
      return false;
    const jboolean cancelled = m_env->CallBooleanMethod(m_listener, m_cancelled);
    return !JNIUtil::ClearException(m_env) && cancelled == JNI_TRUE;
  }

  void Game(u32 position, u32 total, std::string_view title) const
  {
    if (!m_game)
      return;
    jstring jtitle = JNIUtil::ToJString(m_env, title);
    if (!jtitle)
    {
      JNIUtil::ClearException(m_env);
      return;
    }
    m_env->CallVoidMethod(m_listener, m_game, static_cast<jint>(position), static_cast<jint>(total), jtitle);
    JNIUtil::ClearException(m_env);
    m_env->DeleteLocalRef(jtitle);
  }

  void Found(std::string_view game_path, std::string_view cover_path) const
  {
    if (!m_found)
      return;
    jstring jgame = JNIUtil::ToJString(m_env, game_path);
    jstring jcover = JNIUtil::ToJString(m_env, cover_path);
    if (jgame && jcover)
      m_env->CallVoidMethod(m_listener, m_found, jgame, jcover);
    JNIUtil::ClearException(m_env);
    if (jcover)
      m_env->DeleteLocalRef(jcover);
    if (jgame)
      m_env->DeleteLocalRef(jgame);
  }

private:
  JNIEnv* m_env;
  jobject m_listener;
  jmethodID m_game = nullptr;
  jmethodID m_found = nullptr;
  jmethodID m_cancelled = nullptr;
};

// A request's progress: only whether the download was cancelled, which HTTPDownloader's poll checks to stop the
// request in flight. The bytes it reports into it are dropped.
class CancelOnly final : public ProgressCallback
{
public:
  explicit CancelOnly(const Listener& listener) : m_listener(listener) {}

  bool IsCancelled() const override { return m_listener.Cancelled(); }

private:
  const Listener& m_listener;
};

} // namespace

std::string CoverDownloadsAndroid::FindLibraryCover(const AndroidDiscGroups::Game& game)
{
  std::string cover = GameList::GetCoverImagePath(game.path, game.key, game.title);
  if (cover.empty() && !game.discs.empty())
  {
    const AndroidDiscGroups::Disc& first = game.discs.front();
    cover = GameList::GetCoverImagePath(first.path, first.serial, first.title);
  }
  return cover;
}

AndroidCoverDownloads::Summary CoverDownloadsAndroid::Download(JNIEnv* env, const std::vector<std::string>& sources,
                                                               bool use_serial, jobject listener)
{
  const std::vector<AndroidDiscGroups::Game> games = AndroidHost::GetListedGames();
  std::vector<AndroidCoverDownloads::Target> targets;
  targets.reserve(games.size());
  for (const AndroidDiscGroups::Game& game : games)
  {
    targets.push_back({game.key, game.title, std::string(Path::GetFileTitle(game.path)),
                       std::string(Path::GetFileTitle(FileSystem::GetDisplayNameFromPath(game.path)))});
  }

  const Listener reporter(env, listener);
  CancelOnly cancel(reporter);
  const std::unique_ptr<HTTPDownloader> downloader = HTTPDownloader::Create(Host::GetHTTPUserAgent());

  AndroidCoverDownloads::Hooks hooks;
  hooks.has_cover = [&games](size_t index) { return !FindLibraryCover(games[index]).empty(); };
  hooks.fetch = [&downloader, &cancel](const std::string& url) {
    AndroidCoverDownloads::Response response;
    response.status = HTTPDownloader::HTTP_STATUS_ERROR;
    if (!downloader)
      return response;

    downloader->CreateRequest(
      url,
      [&response](s32 status, const std::string& content_type, HTTPDownloader::Request::Data data) {
        response.status = status;
        response.content_type = content_type;
        response.data = std::move(data);
      },
      &cancel);
    downloader->WaitForAllRequests();
    return response;
  };
  hooks.save = [](size_t, const std::string& file_name, std::span<const u8> data) -> std::optional<std::string> {
    Error error;
    if (!FileSystem::EnsureDirectoryExists(EmuFolders::Covers.c_str(), false, &error))
    {
      ERROR_LOG("Can't make the covers folder: {}", error.GetDescription());
      return std::nullopt;
    }
    std::string path = Path::Combine(EmuFolders::Covers, file_name);
    if (FileSystem::FileExists(path.c_str()))
      return std::nullopt;
    if (!FileSystem::WriteAtomicRenamedFile(path, data.data(), data.size(), &error))
    {
      ERROR_LOG("Can't save the cover {}: {}", path, error.GetDescription());
      return std::nullopt;
    }
    return path;
  };
  hooks.progress = [&reporter, &games](size_t index, u32 position, u32 total) {
    reporter.Game(position, total, games[index].title);
  };
  hooks.found = [&reporter, &games](size_t index, const std::string& saved_path) {
    reporter.Found(games[index].path, saved_path);
  };
  hooks.cancelled = [&reporter]() { return reporter.Cancelled(); };

  if (!downloader)
    ERROR_LOG("No HTTP downloader: every source is unreachable.");
  return AndroidCoverDownloads::Run(targets, sources, use_serial, hooks);
}
