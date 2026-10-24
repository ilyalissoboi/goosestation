// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "memcard_sync.h"
#include "host.h"
#include "settings.h"
#include "system.h"

#include "common/dynamic_library.h"
#include "common/error.h"
#include "common/file_system.h"
#include "common/log.h"
#include "common/path.h"
#include "common/scoped_guard.h"
#include "common/sha1_digest.h"
#include "common/string_util.h"
#include "common/threading.h"

#include "scmversion/scmversion.h"

#include "util/imgui_manager.h"

#include "IconsFontAwesome5.h"

#include "emusync.h"

#include "fmt/format.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <ctime>
#include <mutex>
#include <shared_mutex>
#include <thread>
#include <utility>

Log_SetChannel(MemcardSync);

namespace MemcardSync {

// ---- The library, loaded at run time -----------------------------------------------------------
//
// From the emusync release the build pins (dep/emusync/README.md): emusync.dll next to the program
// on Windows, libemusync.so next to it on Linux, libemusync.dylib in the app bundle's Frameworks (or
// next to the program) on macOS. Nothing links against it, so a build or a system without it runs
// without sync: IsAvailable() is false.

#define ES_FUNCTIONS(X)                                                                                                \
  X(es_abi_version)                                                                                                    \
  X(es_status_str)                                                                                                     \
  X(es_client_create)                                                                                                  \
  X(es_client_destroy)                                                                                                 \
  X(es_repo_create_encrypted)                                                                                          \
  X(es_remote_add)                                                                                                     \
  X(es_event_set_notifier)                                                                                             \
  X(es_event_poll)                                                                                                     \
  X(es_event_release)                                                                                                  \
  X(es_container_add)                                                                                                  \
  X(es_container_flushed)                                                                                              \
  X(es_session_begin)                                                                                                  \
  X(es_session_end)                                                                                                    \
  X(es_sync_now)                                                                                                       \
  X(es_conflict_get)                                                                                                   \
  X(es_conflict_resolve)                                                                                               \
  X(es_secret_set)                                                                                                     \
  X(es_secret_get)                                                                                                     \
  X(es_secret_delete)

namespace {
struct EmuSyncApi
{
#define ES_POINTER(name) decltype(&::name) name = nullptr;
  ES_FUNCTIONS(ES_POINTER)
#undef ES_POINTER
};

/// A card the emulator has open, and its session.
struct Card
{
  std::string path;
  // The running game when the card was opened: picks which saves are fetched first.
  std::string serial;
  std::string title;
  es_container_id container = 0;
  es_session_id session = 0;
  std::chrono::steady_clock::time_point opened;
  // In OnCardOpening(): not read yet, so it is followed there, not by Start().
  bool opening = false;
};

/// The settings an apply runs with, read when it is queued.
struct ApplySettings
{
  bool enabled = false;
  std::string remote_uri;
  std::string access_key_id;
  std::string device_name;
};
} // namespace

static EmuSyncApi s_es;

// How long opening a card waits for the network before the card opens as it is.
static constexpr u32 OPEN_SYNC_TIMEOUT_MS = 3000;
// A sync with no event to trigger it.
static constexpr std::chrono::minutes PERIODIC_SYNC{5};
// What exiting gives queued uploads.
static constexpr u32 EXIT_UPLOAD_MS = 5000;
// How long opening a card waits for a start in progress: the start's own check of the storage takes
// up to ~10 s.
static constexpr std::chrono::seconds OPEN_WAITS_FOR_START{12};
// The credential-store service the secrets are kept under.
static constexpr const char* SECRET_SERVICE = "GooseStation";
// Longer than any secret emusync stores (1024 bytes), plus the NUL.
static constexpr size_t SECRET_BUFFER = 1025;

// Serialises Start() and Stop() with each other.
static std::mutex s_lifecycle_lock;
// The client's lifetime. Every es_* call holds it shared; Start() and Stop() hold it unique, so the
// client is never destroyed under a call. The longest shared hold is a card's opening sync (3 s).
static std::shared_mutex s_client_lock;
static es_client* s_client = nullptr;

// Everything else, held briefly.
static std::mutex s_state_lock;
static Config s_config;
// The cards the emulator has open, whether or not sync runs.
static std::vector<Card> s_cards;
static std::string s_status;
static std::string s_last_start_error;
// Conflicts waiting for the player, in the order emusync reported them.
static std::vector<Conflict> s_conflicts;

// The worker thread: periodic syncs, uploads after writes, and draining emusync's events. Writes
// are handed to it so the emulation thread never waits for emusync.
static std::thread s_worker;
static std::mutex s_worker_lock;
static std::condition_variable s_worker_cv;
static bool s_worker_stop = false;
static bool s_worker_wake = false;
static std::vector<es_container_id> s_flush_pending;
// The player's choices, for the worker to hand to emusync.
struct PendingResolution
{
  es_conflict_id id;
  bool keep_local;
  u32 remote_index;
};
static std::vector<PendingResolution> s_resolve_pending;

// The apply thread (ApplySettingsAsync): starts and stops the client off the UI and emulation
// threads.
static std::thread s_apply_thread;
static std::mutex s_apply_lock;
static std::condition_variable s_apply_cv;
static bool s_apply_pending = false;
// A run of the apply thread is in progress (s_apply_pending is cleared when it begins).
static bool s_apply_running = false;
static std::condition_variable s_apply_idle_cv;
static bool s_apply_quit = false;
static std::vector<SecretUpdate> s_apply_secrets;
static std::vector<std::function<void(const std::string&)>> s_apply_done;
static ApplySettings s_apply_settings;

static constexpr const char* NO_LIBRARY = "The emusync library is missing, so memory card sync is unavailable.";
static constexpr const char* LOCKED_STATUS =
  "This storage is encrypted. Enter its passphrase in the memory card sync settings to sync.";

} // namespace MemcardSync

void MemcardSync::WipeSecret(std::string& secret)
{
  // Through a volatile pointer, so the stores can't be dropped as dead before the release.
  volatile char* p = secret.data();
  for (size_t i = 0; i < secret.size(); i++)
    p[i] = 0;
  secret.clear();
  secret.shrink_to_fit();
}

std::string MemcardSync::DataDirFor(std::string_view remote_uri)
{
  // emusync binds a data folder to one storage for good: one folder per address, so switching to
  // another storage (a new folder for encrypted saves, say) starts a fresh one, and switching back
  // finds the old one again. SHA-1 only names the folder; nothing depends on it being secure.
  const auto digest = SHA1Digest::GetDigest(remote_uri.data(), remote_uri.size());
  std::string id;
  for (size_t i = 0; i < 8; i++)
    id += fmt::format("{:02x}", digest[i]);
  return Path::Combine(Path::Combine(EmuFolders::DataRoot, "emusync"), id);
}

std::string MemcardSync::CardNameFor(std::string_view path)
{
  std::string name(Path::GetFileTitle(path));
  for (char& c : name)
  {
    if (c == ' ' || c == '\t' || c == ':' || c == '/' || c == '\\')
      c = '_';
  }
  return name;
}

bool MemcardSync::HasSecret(const char* name)
{
  const std::string_view n = name;
  if (n == SECRET_ACCESS_KEY)
    return Host::GetBaseBoolSettingValue(SECTION, "SecretStored", false);
  if (n == ENCRYPTION_PASSPHRASE)
    return Host::GetBaseBoolSettingValue(SECTION, "PassphraseStored", false);
  return false;
}

static bool OpenEmuSync(DynamicLibrary* lib)
{
#if defined(_WIN32)
  const char* name = "emusync.dll";
  const std::string candidates[] = {Path::Combine(EmuFolders::AppRoot, name)};
#elif defined(__APPLE__)
  const char* name = "libemusync.dylib";
  const std::string candidates[] = {Path::Combine(EmuFolders::AppRoot, "../Frameworks/libemusync.dylib"),
                                    Path::Combine(EmuFolders::AppRoot, name)};
#else
  const char* name = "libemusync.so";
  const std::string candidates[] = {Path::Combine(EmuFolders::AppRoot, name)};
#endif
  for (const std::string& path : candidates)
  {
    if (FileSystem::FileExists(path.c_str()) && lib->Open(path.c_str(), nullptr))
      return true;
  }
  return lib->Open(name, nullptr);
}

// Loads the library once; never unloaded.
static bool LoadEmuSync()
{
  using namespace MemcardSync;
  static std::once_flag once;
  static bool loaded = false;
  std::call_once(once, [] {
    static DynamicLibrary lib;
    if (!OpenEmuSync(&lib))
    {
      INFO_LOG("The emusync library isn't there; no memory card sync.");
      return;
    }
    bool all = true;
#define ES_RESOLVE(name) all = lib.GetSymbol(#name, &s_es.name) && all;
    ES_FUNCTIONS(ES_RESOLVE)
#undef ES_RESOLVE
    if (!all || s_es.es_abi_version() != ES_ABI_VERSION)
    {
      WARNING_LOG("The emusync library isn't the version this build expects; no memory card sync.");
      s_es = {};
      return;
    }
    loaded = true;
  });
  return loaded;
}

bool MemcardSync::IsAvailable()
{
  // Every path into emusync checks this first: the table is empty without the library.
  return LoadEmuSync();
}

static void SetStatus(std::string status)
{
  std::lock_guard lock(MemcardSync::s_state_lock);
  MemcardSync::s_status = std::move(status);
}

static std::string TimeOfDay()
{
  const std::time_t now = std::time(nullptr);
  std::tm local{};
#ifdef _WIN32
  localtime_s(&local, &now);
#else
  localtime_r(&now, &local);
#endif
  char buf[16];
  std::strftime(buf, sizeof(buf), "%H:%M", &local);
  return buf;
}

static void LogMessage(void*, es_log_level level, const char*, const char* msg)
{
  switch (level)
  {
    case ES_LOG_ERROR:
      ERROR_LOG("{}", msg);
      break;
    case ES_LOG_WARN:
      WARNING_LOG("{}", msg);
      break;
    case ES_LOG_INFO:
      INFO_LOG("{}", msg);
      break;
    default:
      DEV_LOG("{}", msg);
      break;
  }
}

static void CopyTo(char* dst, size_t cap, const std::string& src)
{
  const size_t n = std::min(src.size(), cap - 1);
  std::memcpy(dst, src.data(), n);
  dst[n] = '\0';
}

// The credential provider: the keys from Start(), kept in memory only.
static int GetCredentials(void*, const char*, es_credentials* out)
{
  using namespace MemcardSync;
  std::lock_guard lock(s_state_lock);
  if (s_config.access_key_id.empty() || s_config.secret_access_key.empty())
    return ES_E_AUTH;
  out->kind = ES_CRED_S3;
  CopyTo(out->access_key_id, sizeof(out->access_key_id), s_config.access_key_id);
  CopyTo(out->secret_access_key, sizeof(out->secret_access_key), s_config.secret_access_key);
  return 0;
}

static void WakeWorker()
{
  using namespace MemcardSync;
  {
    std::lock_guard lock(s_worker_lock);
    s_worker_wake = true;
  }
  s_worker_cv.notify_one();
}

// Called by emusync on its own thread when events are waiting; must not call into emusync.
static void OnEventsWaiting(void*)
{
  WakeWorker();
}

// Registers a card file (format ps1.card, card named after the file). Needs the client lock
// (shared).
static es_container_id RegisterCard(const std::string& path)
{
  using namespace MemcardSync;
  const std::string card = CardNameFor(path);
  es_container_desc desc = ES_CONTAINER_DESC_INIT;
  desc.format = "ps1.card";
  desc.location = path.c_str();
  desc.platform = "ps1";
  desc.card = card.c_str();
  es_container_id id = 0;
  const es_status st = s_es.es_container_add(s_client, &desc, &id);
  if (st != ES_OK)
  {
    WARNING_LOG("Not syncing {}: {}", path, s_es.es_status_str(st));
    return 0;
  }
  return id;
}

// Begins a session on one card. Needs the client lock (shared). The state lock must NOT be held:
// this can wait for the network.
static es_session_id BeginSession(es_container_id container, const std::string& serial,
                                  const std::string& title_name, u32 timeout_ms, es_session_info* info)
{
  using namespace MemcardSync;
  if (container == 0)
    return 0;
  // The running game decides which saves are fetched first: their title keys are "ps1:SLUS-00067".
  es_title title = ES_TITLE_INIT;
  title.platform = "ps1";
  title.serial = serial.empty() ? "UNKNOWN" : serial.c_str();
  title.display_name = title_name.empty() ? nullptr : title_name.c_str();

  es_session_begin_args args = ES_SESSION_BEGIN_INIT;
  args.title = &title;
  args.mounted = &container;
  args.mounted_count = 1;
  args.timeout_ms = timeout_ms;
  *info = ES_SESSION_INFO_INIT;
  const es_status st = s_es.es_session_begin(s_client, &args, info);
  if (st < 0)
  {
    WARNING_LOG("Could not start syncing a memory card in use: {}", s_es.es_status_str(st));
    return 0;
  }
  return info->id;
}

// Ends a card's session. Needs the client lock (shared).
static void EndSession(const MemcardSync::Card& card)
{
  using namespace MemcardSync;
  if (card.session == 0)
    return;
  const u64 played_ms = static_cast<u64>(
    std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - card.opened).count());
  s_es.es_session_end(s_client, card.session, played_ms);
}

// Registers a card and begins its session, recording both. Needs the client lock (shared).
static void FollowCard(const std::string& path, const std::string& serial, const std::string& title, u32 timeout_ms)
{
  using namespace MemcardSync;
  {
    // Already followed: the same file in both slots.
    std::lock_guard lock(s_state_lock);
    if (std::any_of(s_cards.begin(), s_cards.end(), [&](const Card& c) { return c.path == path && c.session != 0; }))
      return;
  }
  const es_container_id container = RegisterCard(path);
  es_session_info info;
  const es_session_id id = BeginSession(container, serial, title, timeout_ms, &info);
  bool open = false;
  {
    std::lock_guard lock(s_state_lock);
    for (Card& c : s_cards)
    {
      if (c.path != path)
        continue;
      open = true;
      // A session begun meanwhile by the other caller is kept (this one then failed as busy).
      if (c.session == 0)
      {
        c.container = container;
        c.session = id;
      }
    }
  }
  // The card was closed while its session began: end it, or the card would stay marked in use.
  if (!open && id != 0)
  {
    Card closed;
    closed.session = id;
    closed.opened = std::chrono::steady_clock::now();
    EndSession(closed);
    return;
  }
  if (id != 0 && info.units_applied > 0)
  {
    INFO_LOG("{} save(s) updated from other devices on {}.", info.units_applied, Path::GetFileName(path));
    const std::string text = fmt::format(TRANSLATE_FS("MemcardSync", "Updated {} save(s) from other devices on {}"),
                                         info.units_applied, Path::GetFileName(path));
    Host::AddIconOSDMessage(fmt::format("MemcardSync-{}", path), ICON_FA_CLOUD, text, Host::OSD_INFO_DURATION);
    SetStatus(fmt::format("{} at {}.", text, TimeOfDay()));
  }
  else if (id != 0 && !info.remote_reached)
  {
    WARNING_LOG("Storage not reached before opening {}; it opens as it is.", Path::GetFileName(path));
  }
}

static std::string DescribeEvent(const es_event& ev)
{
  using namespace MemcardSync;
  const char* save = ev.unit_name ? ev.unit_name : "a save";
  if (ev.status == ES_E_LOCKED && (ev.type == ES_EV_SYNC_FINISHED || ev.type == ES_EV_ERROR))
    return LOCKED_STATUS;
  switch (ev.type)
  {
    case ES_EV_SYNC_FINISHED:
      if (ev.status == ES_OK)
        return fmt::format("Synced at {}.", TimeOfDay());
      if (ev.status == ES_OFFLINE)
        return fmt::format("Offline at {}: changes upload once the storage can be reached.", TimeOfDay());
      return fmt::format("Sync failed at {}: {}", TimeOfDay(), ev.message ? ev.message : s_es.es_status_str(ev.status));
    case ES_EV_UNIT_APPLIED:
      return fmt::format("Updated {}{} at {}.", save,
                         ev.device_name ? fmt::format(" from {}", ev.device_name) : std::string(), TimeOfDay());
    case ES_EV_APPLY_BLOCKED:
      if (ev.status == ES_E_NO_SPACE)
        return fmt::format("{} does not fit on its memory card.", save);
      if (ev.status == ES_E_BUSY)
        return {}; // the card is in use; it is applied when the card is closed
      return fmt::format("Could not update {}: {}", save, ev.message ? ev.message : s_es.es_status_str(ev.status));
    case ES_EV_CONFLICT:
      return fmt::format("{} was changed both on this device and on another: choose which to keep in the memory card "
                         "sync settings.",
                         save);
    case ES_EV_AUTH_REQUIRED:
      return "The storage rejected the access key. Check it in the memory card sync settings.";
    case ES_EV_ERROR:
      return fmt::format("Sync error: {}", ev.message ? ev.message : s_es.es_status_str(ev.status));
    default:
      return {};
  }
}

static MemcardSync::ConflictVersion ToConflictVersion(const es_version_info& v)
{
  MemcardSync::ConflictVersion out;
  out.device = v.device_name;
  out.modified_unix_ms = v.modified_unix_ms;
  out.size = v.size;
  out.deleted = (v.is_tombstone != 0);
  return out;
}

// Records a conflict emusync reported, or updates the record of it. Needs the client lock (shared).
static void AddConflict(es_conflict_id id)
{
  using namespace MemcardSync;
  es_conflict_info info = ES_CONFLICT_INFO_INIT;
  if (const es_status st = s_es.es_conflict_get(s_client, id, &info); st != ES_OK)
  {
    WARNING_LOG("Could not read conflict {}: {}", id, s_es.es_status_str(st));
    return;
  }

  Conflict c;
  c.id = id;
  // Unit ids are "ps1:<card>:<save>"; the save's name may itself hold ':'.
  const std::string_view unit = info.unit_id;
  if (const size_t first = unit.find(':'); first != std::string_view::npos)
  {
    if (const size_t second = unit.find(':', first + 1); second != std::string_view::npos)
    {
      c.card = unit.substr(first + 1, second - first - 1);
      c.save = unit.substr(second + 1);
    }
  }
  if (c.save.empty())
    c.save = info.unit_name;
  // Title keys are "ps1:SLUS-00067".
  if (const std::string_view key = info.title_key; key.starts_with("ps1:"))
    c.serial = key.substr(4);
  if (info.local.version_id[0] != '\0')
    c.local = ToConflictVersion(info.local);
  const u32 remote_count = std::min<u32>(info.remote_count, static_cast<u32>(std::size(info.remote)));
  for (u32 i = 0; i < remote_count; i++)
    c.remote.push_back(ToConflictVersion(info.remote[i]));

  std::lock_guard lock(s_state_lock);
  const auto it = std::find_if(s_conflicts.begin(), s_conflicts.end(), [id](const Conflict& x) { return x.id == id; });
  if (it != s_conflicts.end())
    *it = std::move(c);
  else
    s_conflicts.push_back(std::move(c));
}

static void RemoveConflict(es_conflict_id id)
{
  using namespace MemcardSync;
  std::lock_guard lock(s_state_lock);
  std::erase_if(s_conflicts, [id](const Conflict& c) { return c.id == id; });
}

// Drops the conflicts resolved meanwhile, on another device say. Needs the client lock (shared).
static void PruneConflicts()
{
  using namespace MemcardSync;
  std::vector<es_conflict_id> ids;
  {
    std::lock_guard lock(s_state_lock);
    for (const Conflict& c : s_conflicts)
      ids.push_back(c.id);
  }
  for (const es_conflict_id id : ids)
  {
    es_conflict_info info = ES_CONFLICT_INFO_INIT;
    if (s_es.es_conflict_get(s_client, id, &info) == ES_E_NOT_FOUND)
      RemoveConflict(id);
  }
}

// Hands the player's choices to emusync. Needs the client lock (shared).
static void ResolvePending(const std::vector<MemcardSync::PendingResolution>& pending)
{
  using namespace MemcardSync;
  for (const PendingResolution& p : pending)
  {
    const es_status st = s_es.es_conflict_resolve(s_client, p.id, p.keep_local ? ES_RESOLVE_KEEP_LOCAL : ES_RESOLVE_TAKE_REMOTE,
                                                  p.remote_index);
    if (st == ES_OK || st == ES_PENDING || st == ES_E_NOT_FOUND)
    {
      INFO_LOG("Conflict {} resolved: {}.", p.id,
               p.keep_local ? std::string("kept this device's version") :
                              fmt::format("took the other device's version {}", p.remote_index));
      RemoveConflict(p.id);
    }
    else
    {
      WARNING_LOG("Could not resolve conflict {}: {}", p.id, s_es.es_status_str(st));
      SetStatus(fmt::format("Could not resolve a conflict: {}", s_es.es_status_str(st)));
    }
  }
}

// Drains emusync's events into the log and the status line. Needs the client lock (shared).
static void DrainEvents()
{
  using namespace MemcardSync;
  es_event ev = ES_EVENT_INIT;
  while (s_es.es_event_poll(s_client, &ev) == 1)
  {
    if (ev.type == ES_EV_CONFLICT)
    {
      AddConflict(ev.conflict_id);
      Host::AddIconOSDMessage(
        fmt::format("MemcardSync-Conflict-{}", ev.conflict_id), ICON_FA_CLOUD,
        fmt::format(TRANSLATE_FS("MemcardSync", "{} was changed on this device and on another. Choose which to keep "
                                                "in Settings > Memory Cards."),
                    ev.unit_name ? ev.unit_name : "A save"),
        Host::OSD_WARNING_DURATION);
    }
    else if (ev.type == ES_EV_SYNC_FINISHED)
    {
      PruneConflicts();
    }

    std::string text = DescribeEvent(ev);
    if (ev.type == ES_EV_UNIT_APPLIED || ev.type == ES_EV_UNIT_UPLOADED)
      INFO_LOG("{} {}", ev.type == ES_EV_UNIT_APPLIED ? "Applied" : "Uploaded", ev.unit_id ? ev.unit_id : "?");
    else if (!text.empty() && ev.type != ES_EV_SYNC_FINISHED)
      WARNING_LOG("{}", text);
    if (!text.empty())
      SetStatus(std::move(text));
    s_es.es_event_release(s_client, &ev);
    ev = ES_EVENT_INIT;
  }
}

static void WorkerLoop()
{
  using namespace MemcardSync;
  Threading::SetNameOfCurrentThread("MemcardSync");
  auto next_sync = std::chrono::steady_clock::now() + PERIODIC_SYNC;
  for (;;)
  {
    std::vector<es_container_id> flush;
    std::vector<PendingResolution> resolve;
    {
      std::unique_lock lock(s_worker_lock);
      s_worker_cv.wait_until(lock, next_sync, [] { return s_worker_stop || s_worker_wake; });
      if (s_worker_stop)
        return;
      s_worker_wake = false;
      flush = std::exchange(s_flush_pending, {});
      resolve = std::exchange(s_resolve_pending, {});
    }

    std::shared_lock client(s_client_lock);
    if (!s_client)
      return;
    for (const es_container_id id : flush)
      s_es.es_container_flushed(s_client, id);
    ResolvePending(resolve);
    if (std::chrono::steady_clock::now() >= next_sync)
    {
      s_es.es_sync_now(s_client);
      next_sync = std::chrono::steady_clock::now() + PERIODIC_SYNC;
    }
    DrainEvents();
  }
}

// Stops the worker and destroys the client. Needs the lifecycle lock and the client lock (unique).
static void StopLocked(std::unique_lock<std::shared_mutex>& client, u32 timeout_ms)
{
  using namespace MemcardSync;
  if (!s_client)
    return;
  {
    std::lock_guard lock(s_worker_lock);
    s_worker_stop = true;
  }
  s_worker_cv.notify_one();
  // The worker takes the client lock shared, which this thread holds unique: release it while the
  // worker winds down, so a worker blocked on it can see the stop flag. Nothing else can start or stop
  // the client meanwhile: the lifecycle lock is held.
  client.unlock();
  if (s_worker.joinable())
    s_worker.join();
  client.lock();

  s_es.es_event_set_notifier(s_client, nullptr, nullptr);
  s_es.es_client_destroy(s_client, timeout_ms);
  s_client = nullptr;

  {
    std::lock_guard lock(s_worker_lock);
    s_flush_pending.clear();
    s_resolve_pending.clear();
  }
  std::lock_guard lock(s_state_lock);
  for (Card& c : s_cards)
  {
    c.container = 0;
    c.session = 0;
  }
  // emusync reports the open ones again once a client is started.
  s_conflicts.clear();
  WipeSecret(s_config.secret_access_key);
  s_config = {};
  s_status.clear();
}

bool MemcardSync::Start(const Config& config, std::string* error)
{
  if (!IsAvailable())
  {
    if (error)
      *error = NO_LIBRARY;
    return false;
  }
  std::lock_guard lifecycle(s_lifecycle_lock);
  std::unique_lock client(s_client_lock);
  StopLocked(client, 2000);

  {
    std::lock_guard lock(s_state_lock);
    s_config = config;
    // Only needed below, once: not kept for the life of the client like the keys.
    WipeSecret(s_config.passphrase);
  }

  es_config cfg = ES_CONFIG_INIT;
  cfg.data_dir = config.data_dir.c_str();
  cfg.device_name = config.device_name.empty() ? nullptr : config.device_name.c_str();
  cfg.emulator_id = "goosestation";
  cfg.emulator_version = config.emulator_version.c_str();
  // The player chooses (GetConflicts(), ResolveConflict()); until then the card keeps this device's version.
  cfg.conflict_policy = ES_POLICY_ASK;
  cfg.log = &LogMessage;
  cfg.log_level = ES_LOG_INFO;
  es_status st = s_es.es_client_create(&cfg, &s_client);
  if (st != ES_OK)
  {
    if (error)
      *error = fmt::format("Could not open the sync data in {}: {}", config.data_dir, s_es.es_status_str(st));
    s_client = nullptr;
    // StopLocked() returns early without a client, so this is the only place the copied keys can go.
    std::lock_guard lock(s_state_lock);
    WipeSecret(s_config.secret_access_key);
    s_config = {};
    return false;
  }

  const bool encrypted = !config.passphrase.empty();
  // Applies to the es_remote_add below: create the storage encrypted, or unlock it.
  if (encrypted)
    st = s_es.es_repo_create_encrypted(s_client, config.passphrase.c_str());
  const es_credential_provider creds = {nullptr, &GetCredentials};
  if (st == ES_OK)
    st = s_es.es_remote_add(s_client, config.remote_uri.c_str(), &creds);
  // Locked: encrypted storage this device has no key for, and no passphrase was given. The remote is
  // added; sync runs and snapshots the cards, and uploads once it is unlocked.
  if (st != ES_OK && st != ES_OFFLINE && st != ES_E_LOCKED)
  {
    if (error)
    {
      if (st == ES_E_AUTH)
        *error = "The storage rejected the access key ID or secret.";
      else if (st == ES_E_PASSPHRASE)
        *error = "The passphrase does not unlock this storage.";
      else if (st == ES_E_INCOMPATIBLE && encrypted)
        *error = "This storage is not encrypted, or was set up by a newer version of emusync. "
                 "Remove the passphrase to use it, or choose a new folder for encrypted saves.";
      else if (st == ES_E_INCOMPATIBLE)
        *error = "This storage was set up by a newer version of emusync, or has changed since this device joined it.";
      else if (st == ES_E_INVALID_ARG)
        *error = "The storage address is not valid.";
      else
        *error = fmt::format("Could not use the storage: {}", s_es.es_status_str(st));
    }
    s_es.es_client_destroy(s_client, 0);
    s_client = nullptr;
    std::lock_guard lock(s_state_lock);
    WipeSecret(s_config.secret_access_key);
    s_config = {};
    return false;
  }
  SetStatus(st == ES_OFFLINE  ? "Storage not reachable yet: sync starts once it is." :
            st == ES_E_LOCKED ? std::string(LOCKED_STATUS) :
                                "Starting...");

  {
    std::lock_guard lock(s_worker_lock);
    s_worker_stop = false;
    s_worker_wake = false;
  }
  s_es.es_event_set_notifier(s_client, &OnEventsWaiting, nullptr);
  s_worker = std::thread(&WorkerLoop);

  // Cards already open are not followed (not even registered) until they are next opened. Beginning
  // a session applies what already waits for them, and a registered card outside a session is
  // written by every sync: either would change the file under the copy the emulator holds, and the
  // next save would write that copy back. A card still opening is followed by OnCardOpening() once
  // this start is done.
  size_t open_cards;
  {
    std::lock_guard lock(s_state_lock);
    open_cards = std::count_if(s_cards.begin(), s_cards.end(), [](const Card& c) { return !c.opening; });
  }
  if (open_cards > 0)
  {
    INFO_LOG("{} memory card(s) already in use sync from the next time they are opened.", open_cards);
    Host::AddIconOSDMessage("MemcardSync-InUse", ICON_FA_CLOUD,
                            TRANSLATE_STR("MemcardSync", "Memory cards in use sync from the next time they are opened."),
                            Host::OSD_INFO_DURATION);
  }
  s_es.es_sync_now(s_client);
  INFO_LOG("Syncing memory cards with {}", config.remote_uri);
  return true;
}

void MemcardSync::Stop(u32 timeout_ms)
{
  std::lock_guard lifecycle(s_lifecycle_lock);
  std::unique_lock client(s_client_lock);
  StopLocked(client, timeout_ms);
}

bool MemcardSync::IsRunning()
{
  std::shared_lock client(s_client_lock);
  return s_client != nullptr;
}

std::string MemcardSync::GetStatus()
{
  std::lock_guard lock(s_state_lock);
  return s_status;
}

void MemcardSync::SyncNow()
{
  std::shared_lock client(s_client_lock);
  if (s_client)
    s_es.es_sync_now(s_client);
}

std::vector<MemcardSync::Conflict> MemcardSync::GetConflicts()
{
  std::lock_guard lock(s_state_lock);
  return s_conflicts;
}

void MemcardSync::ResolveConflict(u64 id, bool keep_local, u32 remote_index)
{
  // Through the worker: emusync's calls take the client lock, which a start in progress holds for seconds.
  {
    std::lock_guard lock(s_worker_lock);
    s_resolve_pending.push_back({id, keep_local, remote_index});
  }
  WakeWorker();
}

// ---- Settings ------------------------------------------------------------------------------------

bool MemcardSync::StoreSecret(const char* name, const std::string& value, std::string* error)
{
  if (!IsAvailable())
  {
    if (error)
      *error = NO_LIBRARY;
    return false;
  }
  // Without a Secret Service (Linux), the secret goes to a file only this user can read here.
  const std::string& fallback = EmuFolders::DataRoot;
  const es_status st = value.empty() ? s_es.es_secret_delete(SECRET_SERVICE, name, fallback.c_str()) :
                                       s_es.es_secret_set(SECRET_SERVICE, name, value.c_str(), fallback.c_str());
  if (st != ES_OK)
  {
    if (error)
      *error = fmt::format("Could not store the secret in the system's credential store: {}", s_es.es_status_str(st));
    return false;
  }
  const std::string_view n = name;
  if (n == SECRET_ACCESS_KEY)
    Host::SetBaseBoolSettingValue(SECTION, "SecretStored", !value.empty());
  else if (n == ENCRYPTION_PASSPHRASE)
    Host::SetBaseBoolSettingValue(SECTION, "PassphraseStored", !value.empty());
  Host::CommitBaseSettingChanges();
  return true;
}

std::optional<std::string> MemcardSync::LoadSecret(const char* name, std::string* error)
{
  if (!IsAvailable())
  {
    if (error)
      *error = NO_LIBRARY;
    return std::nullopt;
  }
  std::string buf(SECRET_BUFFER, '\0');
  size_t len = 0;
  const std::string& fallback = EmuFolders::DataRoot;
  const es_status st = s_es.es_secret_get(SECRET_SERVICE, name, fallback.c_str(), buf.data(), buf.size(), &len);
  if (st == ES_E_NOT_FOUND)
  {
    WipeSecret(buf);
    return std::nullopt;
  }
  if (st != ES_OK)
  {
    WipeSecret(buf);
    if (error)
      *error = fmt::format("Could not read the secret from the system's credential store: {}", s_es.es_status_str(st));
    return std::nullopt;
  }
  std::string value(buf.c_str());
  WipeSecret(buf);
  return value;
}

std::string MemcardSync::GetLastStartError()
{
  std::lock_guard lock(s_state_lock);
  return s_last_start_error;
}

// Starts sync with the settings, or stops it when it is switched off or incomplete. Returns an empty
// string when sync runs or is deliberately off, else a sentence for the user.
static std::string StartFromSettings(const MemcardSync::ApplySettings& s)
{
  using namespace MemcardSync;
  Config c;
  c.remote_uri = std::string(StringUtil::StripWhitespace(s.remote_uri));
  c.access_key_id = std::string(StringUtil::StripWhitespace(s.access_key_id));
  c.device_name = std::string(StringUtil::StripWhitespace(s.device_name));
  if (!s.enabled || c.remote_uri.empty() || c.access_key_id.empty())
  {
    Stop(2000);
    return {};
  }

  std::string error;
  std::optional<std::string> secret = LoadSecret(SECRET_ACCESS_KEY, &error);
  if (!secret.has_value())
  {
    // No secret yet is "incomplete", not an error; a store that can't be read is.
    Stop(2000);
    return error;
  }
  std::optional<std::string> passphrase = LoadSecret(ENCRYPTION_PASSPHRASE, &error);
  if (!passphrase.has_value() && !error.empty())
  {
    WipeSecret(*secret);
    Stop(2000);
    return error;
  }

  c.secret_access_key = std::move(*secret);
  if (passphrase.has_value())
    c.passphrase = std::move(*passphrase);
  c.data_dir = DataDirFor(c.remote_uri);
  c.emulator_version = g_scm_tag_str;
  if (Error dir_error; !FileSystem::EnsureDirectoryExists(c.data_dir.c_str(), true, &dir_error))
  {
    WipeSecret(c.secret_access_key);
    WipeSecret(c.passphrase);
    return fmt::format("Could not create the sync data folder {}: {}", c.data_dir, dir_error.GetDescription());
  }
  const bool started = Start(c, &error);
  WipeSecret(c.secret_access_key);
  WipeSecret(c.passphrase);
  return started ? std::string() : error;
}

static void ApplyLoop()
{
  using namespace MemcardSync;
  Threading::SetNameOfCurrentThread("MemcardSyncApply");
  for (;;)
  {
    std::vector<SecretUpdate> secrets;
    std::vector<std::function<void(const std::string&)>> done;
    ApplySettings settings;
    {
      std::unique_lock lock(s_apply_lock);
      s_apply_cv.wait(lock, [] { return s_apply_pending || s_apply_quit; });
      if (!s_apply_pending)
        return;
      s_apply_pending = false;
      s_apply_running = true;
      secrets = std::exchange(s_apply_secrets, {});
      done = std::exchange(s_apply_done, {});
      settings = s_apply_settings;
    }

    std::string error;
    for (SecretUpdate& u : secrets)
    {
      std::string e;
      if (!StoreSecret(u.name, u.value, &e) && error.empty())
        error = std::move(e);
      WipeSecret(u.value);
    }
    if (error.empty())
      error = StartFromSettings(settings);
    {
      std::lock_guard lock(s_state_lock);
      s_last_start_error = error;
    }
    if (!error.empty())
      WARNING_LOG("Not syncing: {}", error);
    for (const auto& cb : done)
      cb(error);
    {
      std::lock_guard lock(s_apply_lock);
      s_apply_running = false;
    }
    s_apply_idle_cv.notify_all();
  }
}

// Waits, up to OPEN_WAITS_FOR_START, for a start or stop queued or in progress: at startup,
// Initialize() queues one, and a game launched at once (from the command line) must not open its
// cards with no client and miss the saves from other devices.
static void WaitForPendingStart()
{
  using namespace MemcardSync;
  std::unique_lock lock(s_apply_lock);
  if (!s_apply_idle_cv.wait_for(lock, OPEN_WAITS_FOR_START,
                                [] { return s_apply_quit || (!s_apply_pending && !s_apply_running); }))
  {
    WARNING_LOG("Still starting; the memory card opens as it is.");
  }
}

void MemcardSync::ApplySettingsAsync(std::vector<SecretUpdate> secrets,
                                     std::function<void(const std::string&)> done)
{
  ApplySettings settings;
  settings.enabled = Host::GetBaseBoolSettingValue(SECTION, "Enabled", false);
  settings.remote_uri = Host::GetBaseStringSettingValue(SECTION, "RemoteURI", "");
  settings.access_key_id = Host::GetBaseStringSettingValue(SECTION, "AccessKeyID", "");
  settings.device_name = Host::GetBaseStringSettingValue(SECTION, "DeviceName", "");
  std::lock_guard lock(s_apply_lock);
  if (s_apply_quit)
    return;
  s_apply_settings = std::move(settings);
  for (SecretUpdate& u : secrets)
    s_apply_secrets.push_back(std::move(u));
  if (done)
    s_apply_done.push_back(std::move(done));
  s_apply_pending = true;
  if (!s_apply_thread.joinable())
    s_apply_thread = std::thread(&ApplyLoop);
  s_apply_cv.notify_one();
}

bool MemcardSync::IsApplying()
{
  std::lock_guard lock(s_apply_lock);
  return s_apply_pending || s_apply_running;
}

void MemcardSync::Initialize()
{
  if (Host::GetBaseBoolSettingValue(SECTION, "Enabled", false))
    ApplySettingsAsync();
}

void MemcardSync::Shutdown()
{
  {
    std::lock_guard lock(s_apply_lock);
    s_apply_quit = true;
  }
  s_apply_cv.notify_one();
  s_apply_idle_cv.notify_all();
  // A start in progress finishes first (it can wait up to 10 s on the network).
  if (s_apply_thread.joinable())
    s_apply_thread.join();
  Stop(EXIT_UPLOAD_MS);
}

// ---- Card hooks ----------------------------------------------------------------------------------

void MemcardSync::OnCardOpening(std::string_view path)
{
  if (path.empty())
    return;
  {
    std::lock_guard lock(s_state_lock);
    // A card opened twice without closing (shouldn't happen) is followed once.
    if (std::none_of(s_cards.begin(), s_cards.end(), [&](const Card& c) { return c.path == path; }))
      s_cards.push_back(Card{std::string(path), System::GetGameSerial(), System::GetGameTitle(), 0, 0,
                             std::chrono::steady_clock::now(), true});
  }
  // Once this returns, the emulator reads the card: from then on it counts as loaded.
  const ScopedGuard loaded([path]() {
    std::lock_guard lock(s_state_lock);
    for (Card& c : s_cards)
    {
      if (c.path == path)
        c.opening = false;
    }
  });
  if (!Host::GetBaseBoolSettingValue(SECTION, "Enabled", false))
    return;
  WaitForPendingStart();
  std::shared_lock client(s_client_lock);
  if (!s_client)
    return;
  FollowCard(std::string(path), System::GetGameSerial(), System::GetGameTitle(), OPEN_SYNC_TIMEOUT_MS);
}

void MemcardSync::OnCardWritten(std::string_view path)
{
  es_container_id container = 0;
  {
    std::lock_guard lock(s_state_lock);
    for (const Card& c : s_cards)
    {
      if (c.path == path)
        container = c.container;
    }
  }
  // Sync is off, or the card isn't registered.
  if (container == 0)
    return;
  {
    std::lock_guard lock(s_worker_lock);
    if (std::find(s_flush_pending.begin(), s_flush_pending.end(), container) == s_flush_pending.end())
      s_flush_pending.push_back(container);
  }
  WakeWorker();
}

void MemcardSync::OnCardClosed(std::string_view path)
{
  Card card;
  {
    std::lock_guard lock(s_state_lock);
    const auto it = std::find_if(s_cards.begin(), s_cards.end(), [&](const Card& c) { return c.path == path; });
    if (it == s_cards.end())
      return;
    card = std::move(*it);
    s_cards.erase(it);
  }
  std::shared_lock client(s_client_lock);
  if (!s_client)
    return;
  // An upload already queued by the card's last write is taken first; the session's end also
  // snapshots the card once more, then applies what waited.
  EndSession(card);
}
