package com.sidescreen.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class LanHostDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        runCatching { appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    data class DiscoveredMac(
        val hostId: String?,
        val macName: String,
        val host: String,
        val port: Int,
        val seenAt: Long = System.currentTimeMillis(),
    )

    private val discoveredByServiceName = ConcurrentHashMap<String, DiscoveredMac>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    var onHostsUpdated: ((List<DiscoveredMac>) -> Unit)? = null

    fun currentDiscovered(): List<DiscoveredMac> = discoveredByServiceName.values.toList()

    fun start() {
        val mgr = nsdManager ?: return
        if (discoveryListener != null) return

        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "mDNS discovery started for $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (!service.serviceType.contains("_sidescreen._tcp")) return
                    resolveService(mgr, service)
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    discoveredByServiceName.remove(service.serviceName)
                    notifyUpdated()
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "mDNS discovery stopped for $serviceType")
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "mDNS start failed ($errorCode)")
                    stop()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    Log.w(TAG, "mDNS stop failed ($errorCode)")
                }
            }

        discoveryListener = listener
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start mDNS discovery: ${e.message}")
            discoveryListener = null
        }
    }

    fun stop() {
        val mgr = nsdManager ?: return
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            mgr.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    private fun resolveService(
        mgr: NsdManager,
        service: NsdServiceInfo,
    ) {
        try {
            @Suppress("DEPRECATION")
            mgr.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        Log.d(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val inetAddr =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                resolved.hostAddresses.firstOrNull { it is Inet4Address }
                                    ?: resolved.hostAddresses.firstOrNull()
                            } else {
                                resolved.host
                            }
                        val hostIp = inetAddr?.hostAddress ?: return
                        val port = resolved.port.takeIf { it in 1..65535 } ?: return
                        val attrs = resolved.attributes
                        val txtId = attrs["id"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
                        val txtName =
                            attrs["name"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
                                ?: resolved.serviceName

                        val entry =
                            DiscoveredMac(
                                hostId = txtId,
                                macName = txtName,
                                host = hostIp,
                                port = port,
                            )
                        discoveredByServiceName[resolved.serviceName] = entry
                        Log.i(TAG, "Resolved mDNS Mac: $txtName ($hostIp:$port, id=$txtId)")
                        notifyUpdated()
                    }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "resolveService exception: ${e.message}")
        }
    }

    private fun notifyUpdated() {
        onHostsUpdated?.invoke(currentDiscovered())
    }

    /**
     * Checks reachability of all [entries] in parallel, combining any fresh IP/port
     * discovered via mDNS with a fast TCP connect probe (450 ms timeout).
     * Returns the set of `hostId`s that are currently online and updates [storage]
     * if mDNS reported a new IP for a paired Mac.
     */
    suspend fun probeReachablePairedMacs(
        entries: List<PairedHostStorage.Entry>,
        storage: PairedHostStorage,
        timeoutMs: Int = 450,
    ): Set<String> =
        withContext(Dispatchers.IO) {
            val mdnsList = currentDiscovered()
            for (disc in mdnsList) {
                val matchKey = disc.hostId ?: disc.macName
                storage.updateEndpoint(matchKey, disc.host, disc.port)
            }
            val refreshed = storage.loadAll()
            coroutineScope {
                refreshed
                    .map { entry ->
                        async {
                            val mdnsHit =
                                mdnsList.any {
                                    (it.hostId != null && it.hostId == entry.hostId) ||
                                        it.macName.equals(entry.macName, ignoreCase = true)
                                }
                            val tcpOk = isTcpPortReachable(entry.host, entry.port, timeoutMs)
                            if (tcpOk || mdnsHit) entry.hostId else null
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .toSet()
            }
        }

    companion object {
        private const val TAG = "LanHostDiscovery"
        private const val SERVICE_TYPE = "_sidescreen._tcp."

        fun isTcpPortReachable(
            host: String,
            port: Int,
            timeoutMs: Int = 450,
        ): Boolean {
            return try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}
