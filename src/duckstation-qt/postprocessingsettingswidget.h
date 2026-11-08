// SPDX-FileCopyrightText: 2019-2024 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#pragma once

#include "ui_postprocessingchainconfigwidget.h"

#include "util/postprocessing.h"

#include "common/memory_settings_interface.h"

#include <QtWidgets/QTableWidget>
#include <QtWidgets/QWidget>

class SettingsWindow;
class PostProcessingShaderConfigWidget;

class PostProcessingSettingsWidget : public QTabWidget
{
  Q_OBJECT

public:
  PostProcessingSettingsWidget(SettingsWindow* dialog, QWidget* parent);
  ~PostProcessingSettingsWidget();
};

class PostProcessingChainConfigWidget : public QWidget
{
  Q_OBJECT

  friend PostProcessingShaderConfigWidget;

public:
  PostProcessingChainConfigWidget(SettingsWindow* dialog, QWidget* parent, const char* section);
  ~PostProcessingChainConfigWidget();

protected:
  void changeEvent(QEvent* event) override;

private Q_SLOTS:
  void onAddButtonClicked();
  void onRemoveButtonClicked();
  void onClearButtonClicked();
  void onMoveUpButtonClicked();
  void onMoveDownButtonClicked();
  void onReloadButtonClicked();
  void onSelectedShaderChanged();

private:
  SettingsInterface& getSettingsInterfaceToUpdate();
  void commitSettingsUpdate();

  void connectUi();
  void updateButtonsAndConfigPane(std::optional<u32> index);
  std::optional<u32> getSelectedIndex() const;
  void selectIndex(s32 index);
  void updateList(const SettingsInterface& si);
  void updateList();

  /// The display chain as settings.ini has it now, shown again if it isn't what the list shows (a favourite hotkey
  /// changed it while the window was in the background).
  void refreshIfChanged();

  SettingsWindow* m_dialog;

  Ui::PostProcessingChainConfigWidget m_ui;

  const char* m_section;

  PostProcessingShaderConfigWidget* m_shader_config = nullptr;

  /// The chain the list shows, and whether it's on, as last read.
  MemorySettingsInterface m_shown_chain;
  bool m_shown_enabled = false;
};

class PostProcessingShaderConfigWidget : public QWidget
{
  Q_OBJECT

public:
  PostProcessingShaderConfigWidget(QWidget* parent, PostProcessingChainConfigWidget* widget, const char* section,
                                   u32 stage_index, std::vector<PostProcessing::ShaderOption> options);
  ~PostProcessingShaderConfigWidget();

private Q_SLOTS:
  void onResetDefaultsClicked();

protected:
  void createUi();
  void updateConfigForOption(const PostProcessing::ShaderOption& option);

  QGridLayout* m_layout;

  PostProcessingChainConfigWidget* m_widget;
  std::vector<QWidget*> m_widgets;

  const char* m_section;
  u32 m_stage_index;
  std::vector<PostProcessing::ShaderOption> m_options;
};
