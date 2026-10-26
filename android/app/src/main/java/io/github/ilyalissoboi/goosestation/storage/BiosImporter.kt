// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.storage

import android.content.Context
import io.github.ilyalissoboi.goosestation.core.NativeLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The images an import copied (their descriptions), or the [error] that stopped it, with no images. */
data class BiosImportResult(
    val images: List<String>,
    val error: String?,
)

/** Copies the BIOS images in a picked folder to filesDir/bios, where the core reads them. Needs [ContentBridge]. */
class BiosImporter(
    context: Context,
) {
    private val destDir = File(context.filesDir, "bios")

    /**
     * Replaces filesDir/bios with the images at the top of [treeUri]. If it finds none, or on an error, the old
     * copies are kept.
     */
    suspend fun import(treeUri: String): BiosImportResult =
        withContext(Dispatchers.IO) {
            val images = NativeLibrary.importBios(treeUri, destDir.path)
            if (images != null) {
                BiosImportResult(images.toList(), null)
            } else {
                BiosImportResult(emptyList(), "Can't read the BIOS folder.")
            }
        }
}
