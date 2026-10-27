// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

/**
 * The activity the app is shown in: the last one attached, until it detaches. An activity relaunched straight after
 * Back is created and started before the old one stops and is destroyed, so the app ignores lifecycle calls from any
 * activity that isn't [current]: the old one's stop must not end what the new one started.
 */
class CurrentActivity<T : Any> {
    var current: T? = null
        private set

    fun attach(activity: T) {
        current = activity
    }

    /** Detaches [activity], if it's still the current one. */
    fun detach(activity: T) {
        if (current === activity) current = null
    }

    fun isCurrent(activity: T): Boolean = current === activity
}
