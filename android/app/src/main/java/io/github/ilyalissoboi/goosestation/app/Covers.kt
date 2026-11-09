// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.CoverApi
import io.github.ilyalissoboi.goosestation.core.CoverProgress
import io.github.ilyalissoboi.goosestation.core.CoverSummary
import io.github.ilyalissoboi.goosestation.settings.CoreSettings
import io.github.ilyalissoboi.goosestation.settings.CoverSources
import io.github.ilyalissoboi.goosestation.settings.CoverUseSerial
import io.github.ilyalissoboi.goosestation.settings.coverSourceProblem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One of the page's sources: its [text], and an [id] that stays with it as others are added and removed. */
data class CoverSource(
    val id: Int,
    val text: String,
)

/** A download under way: at the game at [position] of [total], [title]; a null title before the first one. */
data class CoverJob(
    val title: String?,
    val position: Int = 0,
    val total: Int = 0,
)

/** What the Covers page shows and does: [Covers]', which the page's tests stand in for. */
interface CoversModel {
    /** The sources, in order; null until read. */
    val sources: List<CoverSource>?

    val useSerial: Boolean

    /** The download under way, or null. */
    val job: CoverJob?

    /** How the last download went, or null. */
    val message: String?

    /** Restore the preset source is asking first. */
    val restoring: Boolean

    /** Restore the preset source has something to do: the sources aren't the preset alone. */
    val canRestore: Boolean

    /** Why Download covers waits, or null when it can start. */
    val blocked: String?

    fun open()

    fun edit(
        id: Int,
        text: String,
    )

    /** Adds an empty source at the end, and gives its id. */
    fun add(): Int

    fun remove(id: Int)

    /** Restores the preset source, asking first when that replaces sources. */
    fun askRestore()

    fun confirmRestore()

    fun cancelRestore()

    fun changeUseSerial(on: Boolean)

    /** Downloads covers for the library's tiles without one, from the sources as they are now. */
    fun download()

    fun cancel()
}

/** Why Download covers waits while a source has no variable. */
const val SOURCES_NEED_A_VARIABLE = "Each source needs \${serial}, \${title} or \${filetitle}."

/** A download's progress, as the page shows it ("Downloading the cover for Crash Bandicoot… 12 of 48"). */
fun coverProgressText(job: CoverJob): String =
    if (job.title == null) "Starting…" else "Downloading the cover for ${job.title}… ${job.position} of ${job.total}"

/** How a download went, as the page says it. */
fun coverSummaryText(summary: CoverSummary): String {
    val found =
        when (summary.found) {
            0 -> "no covers"
            1 -> "1 cover"
            else -> "${summary.found} covers"
        }
    return when {
        summary.cancelled -> if (summary.found > 0) "Cancelled. Found $found." else "Cancelled."
        summary.total == 0 -> "Every game in the library has a cover."
        summary.found == 0 && summary.unreachable == summary.total ->
            "The sources couldn't be reached. Check your network connection."
        else ->
            buildString {
                append("Found $found.")
                if (summary.missing > 0) {
                    val games = if (summary.missing == 1) "1 game" else "${summary.missing} games"
                    append(" $games had none at these sources.")
                }
                if (summary.unreachable > 0) append(" ${summary.unreachable} couldn't be reached.")
                if (summary.unsaved > 0) append(" ${summary.unsaved} couldn't be saved.")
            }
    }
}

/**
 * The Covers page's sources and switch, read and saved through [settings] on [core], and its download, run through
 * [api] on [io]. The app owns it, so a download goes on when the page closes; [onFound] hears each cover as it lands,
 * for the library's tile. Main thread only.
 */
class Covers(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val core: CoroutineDispatcher,
    private val api: CoverApi,
    private val settings: CoreSettings,
    private val onFound: (gamePath: String, coverPath: String) -> Unit,
) : CoversModel {
    override var sources by mutableStateOf<List<CoverSource>?>(null)
        private set

    override var useSerial by mutableStateOf(true)
        private set

    override var job by mutableStateOf<CoverJob?>(null)
        private set

    override var message by mutableStateOf<String?>(null)
        private set

    override var restoring by mutableStateOf(false)
        private set

    override val canRestore get() = texts() != listOf(CoverSources.PRESET)

    override val blocked: String?
        get() {
            val texts = texts()
            return when {
                texts.isEmpty() -> "Add a source first."
                texts.any { coverSourceProblem(it) != null } -> SOURCES_NEED_A_VARIABLE
                else -> null
            }
        }

    private var nextId = 0

    // An edit's save, waiting for the typing to pause.
    private var pendingSave: Job? = null

    @Volatile private var cancelled = false

    // Reaches the page from the thread the download runs on.
    private val progress =
        object : CoverProgress {
            override fun game(
                position: Int,
                total: Int,
                title: String,
            ) {
                scope.launch { job = job?.copy(title = title, position = position, total = total) }
            }

            override fun found(
                gamePath: String,
                coverPath: String,
            ) {
                scope.launch { onFound(gamePath, coverPath) }
            }

            override fun cancelled() = cancelled
        }

    /** Reads the sources and the switch, from none shown, so the page focuses its first row once they're in. */
    override fun open() {
        restoring = false
        // An edit still waiting is saved first, and so read back.
        if (pendingSave?.isActive == true) save()
        sources = null
        scope.launch {
            val (read, serial) = withContext(core) { CoverSources.load(settings) to CoverUseSerial.load(settings) }
            sources = read.map(::newSource)
            useSerial = serial
        }
    }

    /** The page closes: an edit still waiting is saved. */
    fun close() {
        restoring = false
        if (pendingSave?.isActive == true) save()
    }

    /** A game starts, or the app is hidden: an edit still waiting is saved, and a download is cancelled. */
    fun stop() {
        close()
        cancel()
    }

    override fun edit(
        id: Int,
        text: String,
    ) {
        val list = sources ?: return
        if (list.none { it.id == id && it.text != text }) return
        sources = list.map { if (it.id == id) it.copy(text = text) else it }
        pendingSave?.cancel()
        pendingSave =
            scope.launch {
                delay(SAVE_DELAY_MS)
                save()
            }
    }

    override fun add(): Int {
        val source = newSource("")
        sources = sources.orEmpty() + source
        save()
        return source.id
    }

    override fun remove(id: Int) {
        sources = sources?.filterNot { it.id == id } ?: return
        save()
    }

    override fun askRestore() {
        if (texts().isEmpty()) confirmRestore() else restoring = true
    }

    override fun confirmRestore() {
        restoring = false
        sources = listOf(newSource(CoverSources.PRESET))
        save()
    }

    override fun cancelRestore() {
        restoring = false
    }

    override fun changeUseSerial(on: Boolean) {
        useSerial = on
        scope.launch(core) { CoverUseSerial.save(settings, on) }
    }

    override fun download() {
        if (job != null || sources == null || blocked != null) return
        if (pendingSave?.isActive == true) save()
        val texts = texts()
        val serial = useSerial
        cancelled = false
        message = null
        job = CoverJob(null)
        scope.launch {
            val summary = withContext(io) { api.downloadCovers(texts, serial, progress) }
            message = coverSummaryText(summary)
            job = null
        }
    }

    override fun cancel() {
        if (job != null) cancelled = true
    }

    private fun newSource(text: String) = CoverSource(nextId++, text)

    private fun texts() = sources.orEmpty().map { it.text }.filter { it.isNotBlank() }

    // Queued on core after any save before it, so the last one written is the newest.
    private fun save() {
        pendingSave?.cancel()
        pendingSave = null
        val texts = sources?.map { it.text } ?: return
        scope.launch(core) { CoverSources.save(settings, texts) }
    }

    private companion object {
        const val SAVE_DELAY_MS = 500L
    }
}
