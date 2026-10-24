// SPDX-FileCopyrightText: 2019-2022 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once
#include "core/types.h"

#include <QtCore/QTimer>
#include <QtWidgets/QCheckBox>
#include <QtWidgets/QComboBox>
#include <QtWidgets/QGroupBox>
#include <QtWidgets/QLabel>
#include <QtWidgets/QLineEdit>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QVBoxLayout>
#include <QtWidgets/QWidget>
#include <array>
#include <string>
#include <utility>
#include <vector>

class SettingsWindow;

/// Memory card sync (core/memcard_sync.h), on the Memory Cards page. Global only: the storage and keys
/// belong to the computer, not to a game. Every change is stored at once and sync is restarted with it
/// on a background thread, since starting checks the storage over the network.
class MemoryCardSyncWidget : public QGroupBox
{
  Q_OBJECT

public:
  MemoryCardSyncWidget(SettingsWindow* dialog, QWidget* parent);
  ~MemoryCardSyncWidget();

protected:
  void showEvent(QShowEvent* event) override;
  void hideEvent(QHideEvent* event) override;

private:
  void onEnabledChanged(bool enabled);
  void onTextSettingEdited(QLineEdit* edit, const char* key);
  void onSecretEdited();
  void onPassphraseEdited();
  /// A Show button for the field of `secret` (MemcardSync::SECRET_ACCESS_KEY or ENCRYPTION_PASSPHRASE).
  void onShowToggled(QLineEdit* edit, QPushButton* show_button, const char* secret, bool shown);
  void onPassphraseRemoveClicked();
  void onSyncNowClicked();
  /// The conflict screen: each save changed both here and on another device, and which version to keep.
  void onResolveConflictsClicked();

  /// Restarts sync with the stored settings, storing `secrets` first (off the UI thread).
  void apply(std::vector<std::pair<const char*, std::string>> secrets = {});
  /// An apply() is done.
  void onApplied();
  void updateEnabledState();
  void updateSecretPlaceholders();
  void updateStatus();

  QCheckBox* m_enabled = nullptr;
  QLineEdit* m_remote_uri = nullptr;
  QLineEdit* m_access_key_id = nullptr;
  QLineEdit* m_secret = nullptr;
  QPushButton* m_secret_show = nullptr;
  QLineEdit* m_passphrase = nullptr;
  QPushButton* m_passphrase_show = nullptr;
  QPushButton* m_passphrase_remove = nullptr;
  QLineEdit* m_device_name = nullptr;
  QLabel* m_status = nullptr;
  QPushButton* m_sync_now = nullptr;
  QLabel* m_conflicts = nullptr;
  QPushButton* m_resolve_conflicts = nullptr;
  QTimer m_status_timer;
  /// Settings changes still being applied: each apply() until its callback.
  int m_pending_applies = 0;
};

class MemoryCardSettingsWidget : public QWidget
{
  Q_OBJECT

public:
  MemoryCardSettingsWidget(SettingsWindow* dialog, QWidget* parent);
  ~MemoryCardSettingsWidget();

private:
  SettingsWindow* m_dialog;

  struct PortSettingsUI
  {
    QGroupBox* container;
    QVBoxLayout* layout;
    QComboBox* memory_card_type;
    QLineEdit* memory_card_path;
  };

  void createUi(SettingsWindow* dialog);
  void createPortSettingsUi(SettingsWindow* dialog, int index, PortSettingsUI* ui);
  void onBrowseMemoryCardPathClicked(int index);
  void onResetMemoryCardPathClicked(int index);
  void onMemoryCardPathChanged(int index);
  void updateMemoryCardPath(int index);

  std::array<PortSettingsUI, 2> m_port_ui = {};
  QLineEdit* m_memory_card_directory = nullptr;
};
