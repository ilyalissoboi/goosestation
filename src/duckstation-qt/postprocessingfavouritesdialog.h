// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/postprocessing_favourites.h"

#include "common/types.h"

#include <QtWidgets/QDialog>

#include <functional>
#include <vector>

class QLabel;
class QListWidget;
class QPushButton;

class SettingsInterface;

/// The post-processing favourites (saved display chains), for a settings window's display chain: save it as one, apply
/// one to it, rename, reorder and delete them. With game_settings (a game's window), it saves the chain the game runs
/// (its own, or the global one) and applies into the game's own chain; else settings.ini's. on_applied runs after an
/// apply, to commit the window's settings and show its chain again.
class PostProcessingFavouritesDialog final : public QDialog
{
  Q_OBJECT

public:
  PostProcessingFavouritesDialog(QWidget* parent, SettingsInterface* game_settings, std::function<void()> on_applied);
  ~PostProcessingFavouritesDialog() override;

private:
  void refresh(const QString& select = QString());
  void updateButtons();
  QString selectedName() const;
  int selectedRow() const;

  void onApplyClicked();
  void onSaveClicked();
  void onRenameClicked();
  void onMoveClicked(bool down);
  void onDeleteClicked();

  /// The number of stages in the chain the window's settings run.
  u32 runningStageCount() const;

  void showError(const QString& message);

  SettingsInterface* m_game_settings;
  std::function<void()> m_on_applied;
  std::vector<PostProcessing::Favourites::Info> m_favourites;

  QListWidget* m_list;
  QLabel* m_empty;
  QPushButton* m_apply;
  QPushButton* m_save;
  QPushButton* m_rename;
  QPushButton* m_move_up;
  QPushButton* m_move_down;
  QPushButton* m_delete;
};
