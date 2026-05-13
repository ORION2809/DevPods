package com.openclaw.relay.signal

import android.content.Context
import android.util.Log
import com.openclaw.relay.signal.vendor.apple.ApplePodsProvider
import com.openclaw.relay.signal.vendor.genericgatt.GenericGattBatteryProvider
import com.openclaw.relay.signal.vendor.nothing.NothingEarProvider
import com.openclaw.relay.signal.vendor.oppo.OppoRealmeProvider
import com.openclaw.relay.signal.vendor.samsung.SamsungGalaxyBudsProvider
import com.openclaw.relay.signal.vendor.sony.SonyHeadphonesProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private const val TAG = "SignalProviderRegistry"

/**
 * Priority-ordered provider registry that manages all earbud signal sources.
 *
 * Priority (highest first):
 * 1. Vendor-rich providers (AirPods, Samsung, Sony, Nothing, Oppo/Realme)
 * 2. Android MediaSession provider — universal media-button wake/interrupt/approval
 * 3. Assistant entry provider — long-press assistant fallback
 * 4. Generic Bluetooth headset — connection awareness + audio route for all BT audio devices
 * 5. Generic GATT battery — BLE battery service fallback
 */
class SignalProviderRegistry(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mediaSessionProvider = AndroidMediaSessionProvider(context)
    private val assistantProvider = AssistantEntryProvider()
    private val librePodsProvider = LibrePodsAirPodsProvider(context)
    private val genericBtProvider = GenericBluetoothHeadsetProvider(context)
    private val applePodsProvider = ApplePodsProvider(context)
    private val samsungProvider = SamsungGalaxyBudsProvider(context)
    private val sonyProvider = SonyHeadphonesProvider(context)
    private val nothingProvider = NothingEarProvider(context)
    private val oppoRealmeProvider = OppoRealmeProvider(context)
    private val gattBatteryProvider = GenericGattBatteryProvider(context)

    /**
     * All registered providers in priority order (highest priority first).
     * Vendor providers at top; universal fallbacks at bottom.
     */
    private val allProviders: MutableList<EarbudSignalProvider> = mutableListOf(
        applePodsProvider,
        samsungProvider,
        sonyProvider,
        nothingProvider,
        oppoRealmeProvider,
        librePodsProvider,
        mediaSessionProvider,
        assistantProvider,
        genericBtProvider,
        gattBatteryProvider,
    )

    private val _activeProviders = MutableStateFlow<List<EarbudSignalProvider>>(emptyList())
    val activeProviders: StateFlow<List<EarbudSignalProvider>> = _activeProviders.asStateFlow()

    private val _preferredWakeProvider = MutableStateFlow<EarbudSignalProvider?>(null)
    val preferredWakeProvider: StateFlow<EarbudSignalProvider?> = _preferredWakeProvider.asStateFlow()

    private val _providerHealth = MutableStateFlow<Map<String, ProviderHealth>>(emptyMap())
    val providerHealth: StateFlow<Map<String, ProviderHealth>> = _providerHealth.asStateFlow()

    val allEvents: Flow<EarbudSignalEvent>
        get() = merge(*allProviders.map { it.events }.toTypedArray())

    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true

        allProviders.forEach { provider ->
            scope.launch {
                startProviderSafely(provider)
            }
        }

        // Monitor all provider device states for preferred provider selection
        allProviders.forEach { provider ->
            scope.launch {
                provider.deviceState.collect {
                    updatePreferredProvider()
                }
            }
        }

        _activeProviders.value = allProviders.toList()
        updatePreferredProvider()
        Log.i(TAG, "SignalProviderRegistry started with ${allProviders.size} providers")
    }

    private fun updatePreferredProvider() {
        // Priority 1: Any vendor provider with connected physical-input device
        val vendorConnected = allProviders.firstOrNull {
            it.isPhysicalInput &&
                it.providerId !in UNIVERSAL_PROVIDER_IDS &&
                it.deviceState.value?.connectionState == ConnectionState.CONNECTED
        }
        if (vendorConnected != null) {
            _preferredWakeProvider.value = vendorConnected
            return
        }

        // Priority 2: Generic Bluetooth headset with connected device
        if (genericBtProvider.deviceState.value?.connectionState == ConnectionState.CONNECTED) {
            _preferredWakeProvider.value = genericBtProvider
            return
        }

        // Priority 3: MediaSession (always active when started)
        if (mediaSessionProvider.deviceState.value?.connectionState == ConnectionState.CONNECTED) {
            _preferredWakeProvider.value = mediaSessionProvider
            return
        }

        // Priority 4: Assistant entry fallback
        _preferredWakeProvider.value = assistantProvider
    }

    private suspend fun startProviderSafely(provider: EarbudSignalProvider) {
        val previousHealth = _providerHealth.value[provider.providerId]
        try {
            provider.start()
            _providerHealth.value = _providerHealth.value + (provider.providerId to ProviderHealth(
                providerId = provider.providerId,
                status = ProviderStatus.RUNNING,
                lastError = null,
                lastEventAtMs = previousHealth?.lastEventAtMs,
            ))
        } catch (error: SecurityException) {
            Log.w(TAG, "Provider ${provider.providerId} blocked by permission: ${error.message}")
            _providerHealth.value = _providerHealth.value + (provider.providerId to ProviderHealth(
                providerId = provider.providerId,
                status = ProviderStatus.BLOCKED_PERMISSION,
                lastError = error.message,
                lastEventAtMs = previousHealth?.lastEventAtMs,
            ))
        } catch (error: Exception) {
            Log.w(TAG, "Provider ${provider.providerId} failed to start: ${error.message}")
            _providerHealth.value = _providerHealth.value + (provider.providerId to ProviderHealth(
                providerId = provider.providerId,
                status = ProviderStatus.FAILED,
                lastError = error.message,
                lastEventAtMs = previousHealth?.lastEventAtMs,
            ))
        }
    }

    fun recordProviderEvent(providerId: String) {
        val existing = _providerHealth.value[providerId] ?: return
        _providerHealth.value = _providerHealth.value + (providerId to existing.copy(
            lastEventAtMs = System.currentTimeMillis(),
        ))
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false

        scope.launch {
            allProviders.forEach {
                try {
                    it.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping provider ${it.providerId}: ${e.message}")
                }
            }
        }
        scope.cancel()
    }

    suspend fun probeAll(): Map<String, EarbudSignalProvider.ProbeResult> {
        return allProviders.associate { provider ->
            provider.providerId to provider.probe()
        }
    }

    fun getProvider(providerId: String): EarbudSignalProvider? {
        return allProviders.find { it.providerId == providerId }
    }

    fun getAssistantProvider(): AssistantEntryProvider = assistantProvider
    fun getLibrePodsProvider(): LibrePodsAirPodsProvider = librePodsProvider
    fun getMediaSessionProvider(): AndroidMediaSessionProvider = mediaSessionProvider
    fun getGenericBluetoothProvider(): GenericBluetoothHeadsetProvider = genericBtProvider
    fun getApplePodsProvider(): ApplePodsProvider = applePodsProvider
    fun getSamsungProvider(): SamsungGalaxyBudsProvider = samsungProvider
    fun getSonyProvider(): SonyHeadphonesProvider = sonyProvider

    fun registerVendorProvider(provider: EarbudSignalProvider) {
        if (allProviders.any { it.providerId == provider.providerId }) {
            Log.w(TAG, "Provider ${provider.providerId} already registered")
            return
        }
        allProviders.add(0, provider)
        _activeProviders.value = allProviders.toList()
        if (isStarted) {
            scope.launch {
                startProviderSafely(provider)
            }
        }
        updatePreferredProvider()
    }

    companion object {
        private val UNIVERSAL_PROVIDER_IDS = setOf(
            "android_media_session",
            "assistant_entry",
            "generic_bluetooth_headset",
            "generic_gatt_battery",
        )
    }
}

data class ProviderHealth(
    val providerId: String,
    val status: ProviderStatus,
    val lastError: String? = null,
    val lastEventAtMs: Long? = null,
)

enum class ProviderStatus {
    RUNNING,
    BLOCKED_PERMISSION,
    FAILED,
    STOPPED,
}
