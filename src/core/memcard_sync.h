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
// playing different games never conflict, and when the same save was changed on two devices the
// newest version wins and the other stays in emusync's history.
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

/// Queues a full sync: download, apply to the cards not in use, upload.
void SyncNow();

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
