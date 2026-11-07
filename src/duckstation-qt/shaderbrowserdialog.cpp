// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

#include "shaderbrowserdialog.h"

#include "util/postprocessing_browser.h"

#include <QtCore/QItemSelectionModel>
#include <QtCore/QSortFilterProxyModel>
#include <QtGui/QStandardItemModel>
#include <QtWidgets/QDialogButtonBox>
#include <QtWidgets/QHeaderView>
#include <QtWidgets/QLabel>
#include <QtWidgets/QLineEdit>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QStyle>
#include <QtWidgets/QTreeView>
#include <QtWidgets/QVBoxLayout>

#include <map>

namespace {

enum : int
{
  ROLE_PATH = Qt::UserRole + 1, ///< the folder's, or the shader's, browser path
  ROLE_INDEX, ///< a shader's index in the list, or -1 for a folder
};

} // namespace

ShaderBrowserDialog::ShaderBrowserDialog(QWidget* parent, std::vector<PostProcessing::AvailableShader> shaders,
                                         const std::string& last_folder)
  : QDialog(parent), m_shaders(std::move(shaders))
{
  setWindowTitle(tr("Add Shader"));
  resize(680, 560);

  QVBoxLayout* layout = new QVBoxLayout(this);
  m_filter = new QLineEdit(this);
  m_filter->setPlaceholderText(tr("Search by path, e.g. crt"));
  m_filter->setClearButtonEnabled(true);
  layout->addWidget(m_filter);

  m_tree = new QTreeView(this);
  m_tree->setUniformRowHeights(true);
  m_tree->setEditTriggers(QAbstractItemView::NoEditTriggers);
  m_tree->setSortingEnabled(false);
  layout->addWidget(m_tree, 1);

  m_selection = new QLabel(tr("Pick a shader."), this);
  m_selection->setWordWrap(true);
  layout->addWidget(m_selection);

  m_buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);
  m_buttons->button(QDialogButtonBox::Ok)->setText(tr("Add"));
  m_buttons->button(QDialogButtonBox::Ok)->setEnabled(false);
  layout->addWidget(m_buttons);

  m_model = new QStandardItemModel(this);
  m_model->setHorizontalHeaderLabels({tr("Name"), tr("Kind")});
  buildModel();

  m_proxy = new QSortFilterProxyModel(this);
  m_proxy->setSourceModel(m_model);
  m_proxy->setRecursiveFilteringEnabled(true);
  m_proxy->setFilterCaseSensitivity(Qt::CaseInsensitive);
  m_proxy->setFilterRole(ROLE_PATH);
  m_proxy->setFilterKeyColumn(0);
  m_tree->setModel(m_proxy);
  m_tree->header()->setSectionResizeMode(0, QHeaderView::Stretch);
  m_tree->header()->setSectionResizeMode(1, QHeaderView::ResizeToContents);
  m_tree->header()->setStretchLastSection(false);

  connect(m_filter, &QLineEdit::textChanged, this, &ShaderBrowserDialog::onFilterChanged);
  connect(m_tree->selectionModel(), &QItemSelectionModel::currentChanged, this,
          [this](const QModelIndex& current, const QModelIndex&) { onCurrentChanged(current); });
  connect(m_tree, &QTreeView::activated, this, &ShaderBrowserDialog::onActivated);
  connect(m_buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
  connect(m_buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);

  m_open_folder = PostProcessing::Browser::OpenFolder(m_shaders, last_folder);
  openFolder(m_open_folder);
  m_filter->setFocus();
}

ShaderBrowserDialog::~ShaderBrowserDialog() = default;

std::optional<std::string> ShaderBrowserDialog::selectedShader() const
{
  return m_selected.has_value() ? std::optional<std::string>(m_shaders[m_selected.value()].name) : std::nullopt;
}

std::string ShaderBrowserDialog::selectedFolder() const
{
  return m_selected.has_value() ?
           PostProcessing::Browser::FolderOf(PostProcessing::GetBrowserPath(m_shaders[m_selected.value()])) :
           m_open_folder;
}

void ShaderBrowserDialog::buildModel()
{
  const QIcon folder_icon = style()->standardIcon(QStyle::SP_DirIcon);
  std::map<QString, QStandardItem*> folders;
  QStandardItem* const root = m_model->invisibleRootItem();

  // Folders first in each folder, then shaders, as the other browsers show them.
  const auto add_row = [](QStandardItem* parent, QStandardItem* name, QStandardItem* kind, bool is_folder) {
    if (!is_folder)
    {
      parent->appendRow({name, kind});
      return;
    }
    int row = 0;
    while (row < parent->rowCount() && parent->child(row)->data(ROLE_INDEX).toInt() < 0)
      row++;
    parent->insertRow(row, {name, kind});
  };

  for (size_t i = 0; i < m_shaders.size(); i++)
  {
    const QString path = QString::fromStdString(PostProcessing::GetBrowserPath(m_shaders[i]));
    const QStringList parts = path.split(QLatin1Char('/'), Qt::SkipEmptyParts);
    if (parts.isEmpty())
      continue;

    QStandardItem* parent = root;
    QString folder_path;
    for (qsizetype p = 0; p + 1 < parts.size(); p++)
    {
      folder_path = folder_path.isEmpty() ? parts[p] : (folder_path + QLatin1Char('/') + parts[p]);
      auto it = folders.find(folder_path);
      if (it == folders.end())
      {
        QStandardItem* folder = new QStandardItem(folder_icon, parts[p]);
        folder->setEditable(false);
        folder->setData(folder_path, ROLE_PATH);
        folder->setData(-1, ROLE_INDEX);
        QStandardItem* kind = new QStandardItem();
        kind->setEditable(false);
        add_row(parent, folder, kind, true);
        it = folders.emplace(folder_path, folder).first;
      }
      parent = it->second;
    }

    QStandardItem* leaf = new QStandardItem(QString::fromStdString(PostProcessing::Browser::LeafName(m_shaders[i])));
    leaf->setEditable(false);
    leaf->setData(path, ROLE_PATH);
    leaf->setData(static_cast<int>(i), ROLE_INDEX);
    leaf->setToolTip(QString::fromStdString(m_shaders[i].name));
    QStandardItem* kind = new QStandardItem(QString::fromUtf8(PostProcessing::GetShaderKindName(m_shaders[i].kind)));
    kind->setEditable(false);
    add_row(parent, leaf, kind, false);
  }
}

QStandardItem* ShaderBrowserDialog::findFolder(const QString& folder) const
{
  if (folder.isEmpty())
    return nullptr;
  const QModelIndexList matches =
    m_model->match(m_model->index(0, 0), ROLE_PATH, folder, -1, Qt::MatchExactly | Qt::MatchRecursive);
  for (const QModelIndex& match : matches)
  {
    if (match.data(ROLE_INDEX).toInt() < 0)
      return m_model->itemFromIndex(match);
  }
  return nullptr;
}

void ShaderBrowserDialog::openFolder(const std::string& folder)
{
  m_tree->collapseAll();
  QStandardItem* item = findFolder(QString::fromStdString(folder));
  if (!item)
    return;

  const QModelIndex index = m_proxy->mapFromSource(item->index());
  for (QModelIndex parent = index; parent.isValid(); parent = parent.parent())
    m_tree->expand(parent);
  m_tree->setCurrentIndex(index);
  m_tree->scrollTo(index, QAbstractItemView::PositionAtTop);
}

void ShaderBrowserDialog::onFilterChanged(const QString& text)
{
  m_proxy->setFilterFixedString(text.trimmed());
  if (!text.trimmed().isEmpty())
    m_tree->expandAll();
  else
    openFolder(m_open_folder);
}

void ShaderBrowserDialog::onCurrentChanged(const QModelIndex& current)
{
  const int index = current.isValid() ? current.siblingAtColumn(0).data(ROLE_INDEX).toInt() : -1;
  m_selected = (index >= 0) ? std::optional<size_t>(static_cast<size_t>(index)) : std::nullopt;
  m_selection->setText(m_selected.has_value() ?
                         tr("%1 (%2)").arg(QString::fromStdString(m_shaders[m_selected.value()].name))
                           .arg(QString::fromUtf8(PostProcessing::GetShaderKindName(m_shaders[m_selected.value()].kind))) :
                         tr("Pick a shader."));
  m_buttons->button(QDialogButtonBox::Ok)->setEnabled(m_selected.has_value());
}

void ShaderBrowserDialog::onActivated(const QModelIndex& index)
{
  const int shader = index.isValid() ? index.siblingAtColumn(0).data(ROLE_INDEX).toInt() : -1;
  if (shader < 0)
    return;
  m_selected = static_cast<size_t>(shader);
  accept();
}
