// SPDX-FileCopyrightText: 2026 GooseStation contributors
// SPDX-License-Identifier: GPL-3.0

package io.github.ilyalissoboi.goosestation.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Says when a usable network arrives: [update] is true only on a change from not validated (its start) to validated. */
class NetworkGate {
    private var validated = false

    fun update(validated: Boolean): Boolean {
        val arrived = validated && !this.validated
        this.validated = validated
        return arrived
    }
}

/**
 * Watches the default network while [start]ed, calling [onBack] (on a system thread) each time a validated one arrives:
 * waiting achievements are sent then, in the library or in a game.
 */
class NetworkWatch(
    context: Context,
    private val onBack: () -> Unit,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val gate = NetworkGate()
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        val watching =
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: Network,
                    capabilities: NetworkCapabilities,
                ) {
                    val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (synchronized(gate) { gate.update(validated) }) onBack()
                }

                override fun onLost(network: Network) {
                    synchronized(gate) { gate.update(false) }
                }
            }
        connectivity.registerDefaultNetworkCallback(watching)
        callback = watching
    }

    fun stop() {
        callback?.let { connectivity.unregisterNetworkCallback(it) }
        callback = null
        synchronized(gate) { gate.update(false) }
    }
}
