// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui.theme

/**
 * The design system's motion, in milliseconds: brief, and only where it explains something. Compose scales each by
 * the system's animator duration, so with "Remove animations" on they jump to their end.
 */
object Motion {
    /** The focus ring and fill fading in on the element that gains focus, and out on the one that loses it. */
    const val FOCUS_MS = 120

    /** A switch's thumb sliding to its other end, and its colours changing. */
    const val TOGGLE_MS = 120

    /** A dialog, or the pause menu, fading in as it opens. */
    const val DIALOG_MS = 160
}
