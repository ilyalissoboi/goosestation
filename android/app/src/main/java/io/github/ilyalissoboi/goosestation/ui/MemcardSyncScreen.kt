// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.SyncSecrets
import io.github.ilyalissoboi.goosestation.app.SyncStatusWords
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.settings.CoreNames
import io.github.ilyalissoboi.goosestation.settings.MemoryCardSettings
import io.github.ilyalissoboi.goosestation.settings.SettingValues
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.ButtonKind
import io.github.ilyalissoboi.goosestation.ui.components.Choice
import io.github.ilyalissoboi.goosestation.ui.components.LabelValue
import io.github.ilyalissoboi.goosestation.ui.components.NavRow
import io.github.ilyalissoboi.goosestation.ui.components.SettingsPage
import io.github.ilyalissoboi.goosestation.ui.components.SettingsSection
import io.github.ilyalissoboi.goosestation.ui.components.SwitchRow
import io.github.ilyalissoboi.goosestation.ui.components.TextFieldRow
import io.github.ilyalissoboi.goosestation.ui.components.focusRing
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme

/**
 * Settings > Memory cards: Manage cards, which opens the memory card editor ([onManageCards]), with the
 * [cardsSummary], and has the focus on opening; then memory card sync, as desktop and Big Picture have it.
 *
 * The text settings and the secrets are rows showing their value; A, Enter or a tap on one open its field in its
 * place, with Android's keyboard, as Big Picture opens a dialog. Done hands the text to [onText] (`RemoteURI`,
 * `AccessKeyID`, `DeviceName`) or, for a secret, to [onSecret] (a [SyncSecrets] name); B, Back or leaving the field
 * close it unchanged. A stored secret is never shown, not even in its field: the row says "Saved" or "Not set".
 *
 * Remove passphrase, while there is one, calls [onRemovePassphrase]; Sync now, while sync runs, [onSyncNow]; the
 * conflicts' row, when there are any, [onOpenConflicts]. Then, once read, the [cards]' slots. B or Back go back
 * ([onBack]).
 */
@Composable
fun MemcardSyncScreen(
    state: MemcardSyncState,
    onEnabled: (Boolean) -> Unit,
    onText: (key: String, value: String) -> Unit,
    onSecret: (name: String, value: String) -> Unit,
    onRemovePassphrase: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenConflicts: () -> Unit,
    cardsSummary: String,
    onManageCards: () -> Unit,
    onBack: () -> Unit,
    cards: CardSlots? = null,
) {
    val colors = GooseStationTheme.colors
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    // The row whose field is open, by its key, or null.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // Rows that take the focus when what had it goes: Remove passphrase, the conflicts' row.
    val passphraseRow = remember { FocusRequester() }
    var conflictsFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state.conflictCount > 0) { if (state.conflictCount == 0 && conflictsFocused) first.requestFocus() }

    @Composable
    fun Editable(
        label: String,
        key: String,
        shown: String,
        initial: String,
        note: String?,
        secret: Boolean,
        focus: FocusRequester = remember { FocusRequester() },
    ) = EditableRow(
        label = label,
        focus = focus,
        shown = shown,
        initial = initial,
        note = note,
        secret = secret,
        editing = editing == key,
        onEdit = { editing = key },
        onDone = { text ->
            editing = null
            if (secret) onSecret(key, text) else onText(key, text)
        },
        onClose = { if (editing == key) editing = null },
    )

    SettingsPage("Memory cards", onBack = onBack) {
        NavRow("Manage cards", cardsSummary, onClick = onManageCards, modifier = Modifier.focusRequester(first))
        SwitchRow(
            "Sync memory cards",
            state.enabled,
            onToggle = onEnabled,
            note = "Syncs your saves with your other devices, through storage you own.",
        )
        Editable(
            "Storage",
            "RemoteURI",
            state.remoteUri.ifEmpty { NOT_SET },
            state.remoteUri,
            "The bucket and folder your devices share.",
            secret = false,
        )
        Editable(
            "Access key ID",
            "AccessKeyID",
            state.accessKeyId.ifEmpty { NOT_SET },
            state.accessKeyId,
            "From your storage provider.",
            secret = false,
        )
        Editable(
            "Secret access key",
            SyncSecrets.SECRET_ACCESS_KEY,
            if (state.hasSecret) SAVED else NOT_SET,
            "",
            "Kept in this device's Keystore, never in the data folder.",
            secret = true,
        )
        Editable(
            "Encryption passphrase",
            SyncSecrets.ENCRYPTION_PASSPHRASE,
            if (state.hasPassphrase) SAVED else NOT_SET,
            "",
            "Optional. Use the same passphrase on every device: it can't be recovered, and encryption can't be turned off later.",
            secret = true,
            focus = passphraseRow,
        )
        if (state.hasPassphrase) {
            ActionButton(
                "Remove passphrase",
                // The button goes with the passphrase: the focus goes to its row first.
                onClick = {
                    passphraseRow.requestFocus()
                    onRemovePassphrase()
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                kind = ButtonKind.Ghost,
            )
        }
        Editable(
            "Device name",
            "DeviceName",
            state.deviceName.ifEmpty { state.defaultDeviceName },
            state.deviceName,
            "How this device appears on your other devices.",
            secret = false,
        )
        // Wrapped, never cut: an error says what to do about it.
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Status", style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(SyncStatusWords.line(state), style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted)
        }
        ActionButton(
            "Sync now",
            onClick = onSyncNow,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            enabled = state.running && !state.applying,
        )
        if (state.conflictCount > 0) {
            NavRow(
                if (state.conflictCount == 1) "1 conflict" else "${state.conflictCount} conflicts",
                "Choose which version of each save to keep",
                onClick = onOpenConflicts,
                modifier = Modifier.onFocusChanged { conflictsFocused = it.isFocused },
            )
        }
        cards?.let { CardSlotRows(it) }
    }
}

/**
 * Memory cards' card slot settings: their [values], the core's [names] for the card types, the memory card folder's
 * [sharedCards], and where a change goes ([onChange]), as the whole values with that setting changed.
 */
class CardSlots(
    val values: SettingValues,
    val names: CoreNames,
    val sharedCards: List<String>,
    val onChange: (SettingValues) -> Unit,
)

/**
 * Each slot's card type, its shared card when the type is Shared, and, with [oneCardRow], one card for a game's discs.
 * With [first], the first row takes the focus as it shows, as a page's first row does.
 */
@Composable
internal fun CardSlotRows(
    cards: CardSlots,
    oneCardRow: Boolean = true,
    first: Boolean = false,
) {
    val values = cards.values
    val firstRow = remember { FocusRequester() }
    if (first) LaunchedEffect(Unit) { firstRow.requestFocus() }
    SettingsSection("Cards")
    for (slot in 1..2) {
        GameNames(
            values,
            cards.onChange,
            cards.names,
            "Card $slot type",
            MemoryCardSettings.type(slot),
            modifier = if (slot == 1) Modifier.focusRequester(firstRow) else Modifier,
        )
        if (values[MemoryCardSettings.type(slot)] == MemoryCardSettings.SHARED) {
            val path = MemoryCardSettings.path(slot)
            GameChoices(
                values,
                cards.onChange,
                "Shared card $slot",
                path,
                MemoryCardSettings.sharedCards(slot, cards.sharedCards, values[path]).let { choices ->
                    // A game's global card offered too, when the folder hasn't it.
                    if (values.isGame) {
                        offeredCard(
                            choices,
                            MemoryCardSettings.sharedCards(slot, cards.sharedCards, values.global(path)),
                        )
                    } else {
                        choices
                    }
                },
            )
        }
    }
    if (oneCardRow) {
        GameSwitch(
            values,
            cards.onChange,
            "One card for all discs of a game",
            MemoryCardSettings.ONE_CARD_PER_GAME,
            "A multi-disc game keeps its saves on one card.",
        )
    }
}

private fun offeredCard(
    choices: List<Choice<String>>,
    withGlobal: List<Choice<String>>,
) = choices + withGlobal.filter { global -> choices.none { it.value == global.value } }

private const val SAVED = "Saved"
private const val NOT_SET = "Not set"

/**
 * A setting shown as its [label] and the value it [shown]s, with its [note]; [onEdit] opens it. While [editing], its
 * field takes its place, focused, with the keyboard up, holding [initial] (masked, for a [secret]): Done calls [onDone]
 * with the text, and B, Back or the focus leaving call [onClose]. The row takes the focus back once the field closes.
 */
@Composable
private fun EditableRow(
    label: String,
    focus: FocusRequester,
    shown: String,
    initial: String,
    note: String?,
    secret: Boolean,
    editing: Boolean,
    onEdit: () -> Unit,
    onDone: (String) -> Unit,
    onClose: () -> Unit,
) {
    val colors = GooseStationTheme.colors
    val row = focus
    val field = remember { FocusRequester() }
    var wasEditing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    if (editing) {
        // Never saved across a recreation: a secret stays in this composition only, until it's handed over.
        val text = remember { TextFieldState(initial) }
        var hadFocus by remember { mutableStateOf(false) }
        BackHandler(onBack = onClose)
        LaunchedEffect(Unit) {
            field.requestFocus()
            keyboard?.show()
        }
        TextFieldRow(
            label,
            text,
            modifier =
                Modifier.focusRequester(field).onFocusChanged {
                    if (hadFocus && !it.hasFocus) onClose()
                    hadFocus = it.hasFocus
                },
            password = secret,
            imeAction = ImeAction.Done,
            onDone = { onDone(text.text.toString()) },
            // Opened on purpose, by A on the row: the keyboard comes with it.
            keyboardOnFocus = true,
        )
    } else {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(row)
                    .focusRing(role = Role.Button, onClick = onEdit)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LabelValue(
                label = { Text(label, style = MaterialTheme.typography.titleMedium, color = colors.ink) },
                value = { Text(shown, style = MaterialTheme.typography.bodyLarge, color = colors.inkMuted) },
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkMuted) }
        }
    }

    LaunchedEffect(editing) {
        if (wasEditing && !editing) row.requestFocus()
        wasEditing = editing
    }
}
