// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.app.PackJob
import io.github.ilyalissoboi.goosestation.app.PackRow
import io.github.ilyalissoboi.goosestation.app.ShaderPacksModel
import io.github.ilyalissoboi.goosestation.core.ShaderPackInfo

/** A [ShaderPacksModel] in memory, for the pages' tests: the four packs, satpixie installed; actions go to [calls]. */
class SampleShaderPacks : ShaderPacksModel {
    val calls = mutableListOf<String>()

    override var rows by mutableStateOf(
        listOf(
            row("shaders_slang", "libretro slang shaders", "Not installed", "52.5 MB", canInstall = true),
            row(
                "retro-crisis-gdv-ntsc",
                "Retro Crisis GDV-NTSC",
                "Not installed",
                "329 KB",
                canInstall = true,
                note = "The libretro slang shaders come first.",
            ),
            row("satpixie-crt", "satpixie CRT", "Installed 20260122", "48 KB", canRemove = true),
            row(
                "lola-retro",
                "lola-retro",
                "Update available: v1.18 → v1.19",
                "362 KB",
                canUpdate = true,
                canRemove = true,
            ),
        ),
    )
    override var job by mutableStateOf<PackJob?>(null)
    override var message by mutableStateOf<String?>(null)
    override var actions by mutableStateOf<String?>(null)
    override var removing by mutableStateOf<String?>(null)

    override fun open() {
        calls += "open"
    }

    override fun openActions(id: String) {
        actions = id
    }

    override fun closeActions() {
        actions = null
    }

    override fun install(id: String) {
        calls += "install $id"
        actions = null
        job = PackJob(id, false, "Downloading $id…", 5L * 1024 * 1024, 0)
    }

    override fun askRemove(id: String) {
        actions = null
        removing = id
    }

    override fun confirmRemove() {
        calls += "remove $removing"
        removing = null
    }

    override fun cancelRemove() {
        actions = removing
        removing = null
    }

    override fun removeQuestion(id: String) =
        "Remove ${rows.first { it.info.id == id }.info.name}? The files it installed are deleted."

    override fun cancel() {
        calls += "cancel"
        job = null
        message = "Cancelled."
    }

    companion object {
        fun row(
            id: String,
            name: String,
            words: String,
            size: String,
            canInstall: Boolean = false,
            canUpdate: Boolean = false,
            canRemove: Boolean = false,
            note: String? = null,
        ) = PackRow(
            ShaderPackInfo(id, name, "About $name.", "MIT", null, null, false, size),
            words,
            size,
            note,
            canInstall,
            canUpdate,
            canRemove,
        )
    }
}
