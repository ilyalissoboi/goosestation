// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "common/types.h"

#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

// Memory card sync through the cloud, with emusync (https://github.com/ilyalissoboi/emusync).
//
// The memory cards in use are synced, one save at a time, with every other device using the same
// storage: GooseStation on other computers, and anything else that syncs PS1 cards with emusync
// (`emusync watch`, other emulators). A save made on one device shows up on the others; two devices
// playing different games never conflict. When the same save was changed on two devices, the card
// here is left as it is and the player chooses which version to keep (GetConflicts(),
// ResolveConflict()); the other stays in emusync's history.
//
// Each card file is a logical card named after the file (`shared_card_1.mcd` is card
// `shared_card_1`, a per-game card `Final Fantasy VII (USA)_1.mcd` is `Final_Fantasy_VII_(USA)_1`),
// so a card syncs with the card of the same name on the other devices.
//
// When the cards are read and written (the hooks below, called from MemoryCard):
//   Opening: OnCardOpening(), before the card file is read. Newer saves from other devices are
//   written to it first, waiting at most 3 s for the network; offline, the card opens as it is. A
//   card that doesn't exist yet is created if saves arrive for it. From then until OnCardClosed(),
//   emusync never writes to the file: the emulator keeps the card in memory.
//   Writes: OnCardWritten() after the emulator has written the card file uploads the change.
//   Snapshots go to an on-disk queue first, so a save is not lost if the program exits before the
//   upload.
//   Closing: OnCardClosed(), after the card's last write, uploads and applies what arrived meanwhile.
// A card already open when sync starts (sync turned on, or its settings changed, during a game) is
// left alone until it is next opened: emusync can only start following a card before it is read.
//
// Settings: [MemoryCardSync] in settings.ini (Enabled, RemoteURI, AccessKeyID, DeviceName), with the
// secret access key and the optional encryption passphrase in the system's credential store
// (Credential Manager, Keychain, Secret Service) through emusync; never in the INI.
//
// The emusync library is loaded at run time: a build or a system without it has no sync,
// IsAvailable() is false and starting fails, saying why.
//
// Threading: everything is thread-safe. Start() and OnCardOpening() block (local database open, and
// the network as described); every other hook returns quickly.
namespace MemcardSync {

/// Whether the emusync library could be loaded.
bool IsAvailable();

struct Config
{
  /// Private folder for emusync's database and queue. Bound to one storage for good, so use one per
  /// remote_uri (DataDirFor()).
  std::string data_dir;
  /// Shown on other devices, e.g. in a save's history. Empty: the computer's name.
  std::string device_name;
  std::string emulator_version;
  /// The storage, e.g. "s3://my-saves/emusync/v1?region=eu-west-1".
  std::string remote_uri;
  std::string access_key_id;
  std::string secret_access_key;
  /// Optional: the storage's end-to-end encryption passphrase. Set, it creates the storage
  /// encrypted if it is new, or unlocks it if it already is; storage that is not encrypted is then
  /// refused. Empty, unencrypted storage is used as it is, and encrypted storage this device has
  /// already unlocked keeps working (emusync keeps its key in data_dir).
  std::string passphrase;
};

/// Starts syncing, replacing a client that is already running. Blocks while the local database
/// opens and for up to 10 s while the storage is checked. Returns false with `error` set when sync
/// cannot start. Storage that cannot be reached right now is not an error: sync starts working once
/// it can be reached. Does not keep config.passphrase.
bool Start(const Config& config, std::string* error);

/// Stops syncing. Cards are left as they are. Waits up to `timeout_ms` for queued uploads.
void Stop(u32 timeout_ms);

bool IsRunning();

/// One line about the last sync, for the settings page.
std::string GetStatus();

/// Queues a full sync: download, apply to the cards not in use, upload. The status says "Syncing..." until it ends.
void SyncNow();

// ---- Conflicts ------------------------------------------------------------------------------------

/// One device's version of a save in a conflict.
struct ConflictVersion
{
  /// The device that made it.
  std::string device;
  s64 modified_unix_ms = 0;
  u64 size = 0;
  /// The save was deleted there.
  bool deleted = false;
};

/// A save changed both on this device and on another since they last synced. The card here keeps
/// this device's version until the player chooses; the version not chosen stays in emusync's history.
struct Conflict
{
  u64 id = 0;
  /// The logical card (CardNameFor()).
  std::string card;
  /// The save's name on the card, e.g. "BASLUS-00067DRACULA".
  std::string save;
  /// The game's serial, e.g. "SLUS-00067", if the save's name has one.
  std::string serial;
  /// This device's version, if the save is on this device's card.
  std::optional<ConflictVersion> local;
  /// The other devices' versions: usually one.
  std::vector<ConflictVersion> remote;
};

/// The conflicts waiting for the player, oldest first. Ones resolved on another device drop out after
/// the next sync.
std::vector<Conflict> GetConflicts();

/// Keeps this device's version of a conflict's save, or the version remote[remote_index]. Returns at
/// once: the choice is uploaded, and written to the card as soon as the card isn't in use.
void ResolveConflict(u64 id, bool keep_local, u32 remote_index = 0);

// ---- Settings (settings.ini and the system's credential store) ----------------------------------

/// The settings section.
inline constexpr const char* SECTION = "MemoryCardSync";

/// The emusync data folder for `remote_uri`: <data folder>/emusync/<hash of the address>.
std::string DataDirFor(std::string_view remote_uri);

/// Names of the secrets in the system's credential store (service "GooseStation").
inline constexpr const char* SECRET_ACCESS_KEY = "s3-secret-access-key";
inline constexpr const char* ENCRYPTION_PASSPHRASE = "encryption-passphrase";

/// Stores a secret in the system's credential store; an empty value deletes it. Records in
/// settings.ini whether one is stored (so the settings page can say so without asking the store).
/// May block while the system asks to unlock the keychain: not on the UI thread.
bool StoreSecret(const char* name, const std::string& value, std::string* error);

/// The stored secret, if any. May block like StoreSecret().
std::optional<std::string> LoadSecret(const char* name, std::string* error);

/// Whether a secret is recorded as stored (from settings.ini; does not ask the store).
bool HasSecret(const char* name);

/// Applies the settings on a background thread: optionally stores new secrets first, then starts
/// sync with the settings, or stops it when it is switched off or incomplete. Requests made while one
/// runs are folded into one more run with the latest settings. `done`, if set, is called on that
/// thread with an empty string when sync runs or is deliberately off, else a sentence for the user.
struct SecretUpdate
{
  const char* name;
  std::string value;
};
void ApplySettingsAsync(std::vector<SecretUpdate> secrets = {},
                        std::function<void(const std::string& error)> done = {});

/// Whether settings are still being applied: an ApplySettingsAsync() queued or running.
bool IsApplying();

/// The error of the last ApplySettingsAsync() run, empty if it started (or stopped) cleanly.
std::string GetLastStartError();

/// Starts sync from the settings at startup, and stops it at exit, giving queued uploads a few
/// seconds.
void Initialize();
void Shutdown();

/// Overwrites a secret's characters, then empties the string and frees its buffer.
void WipeSecret(std::string& secret);

// ---- Card hooks (MemoryCard) --------------------------------------------------------------------

/// The emulator is about to read the card file at `path` (which may not exist yet).
void OnCardOpening(std::string_view path);

/// The emulator wrote the card file at `path`. Queues the upload and returns at once.
void OnCardWritten(std::string_view path);

/// The emulator closed the card at `path`, after its last write.
void OnCardClosed(std::string_view path);

/// The logical card name of a card file: its name without the extension, with spaces, ':' and '/'
/// made '_' (emusync's card names have none).
std::string CardNameFor(std::string_view path);

} // namespace MemcardSync

#ifdef __ANDROID__
// ---- Android: the host keeps the secrets ---------------------------------------------------------
//
// emusync's es_secret_* return ES_E_INVALID_ARG on Android, whose apps keep secrets in the Android Keystore
// themselves: StoreSecret(), LoadSecret() and HasSecret() ask the host instead, which asks the app. The ini's
// SecretStored and PassphraseStored are still written, as on desktop, but not read: a settings.ini copied from
// desktop says its own keychain has them.
namespace Host {
/// Keeps `value` as the secret `name`, or deletes it if `value` is empty. False if it couldn't.
bool StoreMemcardSyncSecret(const char* name, std::string_view value);
/// The secret `name`, or nullopt for none (or one that can't be read any more).
std::optional<std::string> LoadMemcardSyncSecret(const char* name);
/// Whether the secret `name` is stored, without reading it.
bool HasMemcardSyncSecret(const char* name);
/// This device's name, for a DeviceName left empty ("AYN Thor"): Android's host name is "localhost".
std::string GetMemcardSyncDefaultDeviceName();
/// What GetActivity(), the status, the conflicts or whether settings are being applied say may have changed. From
/// sync's threads, maybe with its locks held: it must not call into sync, only note that the app should look again.
void OnMemcardSyncChanged();
} // namespace Host

// ---- Android: what sync is doing, for the app's badge -------------------------------------------------
namespace MemcardSync {

/// What sync last did, from emusync's events and the start's result.
enum class Activity : u8
{
  None,         ///< Not running, or nothing reported yet.
  Syncing,      ///< A sync, or a start, is under way.
  Synced,       ///< The last sync finished.
  Offline,      ///< The storage couldn't be reached.
  Failed,       ///< The last sync, or the start, failed.
  Locked,       ///< The storage is encrypted and this device has no key for it, or the passphrase is wrong.
  AuthRequired, ///< The storage rejected the access key.
};

Activity GetActivity();

/// The app went to the background, or came back. In the background, sync pushes what it has (es_app_suspend(), for
/// up to 3 s on its own thread, after the card writes already handed to it), then does nothing: no periodic sync, and
/// a start skips its first sync. Back in front, it syncs at once (es_app_resume()) and every 5 minutes again. Without
/// a client, the state is only noted.
void SetAppBackgrounded(bool backgrounded);

/// Tests: syncs every `ms` instead of every 5 minutes (0 for 5 minutes again), from now.
void SetPeriodicSyncForTest(u32 ms);

/// Whether emusync's repository knows the card file at `path` (its logical card, named as RegisterCard() names it): a
/// game used it on this device, or it came from another. Without a client, false. Doesn't wait.
bool IsCardKnown(std::string_view path);

/// Runs `action` with emusync kept off the card file at `path`, for the app's mirror replacing or deleting it: no sync
/// starts or stops meanwhile, and a running client holds a session on the card, as a game opening it does, so emusync
/// doesn't write it (a write of its in progress finishes first). Returns false without running `action` when the card
/// is open in a game, or a session can't begin on it: the caller leaves the card for later.
bool WithCardHeld(std::string_view path, const std::function<void()>& action);

} // namespace MemcardSync
#endif
