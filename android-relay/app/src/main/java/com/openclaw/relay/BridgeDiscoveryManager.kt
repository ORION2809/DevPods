package com.openclaw.relay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class BridgeDiscoveryManager(context: Context) {
    companion object {
        private const val TAG = "BridgeDiscovery"
        private const val SERVICE_TYPE = "_devpods._tcp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val discoveredServices = mutableMapOf<String, NsdServiceInfo>()
    private val _discoveredBridges = MutableStateFlow<List<DiscoveredBridge>>(emptyList())
    val discoveredBridges: StateFlow<List<DiscoveredBridge>> = _discoveredBridges.asStateFlow()

    private val discoveryEvents = Channel<DiscoveryEvent>(Channel.BUFFERED)
    val events: Flow<DiscoveryEvent> = discoveryEvents.receiveAsFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    sealed class DiscoveryEvent {
        data class BridgeFound(val bridge: DiscoveredBridge) : DiscoveryEvent()
        data class BridgeLost(val name: String) : DiscoveryEvent()
        data class Error(val message: String) : DiscoveryEvent()
    }

    fun startDiscovery() {
        if (discoveryListener != null) {
            Log.d(TAG, "Discovery already active")
            return
        }

        discoveredServices.clear()
        _discoveredBridges.value = emptyList()
        RelayStateStore.setIsDiscovering(true)

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Discovery started: $regType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                if (serviceInfo.serviceType != SERVICE_TYPE) return

                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "Service resolved: ${serviceInfo.serviceName} at ${serviceInfo.host}:${serviceInfo.port}")
                        discoveredServices[serviceInfo.serviceName] = serviceInfo
                        updateDiscoveredBridges()
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                discoveredServices.remove(serviceInfo.serviceName)
                updateDiscoveredBridges()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
                discoveryListener = null
                RelayStateStore.setIsDiscovering(false)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                discoveryListener = null
                RelayStateStore.setIsDiscovering(false)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            discoveryListener = null
            RelayStateStore.setIsDiscovering(false)
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop discovery", e)
            }
        }
        discoveryListener = null
        discoveredServices.clear()
        _discoveredBridges.value = emptyList()
        RelayStateStore.setIsDiscovering(false)
    }

    private fun updateDiscoveredBridges() {
        val bridges = discoveredServices.values.map { info ->
            val txt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.attributes?.let { attrs ->
                    attrs.map { (key, value) ->
                        key to String(value, Charsets.UTF_8)
                    }.toMap()
                } ?: emptyMap()
            } else {
                emptyMap()
            }

            DiscoveredBridge(
                name = info.serviceName,
                host = info.host?.hostAddress ?: "",
                port = info.port,
                pairingBaseUrl = txt["pairingBaseUrl"],
                version = txt["version"],
            )
        }.filter { it.host.isNotBlank() }

        _discoveredBridges.value = bridges
        RelayStateStore.setDiscoveredBridges(bridges)
    }
}
