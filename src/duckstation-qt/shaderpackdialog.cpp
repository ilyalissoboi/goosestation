// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shaderpackdialog.h"

#include "core/settings.h"

#include "common/assert.h"
#include "common/error.h"

#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QHeaderView>
#include <QtWidgets/QLabel>
#include <QtWidgets/QMessageBox>
#include <QtWidgets/QProgressBar>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QTableWidget>
#include <QtWidgets/QVBoxLayout>

#include <algorithm>

namespace {

enum Column : int
{
  COLUMN_PACK = 0,
  COLUMN_DESCRIPTION,
  COLUMN_LICENCE,
  COLUMN_SIZE,
  COLUMN_STATUS,
  COLUMN_COUNT,
};

const std::string& Root()
{
  return EmuFolders::GetShaderPacksFolder();
}

QString PackName(std::string_view id)
{
  const ShaderPacks::PackInfo* pack = ShaderPacks::FindPack(id);
  return pack ? QString::fromUtf8(pack->name) : QString::fromUtf8(id.data(), static_cast<qsizetype>(id.size()));
}

QString FormatSize(u64 bytes)
{
  const std::string size = ShaderPacks::FormatSize(bytes);
  return size.empty() ? QStringLiteral("—") : QString::fromStdString(size);
}

} // namespace

ShaderPackDialog::ShaderPackDialog(QWidget* parent) : QDialog(parent)
{
  setWindowTitle(tr("Download Shader Packs"));
  resize(900, 360);

  QVBoxLayout* layout = new QVBoxLayout(this);
  m_packs = new QTableWidget(this);
  m_packs->setColumnCount(COLUMN_COUNT);
  m_packs->setHorizontalHeaderLabels({tr("Pack"), tr("Description"), tr("Licence"), tr("Size"), tr("Status")});
  m_packs->verticalHeader()->hide();
  m_packs->setSelectionMode(QAbstractItemView::NoSelection);
  m_packs->setEditTriggers(QAbstractItemView::NoEditTriggers);
  m_packs->setWordWrap(true);
  layout->addWidget(m_packs, 1);

  QLabel* note = new QLabel(tr("Third-party content under its own licences. Retro Crisis needs the libretro slang "
                               "shaders, and installs them first."),
                            this);
  note->setWordWrap(true);
  layout->addWidget(note);

  m_status = new QLabel(this);
  m_status->setWordWrap(true);
  layout->addWidget(m_status);
  m_progress = new QProgressBar(this);
  m_progress->setVisible(false);
  layout->addWidget(m_progress);

  QHBoxLayout* buttons = new QHBoxLayout();
  buttons->addStretch(1);
  m_install = new QPushButton(tr("Install"), this);
  m_install->setDefault(true);
  m_remove = new QPushButton(tr("Remove"), this);
  m_close = new QPushButton(tr("Close"), this);
  buttons->addWidget(m_install);
  buttons->addWidget(m_remove);
  buttons->addWidget(m_close);
  layout->addLayout(buttons);

  connect(m_install, &QPushButton::clicked, this, &ShaderPackDialog::onInstallClicked);
  connect(m_remove, &QPushButton::clicked, this, &ShaderPackDialog::onRemoveClicked);
  connect(m_close, &QPushButton::clicked, this, [this]() { done(0); });

  populateTable();
  refreshStatuses();

  std::vector<std::string> ids;
  for (const ShaderPacks::PackInfo& pack : ShaderPacks::GetPacks())
    ids.emplace_back(pack.id);
  m_status->setText(tr("Checking for the latest versions…"));
  startWorker(Mode::Check, std::move(ids));
}

ShaderPackDialog::~ShaderPackDialog()
{
  DebugAssert(!m_worker);
}

void ShaderPackDialog::done(int r)
{
  cancelWorker();
  QDialog::done(r);
}

void ShaderPackDialog::populateTable()
{
  const auto packs = ShaderPacks::GetPacks();
  m_packs->setRowCount(static_cast<int>(packs.size()));
  int row = 0;
  for (const ShaderPacks::PackInfo& pack : packs)
  {
    QTableWidgetItem* name = new QTableWidgetItem(QString::fromUtf8(pack.name));
    name->setFlags(Qt::ItemIsEnabled | Qt::ItemIsUserCheckable);
    name->setCheckState(Qt::Unchecked);
    name->setData(Qt::UserRole, QString::fromUtf8(pack.id));
    m_packs->setItem(row, COLUMN_PACK, name);
    m_packs->setItem(row, COLUMN_DESCRIPTION, new QTableWidgetItem(QString::fromUtf8(pack.description)));
    m_packs->setItem(row, COLUMN_LICENCE, new QTableWidgetItem(QString::fromUtf8(pack.licence)));
    m_packs->setItem(row, COLUMN_SIZE, new QTableWidgetItem());
    m_packs->setItem(row, COLUMN_STATUS, new QTableWidgetItem());
    row++;
  }
  m_packs->horizontalHeader()->setSectionResizeMode(COLUMN_DESCRIPTION, QHeaderView::Stretch);
  m_packs->resizeColumnToContents(COLUMN_PACK);
  m_packs->resizeColumnToContents(COLUMN_LICENCE);
}

void ShaderPackDialog::refreshStatuses()
{
  for (int row = 0; row < m_packs->rowCount(); row++)
  {
    QTableWidgetItem* name = m_packs->item(row, COLUMN_PACK);
    const std::string id = name->data(Qt::UserRole).toString().toStdString();
    const std::optional<ShaderPacks::InstalledPack> installed = ShaderPacks::ReadMarker(Root(), id);
    const auto check = m_checks.find(id);
    const std::optional<ShaderPacks::ResolvedVersion> latest =
      (check != m_checks.end()) ? check->second.version : std::nullopt;
    const ShaderPacks::PackStatus status = ShaderPacks::GetPackStatus(installed, latest);

    QString text;
    if (check == m_checks.end() && status.state != ShaderPacks::PackState::Incomplete)
      text = installed.has_value() ? tr("Installed %1 (checking…)").arg(QString::fromStdString(installed->version)) :
                                     tr("Checking…");
    else if (!latest.has_value() && !installed.has_value() && check != m_checks.end())
      text = QString::fromStdString(check->second.error);
    else
      text = QString::fromStdString(ShaderPacks::DescribePackStatus(status));
    m_packs->item(row, COLUMN_STATUS)->setText(text);

    const u64 size = installed.has_value() && installed->archive_size ? installed->archive_size :
                     latest.has_value()                                ? latest->size :
                                                                         0;
    m_packs->item(row, COLUMN_SIZE)->setText(FormatSize(size));

    // Packs to install or update start ticked, once their check is in.
    if (check != m_checks.end() || status.state == ShaderPacks::PackState::Incomplete)
    {
      const bool tick = status.state == ShaderPacks::PackState::NotInstalled ? latest.has_value() :
                                                                               status.state != ShaderPacks::PackState::Installed;
      name->setCheckState(tick ? Qt::Checked : Qt::Unchecked);
    }
  }
  m_packs->resizeColumnToContents(COLUMN_SIZE);
  m_packs->resizeColumnToContents(COLUMN_STATUS);
  m_packs->resizeRowsToContents();
}

std::vector<std::string> ShaderPackDialog::checkedIds(bool installed_only) const
{
  std::vector<std::string> ids;
  for (int row = 0; row < m_packs->rowCount(); row++)
  {
    const QTableWidgetItem* name = m_packs->item(row, COLUMN_PACK);
    if (name->checkState() != Qt::Checked)
      continue;
    std::string id = name->data(Qt::UserRole).toString().toStdString();
    if (installed_only && !ShaderPacks::ReadMarker(Root(), id).has_value())
      continue;
    ids.push_back(std::move(id));
  }
  return ids;
}

void ShaderPackDialog::onInstallClicked()
{
  if (m_worker)
  {
    cancelWorker();
    return;
  }

  const std::vector<std::string> ids = checkedIds(false);
  if (ids.empty())
  {
    m_status->setText(tr("Tick the packs to install or update."));
    return;
  }

  std::vector<std::string> order = ShaderPacks::ExpandDependencies(Root(), ids);
  QStringList names;
  for (const std::string& id : order)
    names.append(PackName(id));
  m_status->setText(tr("Installing %1.").arg(names.join(QStringLiteral(", "))));
  startWorker(Mode::Install, std::move(order));
}

void ShaderPackDialog::onRemoveClicked()
{
  if (m_worker)
    return;

  const std::vector<std::string> ids = checkedIds(true);
  if (ids.empty())
  {
    m_status->setText(tr("Tick the installed packs to remove."));
    return;
  }

  QStringList names;
  for (const std::string& id : ids)
    names.append(PackName(id));
  QString question = tr("Remove the files these packs installed?\n\n%1").arg(names.join(QStringLiteral("\n")));
  const auto ticked = [&ids](const char* id) { return std::find(ids.begin(), ids.end(), id) != ids.end(); };
  if (ticked("shaders_slang") && !ticked("retro-crisis-gdv-ntsc") &&
      ShaderPacks::ReadMarker(Root(), "retro-crisis-gdv-ntsc").has_value())
  {
    question += QStringLiteral("\n\n") +
                tr("The Retro Crisis presets need the libretro slang shaders and will stop working.");
  }
  if (QMessageBox::question(this, tr("Remove Shader Packs"), question) != QMessageBox::Yes)
    return;

  startWorker(Mode::Remove, ids);
}

void ShaderPackDialog::startWorker(Mode mode, std::vector<std::string> ids)
{
  m_worker = std::make_unique<Worker>(this, mode, std::move(ids));
  connect(m_worker.get(), &Worker::statusUpdated, m_status, &QLabel::setText);
  connect(m_worker.get(), &Worker::progressUpdated, this, [this](int value, int range) {
    m_progress->setMaximum(range);
    m_progress->setValue(value);
  });
  connect(m_worker.get(), &Worker::threadFinished, this, &ShaderPackDialog::onWorkerFinished);
  m_progress->setValue(0);
  m_progress->setVisible(mode != Mode::Check);
  m_worker->start();
  updateEnabled();
}

void ShaderPackDialog::onWorkerFinished()
{
  if (!m_worker)
    return;

  m_worker->join();
  const Mode mode = m_worker->mode();
  if (mode == Mode::Check)
  {
    m_checks = m_worker->checks();
    m_status->setText(tr("Tick the packs to install, update or remove."));
  }
  else
  {
    QStringList failed;
    bool cancelled = false;
    for (const ShaderPacks::InstallResult& result : m_worker->results())
    {
      cancelled |= result.cancelled;
      if (!result.success && !result.cancelled)
        failed.append(QStringLiteral("%1: %2").arg(PackName(result.id), QString::fromStdString(result.message)));
    }
    if (cancelled)
      m_status->setText(tr("Cancelled."));
    else if (!failed.isEmpty())
      m_status->setText(tr("Finished with errors. %1").arg(failed.join(QStringLiteral(" "))));
    else
      m_status->setText(mode == Mode::Install ? tr("Done.") : tr("Removed."));
  }

  m_worker.reset();
  m_progress->setVisible(false);
  refreshStatuses();
  updateEnabled();
}

void ShaderPackDialog::cancelWorker()
{
  if (!m_worker)
    return;

  m_worker->requestInterruption();
  m_worker->join();
  m_worker.reset();
  m_progress->setVisible(false);
  m_status->setText(tr("Cancelled."));
  refreshStatuses();
  updateEnabled();
}

void ShaderPackDialog::updateEnabled()
{
  const bool running = static_cast<bool>(m_worker);
  const bool installing = running && m_worker->mode() == Mode::Install;
  m_install->setText(installing ? tr("Cancel") : tr("Install"));
  m_install->setEnabled(!running || installing);
  m_remove->setEnabled(!running);
  m_close->setEnabled(!running || installing || m_worker->mode() == Mode::Check);
  m_packs->setEnabled(!running);
}

ShaderPackDialog::Worker::Worker(QWidget* parent, Mode mode, std::vector<std::string> ids)
  : QtAsyncProgressThread(parent), m_mode(mode), m_ids(std::move(ids))
{
}

ShaderPackDialog::Worker::~Worker() = default;

void ShaderPackDialog::Worker::runAsync()
{
  if (m_mode == Mode::Remove)
  {
    for (const std::string& id : m_ids)
    {
      ShaderPacks::InstallResult& result = m_results.emplace_back();
      result.id = id;
      Error error;
      result.success = ShaderPacks::Uninstall(Root(), id, &error);
      if (!result.success)
        result.message = error.GetDescription();
    }
    return;
  }

  std::unique_ptr<ShaderPacks::Transport> transport = ShaderPacks::CreateTransport();
  if (!transport)
  {
    for (const std::string& id : m_ids)
    {
      m_checks[id].error = tr("Couldn't start a download.").toStdString();
      m_results.push_back({id, false, false, m_checks[id].error});
    }
    return;
  }

  if (m_mode == Mode::Install)
  {
    m_results = ShaderPacks::Install(Root(), m_ids, *transport, this);
    return;
  }

  for (const std::string& id : m_ids)
  {
    const ShaderPacks::PackInfo* pack = ShaderPacks::FindPack(id);
    if (!pack)
      continue;
    Check& check = m_checks[id];
    Error error;
    check.version = ShaderPacks::ResolveLatest(*pack, *transport, this, &error);
    if (!check.version.has_value())
      check.error = error.GetDescription();
    if (IsCancelled())
      break;
  }
}
