// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import io.github.ilyalissoboi.goosestation.core.AchievementsAccount
import io.github.ilyalissoboi.goosestation.core.AchievementsQueue
import io.github.ilyalissoboi.goosestation.core.BiosImage
import io.github.ilyalissoboi.goosestation.core.FavouriteInfo
import io.github.ilyalissoboi.goosestation.core.MemcardSyncState
import io.github.ilyalissoboi.goosestation.core.PadBinding
import io.github.ilyalissoboi.goosestation.core.PadBindingKind
import io.github.ilyalissoboi.goosestation.core.ShaderOptionInfo
import io.github.ilyalissoboi.goosestation.core.StageInfo
import io.github.ilyalissoboi.goosestation.core.SyncActivity
import io.github.ilyalissoboi.goosestation.core.SyncConflict
import io.github.ilyalissoboi.goosestation.core.SyncStatusKind
import io.github.ilyalissoboi.goosestation.core.TypeSetting
import io.github.ilyalissoboi.goosestation.settings.MemoryCoreSettings
import io.github.ilyalissoboi.goosestation.settings.RewindUsage
import io.github.ilyalissoboi.goosestation.ui.components.Choice

/**
 * A [CoreApi] with a pad's Cross and Circle bound and [images] as the BIOS images, which writes the calls that change
 * or read the core, but getPadBindings, to [calls].
 */
class FakeCoreApi(
    private val calls: MutableList<String>,
) : CoreApi {
    private val bindings = mutableMapOf<String, String?>("Cross" to "Button96", "Circle" to "Button97")
    private val hotkeys =
        mutableMapOf<String, String?>(
            "ToggleFastForward" to null,
            "ToggleAnalogMode" to null,
            "Screenshot" to null,
        )
    var images = emptyList<BiosImage>()

    /** Each pad's place in the descriptor table, by descriptor; a pad not in it has none. */
    var indices = emptyMap<String, Int>()

    /** Each controller's pad, by descriptor; one not in it is on Port 1 (pad 0). */
    var ports = emptyMap<String, Int>()

    /** Each pad's controller type, as the core names it; a pad not in it has none. */
    var portTypes = mapOf(0 to "AnalogController", 1 to "None")

    /** The ports a controller can be put on. */
    var offered = listOf(Choice(0, "Port 1"), Choice(1, "Port 2"))

    /** Runs once, as the next read of the BIOS page reaches the core, as if the user acted meanwhile. */
    var duringRead: () -> Unit = {}

    override fun getPadBindings(descriptor: String): List<PadBinding>? =
        bindings.map { (name, binding) -> PadBinding(name, name, PadBindingKind.Button, binding) }

    override fun setPadBinding(
        descriptor: String,
        name: String,
        binding: String?,
    ) {
        calls += "setPadBinding $descriptor $name $binding"
        if (name in macros) macros[name] = binding else bindings[name] = binding
    }

    override fun getPortType(pad: Int): String? = portTypes[pad]

    override fun getControllerPort(descriptor: String): Int? = ports[descriptor] ?: 0

    override fun setControllerPort(
        descriptor: String,
        pad: Int,
    ) {
        calls += "setControllerPort $descriptor $pad"
        ports = ports + (descriptor to pad)
    }

    override fun getOfferedPorts(): List<Choice<Int>> = offered

    /** Each type's settings, as the core would describe them. */
    var typeSettings = emptyMap<String, List<TypeSetting>>()

    override fun setPortType(
        pad: Int,
        type: String,
    ) {
        calls += "setPortType $pad $type"
        portTypes = portTypes + (pad to type)
    }

    override fun getTypeSettings(type: String): List<TypeSetting> = typeSettings[type].orEmpty()

    /** Each type's inputs, as the core lists them; a type not in it has Cross and Circle. */
    var typeInputs = emptyMap<String, List<PadBinding>>()

    override fun getTypeInputs(type: String): List<PadBinding> =
        typeInputs[type] ?: listOf("Cross", "Circle").map { PadBinding(it, it, PadBindingKind.Button, null) }

    /** Each controller's rumble strength, by descriptor; one not in it has 100. */
    var rumble = emptyMap<String, Int>()

    override fun getRumbleStrength(descriptor: String): Int? = rumble[descriptor] ?: 100

    override fun setRumbleStrength(
        descriptor: String,
        percent: Int,
    ) {
        calls += "setRumbleStrength $descriptor $percent"
        rumble = rumble + (descriptor to percent)
    }

    /** Each macro's trigger, as one controller has it. */
    val macros = (1..4).associate { "Macro$it" to null as String? }.toMutableMap()

    override fun getMacroBindings(descriptor: String): List<PadBinding>? =
        macros.map { (name, binding) ->
            PadBinding(name, name.replace("Macro", "Macro "), PadBindingKind.Button, binding)
        }

    override fun getHotkeyBindings(descriptor: String): List<PadBinding>? =
        hotkeys.map { (name, binding) -> PadBinding(name, name, PadBindingKind.Button, binding) }

    // As the core keeps it: Select with the button, on the pad's Android-0.
    override fun setHotkeyBinding(
        descriptor: String,
        name: String,
        button: String?,
    ) {
        calls += "setHotkeyBinding $descriptor $name $button"
        hotkeys[name] = button?.let { "Button109 & Android-0/$it" }
    }

    override fun resetPadBindings(descriptor: String) {
        calls += "resetPadBindings $descriptor"
    }

    override fun startBindingCapture(
        descriptor: String,
        selectChord: Boolean,
    ) {
        calls += "startBindingCapture $descriptor" + if (selectChord) " chord" else ""
    }

    /** Pads the table only has from their second lookup on, as a new pad's descriptor is added a moment after. */
    var notYetInTable = mutableSetOf<String>()

    override fun getControllerIndex(descriptor: String): Int? {
        calls += "getControllerIndex $descriptor"
        return if (notYetInTable.remove(descriptor)) null else indices[descriptor]
    }

    override fun cancelBindingCapture() {
        calls += "cancelBindingCapture"
    }

    override fun applySettings() {
        calls += "applySettings"
    }

    override fun isVulkanFailed(): Boolean = false

    var fastmemAvailable = true

    override fun isFastmemAvailable(): Boolean = fastmemAvailable

    /** Each choice list the core names, by kind: its values, each its own label unless [nameLabels] has one. */
    var names = mutableMapOf<String, List<String>>()

    /** The labels the core gives values in [names], where they differ from the value. */
    var nameLabels = mutableMapOf<String, String>()

    /** The memory card folder's cards. */
    var sharedCards = emptyList<String>()

    /** What each rewind state takes, in bytes of memory and of video memory. */
    var rewindPerSlot = RewindUsage(4L * MIB, 2L * MIB)

    override fun getSettingNames(kind: String): List<Choice<String>> {
        calls += "getSettingNames $kind"
        return names[kind].orEmpty().map { Choice(it, nameLabels[it] ?: it) }
    }

    /** Each game's own settings, by key: what the game settings calls read and change. */
    val gameLayers = mutableMapOf<String, MemoryCoreSettings>()

    /** The key whose game is running: [applyGameSettings] is true for it. */
    var runningKey: String? = null

    /** The input profile each game's file names. */
    var inputProfiles = emptyMap<String, String>()

    /** What Copy writes into a game's file, as settings.ini would give it. */
    var copied: Map<Pair<String, String>, String> = emptyMap()

    fun gameLayer(key: String) = gameLayers.getOrPut(key) { MemoryCoreSettings() }

    override fun applyGameSettings(key: String): Boolean {
        calls += "applyGameSettings $key"
        return key == runningKey
    }

    override fun copyGlobalSettingsToGame(key: String) {
        calls += "copyGlobalSettingsToGame $key"
        for ((at, value) in copied) gameLayer(key).setString(at.first, at.second, value)
    }

    override fun clearGameSettings(key: String) {
        calls += "clearGameSettings $key"
        gameLayers.remove(key)
    }

    override fun getGameSettingKeys(key: String): List<Pair<String, String>> = gameLayers[key]?.keys().orEmpty()

    override fun getGameInputProfile(key: String): String? = inputProfiles[key]

    /** The shaders this fake has: each name's kind and options, their values the defaults. */
    var shaderCatalog = mapOf<String, Pair<String, List<ShaderOptionInfo>>>()

    /** settings.ini, for the chain calls with no key: the chains as desktop's keys, options as comma lists. */
    val globalLayer = MemoryCoreSettings()

    var usesOpenGLES = false

    private fun chainLayer(key: String?) = if (key == null) globalLayer else gameLayer(key)

    private fun stageSection(
        section: String,
        index: Int,
    ) = "$section/Stage${index + 1}"

    override fun getPostProcessingShaders(): List<Pair<String, String>> =
        shaderCatalog.map { (name, entry) -> name to entry.first }.sortedBy { it.first.lowercase() }

    override fun getPostProcessingStages(
        key: String?,
        section: String,
    ): List<StageInfo> {
        calls += "getPostProcessingStages $key $section"
        val layer = if (key == null) globalLayer else gameLayers[key] ?: return emptyList()
        return (0 until layer.getInt(section, "StageCount", 0)).map { index ->
            val at = stageSection(section, index)
            val shader = layer.getString(at, "ShaderName", "")
            val entry = shaderCatalog[shader]
            val options =
                entry?.second.orEmpty().map { option ->
                    val set = layer.getString(at, option.name, "").takeIf { it.isNotEmpty() }
                    val value = set?.split(',')?.map { it.trim().toFloat() }?.toFloatArray() ?: option.defaultValue
                    option.withValue(value)
                }
            StageInfo(shader, entry?.first.orEmpty(), options.toTypedArray())
        }
    }

    override fun addPostProcessingStage(
        key: String?,
        section: String,
        shader: String,
    ): String? {
        calls += "addPostProcessingStage $key $section $shader"
        if (shader !in shaderCatalog) return "The shader '$shader' couldn't be loaded."
        val layer = chainLayer(key)
        val count = layer.getInt(section, "StageCount", 0)
        layer.setInt(section, "StageCount", count + 1)
        layer.setString(stageSection(section, count), "ShaderName", shader)
        return null
    }

    override fun removePostProcessingStage(
        key: String?,
        section: String,
        index: Int,
    ) {
        calls += "removePostProcessingStage $key $section $index"
        val layer = chainLayer(key)
        val count = layer.getInt(section, "StageCount", 0)
        if (index !in 0 until count) return
        for (i in index until count - 1) copyStage(layer, section, i + 1, i)
        clearStage(layer, section, count - 1)
        layer.setInt(section, "StageCount", count - 1)
    }

    override fun movePostProcessingStage(
        key: String?,
        section: String,
        index: Int,
        down: Boolean,
    ) {
        calls += "movePostProcessingStage $key $section $index $down"
        val layer = chainLayer(key)
        val count = layer.getInt(section, "StageCount", 0)
        val other = if (down) index + 1 else index - 1
        if (index !in 0 until count || other !in 0 until count) return
        val held = stageValues(layer, section, index)
        copyStage(layer, section, other, index)
        clearStage(layer, section, other)
        for ((name, value) in held) layer.setString(stageSection(section, other), name, value)
    }

    override fun setPostProcessingOption(
        key: String?,
        section: String,
        index: Int,
        name: String,
        values: FloatArray,
    ) {
        calls += "setPostProcessingOption $key $section $index $name ${values.joinToString(",")}"
        chainLayer(key).setString(stageSection(section, index), name, values.joinToString(",") { formatFloat(it) })
    }

    override fun copyPostProcessingChain(
        key: String,
        section: String,
    ) {
        calls += "copyPostProcessingChain $key $section"
        val game = gameLayer(key)
        clearChain(game, section)
        game.setBool(section, "Enabled", globalLayer.getBool(section, "Enabled", false))
        val count = globalLayer.getInt(section, "StageCount", 0)
        if (count == 0) return
        game.setInt(section, "StageCount", count)
        for (i in 0 until count) {
            for ((name, value) in stageValues(
                globalLayer,
                section,
                i,
            )) {
                game.setString(stageSection(section, i), name, value)
            }
        }
    }

    override fun clearPostProcessingChain(
        key: String,
        section: String,
    ) {
        calls += "clearPostProcessingChain $key $section"
        val game = gameLayers[key] ?: return
        clearChain(game, section)
        if (game.values.isEmpty()) gameLayers.remove(key)
    }

    /** The keys whose games' own renderer is OpenGL ES, over [usesOpenGLES]. */
    var gamesOnOpenGLES = emptySet<String>()

    override fun postProcessingUsesOpenGLES(key: String?) =
        if (key != null &&
            key in gamesOnOpenGLES
        ) {
            true
        } else {
            usesOpenGLES
        }

    /** The favourites, in order: each name and its display chain's stages, each its keys and values. */
    val favourites = mutableListOf<Pair<String, List<List<Pair<String, String>>>>>()

    /** What the next favourite edit answers instead of doing it, once: the core's refusal. */
    var favouriteRefusal: String? = null

    private fun favouriteIndex(name: String) =
        favourites.indexOfFirst { it.first.equals(name.trim(), ignoreCase = true) }

    override fun getPostProcessingFavourites(): List<FavouriteInfo> {
        calls += "getPostProcessingFavourites"
        return favourites.map { (name, stages) ->
            val shaders = stages.mapNotNull { stage -> stage.firstOrNull { it.first == "ShaderName" }?.second }
            FavouriteInfo(name, shaders.toTypedArray(), shaders.filter { it !in shaderCatalog }.toTypedArray())
        }
    }

    private fun refused(): String? = favouriteRefusal?.also { favouriteRefusal = null }

    override fun savePostProcessingFavourite(
        key: String?,
        name: String,
        replace: Boolean,
    ): String? {
        calls += "savePostProcessingFavourite $key $name $replace"
        refused()?.let { return it }
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "A favourite needs a name."
        val index = favouriteIndex(trimmed)
        if (index >= 0 && !replace) return "A favourite named $trimmed already exists."
        val section = "PostProcessing"
        val game = key?.let { gameLayers[it] }?.takeIf { it.values.containsKey("$section/Enabled") }
        val from = game ?: globalLayer
        val stages = (0 until from.getInt(section, "StageCount", 0)).map { stageValues(from, section, it) }
        if (index >= 0) favourites[index] = favourites[index].first to stages else favourites += trimmed to stages
        return null
    }

    override fun applyPostProcessingFavourite(
        key: String?,
        name: String,
    ): String? {
        calls += "applyPostProcessingFavourite $key $name"
        refused()?.let { return it }
        val index = favouriteIndex(name)
        if (index < 0) return "There's no favourite named $name."
        val section = "PostProcessing"
        val layer = chainLayer(key)
        clearChain(layer, section)
        layer.setBool(section, "Enabled", true)
        val stages = favourites[index].second
        layer.setInt(section, "StageCount", stages.size)
        stages.forEachIndexed {
            i,
            stage,
            ->
            stage.forEach { (k, v) -> layer.setString(stageSection(section, i), k, v) }
        }
        return null
    }

    override fun renamePostProcessingFavourite(
        from: String,
        to: String,
    ): String? {
        calls += "renamePostProcessingFavourite $from $to"
        refused()?.let { return it }
        val index = favouriteIndex(from)
        if (index < 0) return "There's no favourite named $from."
        val other = favouriteIndex(to)
        if (other >= 0 && other != index) return "A favourite named ${to.trim()} already exists."
        favourites[index] = to.trim() to favourites[index].second
        return null
    }

    override fun movePostProcessingFavourite(
        name: String,
        down: Boolean,
    ) {
        calls += "movePostProcessingFavourite $name $down"
        val index = favouriteIndex(name)
        val other = if (down) index + 1 else index - 1
        if (index < 0 || other !in favourites.indices) return
        favourites[index] = favourites[other].also { favourites[other] = favourites[index] }
    }

    override fun deletePostProcessingFavourite(name: String): String? {
        calls += "deletePostProcessingFavourite $name"
        refused()?.let { return it }
        val index = favouriteIndex(name)
        if (index < 0) return "There's no favourite named $name."
        favourites.removeAt(index)
        return null
    }

    override fun reloadPostProcessingShaders(): Boolean {
        calls += "reloadPostProcessingShaders"
        return runningKey != null
    }

    private fun stageValues(
        layer: MemoryCoreSettings,
        section: String,
        index: Int,
    ): List<Pair<String, String>> {
        val prefix = stageSection(section, index) + "/"
        return layer.values.filterKeys { it.startsWith(prefix) }.map { (at, value) -> at.removePrefix(prefix) to value }
    }

    private fun clearStage(
        layer: MemoryCoreSettings,
        section: String,
        index: Int,
    ) {
        val prefix = stageSection(section, index) + "/"
        layer.values.keys.removeAll { it.startsWith(prefix) }
    }

    private fun copyStage(
        layer: MemoryCoreSettings,
        section: String,
        from: Int,
        to: Int,
    ) {
        val held = stageValues(layer, section, from)
        clearStage(layer, section, to)
        for ((name, value) in held) layer.setString(stageSection(section, to), name, value)
    }

    private fun clearChain(
        layer: MemoryCoreSettings,
        section: String,
    ) {
        for (i in 0 until layer.getInt(section, "StageCount", 0)) clearStage(layer, section, i)
        layer.delete(section, "StageCount")
        layer.delete(section, "Enabled")
    }

    // As the host does: the flag while the file has a controller key, and none once it hasn't.
    override fun updateGameControllers(key: String) {
        calls += "updateGameControllers $key"
        val file = gameLayer(key)
        val flags = setOf("UseGameSettingsForController", "GameSettingsInitialized")
        val controllerKeys =
            file.keys().any { (section, name) ->
                (section == "ControllerPorts" && name !in flags) || section.matches(Regex("Pad[1-8]"))
            }
        if (controllerKeys) {
            file.setBool("ControllerPorts", "UseGameSettingsForController", true)
        } else {
            file.delete("ControllerPorts", "UseGameSettingsForController")
        }
    }

    override fun getSharedCardNames(): List<String> {
        calls += "getSharedCardNames"
        return sharedCards
    }

    /** Runs once, as the next rewind estimate is asked for, as if the user acted meanwhile. */
    var onRewind: () -> Unit = {}

    override fun getRewindMemoryUsage(slots: Int): RewindUsage {
        val action = onRewind
        onRewind = {}
        action()
        calls += "getRewindMemoryUsage $slots"
        return RewindUsage(rewindPerSlot.ramBytes * slots, rewindPerSlot.vramBytes * slots)
    }

    override fun getBiosImages(): List<BiosImage> {
        calls += "getBiosImages"
        takeDuringRead()
        return images
    }

    override fun getAutoBios(region: String): String? = images.firstOrNull()?.description

    /** The account the fake reports; [signIn] sets it signed in as the username unless [signInError] is set. */
    var account = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0)
    var signInError: String? = null

    override fun getAchievementsAccount(): AchievementsAccount {
        calls += "getAchievementsAccount"
        return account
    }

    var queue = AchievementsQueue.NONE

    override fun getAchievementsQueue(): AchievementsQueue = queue

    override fun signIn(
        username: String,
        password: String,
    ): String? {
        calls += "signIn $username"
        signInError?.let { return it }
        account =
            AchievementsAccount(
                AchievementsAccount.SIGNED_IN,
                username,
                username.replaceFirstChar { it.uppercase() },
                300,
                940,
            )
        return null
    }

    override fun signOut() {
        calls += "signOut"
        account = AchievementsAccount(AchievementsAccount.SIGNED_OUT, "", "", 0, 0)
    }

    /** Memory card sync as the fake reports it; [conflicts] are its conflicts. */
    var syncState =
        MemcardSyncState(
            SyncStatusKind.Off,
            enabled = false,
            running = false,
            applying = false,
            remoteUri = "",
            accessKeyId = "",
            deviceName = "",
            defaultDeviceName = "Goose Test Device",
            hasSecret = false,
            hasPassphrase = false,
            passphraseRecorded = false,
            startError = "",
            status = "",
            activity = SyncActivity.None,
            conflictCount = 0,
        )
    var conflicts = emptyList<SyncConflict>()

    // Set: reading the state throws it.
    var syncStateFails: RuntimeException? = null

    // Called as the state is read, before it's returned: a change sync reports meanwhile.
    var whileReadingSyncState: (() -> Unit)? = null

    override fun getMemcardSyncState(): MemcardSyncState {
        calls += "getMemcardSyncState"
        whileReadingSyncState?.let {
            whileReadingSyncState = null
            it()
        }
        syncStateFails?.let { throw it }
        return syncState
    }

    override fun applyMemcardSync() {
        calls += "applyMemcardSync"
    }

    override fun memcardSyncNow() {
        calls += "memcardSyncNow"
    }

    override fun getMemcardSyncConflicts(): List<SyncConflict> {
        calls += "getMemcardSyncConflicts"
        return conflicts
    }

    override fun resolveMemcardSyncConflict(
        id: Long,
        keepLocal: Boolean,
        remoteIndex: Int,
    ) {
        calls += "resolveMemcardSyncConflict $id $keepLocal $remoteIndex"
    }

    private fun takeDuringRead() {
        val action = duringRead
        duringRead = {}
        action()
    }

    private companion object {
        const val MIB = 1_048_576L
    }
}

/** A float as the core writes it: no trailing ".0" on a whole number. */
private fun formatFloat(value: Float) =
    if (value ==
        value.toLong().toFloat()
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }
