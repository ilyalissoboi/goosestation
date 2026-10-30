// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The core's resources (the repository's data/resources), which the APK carries as assets under `resources/`. The core
 * reads them from files, so they're copied to `filesDir/resources` on first run and after every install or upgrade (a
 * debug build reinstalled keeps its version code, so that alone isn't enough).
 */
object Resources {
    private const val TAG = "Resources"
    private const val ASSET_DIR = "resources"
    private const val STAMP_FILE = "resources.version"

    /** Copies the resources out if they're missing or from another install, and returns their folder. Blocks. */
    @Throws(IOException::class)
    fun extract(context: Context): File {
        val target = File(context.filesDir, ASSET_DIR)
        val stamp = File(context.filesDir, STAMP_FILE)
        val version = installStamp(context)
        if (target.isDirectory && stamp.isFile && stamp.readText() == version) return target

        Log.i(TAG, "Extracting the resources for install $version.")
        // Into a fresh folder first, so a copy cut short is never taken for a complete one.
        val staging = File(context.filesDir, "$ASSET_DIR.tmp")
        staging.deleteRecursively()
        copyAssets(context.assets, ASSET_DIR, staging)

        stamp.delete()
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("Couldn't move $staging to $target.")
        stamp.writeText(version)
        return target
    }

    private fun installStamp(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.longVersionCode}-${info.lastUpdateTime}"
    }

    // AssetManager has no way to tell a file from a folder: a name with children is a folder.
    private fun copyAssets(
        assets: AssetManager,
        path: String,
        destination: File,
    ) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(path).use { input -> destination.outputStream().use { input.copyTo(it) } }
            return
        }

        if (!destination.mkdirs() && !destination.isDirectory) throw IOException("Couldn't create $destination.")
        for (child in children) copyAssets(assets, "$path/$child", File(destination, child))
    }
}
