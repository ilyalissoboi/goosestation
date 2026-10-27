// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.app.Activity
import android.content.Intent
import kotlin.system.exitProcess

/** Starts the app afresh in a new process: the core's data root can't change while it runs. */
object ProcessRestart {
    /** Everything to keep must be saved first: the process ends here. */
    fun restart(activity: Activity) {
        activity.startActivity(Intent.makeRestartActivityTask(activity.componentName))
        exitProcess(0)
    }
}
