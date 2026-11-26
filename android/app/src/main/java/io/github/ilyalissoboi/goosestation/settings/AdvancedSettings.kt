// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

import io.github.ilyalissoboi.goosestation.ui.components.NumberScale

/**
 * The Advanced page's settings, by settings.ini's names, each with the core's Android default: the useful ones only.
 * Desktop's developer tools stay in settings.ini.
 */
object AdvancedSettings {
    val LOG_LEVEL = NameSetting("Logging", "LogLevel", "Info", NameList.LogLevel)
    val LOG_TO_FILE = BoolSetting("Logging", "LogToFile", false)
    val SAVE_STATE_COMPRESSION =
        NameSetting("Main", "SaveStateCompression", "ZstDefault", NameList.SaveStateCompression)
    val LOAD_DEVICES = BoolSetting("Main", "LoadDevicesFromSaveStates", false)
    val REGION_CHECK = BoolSetting("CDROM", "RegionCheck", false)
    val BOOT_WITHOUT_SBI = BoolSetting("CDROM", "AllowBootingWithoutSBIFile", false)
    val MEMORY_EXCEPTIONS = BoolSetting("CPU", "RecompilerMemoryExceptions", false)
    val BLOCK_LINKING = BoolSetting("CPU", "RecompilerBlockLinking", true)

    /** The core's default on arm64 (`Settings::DEFAULT_CPU_FASTMEM_MODE`). */
    val FASTMEM = NameSetting("CPU", "FastmemMode", "MMap", NameList.FastmemMode)

    /** 0 is Automatic; 1 builds shaders and pipelines one at a time, as before. The device's, not a game's. */
    val SHADER_COMPILE_THREADS = IntSetting("GPU", "ShaderCompileThreads", 0)

    val all: List<Setting<*>> =
        listOf(
            LOG_LEVEL,
            LOG_TO_FILE,
            SAVE_STATE_COMPRESSION,
            LOAD_DEVICES,
            REGION_CHECK,
            BOOT_WITHOUT_SBI,
            MEMORY_EXCEPTIONS,
            BLOCK_LINKING,
            FASTMEM,
            SHADER_COMPILE_THREADS,
        )

    /** A game's page: all but logging, which desktop's Copy keeps global too, and the device's compile threads. */
    val GAME: List<Setting<*>> = all - setOf(LOG_LEVEL, LOG_TO_FILE, SHADER_COMPILE_THREADS)

    /** The compile threads' values, Automatic named with what it gives on this device ([automatic]). */
    fun compileThreadsScale(automatic: Int) =
        NumberScale.Range(0, 16, 1, presets = listOf(0, 1, 2, 4, 6, 8)) {
            when (it) {
                0 -> "Automatic ($automatic)"
                1 -> "1 (one at a time)"
                else -> "$it"
            }
        }
}

/** What Automatic gives on this device ([automatic] threads), and why it uses one ([note]), if it does. */
data class CompileThreadsInfo(
    val automatic: Int = 1,
    val note: String? = null,
)
