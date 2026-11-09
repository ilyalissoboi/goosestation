// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** How a hand-off to Android's installer ended. */
sealed interface InstallOutcome {
    /** Installed: the app is being replaced. */
    data object Installed : InstallOutcome

    /** The player said no to the installer. */
    data object Aborted : InstallOutcome

    /** The installer refused it; [message] is its own words. */
    data class Failed(
        val message: String,
    ) : InstallOutcome
}

/** Android's installer, as `Updates` uses it (and its tests fake it). */
interface InstallerApi {
    /** Whether the player allows GooseStation to install apps. */
    fun canInstall(): Boolean

    /** Opens Android's setting that allows GooseStation to install apps. */
    fun openPermissionSettings()

    /**
     * Hands [apk] to the installer, and waits for how it ends. When the installer has a question for the player, [ask]
     * gets what shows it, to run when the app is on screen (Android won't show it from the background).
     */
    suspend fun install(
        apk: File,
        ask: (show: () -> Unit) -> Unit,
    ): InstallOutcome
}

/**
 * [InstallerApi] on a [PackageInstaller] session. The installer's questions and Android's settings are activities of
 * their own, which [startActivity] starts from the app's current activity.
 */
class Installer(
    private val context: Context,
    private val startActivity: (Intent) -> Unit,
) : InstallerApi {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun openPermissionSettings() =
        startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
        )

    override suspend fun install(
        apk: File,
        ask: (show: () -> Unit) -> Unit,
    ): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val session =
            try {
                withContext(Dispatchers.IO) {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(context.packageName)
                    val id = installer.createSession(params)
                    installer.openSession(id).also { session ->
                        session.openWrite(APK_ASSET, 0, apk.length()).use { out ->
                            apk.inputStream().use { it.copyTo(out) }
                            session.fsync(out)
                        }
                    }
                }
            } catch (e: IOException) {
                return InstallOutcome.Failed(e.message ?: "couldn't write the update")
            } catch (e: RuntimeException) {
                // A SecurityException, or the session refusing a write: a failure the banner names, never a crash.
                return InstallOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
        return suspendCancellableCoroutine { continuation ->
            val action = "${context.packageName}.INSTALL_STATUS.${System.nanoTime()}"
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        received: Context,
                        intent: Intent,
                    ) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        val outcome =
                            when (status) {
                                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                    userAction(intent)?.let { question -> ask { startActivity(question) } }
                                    return
                                }
                                PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Installed
                                PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Aborted
                                else ->
                                    InstallOutcome.Failed(
                                        intent.getStringExtra(
                                            PackageInstaller.EXTRA_STATUS_MESSAGE,
                                        ) ?: "status $status",
                                    )
                            }
                        Log.i(TAG, "The installer ended: $outcome")
                        context.unregisterReceiver(this)
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                }
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { session.abandon() }
            }
            // Mutable: the installer adds the status to it. Explicit to this app, so nothing else receives it.
            val status =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            try {
                session.commit(status.intentSender)
                session.close()
            } catch (e: RuntimeException) {
                runCatching { context.unregisterReceiver(receiver) }
                runCatching { session.abandon() }
                if (continuation.isActive) {
                    continuation.resume(
                        InstallOutcome.Failed(e.message ?: e.javaClass.simpleName),
                    )
                }
            }
        }
    }

    private fun userAction(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val TAG = "GooseStationUpdate"
    }
}
