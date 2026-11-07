// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ilyalissoboi.goosestation.core.PackProgress
import io.github.ilyalissoboi.goosestation.core.ShaderPackApi
import io.github.ilyalissoboi.goosestation.core.ShaderPackCheck
import io.github.ilyalissoboi.goosestation.core.ShaderPackInfo
import io.github.ilyalissoboi.goosestation.core.ShaderPackResult
import io.github.ilyalissoboi.goosestation.core.ShaderPackStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pack as the page shows it: its [words] (its state), its worded [size], a [note], and what can be done with it. */
data class PackRow(
    val info: ShaderPackInfo,
    val words: String,
    val size: String,
    val note: String?,
    val canInstall: Boolean,
    val canUpdate: Boolean,
    val canRemove: Boolean,
)

/** The job running: on pack [id], a removal or an install, at [step] with [value] of [range] (0: unknown). */
data class PackJob(
    val id: String,
    val removing: Boolean,
    val step: String,
    val value: Long = 0,
    val range: Long = 0,
)

/** What the shader packs page shows and does: [ShaderPacks]', which the page's tests stand in for. */
interface ShaderPacksModel {
    val rows: List<PackRow>

    /** The job running, or null. */
    val job: PackJob?

    /** How the last job went, or null. */
    val message: String?

    /** The pack whose actions show, or null. */
    val actions: String?

    /** The pack whose removal asks first, or null. */
    val removing: String?

    fun open()

    fun openActions(id: String)

    fun closeActions()

    /** Installs or updates the pack (its dependency first). */
    fun install(id: String)

    fun askRemove(id: String)

    fun confirmRemove()

    fun cancelRemove()

    /** What removing [id] asks. */
    fun removeQuestion(id: String): String

    /** Stops the running install. */
    fun cancel()
}

/** A job's progress worded: a percentage of a known [range], else the bytes so far ("48 KB", "52.5 MB"). */
fun packProgressText(
    value: Long,
    range: Long,
): String =
    when {
        range > 0 -> "${value * 100 / range}%"
        value <= 0 -> ""
        value < 1024 * 1024 -> "${(value + 1023) / 1024} KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024.0))
    }

/**
 * The shader packs: read, checked against GitHub (once a check works, once per run), installed and removed through
 * [api] on [io]. The app owns it, so a job goes on when its page closes; [onChanged] hears each job that changed the
 * packs, to read the shaders again. Main thread only.
 */
class ShaderPacks(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val api: ShaderPackApi,
    private val onChanged: () -> Unit,
) : ShaderPacksModel {
    override var rows by mutableStateOf<List<PackRow>>(emptyList())
        private set

    override var job by mutableStateOf<PackJob?>(null)
        private set

    override var message by mutableStateOf<String?>(null)
        private set

    override var actions by mutableStateOf<String?>(null)
        private set

    override var removing by mutableStateOf<String?>(null)
        private set

    private var infos = emptyList<ShaderPackInfo>()
    private var statuses = emptyMap<String, ShaderPackStatus>()
    private var checks: Map<String, ShaderPackCheck>? = null
    private var checking = false

    @Volatile private var cancelled = false

    // Reaches the page from the thread a job runs on.
    private val progress =
        object : PackProgress {
            override fun status(text: String) {
                scope.launch { job = job?.copy(step = text, value = 0, range = 0) }
            }

            override fun progress(
                value: Long,
                range: Long,
            ) {
                scope.launch { job = job?.copy(value = value, range = range) }
            }

            override fun cancelled() = cancelled
        }

    override fun open() {
        scope.launch {
            read()
            // Checked until a check works: it's tried again each time the page opens after one that didn't.
            val checked = checks?.values?.any { it.error.isEmpty() } == true
            if (checked || checking) return@launch
            checking = true
            val found = withContext(io) { api.checkShaderPacks(progress) }
            checks = found.associateBy { it.id }
            checking = false
            read()
        }
    }

    fun close() {
        actions = null
        removing = null
    }

    override fun openActions(id: String) {
        if (job == null) actions = id
    }

    override fun closeActions() {
        actions = null
    }

    override fun install(id: String) {
        actions = null
        run(id, removing = false) { listOf(id).let { api.installShaderPacks(it, progress) } }
    }

    override fun askRemove(id: String) {
        actions = null
        removing = id
    }

    override fun confirmRemove() {
        val id = removing ?: return
        removing = null
        run(id, removing = true) { listOf(ShaderPackResult(id, api.removeShaderPack(id) == null, false, "")) }
    }

    override fun cancelRemove() {
        val id = removing ?: return
        removing = null
        actions = id
    }

    override fun removeQuestion(id: String): String {
        val name = infos.firstOrNull { it.id == id }?.name ?: id
        val dependent = infos.firstOrNull { it.needs == id && it.installedVersion != null }
        return if (dependent != null) {
            "Remove $name? The ${dependent.name.substringBefore(" GDV")} presets need the $name and will stop working."
        } else {
            "Remove $name? The files it installed are deleted."
        }
    }

    override fun cancel() {
        if (job?.removing == false) cancelled = true
    }

    private fun run(
        id: String,
        removing: Boolean,
        work: () -> List<ShaderPackResult>,
    ) {
        if (job != null) return
        cancelled = false
        message = null
        job = PackJob(id, removing, if (removing) "Removing…" else "Starting…")
        scope.launch {
            val results = withContext(io) { work() }
            val failed =
                results.filter { !it.ok && !it.cancelled }.joinToString(" ") { result ->
                    "${infos.firstOrNull { it.id == result.id }?.name ?: result.id}: ${result.message}"
                }
            message =
                when {
                    results.any { it.cancelled } -> "Cancelled."
                    failed.isNotEmpty() -> "Finished with errors. $failed"
                    removing -> "Removed."
                    else -> "Done."
                }
            read()
            job = null
            onChanged()
        }
    }

    // Reads the packs and their states (with the check's versions, once it's in), on io.
    private suspend fun read() {
        val latest = checks
        val (read, described) =
            withContext(io) {
                val read = api.getShaderPacks()
                read to read.associate { it.id to api.describeShaderPack(it.id, latest?.get(it.id)?.version) }
            }
        infos = read
        statuses = described
        rows = read.map { row(it, latest) }
    }

    private fun row(
        info: ShaderPackInfo,
        latest: Map<String, ShaderPackCheck>?,
    ): PackRow {
        val status = statuses[info.id]
        val check = latest?.get(info.id)
        val installed = info.installedVersion != null || info.incomplete
        val state = status?.state
        val words =
            when {
                state == "Incomplete" -> status.words
                latest == null ->
                    if (info.installedVersion !=
                        null
                    ) {
                        "Installed ${info.installedVersion} (checking…)"
                    } else {
                        "Checking…"
                    }
                !installed && check != null && check.error.isNotEmpty() -> check.error
                else -> status?.words.orEmpty()
            }
        val needs = info.needs?.let { id -> infos.firstOrNull { it.id == id } }
        return PackRow(
            info = info,
            words = words,
            size = info.installedSize.ifEmpty { check?.size.orEmpty() },
            note = needs?.takeIf { it.installedVersion == null }?.let { "The ${it.name} come first." },
            canInstall = (state == "NotInstalled" || state == "Incomplete") && check?.version != null,
            canUpdate = state == "UpdateAvailable",
            canRemove = installed,
        )
    }
}
