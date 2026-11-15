// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Whose line the status line shows: each kind of work in progress has one owner, which clears only its own. */
enum class Status {
    Starting,
    StartingCore,

    /** "Copying…" alone: the first pull as it starts, a catch-up, and what a start refused during one says. */
    Copying,

    /** "Copying…" with how far the first pull has got. */
    CopyingProgress,
    LookingAtFolder,
    Scanning,
    Stopping,

    /** Something to read, with no work behind it: a refusal, an answer. */
    Note,
    Error,
}

/**
 * The app's one status line: its latest error, or what it's doing. It remembers which [Status] put the line there, so
 * no owner decides anything by the line's words, and none clears another's line. Main thread only.
 */
class StatusLine(
    kind: Status? = null,
    text: String? = null,
) {
    /** The line, or null for none. */
    var text by mutableStateOf(text)
        private set

    var isError by mutableStateOf(false)
        private set

    private var kind: Status? = kind

    /** Shows [text] as [kind]'s line, in place of whatever is there, an error included. */
    fun show(
        kind: Status,
        text: String,
    ) {
        this.kind = kind
        this.text = text
        isError = kind == Status.Error
    }

    /** Shows [text] as [kind]'s line, unless an error is showing: work going on doesn't hide what went wrong. */
    fun progress(
        kind: Status,
        text: String,
    ) {
        if (!isError) show(kind, text)
    }

    fun error(text: String) = show(Status.Error, text)

    /** Whether the line is [kind]'s. */
    fun shows(kind: Status) = text != null && this.kind == kind

    /** Clears the line if it's [kind]'s: an owner's own line, and no other. */
    fun clear(kind: Status) {
        if (shows(kind)) clear()
    }

    /** Clears the line, whoever's it is: for leaving what it was about. */
    fun clear() {
        kind = null
        text = null
        isError = false
    }
}
