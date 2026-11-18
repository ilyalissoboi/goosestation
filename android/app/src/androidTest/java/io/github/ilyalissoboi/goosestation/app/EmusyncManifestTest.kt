// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** What the app takes from emusync's AAR manifest (AndroidManifest.xml). */
@RunWith(AndroidJUnit4::class)
class EmusyncManifestTest {
    /** The AAR's DropboxRedirectActivity is removed: GooseStation has no Dropbox sign-in to return to. */
    @Test
    fun noActivityTakesTheDropboxRedirect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val redirect =
            Intent(Intent.ACTION_VIEW, Uri.parse("${context.packageName}.emusync://dropbox?code=c&state=s"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(context.packageName)
        assertEquals(emptyList<ResolveInfo>(), context.packageManager.queryIntentActivities(redirect, 0))
    }
}
