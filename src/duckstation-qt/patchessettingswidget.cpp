// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "patchessettingswidget.h"
#include "qthost.h"
#include "settingswindow.h"

#include "core/achievements.h"
#include "core/cheats.h"
#include "core/system.h"

#include "util/ini_settings_interface.h"

#include "common/error.h"
#include "common/file_system.h"

#include <QtCore/QSignalBlocker>
#include <QtGui/QStandardItemModel>
#include <QtWidgets/QComboBox>
#include <QtWidgets/QFileDialog>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QHeaderView>
#include <QtWidgets/QLabel>
#include <QtWidgets/QMessageBox>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QSpinBox>
#include <QtWidgets/QTreeWidget>
#include <QtWidgets/QVBoxLayout>

PatchesSettingsWidget::PatchesSettingsWidget(SettingsWindow* dialog, QWidget* parent, std::string serial,
                                             std::string title)
  : QWidget(parent), m_dialog(dialog), m_serial(std::move(serial)), m_title(std::move(title))
{
  QVBoxLayout* layout = new QVBoxLayout(this);
  layout->setContentsMargins(0, 0, 0, 0);

  m_status = new QLabel(this);
  m_status->setWordWrap(true);
  layout->addWidget(m_status);

  m_tree = new QTreeWidget(this);
  m_tree->setColumnCount(3);
  m_tree->setHeaderLabels({tr("Patch"), tr("Setting"), tr("Note")});
  m_tree->setRootIsDecorated(false);
  m_tree->setSelectionMode(QAbstractItemView::NoSelection);
  m_tree->header()->setSectionResizeMode(0, QHeaderView::Stretch);
  m_tree->header()->setSectionResizeMode(1, QHeaderView::ResizeToContents);
  m_tree->header()->setSectionResizeMode(2, QHeaderView::Stretch);
  layout->addWidget(m_tree, 1);

  QHBoxLayout* buttons = new QHBoxLayout();
  buttons->addStretch(1);
  m_import = new QPushButton(tr("Import..."), this);
  m_import->setToolTip(tr("Adds the codes in a cheat file (any format GooseStation reads, the newer community format "
                          "included) to this game's file in the cheats folder. Codes its file or GooseStation's marks "
                          "call patches show here; the rest are cheats."));
  buttons->addWidget(m_import);
  layout->addLayout(buttons);
  connect(m_import, &QPushButton::clicked, this, &PatchesSettingsWidget::onImportClicked);
  connect(m_tree, &QTreeWidget::itemChanged, this, [this](QTreeWidgetItem* item, int column) {
    const QVariant index = item->data(0, Qt::UserRole);
    if (column == 0 && index.isValid() && (item->flags() & Qt::ItemIsUserCheckable))
      setChoice(static_cast<size_t>(index.toULongLong()), (item->checkState(0) == Qt::Checked) ? 0 : -1);
  });

  reload();
  fill();
}

PatchesSettingsWidget::~PatchesSettingsWidget() = default;

void PatchesSettingsWidget::reload()
{
  m_list.reset();
  m_running = false;
  m_hardcore = false;

  Host::RunOnCPUThread(
    [this]() {
      if (!System::IsValid() || System::GetGameSerial() != m_serial)
        return;
      m_running = true;
      m_hardcore = Achievements::IsHardcoreModeActive();
      if (const Patches::PatchList* live = System::GetPatchList())
        m_list = std::make_unique<Patches::PatchList>(*live);
    },
    true);

  if (!m_running)
    m_list = Patches::LoadForGame(m_serial, m_title);
}

QString PatchesSettingsWidget::noteFor(const Patches::Patch& patch) const
{
  QStringList notes;
  if (m_running && patch.running != patch.saved)
  {
    notes.append(patch.saved < 0 ? tr("Off at the next start.") :
                                   tr("%1 at the next start.")
                                     .arg(QString::fromStdString(
                                       patch.choices[static_cast<size_t>(patch.saved)].code.description)));
  }

  const s32 shown = (patch.saved >= 0) ? patch.saved : 0;
  const Patches::Choice& choice = patch.choices[static_cast<size_t>(shown)];
  if (choice.needs.may_need_overclock)
    notes.append(tr("May need a CPU overclock to hold its frame rate."));
  if (choice.needs.overclock_percent != 0)
    notes.append(tr("Runs with a %1% CPU overclock.").arg(choice.needs.overclock_percent));
  if (!choice.code.details.empty())
    notes.append(QString::fromStdString(choice.code.details));
  for (const std::string& note : choice.code.notes)
    notes.append(QString::fromStdString(note));
  if (choice.code.IsManuallyActivated())
    notes.append(tr("A patch applied by hand can't be switched on."));
  return notes.join(QLatin1Char(' '));
}

void PatchesSettingsWidget::fill()
{
  QSignalBlocker blocker(m_tree);
  m_tree->clear();

  if (m_hardcore)
    m_status->setText(tr("Patches are off in hardcore mode."));
  else if (!m_list)
    m_status->setText(tr("No patches for this game. Patches are widescreen, frame-rate and display-mode codes."));
  else
    m_status->setText(tr("Patches set what they need (the aspect ratio, a CPU overclock) for this game only, while "
                         "they're on. They work whether or not cheats are enabled."));
  m_tree->setEnabled(m_list && !m_hardcore);
  m_import->setEnabled(!m_hardcore);
  if (!m_list)
    return;

  QTreeWidgetItem* group_item = nullptr;
  std::string group_name;
  for (size_t i = 0; i < m_list->Count(); i++)
  {
    const Patches::Patch& patch = m_list->Get(i);
    QTreeWidgetItem* parent = nullptr;
    if (!patch.group.empty())
    {
      if (!group_item || group_name != patch.group)
      {
        group_item = new QTreeWidgetItem(m_tree, {QString::fromStdString(patch.group)});
        group_item->setFlags(Qt::ItemIsEnabled);
        QFont font = group_item->font(0);
        font.setBold(true);
        group_item->setFont(0, font);
        group_name = patch.group;
        m_tree->setRootIsDecorated(true);
      }
      parent = group_item;
    }

    QTreeWidgetItem* item = parent ? new QTreeWidgetItem(parent) : new QTreeWidgetItem(m_tree);
    item->setData(0, Qt::UserRole, QVariant::fromValue(static_cast<qulonglong>(i)));
    item->setText(0, QString::fromStdString(patch.title));
    item->setText(2, noteFor(patch));
    item->setToolTip(2, item->text(2));

    QWidget* setting = new QWidget(m_tree);
    QHBoxLayout* setting_layout = new QHBoxLayout(setting);
    setting_layout->setContentsMargins(0, 0, 0, 0);

    if (patch.choices.size() > 1)
    {
      QComboBox* choices = new QComboBox(setting);
      choices->addItem(tr("Off"));
      for (const Patches::Choice& choice : patch.choices)
        choices->addItem(QString::fromStdString(choice.code.description));
      // A choice that can't run is listed, but can't be picked.
      if (QStandardItemModel* model = qobject_cast<QStandardItemModel*>(choices->model()))
      {
        for (size_t c = 0; c < patch.choices.size(); c++)
        {
          if (!m_list->CanChoose(i, static_cast<s32>(c)))
            model->item(static_cast<int>(c) + 1)->setEnabled(false);
        }
      }
      choices->setCurrentIndex(patch.saved + 1);
      connect(choices, &QComboBox::currentIndexChanged, this, [this, i](int row) { setChoice(i, row - 1); });
      setting_layout->addWidget(choices);
    }
    else
    {
      item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
      item->setCheckState(0, (patch.saved == 0) ? Qt::Checked : Qt::Unchecked);
    }

    // A value for the shown choice's code, if it has options.
    const s32 shown = (patch.saved >= 0) ? patch.saved : 0;
    const CheatCode& code = patch.choices[static_cast<size_t>(shown)].code;
    if (!m_list->CanChoose(i, shown))
    {
      item->setDisabled(true);
    }
    else if (!code.options.empty())
    {
      QComboBox* values = new QComboBox(setting);
      for (const CheatCode::Option& option : code.options)
      {
        values->addItem(QString::fromStdString(option.name), static_cast<uint>(option.value));
        if (option.value == code.option_value)
          values->setCurrentIndex(values->count() - 1);
      }
      connect(values, &QComboBox::currentIndexChanged, this, [this, i, shown, values](int row) {
        setValue(i, shown, values->itemData(row).toUInt());
      });
      setting_layout->addWidget(values);
    }
    else if (code.option_range.has_value())
    {
      QSpinBox* value = new QSpinBox(setting);
      value->setRange(static_cast<int>(code.option_range->first),
                      static_cast<int>(std::min<u32>(code.option_range->second, INT32_MAX)));
      value->setValue(static_cast<int>(code.option_value));
      connect(value, &QSpinBox::editingFinished, this,
              [this, i, shown, value]() { setValue(i, shown, static_cast<u32>(value->value())); });
      setting_layout->addWidget(value);
    }

    if (setting_layout->count() > 0)
      m_tree->setItemWidget(item, 1, setting);
    else
      delete setting;
  }
  m_tree->expandAll();

}

void PatchesSettingsWidget::setChoice(size_t index, s32 choice)
{
  if (!m_list || index >= m_list->Count() || m_list->Get(index).saved == choice)
    return;

  bool reset_now = false;
  if (m_running && m_list->NeedsRestart(index, choice))
  {
    QMessageBox box(QMessageBox::Question, tr("Switch Patch"),
                    tr("\"%1\" can't be undone while the game runs. Reset the game now, or apply the change at the "
                       "next start? Until then, the patch and what it set stay as they are.")
                      .arg(QString::fromStdString(m_list->Get(index).title)),
                    QMessageBox::NoButton, this);
    QPushButton* reset = box.addButton(tr("Reset Now"), QMessageBox::AcceptRole);
    QPushButton* later = box.addButton(tr("At Next Start"), QMessageBox::RejectRole);
    box.addButton(QMessageBox::Cancel);
    box.setDefaultButton(later);
    box.exec();
    if (box.clickedButton() == reset)
      reset_now = true;
    else if (box.clickedButton() != later)
    {
      refill(); // back as it was
      return;
    }
  }

  m_list->Set(index, choice);
  save();
  if (reset_now)
    g_emu_thread->resetSystem(true);
  // The running game's list as it is after the change (and the reset, if it happened): its calls queue behind them.
  if (m_running)
    reload();
  refill();
}

void PatchesSettingsWidget::refill()
{
  // Never inside the tree's own signals: rebuilding deletes the item that sent it.
  QMetaObject::invokeMethod(this, &PatchesSettingsWidget::fill, Qt::QueuedConnection);
}

void PatchesSettingsWidget::setValue(size_t index, s32 choice, u32 value)
{
  if (!m_list)
    return;

  Error error;
  if (!m_list->SetValue(index, choice, value, &error))
  {
    QMessageBox::critical(this, tr("Patch Value"), QString::fromStdString(error.GetDescription()));
    refill();
    return;
  }
  save();
}

void PatchesSettingsWidget::save()
{
  INISettingsInterface* sif = m_dialog->getSettingsInterface();
  Patches::WriteSwitches(*sif, m_list->Switches());
  m_dialog->saveAndReloadGameSettings();
}

void PatchesSettingsWidget::onImportClicked()
{
  const QString filter = tr("Cheat Files (*.cht *.ini *.txt);;All Files (*.*)");
  const QString filename =
    QDir::toNativeSeparators(QFileDialog::getOpenFileName(this, tr("Import Patches"), QString(), filter));
  if (filename.isEmpty())
    return;

  CheatList imported;
  if (!imported.LoadFromFile(filename.toUtf8().constData(), CheatList::Format::Autodetect) ||
      imported.GetCodeCount() == 0)
  {
    QMessageBox::critical(this, tr("Import Patches"), tr("No codes could be read from this file."));
    return;
  }

  // Into the game's own file, beside its cheats, each as a patch.
  const std::string path = Patches::CheatFileForTitle(m_title);
  CheatList file;
  if (FileSystem::FileExists(path.c_str()))
    file.LoadFromFile(path.c_str(), CheatList::Format::Autodetect);
  // A code is a patch when its own file says so (override keys, #patch) or the marks name it for this game; the rest
  // are cheats, which only the cheat manager shows, with "Enable Cheats" on.
  int patches = 0;
  for (u32 i = 0; i < imported.GetCodeCount(); i++)
  {
    CheatCode& code = imported.GetCode(i);
    code.enabled = false;
    if (Patches::Classify(m_serial, code, Patches::BundledMarks()).has_value())
      patches++;
  }
  const int cheats = static_cast<int>(imported.GetCodeCount()) - patches;
  file.MergeList(imported);
  Error error;
  if (!FileSystem::EnsureDirectoryExists(EmuFolders::Cheats.c_str(), false, &error) ||
      !file.SaveToPCSXRFile(path.c_str()))
  {
    QMessageBox::critical(this, tr("Import Patches"), tr("Couldn't write %1.").arg(QString::fromStdString(path)));
    return;
  }

  // The running game reads its patches and cheats again.
  if (m_running)
  {
    Host::RunOnCPUThread(
      []() {
        System::LoadPatchList();
        if (g_settings.enable_cheats)
          System::LoadCheatList();
      },
      true);
  }

  QMessageBox::information(
    this, tr("Import Patches"),
    cheats == 0 ? tr("%n patch(es) imported.", "", patches) :
                  tr("%1 patch(es) and %2 cheat(s) imported. The cheats are in the cheat manager, with Enable Cheats on.")
                    .arg(patches)
                    .arg(cheats));
  reload();
  fill();
}
