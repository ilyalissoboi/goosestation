// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.settings

/**
 * A page's settings and their values, as read from settings.ini, then as the page changed them, one at a time. Kept
 * in the page's order. Equal when every value is, and, for a game's, when the game sets the same ones.
 *
 * A game's values ([readGame]) sit over the global ones: each is the game's own where its file sets it ([isSet]), and
 * else follows the global one ([global]). Setting one ([with]) makes it the game's own, even at the global value.
 */
class SettingValues private constructor(
    private val values: LinkedHashMap<Setting<*>, Any?>,
    /** The global values, for a game's; null for settings.ini's own. */
    private val globals: Map<Setting<*>, Any?>?,
    /** The settings the game sets. */
    private val set: Set<Setting<*>>,
) {
    /** Whether these are a game's, over the global ones. */
    val isGame get() = globals != null

    /** [setting]'s value in effect, the game's or else the global one; it must be one these were read with. */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(setting: Setting<T>): T {
        require(setting in values) { "${setting.section}/${setting.key} wasn't read with this page" }
        return values[setting] as T
    }

    /** Whether the game sets [setting]; never for settings.ini's own values. */
    fun isSet(setting: Setting<*>): Boolean = setting in set

    /** [setting]'s global value: for settings.ini's own values, its value. */
    @Suppress("UNCHECKED_CAST")
    fun <T> global(setting: Setting<T>): T {
        require(setting in values) { "${setting.section}/${setting.key} wasn't read with this page" }
        return (globals ?: values)[setting] as T
    }

    /** These values, with [setting]'s [value] instead: for a game's, its own from now on. */
    fun <T> with(
        setting: Setting<T>,
        value: T,
    ): SettingValues {
        require(setting in values) { "${setting.section}/${setting.key} wasn't read with this page" }
        return SettingValues(
            LinkedHashMap(values).apply { put(setting, value) },
            globals,
            if (isGame) set + setting else set,
        )
    }

    /** A game's values with [setting] following the global one again. */
    fun withGlobal(setting: Setting<*>): SettingValues {
        val globals = checkNotNull(globals) { "Only a game's values can use the global setting" }
        require(setting in values) { "${setting.section}/${setting.key} wasn't read with this page" }
        return SettingValues(LinkedHashMap(values).apply { put(setting, globals[setting]) }, globals, set - setting)
    }

    /** How many of [of] the game sets. */
    fun setCount(of: Collection<Setting<*>>): Int = of.count { it in set }

    /** The settings whose values, or whether the game sets them, differ from [old]'s, in the page's order. */
    fun changed(old: SettingValues): List<Setting<*>> =
        values.keys.filter { values[it] != old.values[it] || (it in set) != (it in old.set) }

    /**
     * Writes to [s] the settings that changed from [old], and nothing else. For a game's, [s] is its own settings
     * ([LayerSettings]): each it sets is written, and each it no longer sets is deleted.
     */
    fun save(
        s: CoreSettings,
        old: SettingValues,
    ) {
        for (setting in changed(old)) {
            if (isGame && setting !in set) {
                val layer = requireNotNull(s as? LayerSettings) { "A game's values save to its own settings" }
                layer.delete(setting.section, setting.key)
            } else {
                write(s, setting)
            }
        }
    }

    private fun <T> write(
        s: CoreSettings,
        setting: Setting<T>,
    ) = setting.write(s, get(setting))

    override fun equals(other: Any?) = other is SettingValues && values == other.values && set == other.set

    override fun hashCode() = 31 * values.hashCode() + set.hashCode()

    override fun toString() =
        values.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            "${k.key}=$v" + if (k in set) "*" else ""
        }

    companion object {
        /** [settings]' values in [s], each its default when it isn't set. */
        fun read(
            s: CoreSettings,
            settings: Collection<Setting<*>>,
        ) = SettingValues(
            LinkedHashMap<Setting<*>, Any?>().apply { for (it in settings) put(it, it.read(s)) },
            null,
            emptySet(),
        )

        /** A game's values for [settings]: each its [global] value, and the [game]'s own where its file sets it. */
        fun readGame(
            global: CoreSettings,
            game: LayerSettings,
            settings: Collection<Setting<*>>,
        ): SettingValues {
            val globals = LinkedHashMap<Setting<*>, Any?>()
            val values = LinkedHashMap<Setting<*>, Any?>()
            val set = LinkedHashSet<Setting<*>>()
            for (setting in settings) {
                globals[setting] = setting.read(global)
                if (game.contains(setting.section, setting.key)) {
                    values[setting] = setting.read(game)
                    set += setting
                } else {
                    values[setting] = globals[setting]
                }
            }
            return SettingValues(values, globals, set)
        }
    }
}
