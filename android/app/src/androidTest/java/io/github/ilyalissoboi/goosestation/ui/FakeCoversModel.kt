// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.app.CoverJob
import io.github.ilyalissoboi.goosestation.app.CoverSource
import io.github.ilyalissoboi.goosestation.app.CoversModel
import io.github.ilyalissoboi.goosestation.app.SOURCES_NEED_A_VARIABLE
import io.github.ilyalissoboi.goosestation.settings.CoverSources
import io.github.ilyalissoboi.goosestation.settings.coverSourceProblem

/** A Covers page model in memory, as the app's behaves but for the download, which a test sets [job] for. */
class FakeCoversModel(
    texts: List<String> = listOf(CoverSources.PRESET),
    job: CoverJob? = null,
    message: String? = null,
) : CoversModel {
    private var nextId = 0

    override var sources by mutableStateOf<List<CoverSource>?>(texts.map { CoverSource(nextId++, it) })
    override var useSerial by mutableStateOf(true)
    override var job by mutableStateOf(job)
    override var message by mutableStateOf(message)
    override var restoring by mutableStateOf(false)

    val edits = mutableListOf<String>()
    var downloads = 0
    var cancels = 0

    private fun texts() = sources.orEmpty().map { it.text }.filter { it.isNotBlank() }

    override val canRestore get() = texts() != listOf(CoverSources.PRESET)

    override val blocked: String?
        get() =
            when {
                texts().isEmpty() -> "Add a source first."
                texts().any { coverSourceProblem(it) != null } -> SOURCES_NEED_A_VARIABLE
                else -> null
            }

    override fun open() {}

    override fun edit(
        id: Int,
        text: String,
    ) {
        if (sources!!.none { it.id == id && it.text != text }) return
        edits += text
        sources = sources!!.map { if (it.id == id) it.copy(text = text) else it }
    }

    override fun add(): Int {
        val source = CoverSource(nextId++, "")
        sources = sources!! + source
        return source.id
    }

    override fun remove(id: Int) {
        sources = sources!!.filterNot { it.id == id }
    }

    override fun askRestore() {
        if (texts().isEmpty()) confirmRestore() else restoring = true
    }

    override fun confirmRestore() {
        restoring = false
        sources = listOf(CoverSource(nextId++, CoverSources.PRESET))
    }

    override fun cancelRestore() {
        restoring = false
    }

    override fun changeUseSerial(on: Boolean) {
        useSerial = on
    }

    override fun download() {
        downloads++
        job = CoverJob(null)
    }

    override fun cancel() {
        cancels++
    }
}
