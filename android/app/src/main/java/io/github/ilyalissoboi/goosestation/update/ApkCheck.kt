// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** An app's identity, as Android checks an update against it: its package, version code and signing certificates. */
data class PackageFacts(
    val packageName: String,
    val versionCode: Long,
    /** Each signing certificate's SHA-256, in hex. */
    val signers: Set<String>,
)

/** Whether a downloaded APK is an update to this app, before Android's installer sees it. */
object ApkCheck {
    /**
     * Whether [candidate] (null: not an APK) is an update of [own] that the release promised: the same package, the
     * same signing certificates (none is no proof), and the release's [expectedCode], higher than [own]'s.
     */
    fun verdict(
        own: PackageFacts,
        candidate: PackageFacts?,
        expectedCode: Int,
    ): Boolean =
        candidate != null &&
            candidate.packageName == own.packageName &&
            own.signers.isNotEmpty() &&
            candidate.signers == own.signers &&
            candidate.versionCode == expectedCode.toLong() &&
            candidate.versionCode > own.versionCode

    /** This app's facts, as installed. */
    fun own(context: Context): PackageFacts {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(SIGNING.toLong()))
            } else {
                pm.getPackageInfo(context.packageName, SIGNING)
            }
        return facts(info)!!
    }

    /** [file]'s facts, or null when it isn't an APK PackageManager can read. */
    fun ofArchive(
        context: Context,
        file: File,
    ): PackageFacts? {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(SIGNING.toLong()))
            } else {
                pm.getPackageArchiveInfo(file.path, SIGNING)
            }
        return info?.let(::facts)
    }

    private const val SIGNING = PackageManager.GET_SIGNING_CERTIFICATES

    private fun facts(info: PackageInfo): PackageFacts? {
        val signing = info.signingInfo ?: return null
        // The current signers: an APK signed by several lists them all; a rotated key's history ends with the newest.
        val current =
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners.toList()
            } else {
                listOfNotNull(signing.signingCertificateHistory.lastOrNull())
            }
        val digest = MessageDigest.getInstance("SHA-256")
        val signers = current.map { digest.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        return PackageFacts(info.packageName, info.longVersionCode, signers)
    }
}
