// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.update

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Android's installer through a session: the APK written, committed, and how it ended coming back. */
@RunWith(AndroidJUnit4::class)
class InstallerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    // Allowed only, never taken back: Android kills an app when "Install unknown apps" is taken from it, which would end
    // the test's own process. The emulators keep it allowed for GooseStation Debug; nothing else depends on it.
    private fun allowInstalls() {
        instrumentation.uiAutomation
            .executeShellCommand("appops set ${app.packageName} REQUEST_INSTALL_PACKAGES allow")
            .close()
        val end = System.currentTimeMillis() + 5000
        while (!app.packageManager.canRequestPackageInstalls() && System.currentTimeMillis() < end) Thread.sleep(50)
    }

    // Not this app's own APK: Android would install it at once (an update from the app itself, same signer, needs no
    // question here), and replace the app under the test. Another package's APK runs the whole session, write, commit
    // and the status coming back, and is refused.
    @Test
    fun anotherPackagesApkComesBackFailed() =
        runBlocking {
            allowInstalls()
            val asked = mutableListOf<Intent>()
            val installer = Installer(app) { asked += it }
            assertTrue(installer.canInstall())
            val apk = File(app.cacheDir, "installer-test.apk")
            File(instrumentation.context.packageCodePath).copyTo(apk, overwrite = true)
            try {
                val outcome = withTimeout(60_000) { installer.install(apk) { show -> show() } }
                assertTrue(outcome.toString(), outcome is InstallOutcome.Failed)
                assertTrue(asked.isEmpty())
            } finally {
                apk.delete()
            }
        }

    @Test
    fun aFileThatIsNoApkComesBackFailed() =
        runBlocking {
            allowInstalls()
            val apk = File(app.cacheDir, "installer-test.apk").apply { writeText("<html>Sign in</html>") }
            try {
                val outcome = withTimeout(60_000) { Installer(app) {}.install(apk) { show -> show() } }
                assertTrue(outcome.toString(), outcome is InstallOutcome.Failed)
            } finally {
                apk.delete()
            }
        }
}
