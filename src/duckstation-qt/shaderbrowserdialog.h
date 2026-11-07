// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "util/postprocessing.h"

#include <QtWidgets/QDialog>

#include <optional>
#include <string>
#include <vector>

class QDialogButtonBox;
class QLabel;
class QLineEdit;
class QModelIndex;
class QSortFilterProxyModel;
class QStandardItem;
class QStandardItemModel;
class QTreeView;

/// Adding a post-processing stage: a tree of every shader and preset by folder, with a search box filtering by path,
/// opening on the last folder used. Adapted from the PCSX2 fork's ShaderPresetPickerDialog.
class ShaderBrowserDialog final : public QDialog
{
  Q_OBJECT

public:
  ShaderBrowserDialog(QWidget* parent, std::vector<PostProcessing::AvailableShader> shaders,
                      const std::string& last_folder);
  ~ShaderBrowserDialog() override;

  /// The chosen shader's stage name, once accepted.
  std::optional<std::string> selectedShader() const;

  /// The folder of the chosen shader, for opening on next time.
  std::string selectedFolder() const;

private:
  void buildModel();
  QStandardItem* findFolder(const QString& folder) const;
  void openFolder(const std::string& folder);
  void onFilterChanged(const QString& text);
  void onCurrentChanged(const QModelIndex& current);
  void onActivated(const QModelIndex& index);

  std::vector<PostProcessing::AvailableShader> m_shaders;
  std::optional<size_t> m_selected;
  std::string m_open_folder;

  QLineEdit* m_filter;
  QTreeView* m_tree;
  QLabel* m_selection;
  QDialogButtonBox* m_buttons;
  QStandardItemModel* m_model;
  QSortFilterProxyModel* m_proxy;
};
