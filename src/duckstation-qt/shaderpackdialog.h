// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#pragma once

#include "qtprogresscallback.h"

#include "core/shader_packs.h"

#include <QtWidgets/QDialog>

#include <map>
#include <memory>
#include <optional>
#include <string>
#include <vector>

class QLabel;
class QProgressBar;
class QPushButton;
class QTableWidget;

/// Downloads, updates and removes the shader packs, adapted from the PCSX2 fork's ShaderPackDownloadDialog. Network and
/// file work run on a worker thread; versions are checked when it opens.
class ShaderPackDialog final : public QDialog
{
  Q_OBJECT

public:
  explicit ShaderPackDialog(QWidget* parent);
  ~ShaderPackDialog() override;

  /// Every way out (a button, Escape, the window's close button) comes through here, so the worker is stopped and
  /// joined before the dialog can go.
  void done(int r) override;

private:
  enum class Mode
  {
    Check,
    Install,
    Remove,
  };

  struct Check
  {
    std::optional<ShaderPacks::ResolvedVersion> version;
    std::string error;
  };

  class Worker final : public QtAsyncProgressThread
  {
  public:
    Worker(QWidget* parent, Mode mode, std::vector<std::string> ids);
    ~Worker() override;

    Mode mode() const { return m_mode; }
    const std::map<std::string, Check>& checks() const { return m_checks; }
    const std::vector<ShaderPacks::InstallResult>& results() const { return m_results; }

  protected:
    void runAsync() override;

  private:
    Mode m_mode;
    std::vector<std::string> m_ids;
    std::map<std::string, Check> m_checks;
    std::vector<ShaderPacks::InstallResult> m_results;
  };

  void populateTable();
  void refreshStatuses();
  std::vector<std::string> checkedIds(bool installed_only) const;
  void startWorker(Mode mode, std::vector<std::string> ids);
  void cancelWorker();
  void updateEnabled();
  void onWorkerFinished();
  void onInstallClicked();
  void onRemoveClicked();

  QTableWidget* m_packs;
  QLabel* m_status;
  QProgressBar* m_progress;
  QPushButton* m_install;
  QPushButton* m_remove;
  QPushButton* m_close;

  std::unique_ptr<Worker> m_worker;
  std::map<std::string, Check> m_checks;
};
