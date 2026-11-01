// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

/**
 * What a save state shows of itself: its [picture] (null if it has none), and the number of the running game's disc it
 * was made on, 0 when that isn't known or no game runs. Made by native code.
 */
class SaveStatePreview(
    val picture: ArgbImage?,
    val disc: Int,
)
