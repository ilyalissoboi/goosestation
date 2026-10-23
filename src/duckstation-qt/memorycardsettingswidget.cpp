// SPDX-FileCopyrightText: 2019-2022 Connor McLaughlin <stenzek@gmail.com>
// SPDX-License-Identifier: (GPL-3.0 OR CC-BY-NC-ND-4.0)

#include "memorycardsettingswidget.h"

#include "core/controller.h"
#include "core/host.h"
#include "core/memcard_sync.h"
#include "core/settings.h"
#include "inputbindingwidgets.h"
#include "mainwindow.h"
#include "qthost.h"
#include "qtutils.h"
#include "settingswindow.h"
#include "settingwidgetbinder.h"

#include "common/small_string.h"
#include "common/string_util.h"

#include <QtCore/QPointer>
#include <QtCore/QSysInfo>
#include <QtCore/QUrl>
#include <QtWidgets/QFileDialog>
#include <QtWidgets/QFormLayout>
#include <QtWidgets/QLabel>

#include <optional>
#include <string_view>
#include <thread>

static constexpr char MEMORY_CARD_IMAGE_FILTER[] =
  QT_TRANSLATE_NOOP("MemoryCardSettingsWidget", "All Memory Card Types (*.mcd *.mcr *.mc)");

MemoryCardSettingsWidget::MemoryCardSettingsWidget(SettingsWindow* dialog, QWidget* parent)
  : QWidget(parent), m_dialog(dialog)
{
  createUi(dialog);
}

MemoryCardSettingsWidget::~MemoryCardSettingsWidget() = default;

void MemoryCardSettingsWidget::createUi(SettingsWindow* dialog)
{
  QVBoxLayout* layout = new QVBoxLayout(this);
  layout->setContentsMargins(0, 0, 0, 0);

  for (int i = 0; i < static_cast<int>(m_port_ui.size()); i++)
  {
    createPortSettingsUi(dialog, i, &m_port_ui[i]);
    layout->addWidget(m_port_ui[i].container);
  }

  {
    QGroupBox* box = new QGroupBox(tr("Shared Settings"), this);
    QVBoxLayout* box_layout = new QVBoxLayout(box);
    QPushButton* browse = new QPushButton(tr("Browse..."), box);
    QPushButton* open_memcards = new QPushButton(tr("Open..."), box);
    QPushButton* reset = new QPushButton(tr("Reset"), box);

    {
      QLabel* label = new QLabel(tr("Memory Card Directory:"), box);
      box_layout->addWidget(label);

      QHBoxLayout* hbox = new QHBoxLayout();
      m_memory_card_directory = new QLineEdit(box);

      hbox->addWidget(m_memory_card_directory);
      hbox->addWidget(browse);
      hbox->addWidget(open_memcards);
      hbox->addWidget(reset);

      box_layout->addLayout(hbox);
    }

    QCheckBox* playlist_title_as_game_title = new QCheckBox(tr("Use Single Card For Multi-Disc Games"), box);
    SettingWidgetBinder::BindWidgetToBoolSetting(m_dialog->getSettingsInterface(), playlist_title_as_game_title,
                                                 "MemoryCards", "UsePlaylistTitle", true);
    box_layout->addWidget(playlist_title_as_game_title);
    dialog->registerWidgetHelp(
      playlist_title_as_game_title, tr("Use Single Card For Multi-Disc Games"), tr("Checked"),
      tr("When playing a multi-disc game and using per-game (title) memory cards, a single memory card "
         "will be used for all discs. If unchecked, a separate card will be used for each disc."));

    box_layout->addWidget(QtUtils::CreateHorizontalLine(box));

    {
      QHBoxLayout* hbox = new QHBoxLayout();
      QLabel* label = new QLabel(
        tr("The memory card editor enables you to move saves between cards, as well as import cards of other formats."),
        box);
      label->setWordWrap(true);
      hbox->addWidget(label, 1);

      QPushButton* button = new QPushButton(tr("Memory Card Editor..."), box);
      connect(button, &QPushButton::clicked, []() { g_main_window->openMemoryCardEditor(QString(), QString()); });
      hbox->addWidget(button);
      box_layout->addLayout(hbox);
    }

    layout->addWidget(box);

    SettingWidgetBinder::BindWidgetToFolderSetting(
      m_dialog->getSettingsInterface(), m_memory_card_directory, browse, tr("Select Memory Card Directory"),
      open_memcards, reset, "MemoryCards", "Directory", Path::Combine(EmuFolders::DataRoot, "memcards"));
  }

  if (!dialog->isPerGameSettings())
    layout->addWidget(new MemoryCardSyncWidget(dialog, this));

  layout->addStretch(1);

  setLayout(layout);
}

void MemoryCardSettingsWidget::createPortSettingsUi(SettingsWindow* dialog, int index, PortSettingsUI* ui)
{
  ui->container = new QGroupBox(tr("Memory Card %1").arg(index + 1), this);
  ui->layout = new QVBoxLayout(ui->container);

  ui->memory_card_type = new QComboBox(ui->container);
  for (int i = 0; i < static_cast<int>(MemoryCardType::Count); i++)
  {
    ui->memory_card_type->addItem(
      QString::fromUtf8(Settings::GetMemoryCardTypeDisplayName(static_cast<MemoryCardType>(i))));
  }

  const MemoryCardType default_value = (index == 0) ? MemoryCardType::PerGameTitle : MemoryCardType::None;
  SettingWidgetBinder::BindWidgetToEnumSetting(m_dialog->getSettingsInterface(), ui->memory_card_type, "MemoryCards",
                                               fmt::format("Card{}Type", index + 1), &Settings::ParseMemoryCardTypeName,
                                               &Settings::GetMemoryCardTypeName, default_value);
  ui->layout->addWidget(new QLabel(tr("Memory Card Type:"), ui->container));
  ui->layout->addWidget(ui->memory_card_type);

  QHBoxLayout* memory_card_layout = new QHBoxLayout();
  ui->memory_card_path = new QLineEdit(ui->container);
  updateMemoryCardPath(index);
  connect(ui->memory_card_path, &QLineEdit::textChanged, this, [this, index]() { onMemoryCardPathChanged(index); });
  if (ui->memory_card_path->text().isEmpty())
  {
    QSignalBlocker sb(ui->memory_card_path);
    ui->memory_card_path->setText(QString::fromStdString(g_settings.GetSharedMemoryCardPath(static_cast<u32>(index))));
  }
  memory_card_layout->addWidget(ui->memory_card_path);

  QPushButton* memory_card_path_browse = new QPushButton(tr("Browse..."), ui->container);
  connect(memory_card_path_browse, &QPushButton::clicked, this,
          [this, index]() { onBrowseMemoryCardPathClicked(index); });
  memory_card_layout->addWidget(memory_card_path_browse);

  QPushButton* memory_card_path_reset = new QPushButton(tr("Reset"), ui->container);
  connect(memory_card_path_reset, &QPushButton::clicked, this,
          [this, index]() { onResetMemoryCardPathClicked(index); });
  memory_card_layout->addWidget(memory_card_path_reset);

  ui->layout->addWidget(new QLabel(tr("Shared Memory Card Path:"), ui->container));
  ui->layout->addLayout(memory_card_layout);

  ui->layout->addStretch(1);
}

void MemoryCardSettingsWidget::onBrowseMemoryCardPathClicked(int index)
{
  QString path = QDir::toNativeSeparators(QFileDialog::getOpenFileName(this, tr("Select path to memory card image"),
                                                                       QString(), tr(MEMORY_CARD_IMAGE_FILTER)));
  if (path.isEmpty())
    return;

  m_port_ui[index].memory_card_path->setText(path);
}

void MemoryCardSettingsWidget::onMemoryCardPathChanged(int index)
{
  const auto key = TinyString::from_format("Card{}Path", index + 1);
  std::string relative_path(
    Path::MakeRelative(m_port_ui[index].memory_card_path->text().toStdString(), EmuFolders::MemoryCards));
  m_dialog->setStringSettingValue("MemoryCards", key, relative_path.c_str());
}

void MemoryCardSettingsWidget::onResetMemoryCardPathClicked(int index)
{
  const auto key = TinyString::from_format("Card{}Path", index + 1);
  if (m_dialog->isPerGameSettings())
    m_dialog->removeSettingValue("MemoryCards", key);
  else
    m_dialog->setStringSettingValue("MemoryCards", key, Settings::GetDefaultSharedMemoryCardName(index).c_str());

  updateMemoryCardPath(index);
}

void MemoryCardSettingsWidget::updateMemoryCardPath(int index)
{
  const auto key = TinyString::from_format("Card{}Path", index + 1);
  std::string path(
    m_dialog->getEffectiveStringValue("MemoryCards", key, Settings::GetDefaultSharedMemoryCardName(index).c_str()));
  if (!Path::IsAbsolute(path))
    path = Path::Canonicalize(Path::Combine(EmuFolders::MemoryCards, path));

  QSignalBlocker db(m_port_ui[index].memory_card_path);
  m_port_ui[index].memory_card_path->setText(QString::fromStdString(path));
}

// ---- Memory card sync --------------------------------------------------------------------------

MemoryCardSyncWidget::MemoryCardSyncWidget(SettingsWindow* dialog, QWidget* parent)
  : QGroupBox(tr("Memory Card Sync"), parent)
{
  using namespace MemcardSync;

  QFormLayout* form = new QFormLayout(this);

  m_enabled = new QCheckBox(tr("Sync memory cards through the cloud"), this);
  form->addRow(m_enabled);

  m_remote_uri = new QLineEdit(this);
  m_remote_uri->setPlaceholderText(QStringLiteral("s3://my-saves/emusync/v1?region=eu-west-1"));
  form->addRow(tr("Storage:"), m_remote_uri);

  m_access_key_id = new QLineEdit(this);
  form->addRow(tr("Access Key ID:"), m_access_key_id);

  const auto secret_row = [this](QLineEdit** edit, QPushButton** show) {
    QHBoxLayout* row = new QHBoxLayout();
    *edit = new QLineEdit(this);
    (*edit)->setEchoMode(QLineEdit::Password);
    *show = new QPushButton(tr("Show"), this);
    (*show)->setCheckable(true);
    // Clicking Show leaves the focus in its field: taking it would finish the edit, which saves what
    // was typed and empties the field, so there would be nothing left to show. Tab still reaches it.
    (*show)->setFocusPolicy(Qt::TabFocus);
    row->addWidget(*edit, 1);
    row->addWidget(*show);
    return row;
  };
  form->addRow(tr("Secret Access Key:"), secret_row(&m_secret, &m_secret_show));
  QHBoxLayout* passphrase_row = secret_row(&m_passphrase, &m_passphrase_show);
  m_passphrase_remove = new QPushButton(tr("Remove"), this);
  passphrase_row->addWidget(m_passphrase_remove);
  form->addRow(tr("Encryption Passphrase:"), passphrase_row);

  m_device_name = new QLineEdit(this);
  // emusync names the device after the computer when no name is set.
  m_device_name->setPlaceholderText(QSysInfo::machineHostName());
  form->addRow(tr("Device Name:"), m_device_name);

  QHBoxLayout* status_row = new QHBoxLayout();
  m_status = new QLabel(this);
  m_status->setWordWrap(true);
  m_sync_now = new QPushButton(tr("Sync Now"), this);
  status_row->addWidget(m_status, 1);
  status_row->addWidget(m_sync_now);
  form->addRow(status_row);

  m_enabled->setChecked(Host::GetBaseBoolSettingValue(SECTION, "Enabled", false));
  m_remote_uri->setText(QString::fromStdString(Host::GetBaseStringSettingValue(SECTION, "RemoteURI")));
  m_access_key_id->setText(QString::fromStdString(Host::GetBaseStringSettingValue(SECTION, "AccessKeyID")));
  m_device_name->setText(QString::fromStdString(Host::GetBaseStringSettingValue(SECTION, "DeviceName")));

  connect(m_enabled, &QCheckBox::toggled, this, &MemoryCardSyncWidget::onEnabledChanged);
  connect(m_remote_uri, &QLineEdit::editingFinished, this, [this]() { onTextSettingEdited(m_remote_uri, "RemoteURI"); });
  connect(m_access_key_id, &QLineEdit::editingFinished, this,
          [this]() { onTextSettingEdited(m_access_key_id, "AccessKeyID"); });
  connect(m_device_name, &QLineEdit::editingFinished, this,
          [this]() { onTextSettingEdited(m_device_name, "DeviceName"); });
  connect(m_secret, &QLineEdit::editingFinished, this, &MemoryCardSyncWidget::onSecretEdited);
  connect(m_secret_show, &QPushButton::toggled, this,
          [this](bool shown) { onShowToggled(m_secret, m_secret_show, SECRET_ACCESS_KEY, shown); });
  connect(m_passphrase, &QLineEdit::editingFinished, this, &MemoryCardSyncWidget::onPassphraseEdited);
  connect(m_passphrase_show, &QPushButton::toggled, this,
          [this](bool shown) { onShowToggled(m_passphrase, m_passphrase_show, ENCRYPTION_PASSPHRASE, shown); });
  connect(m_passphrase_remove, &QPushButton::clicked, this, &MemoryCardSyncWidget::onPassphraseRemoveClicked);
  connect(m_sync_now, &QPushButton::clicked, this, &MemoryCardSyncWidget::onSyncNowClicked);

  m_status_timer.setInterval(2000);
  connect(&m_status_timer, &QTimer::timeout, this, &MemoryCardSyncWidget::updateStatus);

  dialog->registerWidgetHelp(
    m_enabled, tr("Sync Memory Cards"), tr("Unchecked"),
    tr("Your memory card saves sync with your other devices, one save at a time, through storage you own. Newer "
       "saves from other devices are put on a card before it opens; your saves upload while you play."));
  dialog->registerWidgetHelp(
    m_remote_uri, tr("Storage"), tr("Not set"),
    tr("Bucket and folder, e.g. s3://my-saves/emusync/v1?region=eu-west-1. Use the same on every device. "
       "S3-compatible services take an endpoint: s3://bucket/folder?endpoint=https://...&region=auto."));
  dialog->registerWidgetHelp(m_access_key_id, tr("Access Key ID"), tr("Not set"),
                             tr("From your storage provider. A key that can only read and write this bucket is enough."));
  dialog->registerWidgetHelp(
    m_secret, tr("Secret Access Key"), tr("Not set"),
    tr("Kept in your system's credential store (Credential Manager, Keychain or Secret Service), never in the "
       "settings file. Show displays the saved key; type or paste a new one to replace it."));
  dialog->registerWidgetHelp(
    m_passphrase, tr("Encryption Passphrase"), tr("None"),
    tr("Optional. Encrypts your saves on this computer before they upload, so the storage provider can't read them. "
       "New storage is created encrypted; for storage that is already encrypted, enter the passphrase used when it "
       "was set up. Use the same passphrase on every device. It can't be recovered, and encrypted storage can't be "
       "made unencrypted. Kept in your system's credential store."));
  dialog->registerWidgetHelp(m_device_name, tr("Device Name"), tr("This computer's name"),
                             tr("How this computer appears in a save's history on your other devices."));

  updateEnabledState();
  updateSecretPlaceholders();
  updateStatus();
}

MemoryCardSyncWidget::~MemoryCardSyncWidget() = default;

void MemoryCardSyncWidget::showEvent(QShowEvent* event)
{
  QGroupBox::showEvent(event);
  updateStatus();
  m_status_timer.start();
}

void MemoryCardSyncWidget::hideEvent(QHideEvent* event)
{
  QGroupBox::hideEvent(event);
  m_status_timer.stop();
}

void MemoryCardSyncWidget::onEnabledChanged(bool enabled)
{
  Host::SetBaseBoolSettingValue(MemcardSync::SECTION, "Enabled", enabled);
  Host::CommitBaseSettingChanges();
  apply();
}

void MemoryCardSyncWidget::onTextSettingEdited(QLineEdit* edit, const char* key)
{
  const std::string value = edit->text().trimmed().toStdString();
  if (value == Host::GetBaseStringSettingValue(MemcardSync::SECTION, key))
    return;
  Host::SetBaseStringSettingValue(MemcardSync::SECTION, key, value.c_str());
  Host::CommitBaseSettingChanges();
  apply();
}

// Whether the field holds the stored secret that Show put there, unchanged: nothing to save.
static bool ShowsStoredSecret(const QLineEdit* edit)
{
  const QVariant revealed = edit->property("revealed");
  return revealed.isValid() && revealed.toString() == edit->text();
}

// Empties a secret's field and masks it again, once its value is handed over or removed.
static void ResetSecretField(QLineEdit* edit, QPushButton* show_button)
{
  edit->clear();
  edit->setProperty("revealed", QVariant());
  show_button->setChecked(false);
}

void MemoryCardSyncWidget::onSecretEdited()
{
  const QString text = m_secret->text().trimmed();
  if (text.isEmpty() || ShowsStoredSecret(m_secret))
    return;
  ResetSecretField(m_secret, m_secret_show);
  apply({{MemcardSync::SECRET_ACCESS_KEY, text.toStdString()}});
}

void MemoryCardSyncWidget::onPassphraseEdited()
{
  // Not trimmed: emusync's own tool keeps spaces too, and the same passphrase has to unlock the
  // storage on every device.
  const QString text = m_passphrase->text();
  if (text.isEmpty() || ShowsStoredSecret(m_passphrase))
    return;
  ResetSecretField(m_passphrase, m_passphrase_show);
  apply({{MemcardSync::ENCRYPTION_PASSPHRASE, text.toStdString()}});
}

void MemoryCardSyncWidget::onShowToggled(QLineEdit* edit, QPushButton* show_button, const char* secret, bool shown)
{
  edit->setEchoMode(shown ? QLineEdit::Normal : QLineEdit::Password);
  show_button->setText(shown ? tr("Hide") : tr("Show"));
  if (!shown)
  {
    // Hide puts a stored secret that was shown away again, rather than only masking it.
    if (ShowsStoredSecret(edit))
      edit->clear();
    edit->setProperty("revealed", QVariant());
    return;
  }
  // Show unmasks what is being typed; with nothing typed, it loads the stored secret, e.g. to set up
  // another device with it (and losing the passphrase loses the saves). While settings apply, e.g. a
  // value just saved by tabbing to Show, the store may not have it yet: apply() shows it once they
  // are applied.
  if (!edit->text().isEmpty() || m_pending_applies > 0 || !MemcardSync::HasSecret(secret))
    return;

  // Reading the credential store can block (an unlock prompt), so not on the UI thread.
  std::thread([widget = QPointer<MemoryCardSyncWidget>(this), edit, show_button, secret]() {
    std::string error;
    std::optional<std::string> value = MemcardSync::LoadSecret(secret, &error);
    QtHost::RunOnUIThread([widget, edit, show_button, secret, value = std::move(value),
                           error = std::move(error)]() mutable {
      if (widget && show_button->isChecked() && edit->text().isEmpty())
      {
        if (value.has_value())
        {
          const QString text = QString::fromStdString(*value);
          edit->setText(text);
          edit->setProperty("revealed", text);
        }
        else if (!error.empty())
        {
          widget->m_status->setText(QString::fromStdString(error));
        }
        else
        {
          widget->m_status->setText(std::string_view(secret) == MemcardSync::SECRET_ACCESS_KEY ?
                                      MemoryCardSyncWidget::tr("No secret access key is saved.") :
                                      MemoryCardSyncWidget::tr("No passphrase is saved."));
        }
      }
      if (value.has_value())
        MemcardSync::WipeSecret(*value);
    });
  }).detach();
}

void MemoryCardSyncWidget::onPassphraseRemoveClicked()
{
  ResetSecretField(m_passphrase, m_passphrase_show);
  apply({{MemcardSync::ENCRYPTION_PASSPHRASE, std::string()}});
}

void MemoryCardSyncWidget::onSyncNowClicked()
{
  MemcardSync::SyncNow();
  m_status->setText(tr("Syncing..."));
}

void MemoryCardSyncWidget::apply(std::vector<std::pair<const char*, std::string>> secrets)
{
  std::vector<MemcardSync::SecretUpdate> updates;
  for (auto& [name, value] : secrets)
    updates.push_back({name, std::move(value)});
  m_pending_applies++;
  updateStatus();
  MemcardSync::ApplySettingsAsync(std::move(updates),
                                  [widget = QPointer<MemoryCardSyncWidget>(this)](const std::string&) {
                                    QtHost::RunOnUIThread([widget]() {
                                      if (widget)
                                        widget->onApplied();
                                    });
                                  });
}

void MemoryCardSyncWidget::onApplied()
{
  m_pending_applies--;
  updateSecretPlaceholders();
  updateStatus();
  // Only once every queued change is in: an earlier one finishing doesn't mean a secret saved since
  // is stored yet.
  if (m_pending_applies > 0)
    return;
  // A Show pressed while they applied.
  if (m_secret_show->isChecked())
    onShowToggled(m_secret, m_secret_show, MemcardSync::SECRET_ACCESS_KEY, true);
  if (m_passphrase_show->isChecked())
    onShowToggled(m_passphrase, m_passphrase_show, MemcardSync::ENCRYPTION_PASSPHRASE, true);
}

void MemoryCardSyncWidget::updateEnabledState()
{
  if (MemcardSync::IsAvailable())
    return;
  for (QWidget* w : {static_cast<QWidget*>(m_enabled), static_cast<QWidget*>(m_remote_uri),
                     static_cast<QWidget*>(m_access_key_id), static_cast<QWidget*>(m_secret),
                     static_cast<QWidget*>(m_secret_show), static_cast<QWidget*>(m_passphrase),
                     static_cast<QWidget*>(m_passphrase_show), static_cast<QWidget*>(m_passphrase_remove),
                     static_cast<QWidget*>(m_device_name), static_cast<QWidget*>(m_sync_now)})
  {
    w->setEnabled(false);
  }
}

void MemoryCardSyncWidget::updateSecretPlaceholders()
{
  m_secret->setPlaceholderText(MemcardSync::HasSecret(MemcardSync::SECRET_ACCESS_KEY) ? tr("Saved") : tr("Not set"));
  const bool has_passphrase = MemcardSync::HasSecret(MemcardSync::ENCRYPTION_PASSPHRASE);
  m_passphrase->setPlaceholderText(has_passphrase ? tr("Saved") : tr("No passphrase saved"));
  m_passphrase_remove->setEnabled(has_passphrase && MemcardSync::IsAvailable());
}

void MemoryCardSyncWidget::updateStatus()
{
  QString text;
  if (!MemcardSync::IsAvailable())
    text = tr("Memory card sync is unavailable: the emusync library is missing.");
  else if (m_pending_applies > 0)
    text = tr("Connecting to the storage...");
  else if (const std::string error = MemcardSync::GetLastStartError(); !error.empty())
    text = QString::fromStdString(error);
  else if (!m_enabled->isChecked())
    text = tr("Off.");
  else if (!MemcardSync::IsRunning())
    text = tr("Not syncing: fill in the storage, access key ID and secret access key.");
  else if (const std::string status = MemcardSync::GetStatus(); !status.empty())
    text = QString::fromStdString(status);
  else
    text = tr("Syncing. Nothing has changed yet.");
  m_status->setText(text);
  m_sync_now->setEnabled(MemcardSync::IsAvailable() && MemcardSync::IsRunning() && m_pending_applies == 0);
}
