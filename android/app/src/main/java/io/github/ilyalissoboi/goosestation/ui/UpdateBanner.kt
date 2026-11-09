// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyalissoboi.goosestation.app.UpdateFailure
import io.github.ilyalissoboi.goosestation.app.UpdateState
import io.github.ilyalissoboi.goosestation.ui.components.ActionButton
import io.github.ilyalissoboi.goosestation.ui.components.Notice
import io.github.ilyalissoboi.goosestation.ui.components.NoticeStatus
import io.github.ilyalissoboi.goosestation.ui.components.ProgressBar
import io.github.ilyalissoboi.goosestation.ui.components.notAFocusStop
import io.github.ilyalissoboi.goosestation.ui.theme.GooseStationTheme
import io.github.ilyalissoboi.goosestation.ui.theme.GsIcons
import io.github.ilyalissoboi.goosestation.update.AppVersion
import io.github.ilyalissoboi.goosestation.update.Changes

/**
 * What the library's update banner shows ([state]) and does. [ownVersion] is this build's, [failureMessage] the
 * installer's words for its failure. [loadChanges] gives What's new its commits (null when they couldn't be had).
 */
data class UpdateBannerModel(
    val state: UpdateState,
    val ownVersion: String,
    val failureMessage: String?,
    val onUpdate: () -> Unit,
    val onSkip: () -> Unit,
    val onHide: () -> Unit,
    val onCancel: () -> Unit,
    val onOpenSettings: () -> Unit,
    val loadChanges: suspend () -> Changes?,
)

/** Where to allow GooseStation to install apps, once the player came back from Android's settings without it. */
const val INSTALL_PERMISSION_PATH = "Settings → Apps → Special app access → Install unknown apps → GooseStation"

/**
 * The library's update banner: a newer version (Update, What's new, which calls [onWhatsNew], and Skip this version),
 * its download (progress and Cancel), and each step after it, as an info notice; a failure, with Try again, as a
 * danger one. Never a primary button. It never takes the focus itself. While the focus is in it, B or Back hide an
 * offer or a failure ([UpdateBannerModel.onHide]), as the ✕ does by touch, which is no focus stop; in the other
 * states nothing hides it. From 700 dp wide (more for larger text), the buttons sit beside the words rather than
 * under them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateBanner(
    model: UpdateBannerModel,
    onWhatsNew: () -> Unit,
    modifier: Modifier = Modifier,
    whatsNewFocus: FocusRequester? = null,
) {
    val colors = GooseStationTheme.colors
    val state = model.state
    val version =
        when (state) {
            UpdateState.None -> return
            is UpdateState.Available -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Checking -> state.release
            is UpdateState.NeedsPermission -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.Failed -> state.release
        }.versionName.let(AppVersion::short)
    val hideable = state is UpdateState.Available || state is UpdateState.Failed
    val down = remember { HashSet<Key>() }

    val (text, note) =
        when (state) {
            is UpdateState.Available ->
                "GooseStation $version is available" to
                    "You have ${AppVersion.short(model.ownVersion)}."
            is UpdateState.Downloading -> "Downloading GooseStation $version…" to megabytes(state.done, state.total)
            is UpdateState.Checking -> "Checking the download…" to null
            is UpdateState.NeedsPermission ->
                "To install the update, allow GooseStation to install apps." to
                    if (state.refused) INSTALL_PERMISSION_PATH else "Android's settings may need touch."
            is UpdateState.Installing -> "Installing GooseStation $version…" to "Android's installer may need touch."
            is UpdateState.Failed ->
                when (state.reason) {
                    UpdateFailure.Download -> "The download didn't finish. Check your network connection."
                    UpdateFailure.NotThisApp -> "The download isn't a GooseStation update, so it wasn't installed."
                    UpdateFailure.Installer -> "Android didn't install the update."
                } to model.failureMessage?.takeIf { state.reason == UpdateFailure.Installer }?.let(::breakable)
            UpdateState.None -> return
        }
    val actions: List<Pair<String, () -> Unit>> =
        when (state) {
            is UpdateState.Available ->
                listOf("Update" to model.onUpdate, "What's new" to onWhatsNew, "Skip this version" to model.onSkip)
            is UpdateState.Downloading -> listOf("Cancel" to model.onCancel)
            is UpdateState.NeedsPermission -> listOf("Open settings" to model.onOpenSettings)
            is UpdateState.Failed -> listOf("Try again" to model.onUpdate)
            else -> emptyList()
        }

    val buttons: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            actions.forEach { (label, action) ->
                ActionButton(
                    text = label,
                    onClick = action,
                    modifier =
                        if (label == "What's new" && whatsNewFocus != null) {
                            Modifier.focusRequester(whatsNewFocus)
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
    val cross: @Composable () -> Unit = {
        if (hideable) {
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .pointerInput(Unit) { detectTapGestures { model.onHide() } }
                        // A button to a screen reader, which can't tap it; still not a focus stop.
                        .semantics {
                            contentDescription = "Hide"
                            role = Role.Button
                            onClick {
                                model.onHide()
                                true
                            }
                            notAFocusStop = "B hides the banner"
                        },
                contentAlignment = Alignment.Center,
            ) {
                Icon(GsIcons.close, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
    val words: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            if (state is UpdateState.Downloading) {
                ProgressBar(fraction = if (state.total > 0) state.done.toFloat() / state.total else null)
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.ink) }
        }
    }

    // On a wide screen the buttons go beside the words, so the banner takes half the height from the games.
    BoxWithConstraints(
        modifier.onKeyEvent { event ->
            if (event.key != Key.ButtonB && event.key != Key.Back) return@onKeyEvent false
            if (!hideable) return@onKeyEvent true
            when (event.type) {
                KeyEventType.KeyDown -> down += event.key
                KeyEventType.KeyUp -> if (down.remove(event.key)) model.onHide()
            }
            true
        },
    ) {
        val wide = maxWidth >= WIDE * LocalDensity.current.fontScale
        Notice(
            status = if (state is UpdateState.Failed) NoticeStatus.Danger else NoticeStatus.Info,
            icon = if (state is UpdateState.Downloading) GsIcons.sync else null,
            actions = if (wide || actions.isEmpty()) null else buttons,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = if (wide) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) { words() }
                if (wide && actions.isNotEmpty()) buttons()
                cross()
            }
        }
    }
}

// From this width (at the normal text size; more for larger text) the buttons fit beside the words.
private val WIDE = 700.dp

/**
 * [text] that may wrap after each `_`, as the installer's codes (INSTALL_FAILED_INSUFFICIENT_STORAGE) need on a narrow
 * screen: a zero-width space follows each.
 */
internal fun breakable(text: String) = text.replace("_", "_\u200B")

/** "12 of 48 MB", in whole megabytes rounded down; "12 MB" when the size isn't known. */
internal fun megabytes(
    done: Long,
    total: Long,
): String {
    val mb = 1024 * 1024
    return if (total > 0) "${done / mb} of ${total / mb} MB" else "${done / mb} MB"
}
