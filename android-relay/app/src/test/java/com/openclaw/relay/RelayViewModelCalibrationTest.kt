package com.openclaw.relay

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.openclaw.relay.calibration.CalibrationConfidence
import com.openclaw.relay.calibration.EarbudCalibrationProfile
import com.openclaw.relay.calibration.GestureActionMap
import com.openclaw.relay.calibration.CalibratedGesture
import com.openclaw.relay.calibration.GestureAction
import com.openclaw.relay.device.DeviceProfileStorage
import com.openclaw.relay.signal.GestureType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayViewModelCalibrationTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        resetState()
        context = TestContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        resetState()
    }

    private fun resetState() {
        RelayStateStore.setSetupPhase(SetupPhase.NOT_STARTED)
        RelayStateStore.resetVoiceProofRun()
        RelayStateStore.dismissSetupWizard()
        RelayStateStore.setCapabilityMatrix(com.openclaw.relay.device.DeviceCapabilityMatrix())
        RelayStateStore.clearCalibration()
        RelayStateStore.clearError()
    }

    @Test
    fun `initialize restores persisted runtimeMissCount and sets calibrationRequired when threshold reached`() {
        val viewModel = RelayViewModel()
        val profile = EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test Buds",
            deviceAddressHash = DeviceProfileStorage.hashDeviceIdentity("Test Buds"),
            phoneModel = "Test Phone",
            androidVersion = "unknown",
            providerId = "unknown",
            calibratedGestures = listOf(
                CalibratedGesture(
                    requestedGesture = GestureType.SINGLE_PRESS,
                    confidence = CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = GestureActionMap(
                mappings = mapOf(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)
            ),
            routeProof = com.openclaw.relay.calibration.RouteProofResult(isSuccess = true),
        )
        DeviceProfileStorage.saveDeviceCalibrationProfile(context, profile)
        DeviceProfileStorage.setCurrentDevice(context, "Test Buds", "Test Phone")
        DeviceProfileStorage.saveRuntimeMissCount(context, 5)

        resetState()
        RelayStateStore.setCalibrationRequired(false)

        viewModel.initialize(context)

        assertEquals(5, RelayStateStore.state.value.runtimeMissCount)
        assertEquals(true, RelayStateStore.state.value.calibrationRequired)
    }

    @Test
    fun `skipCalibration clears state and moves to GESTURE_MAPPING`() {
        val viewModel = RelayViewModel()
        RelayStateStore.setSetupPhase(SetupPhase.CALIBRATION)

        viewModel.skipCalibration()

        assertEquals(SetupPhase.GESTURE_TEST, RelayStateStore.state.value.setupPhase)
        assertEquals(true, RelayStateStore.state.value.calibrationRequired)
        assertNull(RelayStateStore.state.value.calibrationSession)
    }

    @Test
    fun `resetCalibration clears storage and state`() {
        val viewModel = RelayViewModel()
        RelayStateStore.setCalibrationProfile(
            EarbudCalibrationProfile(
                profileId = "test",
                deviceModel = "Test",
                deviceAddressHash = "abc",
                phoneModel = "Phone",
                androidVersion = "15",
                providerId = "test",
            )
        )
        DeviceProfileStorage.saveCalibrationProfile(context, RelayStateStore.state.value.calibrationProfile!!)

        viewModel.resetCalibration(context)

        assertNull(RelayStateStore.state.value.calibrationProfile)
        assertNull(RelayStateStore.state.value.calibrationSession)
        assertEquals(true, RelayStateStore.state.value.calibrationRequired)
        assertNull(DeviceProfileStorage.loadCalibrationProfile(context))
    }

    @Test
    fun `finishCalibration with no results marks required and moves to GESTURE_TEST`() {
        val viewModel = RelayViewModel()
        RelayStateStore.setSetupPhase(SetupPhase.CALIBRATION)

        viewModel.finishCalibration(context)

        assertEquals(SetupPhase.GESTURE_TEST, RelayStateStore.state.value.setupPhase)
        assertEquals(true, RelayStateStore.state.value.calibrationRequired)
        assertNull(RelayStateStore.state.value.calibrationSession)
    }

    @Test
    fun `finishCalibration with results saves profile without route proof`() {
        saveCurrentDeviceEntry()
        val viewModel = RelayViewModel()
        RelayStateStore.setSetupPhase(SetupPhase.CALIBRATION)

        viewModel.calibrationResults.add(
            CalibratedGesture(
                requestedGesture = GestureType.SINGLE_PRESS,
                fingerprint = com.openclaw.relay.calibration.SignalFingerprint(
                    providerId = "android_media_session",
                    gestureType = GestureType.SINGLE_PRESS,
                ),
                confidence = CalibrationConfidence.PROVEN,
            )
        )
        viewModel.calibrationResults.add(
            CalibratedGesture(
                requestedGesture = GestureType.DOUBLE_PRESS,
                fingerprint = com.openclaw.relay.calibration.SignalFingerprint(
                    providerId = "android_media_session",
                    gestureType = GestureType.DOUBLE_PRESS,
                ),
                confidence = CalibrationConfidence.PROVEN,
            )
        )
        viewModel.calibrationResults.add(
            CalibratedGesture(
                requestedGesture = GestureType.LONG_PRESS,
                fingerprint = com.openclaw.relay.calibration.SignalFingerprint(
                    providerId = "android_media_session",
                    gestureType = GestureType.LONG_PRESS,
                ),
                confidence = CalibrationConfidence.PROVEN,
            )
        )

        viewModel.finishCalibration(context)

        assertEquals(SetupPhase.GESTURE_MAPPING, RelayStateStore.state.value.setupPhase)
        assertEquals(true, RelayStateStore.state.value.calibrationRequired)
        val savedProfile = DeviceProfileStorage.loadDeviceCalibrationProfile(context)
        assertNotNull(savedProfile)
        assertFalse(savedProfile!!.isReadyForRuntime())
        assertEquals(
            com.openclaw.relay.device.DeviceProfileStorage.hashDeviceIdentity("Test Buds"),
            savedProfile.deviceAddressHash
        )
        assertTrue(savedProfile.isModelScoped)
        assertNull(savedProfile.routeProof)
    }

    @Test
    fun `completeSetup updates profile with real route proof from proof run`() {
        saveCurrentDeviceEntry()
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }
        RelayStateStore.setCalibrationProfile(
            EarbudCalibrationProfile(
                profileId = "test",
                deviceModel = "Test Buds",
                deviceAddressHash = com.openclaw.relay.device.DeviceProfileStorage.hashDeviceIdentity("Test Buds"),
                phoneModel = "Test Phone",
                androidVersion = "15",
                providerId = "test",
                calibratedGestures = listOf(
                    CalibratedGesture(
                        requestedGesture = GestureType.SINGLE_PRESS,
                        confidence = CalibrationConfidence.PROVEN,
                    )
                ),
                gestureActionMap = GestureActionMap(
                    mappings = mapOf(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)
                ),
                routeProof = null,
            )
        )
        RelayStateStore.setCalibrationRequired(true)

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_PROVEN, RelayStateStore.state.value.setupPhase)
        val savedProfile = DeviceProfileStorage.loadDeviceCalibrationProfile(context)
        assertNotNull(savedProfile)
        assertTrue(savedProfile!!.isReadyForRuntime())
        assertNotNull(savedProfile.routeProof)
        assertTrue(savedProfile.routeProof!!.isSuccess)
        assertTrue(savedProfile.routeProof!!.sttSuccess)
        assertTrue(savedProfile.routeProof!!.ttsSuccess)
        assertTrue(savedProfile.routeProof!!.bargeInSuccess)
    }

    @Test
    fun `completeSetup marks DEGRADED when calibrationRequired and no profile`() {
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }
        RelayStateStore.setCalibrationRequired(true)

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_DEGRADED, RelayStateStore.state.value.setupPhase)
    }

    @Test
    fun `completeSetup marks PROVEN when calibrationRequired but profile is ready`() {
        saveCurrentDeviceEntry()
        val viewModel = RelayViewModel()
        viewModel.startVoiceProofRun()
        recordSuccessfulProofRun()
        repeat(5) { index ->
            RelayStateStore.recordTtsInterruptionMetrics(successfulInterruptionMetrics(index))
        }
        RelayStateStore.setCalibrationRequired(true)
        RelayStateStore.setCalibrationProfile(
            EarbudCalibrationProfile(
                profileId = "test",
                deviceModel = "Test",
                deviceAddressHash = "abc",
                phoneModel = "Phone",
                androidVersion = "15",
                providerId = "test",
                calibratedGestures = listOf(
                    CalibratedGesture(
                        requestedGesture = GestureType.SINGLE_PRESS,
                        confidence = CalibrationConfidence.PROVEN,
                    )
                ),
                gestureActionMap = GestureActionMap(
                    mappings = mapOf(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)
                ),
                routeProof = com.openclaw.relay.calibration.RouteProofResult(isSuccess = true),
            )
        )

        viewModel.completeSetup(context)

        assertEquals(SetupPhase.COMPLETE_PROVEN, RelayStateStore.state.value.setupPhase)
    }

    private fun saveCurrentDeviceEntry() {
        val entry = com.openclaw.relay.device.DeviceCapabilityEntry(
            deviceModel = "Test Buds",
            phoneModel = "Test Phone",
            androidVersion = "15",
            providersObserved = emptyList(),
            wakeGesture = com.openclaw.relay.device.CapabilityStatus.PROVEN,
            interruptGesture = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
            approveRejectGesture = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
            earDetection = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
            batteryStatus = com.openclaw.relay.device.CapabilityStatus.UNPROVEN,
            sttAfterWake = com.openclaw.relay.device.CapabilityStatus.PROVEN,
            ttsInterruption = com.openclaw.relay.device.CapabilityStatus.PROVEN,
        )
        com.openclaw.relay.device.DeviceProfileStorage.saveMatrix(
            context,
            com.openclaw.relay.device.DeviceCapabilityMatrix(entries = listOf(entry))
        )
        com.openclaw.relay.device.DeviceProfileStorage.setCurrentDevice(
            context,
            entry.deviceModel,
            entry.phoneModel
        )
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
