// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import java.io.File
import java.util.UUID

/** Builds trees in the debug build's [TestDocumentsProvider], and turns the provider on and off. */
object TestTrees {
    private const val ENABLE_TIMEOUT_MS = 5_000L

    /** Creates a tree of [files] (relative paths to bytes), enables the provider, and returns the tree URI. */
    fun create(
        context: Context,
        files: Map<String, ByteArray>,
    ): String {
        val name = "tree-${UUID.randomUUID()}"
        val root = File(TestDocumentsProvider.baseDirectory(context), name)
        check(root.mkdirs()) { "Can't create $root" }
        for ((path, bytes) in files) {
            val file = File(root, path)
            file.parentFile!!.mkdirs()
            file.writeBytes(bytes)
        }

        setProviderEnabled(context, true)
        return DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, name).toString()
    }

    /** The folder behind [treeUri], one that [create] returned, for a test to change behind the provider's back. */
    fun directory(
        context: Context,
        treeUri: String,
    ) = File(TestDocumentsProvider.baseDirectory(context), DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)))

    /** Deletes every tree. */
    fun deleteAll(context: Context) {
        TestDocumentsProvider.baseDirectory(context).deleteRecursively()
    }

    /**
     * Enables or disables the provider, and waits until the system sees the change. DONT_KILL_APP is needed:
     * otherwise changing a component of the running app kills it, and the test with it.
     */
    fun setProviderEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        val state =
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, TestDocumentsProvider::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )

        val deadline = SystemClock.elapsedRealtime() + ENABLE_TIMEOUT_MS
        while (isProviderAvailable(context) != enabled) {
            check(SystemClock.elapsedRealtime() < deadline) { "The test provider didn't become enabled=$enabled" }
            Thread.sleep(20)
        }
    }

    private fun isProviderAvailable(context: Context): Boolean =
        context.packageManager.resolveContentProvider(TestDocumentsProvider.AUTHORITY, 0) != null
}
