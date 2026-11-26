package com.nx.android.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * One mDNS service type, resolved to a port when the service runs on this phone.
 *
 * Wireless debugging publishes two services: the pairing one (a port that changes) and the
 * connection one. The phone's own adbd answers on the loopback, so a service of another
 * device is not ours.
 */
internal class AdbMdns(app: Context, private val type: String, private val onPort: (Int) -> Unit) {
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var stopped = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) {}
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
        override fun onServiceLost(s: NsdServiceInfo) {}
        override fun onServiceFound(s: NsdServiceInfo) { synchronized(queue) { queue.addLast(s) }; next() }
    }

    fun start() = runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
    fun stop() { stopped = true; runCatching { nsd.stopServiceDiscovery(listener) } }

    // NsdManager resolves one service at a time (FAILURE_ALREADY_ACTIVE otherwise).
    private fun next() {
        val s = synchronized(queue) { if (resolving || stopped) null else queue.removeFirstOrNull()?.also { resolving = true } } ?: return
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, e: Int) { done() }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    @Suppress("DEPRECATION") val host = si.host
                    if (host != null && isThisPhone(host) && si.port in 1..65535) onPort(si.port)
                    done()
                }
            })
        }.onFailure { done() }
    }

    private fun done() { synchronized(queue) { resolving = false }; next() }

    private fun isThisPhone(a: InetAddress): Boolean = a.isLoopbackAddress || runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { i -> i.inetAddresses.toList().any { it.address.contentEquals(a.address) } }
    }.getOrDefault(false)
}
