package com.openclaw.relay.calibration

import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.GestureType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarbudCalibrationEngineTest {

    private var clockMs = 1_000L

    private fun engine(channel: Channel<EarbudSignalEvent>) = EarbudCalibrationEngine(
        allProviderEvents = channel.receiveAsFlow(),
        clock = { clockMs },
        dispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `startGestureCalibration - detects single press on first attempt`() {
        runBlocking {
            val channel = Channel<EarbudSignalEvent>(capacity = 10)
            val engine = engine(channel)
            engine.startGestureCalibration("session-1", GestureType.SINGLE_PRESS, maxAttempts = 3)

            channel.send(
                EarbudSignalEvent.WakeGesture(
                    providerId = "android_media_session",
                    deviceId = "test-device",
                    gestureType = GestureType.SINGLE_PRESS,
                )
            )

            val state = engine.sessionState.value
            assertEquals(GestureType.SINGLE_PRESS, state?.requestedGesture)
            assertEquals(1, state?.observations?.size)

            val result = engine.buildResult()
            assertNotNull(result)
            assertEquals(GestureType.SINGLE_PRESS, result?.requestedGesture)
            assertEquals(CalibrationConfidence.OBSERVED, result?.confidence)
            engine.cancelCalibration()
            channel.close()
        }
    }

    @Test
    fun `startGestureCalibration - requires two observations for PROVEN`() {
        runBlocking {
            val channel = Channel<EarbudSignalEvent>(capacity = 10)
            val engine = engine(channel)
            engine.startGestureCalibration("session-2", GestureType.DOUBLE_PRESS, maxAttempts = 3)

            channel.send(
                EarbudSignalEvent.WakeGesture(
                    providerId = "android_media_session",
                    deviceId = "test-device",
                    gestureType = GestureType.DOUBLE_PRESS,
                )
            )
            // Wait for inter-attempt delay (1s) + processing margin
            Thread.sleep(1_200L)
            channel.send(
                EarbudSignalEvent.WakeGesture(
                    providerId = "android_media_session",
                    deviceId = "test-device",
                    gestureType = GestureType.DOUBLE_PRESS,
                )
            )
            Thread.sleep(200L)

            val state = engine.sessionState.value
            assertEquals(2, state?.observations?.size)

            val result = engine.buildResult()
            assertNotNull(result)
            assertEquals(CalibrationConfidence.PROVEN, result?.confidence)
            assertEquals(2, result?.repeatCount)
            assertEquals(0, result?.failureCount)
            engine.cancelCalibration()
            channel.close()
        }
    }

    @Test
    fun `startGestureCalibration - times out when no event received`() {
        runBlocking {
            val channel = Channel<EarbudSignalEvent>(capacity = 10)
            val engine = engine(channel)
            engine.startGestureCalibration("session-3", GestureType.TRIPLE_PRESS, maxAttempts = 2, timeoutPerAttemptMs = 50L)

            // Wait for both attempts to time out (includes 1s inter-attempt delay)
            Thread.sleep(1_500L)

            val state = engine.sessionState.value
            assertEquals(GestureType.TRIPLE_PRESS, state?.requestedGesture)
            assertEquals(CalibrationSessionStatus.TIMEOUT, state?.status)

            val result = engine.buildResult()
            assertNotNull(result)
            assertEquals(CalibrationConfidence.UNSUPPORTED, result?.confidence)
            engine.cancelCalibration()
            channel.close()
        }
    }

    @Test
    fun `startGestureCalibration - ignores non-matching gesture types`() {
        runBlocking {
            val channel = Channel<EarbudSignalEvent>(capacity = 10)
            val engine = engine(channel)
            engine.startGestureCalibration("session-4", GestureType.SINGLE_PRESS, maxAttempts = 1, timeoutPerAttemptMs = 50L)

            channel.send(
                EarbudSignalEvent.WakeGesture(
                    providerId = "android_media_session",
                    deviceId = "test-device",
                    gestureType = GestureType.DOUBLE_PRESS,
                )
            )
            Thread.sleep(150L)

            val state = engine.sessionState.value
            assertEquals(CalibrationSessionStatus.TIMEOUT, state?.status)
            assertEquals(0, state?.observations?.size)
            engine.cancelCalibration()
            channel.close()
        }
    }

    @Test
    fun `startGestureCalibration populates detectionLatencyMs in fingerprint`() {
        runBlocking {
            val channel = Channel<EarbudSignalEvent>(capacity = 10)
            val engine = engine(channel)
            engine.startGestureCalibration("session-latency", GestureType.SINGLE_PRESS, maxAttempts = 1)

            clockMs = 2_000L
            channel.send(
                EarbudSignalEvent.WakeGesture(
                    providerId = "android_media_session",
                    deviceId = "test-device",
                    gestureType = GestureType.SINGLE_PRESS,
                    timestamp = 2_000L,
                )
            )

            val result = engine.buildResult()
            assertNotNull(result)
            assertEquals(1_000L, result?.fingerprint?.detectionLatencyMs)
            engine.cancelCalibration()
            channel.close()
        }
    }

    @Test
    fun `detectCollision - same provider and gesture type collides`() {
        val engine = engine(Channel())

        val gestureA = CalibratedGesture(
            requestedGesture = GestureType.SINGLE_PRESS,
            fingerprint = SignalFingerprint(
                providerId = "android_media_session",
                gestureType = GestureType.SINGLE_PRESS,
            ),
            confidence = CalibrationConfidence.PROVEN,
        )
        val gestureB = CalibratedGesture(
            requestedGesture = GestureType.DOUBLE_PRESS,
            fingerprint = SignalFingerprint(
                providerId = "android_media_session",
                gestureType = GestureType.SINGLE_PRESS,
            ),
            confidence = CalibrationConfidence.PROVEN,
        )

        val collision = engine.detectCollision(gestureA, listOf(gestureB))
        assertEquals(GestureType.DOUBLE_PRESS, collision)
    }

    @Test
    fun `detectCollision - different providers do not collide`() {
        val engine = engine(Channel())

        val gestureA = CalibratedGesture(
            requestedGesture = GestureType.SINGLE_PRESS,
            fingerprint = SignalFingerprint(
                providerId = "android_media_session",
                gestureType = GestureType.SINGLE_PRESS,
            ),
            confidence = CalibrationConfidence.PROVEN,
        )
        val gestureB = CalibratedGesture(
            requestedGesture = GestureType.DOUBLE_PRESS,
            fingerprint = SignalFingerprint(
                providerId = "librepods_airpods",
                gestureType = GestureType.DOUBLE_PRESS,
            ),
            confidence = CalibrationConfidence.PROVEN,
        )

        val collision = engine.detectCollision(gestureA, listOf(gestureB))
        assertNull(collision)
    }

    @Test
    fun `cancelCalibration - stops active session`() {
        runBlocking {
            val channel = Channel<EarbudSignalEvent>(capacity = 10)
            val engine = engine(channel)
            engine.startGestureCalibration("session-5", GestureType.LONG_PRESS, maxAttempts = 3)
            engine.cancelCalibration()

            val state = engine.sessionState.value
            assertTrue(state == null || state.status != CalibrationSessionStatus.COMPLETE)
            channel.close()
        }
    }
}
