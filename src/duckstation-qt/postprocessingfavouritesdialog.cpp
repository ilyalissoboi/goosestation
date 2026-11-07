// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "postprocessingfavouritesdialog.h"

#include "core/host.h"
#include "core/settings.h"

#include "util/ini_settings_interface.h"
#include "util/postprocessing.h"

#include "common/error.h"

#include <QtWidgets/QDialogButtonBox>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QInputDialog>
#include <QtWidgets/QLabel>
#include <QtWidgets/QListWidget>
#include <QtWidgets/QMessageBox>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QVBoxLayout>

using namespace PostProcessing;

static constexpr const char* DISPLAY = Config::DISPLAY_CHAIN_SECTION;

PostProcessingFavouritesDialog::PostProcessingFavouritesDialog(QWidget* parent, SettingsInterface* game_settings,
                                                               std::function<void()> on_applied)
  : QDialog(parent), m_game_settings(game_settings), m_on_applied(std::move(on_applied))
{
  setWindowTitle(tr("Post-Processing Favourites"));
  resize(640, 400);

  QVBoxLayout* layout = new QVBoxLayout(this);
  QHBoxLayout* row = new QHBoxLayout();
  layout->addLayout(row, 1);

  QVBoxLayout* left = new QVBoxLayout();
  m_list = new QListWidget(this);
  m_list->setWordWrap(true);
  left->addWidget(m_list, 1);
  m_empty = new QLabel(tr("No favourites yet: save the current chain as one."), this);
  m_empty->setWordWrap(true);
  left->addWidget(m_empty);
  row->addLayout(left, 1);

  QVBoxLayout* buttons = new QVBoxLayout();
  const auto add_button = [this, buttons](const QString& text, const char* icon) {
    QPushButton* button = new QPushButton(QIcon::fromTheme(QString::fromUtf8(icon)), text, this);
    buttons->addWidget(button);
    return button;
  };
  m_apply = add_button(tr("Apply"), "play-line");
  m_save = add_button(tr("Save Current…"), "save-3-line");
  m_rename = add_button(tr("Rename…"), "price-tag-3-line");
  m_move_up = add_button(tr("Move Up"), "arrow-up-line");
  m_move_down = add_button(tr("Move Down"), "arrow-down-line");
  m_delete = add_button(tr("Delete"), "trash-fill");
  buttons->addStretch(1);
  row->addLayout(buttons);

  QDialogButtonBox* close = new QDialogButtonBox(QDialogButtonBox::Close, this);
  layout->addWidget(close);

  connect(close, &QDialogButtonBox::rejected, this, &QDialog::reject);
  connect(m_list, &QListWidget::itemSelectionChanged, this, &PostProcessingFavouritesDialog::updateButtons);
  connect(m_list, &QListWidget::itemDoubleClicked, this, &PostProcessingFavouritesDialog::onApplyClicked);
  connect(m_apply, &QPushButton::clicked, this, &PostProcessingFavouritesDialog::onApplyClicked);
  connect(m_save, &QPushButton::clicked, this, &PostProcessingFavouritesDialog::onSaveClicked);
  connect(m_rename, &QPushButton::clicked, this, &PostProcessingFavouritesDialog::onRenameClicked);
  connect(m_move_up, &QPushButton::clicked, this, [this]() { onMoveClicked(false); });
  connect(m_move_down, &QPushButton::clicked, this, [this]() { onMoveClicked(true); });
  connect(m_delete, &QPushButton::clicked, this, &PostProcessingFavouritesDialog::onDeleteClicked);

  refresh();
}

PostProcessingFavouritesDialog::~PostProcessingFavouritesDialog() = default;

void PostProcessingFavouritesDialog::refresh(const QString& select)
{
  {
    const auto lock = Host::GetSettingsLock();
    m_favourites = Favourites::List(EmuFolders::PostProcessing, *Host::Internal::GetBaseSettingsLayer());
  }

  m_list->clear();
  QListWidgetItem* selected = nullptr;
  for (const Favourites::Info& info : m_favourites)
  {
    const QString name = QString::fromStdString(info.name);
    const QString summary = QString::fromStdString(Favourites::Summary(info));
    QListWidgetItem* item = new QListWidgetItem(summary.isEmpty() ? name : (name + QChar('\n') + summary), m_list);
    item->setData(Qt::UserRole, name);
    item->setToolTip(summary);
    if (!info.missing.empty())
      item->setForeground(palette().color(QPalette::Disabled, QPalette::Text));
    if (!select.isEmpty() && name.compare(select, Qt::CaseInsensitive) == 0)
      selected = item;
  }

  m_list->setVisible(!m_favourites.empty());
  m_empty->setVisible(m_favourites.empty());
  if (selected)
    m_list->setCurrentItem(selected);
  updateButtons();
}

void PostProcessingFavouritesDialog::updateButtons()
{
  const int row = selectedRow();
  const bool has = (row >= 0);
  m_apply->setEnabled(has);
  m_rename->setEnabled(has);
  m_delete->setEnabled(has);
  m_move_up->setEnabled(has && row > 0);
  m_move_down->setEnabled(has && row < (m_list->count() - 1));
}

QString PostProcessingFavouritesDialog::selectedName() const
{
  const QList<QListWidgetItem*> items = m_list->selectedItems();
  return items.empty() ? QString() : items.first()->data(Qt::UserRole).toString();
}

int PostProcessingFavouritesDialog::selectedRow() const
{
  const QList<QListWidgetItem*> items = m_list->selectedItems();
  return items.empty() ? -1 : m_list->row(items.first());
}

u32 PostProcessingFavouritesDialog::runningStageCount() const
{
  const auto lock = Host::GetSettingsLock();
  const SettingsInterface& si = (m_game_settings && Config::HasOwnChain(*m_game_settings, DISPLAY)) ?
                                  *m_game_settings :
                                  *Host::Internal::GetBaseSettingsLayer();
  return Config::GetStageCount(si, DISPLAY);
}

void PostProcessingFavouritesDialog::showError(const QString& message)
{
  QMessageBox::critical(this, windowTitle(), message);
}

void PostProcessingFavouritesDialog::onApplyClicked()
{
  const QString name = selectedName();
  if (name.isEmpty())
    return;

  const u32 stages = runningStageCount();
  if (stages > 0)
  {
    const QString question = (stages == 1) ? tr("Apply %1? It replaces the shader in the chain.").arg(name) :
                                             tr("Apply %1? It replaces the %2 shaders in the chain.").arg(name).arg(stages);
    if (QMessageBox::question(this, windowTitle(), question, QMessageBox::Yes | QMessageBox::No, QMessageBox::No) !=
        QMessageBox::Yes)
    {
      return;
    }
  }

  Error error;
  bool applied;
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& to = m_game_settings ? *m_game_settings : *Host::Internal::GetBaseSettingsLayer();
    applied = Favourites::Apply(EmuFolders::PostProcessing, name.toStdString(), to, &error);
  }
  if (!applied)
  {
    showError(QString::fromStdString(error.GetDescription()));
    refresh();
    return;
  }

  if (m_on_applied)
    m_on_applied();
}

void PostProcessingFavouritesDialog::onSaveClicked()
{
  bool ok = false;
  const QString name =
    QInputDialog::getText(this, tr("Save as Favourite"), tr("Name:"), QLineEdit::Normal, QString(), &ok).trimmed();
  if (!ok)
    return;

  // Asked outside the settings lock: a question runs an event loop.
  std::optional<std::string> taken;
  {
    const auto lock = Host::GetSettingsLock();
    taken = Favourites::Find(EmuFolders::PostProcessing, *Host::Internal::GetBaseSettingsLayer(), name.toStdString());
  }
  if (taken.has_value() &&
      QMessageBox::question(this, tr("Save as Favourite"),
                            tr("A favourite named %1 already exists. Replace it?").arg(QString::fromStdString(*taken)),
                            QMessageBox::Yes | QMessageBox::No, QMessageBox::No) != QMessageBox::Yes)
  {
    return;
  }

  Error error;
  bool saved;
  {
    const auto lock = Host::GetSettingsLock();
    SettingsInterface& base = *Host::Internal::GetBaseSettingsLayer();
    const SettingsInterface& chain =
      (m_game_settings && Config::HasOwnChain(*m_game_settings, DISPLAY)) ? *m_game_settings : base;
    saved = Favourites::Save(EmuFolders::PostProcessing, base, name.toStdString(), chain, taken.has_value(), &error);
  }
  if (!saved)
  {
    showError(QString::fromStdString(error.GetDescription()));
    return;
  }

  Host::CommitBaseSettingChanges();
  refresh(taken.has_value() ? QString::fromStdString(*taken) : name);
}

void PostProcessingFavouritesDialog::onRenameClicked()
{
  const QString from = selectedName();
  if (from.isEmpty())
    return;

  bool ok = false;
  const QString to =
    QInputDialog::getText(this, tr("Rename Favourite"), tr("Name:"), QLineEdit::Normal, from, &ok).trimmed();
  if (!ok || to == from)
    return;

  Error error;
  bool renamed;
  {
    const auto lock = Host::GetSettingsLock();
    renamed = Favourites::Rename(EmuFolders::PostProcessing, *Host::Internal::GetBaseSettingsLayer(),
                                 from.toStdString(), to.toStdString(), &error);
  }
  if (!renamed)
  {
    showError(QString::fromStdString(error.GetDescription()));
    return;
  }

  Host::CommitBaseSettingChanges();
  refresh(to);
}

void PostProcessingFavouritesDialog::onMoveClicked(bool down)
{
  const QString name = selectedName();
  if (name.isEmpty())
    return;

  {
    const auto lock = Host::GetSettingsLock();
    Favourites::Move(EmuFolders::PostProcessing, *Host::Internal::GetBaseSettingsLayer(), name.toStdString(), down);
  }
  Host::CommitBaseSettingChanges();
  refresh(name);
}

void PostProcessingFavouritesDialog::onDeleteClicked()
{
  const QString name = selectedName();
  const int row = selectedRow();
  if (name.isEmpty() ||
      QMessageBox::question(this, windowTitle(), tr("Delete the favourite %1?").arg(name),
                            QMessageBox::Yes | QMessageBox::No, QMessageBox::No) != QMessageBox::Yes)
  {
    return;
  }

  Error error;
  bool deleted;
  {
    const auto lock = Host::GetSettingsLock();
    deleted = Favourites::Delete(EmuFolders::PostProcessing, *Host::Internal::GetBaseSettingsLayer(),
                                 name.toStdString(), &error);
  }
  if (!deleted)
  {
    showError(QString::fromStdString(error.GetDescription()));
    return;
  }

  // The one after it takes its place, or the one before it at the end.
  QString select;
  if (static_cast<size_t>(row + 1) < m_favourites.size())
    select = QString::fromStdString(m_favourites[row + 1].name);
  else if (row > 0)
    select = QString::fromStdString(m_favourites[row - 1].name);
  Host::CommitBaseSettingChanges();
  refresh(select);
}
