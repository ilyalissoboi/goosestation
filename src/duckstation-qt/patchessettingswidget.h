// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "core/patches.h"

#include <QtWidgets/QWidget>

#include <memory>
#include <string>

class QLabel;
class QPushButton;
class QTreeWidget;
class QTreeWidgetItem;

class SettingsWindow;

/// Game properties' Patches page: the game's widescreen, frame-rate and display-mode patches, whatever "Enable
/// Cheats" says. A plain patch is a check box, a patch with choices a list (Off and its choices), and a code with
/// values a list or a number beside it. Switches go into the game's settings, which the running game follows; a
/// patch that can't be undone at once asks to reset now or apply at the next start. Off in hardcore mode.
class PatchesSettingsWidget final : public QWidget
{
  Q_OBJECT

public:
  PatchesSettingsWidget(SettingsWindow* dialog, QWidget* parent, std::string serial, std::string title);
  ~PatchesSettingsWidget() override;

private Q_SLOTS:
  void onImportClicked();

private:
  /// The running game's list when it's this game (its indices are the running game's), else one built for it.
  void reload();
  void fill();
  void setChoice(size_t index, s32 choice);
  void setValue(size_t index, s32 choice, u32 value);
  void save();
  QString noteFor(const Patches::Patch& patch) const;

  SettingsWindow* m_dialog;
  std::string m_serial;
  std::string m_title;
  std::unique_ptr<Patches::PatchList> m_list;
  bool m_running = false;  // the list is the running game's
  bool m_hardcore = false; // the running game is in hardcore mode

  QLabel* m_status;
  QTreeWidget* m_tree;
  QPushButton* m_import;
};
