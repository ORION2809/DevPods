package com.openclaw.relay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openclaw.relay.device.CapabilityStatus
import com.openclaw.relay.device.DeviceCapabilityEntry
import com.openclaw.relay.device.DeviceCapabilityMatrix
import com.openclaw.relay.device.DeviceProfileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SetupProofGatingTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        resetRelayState()
        context = TestContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        resetRelayState()
    }

    @Test
    fun `completeSetup sets COMPLETE_DEGRADED when proof has blocking failures`() {
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()

        // Simulate a proof run with blocking failures
        RelayStateStore.recordSpeechSessionMetrics(
            SpeechSessionMetrics(
                sessionId = "test-1",
                engineId = "platform",
                startedAtMs = System.currentTimeMillis(),
                endpointReason = SpeechEndpointReason.AUDIO_ERROR,
                routeProof = AudioRouteProof(
                    routeState = AudioRouteProofState.ROUTE_BLUETOOTH_SUSPECT,
                ),
            )
        )

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `completeSetup sets COMPLETE_PROVEN when proof passed for a directly proven device`() {
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.PROVEN,
        )
        RelayStateStore.setCalibrationProfile(readyCalibrationProfile())

        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_PROVEN, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `completeSetup sets COMPLETE_DEGRADED when proof only supports a fallback wake path`() {
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.FALLBACK_PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.PROVEN,
        )

        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `completeSetup sets COMPLETE_DEGRADED when calibration is required but no profile exists`() {
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.PROVEN,
        )
        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }
        RelayStateStore.setCalibrationRequired(true)

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `completeSetup sets COMPLETE_PROVEN when calibration is required and profile exists`() {
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.PROVEN,
        )
        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }
        RelayStateStore.setCalibrationRequired(true)
        RelayStateStore.setCalibrationProfile(
            com.openclaw.relay.calibration.EarbudCalibrationProfile(
                profileId = "test",
                deviceModel = "Test",
                deviceAddressHash = "abc",
                phoneModel = "Phone",
                androidVersion = "15",
                providerId = "test",
                calibratedGestures = listOf(
                    com.openclaw.relay.calibration.CalibratedGesture(
                        requestedGesture = com.openclaw.relay.signal.GestureType.SINGLE_PRESS,
                        confidence = com.openclaw.relay.calibration.CalibrationConfidence.PROVEN,
                    )
                ),
                gestureActionMap = com.openclaw.relay.calibration.GestureActionMap(
                    mappings = mapOf(
                        com.openclaw.relay.signal.GestureType.SINGLE_PRESS to com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN
                    )
                ),
                routeProof = com.openclaw.relay.calibration.RouteProofResult(isSuccess = true),
            )
        )

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_PROVEN, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `initialize sets COMPLETE_PROVEN when saved setup has a fully proven capability entry and ready profile`(){
        UserOnboardingManager.markSetupCompleted(context)
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.PROVEN,
        )
        DeviceProfileStorage.saveDeviceCalibrationProfile(context, readyCalibrationProfile())

        val viewModel = RelayViewModel()
        viewModel.initialize(context)

        assertEquals(SetupPhase.COMPLETE_PROVEN, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `initialize sets COMPLETE_DEGRADED when saved setup has no direct proven entry`() {
        UserOnboardingManager.markSetupCompleted(context)
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.FALLBACK_PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.PROVEN,
        )

        val viewModel = RelayViewModel()
        viewModel.initialize(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `initialize sets COMPLETE_DEGRADED when saved setup lacks proven STT or TTS`() {
        UserOnboardingManager.markSetupCompleted(context)
        saveCurrentDeviceEntry(
            wakeGesture = CapabilityStatus.PROVEN,
            sttAfterWake = CapabilityStatus.PROVEN,
            ttsInterruption = CapabilityStatus.UNPROVEN,
        )

        val viewModel = RelayViewModel()
        viewModel.initialize(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `initialize sets COMPLETE_DEGRADED when saved setup has empty matrix`() {
        UserOnboardingManager.markSetupCompleted(context)
        DeviceProfileStorage.saveMatrix(context, DeviceCapabilityMatrix())

        val viewModel = RelayViewModel()
        viewModel.initialize(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    private fun resetRelayState() {
        RelayStateStore.setSetupPhase(SetupPhase.NOT_STARTED)
        RelayStateStore.resetVoiceProofRun()
        RelayStateStore.dismissSetupWizard()
        RelayStateStore.setCapabilityMatrix(DeviceCapabilityMatrix())
        RelayStateStore.clearCalibration()
        RelayStateStore.clearError()
    }

    private fun recordSuccessfulProofRun() {
        val now = System.currentTimeMillis()
        repeat(20) { i ->
            RelayStateStore.recordSpeechSessionMetrics(
                SpeechSessionMetrics(
                    sessionId = "test-$i",
                    engineId = "platform",
                    startedAtMs = now,
                    endpointReason = SpeechEndpointReason.FINAL,
                    routeProof = AudioRouteProof(
                        routeState = AudioRouteProofState.ROUTE_BLUETOOTH_ACTIVE,
                    ),
                    finalAtMs = now + 500,
                    finalTranscriptLength = 10,
                    ttsStartAtMs = now + 600,
                    ttsDoneAtMs = now + 800,
                )
            )
        }
    }

    private fun successfulInterruptionMetrics(index: Int): TtsInterruptionMetrics {
        val requestedAtMs = 1_000L + index
        return TtsInterruptionMetrics(
            interruptionId = "interrupt-$index",
            reason = TtsInterruptionReason.BARGE_IN,
            requestedAtMs = requestedAtMs,
            ttsStoppedAtMs = requestedAtMs + 100,
            listeningStartedAtMs = requestedAtMs + 150,
        )
    }

    private fun saveCurrentDeviceEntry(
        wakeGesture: CapabilityStatus,
        sttAfterWake: CapabilityStatus,
        ttsInterruption: CapabilityStatus,
    ) {
        val entry = DeviceCapabilityEntry(
            deviceModel = "Test Buds",
            phoneModel = "Test Phone",
            androidVersion = "15",
            providersObserved = emptyList(),
            wakeGesture = wakeGesture,
            interruptGesture = CapabilityStatus.UNPROVEN,
            approveRejectGesture = CapabilityStatus.UNPROVEN,
            earDetection = CapabilityStatus.UNPROVEN,
            batteryStatus = CapabilityStatus.UNPROVEN,
            sttAfterWake = sttAfterWake,
            ttsInterruption = ttsInterruption,
        )
        DeviceProfileStorage.saveMatrix(context, DeviceCapabilityMatrix(entries = listOf(entry)))
        DeviceProfileStorage.setCurrentDevice(context, entry.deviceModel, entry.phoneModel)
    }

    private fun readyCalibrationProfile(): com.openclaw.relay.calibration.EarbudCalibrationProfile {
        return com.openclaw.relay.calibration.EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test Buds",
            deviceAddressHash = DeviceProfileStorage.hashDeviceIdentity("Test Buds"),
            phoneModel = "Test Phone",
            androidVersion = android.os.Build.VERSION.RELEASE ?: "unknown",
            providerId = "test",
            calibratedGestures = listOf(
                com.openclaw.relay.calibration.CalibratedGesture(
                    requestedGesture = com.openclaw.relay.signal.GestureType.SINGLE_PRESS,
                    confidence = com.openclaw.relay.calibration.CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = com.openclaw.relay.calibration.GestureActionMap(
                mappings = mapOf(
                    com.openclaw.relay.signal.GestureType.SINGLE_PRESS to com.openclaw.relay.calibration.GestureAction.WAKE_AND_LISTEN
                )
            ),
            routeProof = com.openclaw.relay.calibration.RouteProofResult(isSuccess = true),
        )
    }

    private class TestContext : ContextWrapper(null) {
        private val prefs = mutableMapOf<String, SharedPreferences>()

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return prefs.getOrPut(name) { TestSharedPreferences() }
        }
    }

    private class TestSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any?>()
        private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): Map<String, *> = data
        override fun getString(key: String, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
            val value = data[key] as? Set<*> ?: return defValues
            return value.filterIsInstance<String>().toSet()
        }
        override fun getInt(key: String, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = TestEditor(data)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.add(listener)
        }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            listeners.remove(listener)
        }
    }

    private class TestEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableListOf<String>()
        private var clearFlag = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { removals.add(key) }
        override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
        override fun commit(): Boolean = applyChanges().let { true }
        override fun apply() { applyChanges() }

        private fun applyChanges() {
            if (clearFlag) data.clear()
            removals.forEach { data.remove(it) }
            data.putAll(pending)
            pending.clear()
            removals.clear()
            clearFlag = false
        }
    }
}
