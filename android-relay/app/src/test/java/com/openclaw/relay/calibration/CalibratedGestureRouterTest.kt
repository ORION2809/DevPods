package com.openclaw.relay.calibration

import com.openclaw.relay.RelayStateStore
import com.openclaw.relay.signal.BudSide
import com.openclaw.relay.signal.EarbudSignalEvent
import com.openclaw.relay.signal.GestureType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalibratedGestureRouterTest {

    @Before
    fun setUp() {
        RelayStateStore.clearCalibration()
    }

    @After
    fun tearDown() {
        RelayStateStore.clearCalibration()
    }

    private fun router(profile: EarbudCalibrationProfile? = null) = CalibratedGestureRouter {
        profile
    }

    private fun provenProfile(
        vararg mappings: Pair<GestureType, GestureAction>,
    ): EarbudCalibrationProfile {
        return EarbudCalibrationProfile(
            profileId = "test-profile",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc123",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            calibratedGestures = mappings.map { (gesture, _) ->
                CalibratedGesture(
                    requestedGesture = gesture,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = gesture,
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                )
            },
            gestureActionMap = GestureActionMap(mappings.toMap()),
            routeProof = RouteProofResult(isSuccess = true),
        )
    }

    @Test
    fun `route - no profile returns null`() {
        val router = router(null)
        val event = EarbudSignalEvent.WakeGesture(
            providerId = "android_media_session",
            deviceId = "test",
            gestureType = GestureType.SINGLE_PRESS,
        )
        assertNull(router.route(event))
    }

    @Test
    fun `route - unready profile returns null`() {
        val profile = EarbudCalibrationProfile(
            profileId = "incomplete",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            routeProof = null,
        )
        val router = router(profile)
        val event = EarbudSignalEvent.WakeGesture(
            providerId = "android_media_session",
            deviceId = "test",
            gestureType = GestureType.SINGLE_PRESS,
        )
        assertNull(router.route(event))
    }

    @Test
    fun `route - matching proven gesture returns mapped action`() {
        val profile = provenProfile(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)
        val router = router(profile)
        val event = EarbudSignalEvent.WakeGesture(
            providerId = "android_media_session",
            deviceId = "test",
            gestureType = GestureType.SINGLE_PRESS,
        )
        assertEquals(GestureAction.WAKE_AND_LISTEN, router.route(event))
    }

    @Test
    fun `route - non-matching gesture returns null`() {
        val profile = provenProfile(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)
        val router = router(profile)
        val event = EarbudSignalEvent.WakeGesture(
            providerId = "android_media_session",
            deviceId = "test",
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertNull(router.route(event))
    }

    @Test
    fun `route - observed but not proven gesture returns null`() {
        val profile = EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            calibratedGestures = listOf(
                CalibratedGesture(
                    requestedGesture = GestureType.SINGLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.SINGLE_PRESS,
                    ),
                    confidence = CalibrationConfidence.OBSERVED,
                )
            ),
            gestureActionMap = GestureActionMap(mapOf(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)),
            routeProof = RouteProofResult(isSuccess = true),
        )
        val router = router(profile)
        val event = EarbudSignalEvent.WakeGesture(
            providerId = "android_media_session",
            deviceId = "test",
            gestureType = GestureType.SINGLE_PRESS,
        )
        assertNull(router.route(event))
    }

    @Test
    fun `canRouteApproval - exact match returns true`() {
        val profile = provenProfile(
            GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN,
            GestureType.DOUBLE_PRESS to GestureAction.APPROVE,
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertTrue(router.canRouteApproval(event))
    }

    @Test
    fun `canRouteApproval - ambiguous match returns false`() {
        val profile = EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            calibratedGestures = listOf(
                CalibratedGesture(
                    requestedGesture = GestureType.DOUBLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.DOUBLE_PRESS,
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                ),
                CalibratedGesture(
                    requestedGesture = GestureType.TRIPLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.DOUBLE_PRESS, // same signal!
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = GestureActionMap(
                mapOf(
                    GestureType.DOUBLE_PRESS to GestureAction.APPROVE,
                    GestureType.TRIPLE_PRESS to GestureAction.REJECT,
                )
            ),
            routeProof = RouteProofResult(isSuccess = true),
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertFalse(router.canRouteApproval(event))
    }

    @Test
    fun `route - ready profile with routeProof succeeds`() {
        val profile = provenProfile(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)
        assertTrue(profile.isReadyForRuntime())
    }

    @Test
    fun `route - profile without routeProof is not ready`() {
        val profile = EarbudCalibrationProfile(
            profileId = "incomplete",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            calibratedGestures = listOf(
                CalibratedGesture(
                    requestedGesture = GestureType.SINGLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.SINGLE_PRESS,
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = GestureActionMap(mapOf(GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN)),
            routeProof = null,
        )
        assertFalse(profile.isReadyForRuntime())
    }

    @Test
    fun `canRouteApproval - no profile returns false`() {
        val router = router(null)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertFalse(router.canRouteApproval(event))
    }

    @Test
    fun `routeApprovalAction - exact match returns APPROVE and records matched signal`() {
        val profile = provenProfile(
            GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN,
            GestureType.DOUBLE_PRESS to GestureAction.APPROVE,
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertEquals(GestureAction.APPROVE, router.routeApprovalAction(event))
        val matched = RelayStateStore.state.value.recentMatchedSignals
        assertEquals(1, matched.size)
        assertEquals("DOUBLE_PRESS", matched.first().gestureType)
        assertEquals("APPROVE", matched.first().action)
    }

    @Test
    fun `routeApprovalAction - exact match returns REJECT and records matched signal`() {
        val profile = provenProfile(
            GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN,
            GestureType.LONG_PRESS to GestureAction.REJECT,
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.LONG_PRESS,
        )
        assertEquals(GestureAction.REJECT, router.routeApprovalAction(event))
        val matched = RelayStateStore.state.value.recentMatchedSignals
        assertEquals(1, matched.size)
        assertEquals("LONG_PRESS", matched.first().gestureType)
        assertEquals("REJECT", matched.first().action)
    }

    @Test
    fun `routeApprovalAction - match decays runtimeMissCount`() {
        RelayStateStore.setRuntimeMissCount(3)
        val profile = provenProfile(
            GestureType.SINGLE_PRESS to GestureAction.WAKE_AND_LISTEN,
            GestureType.DOUBLE_PRESS to GestureAction.APPROVE,
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertEquals(GestureAction.APPROVE, router.routeApprovalAction(event))
        assertEquals(2, RelayStateStore.state.value.runtimeMissCount)
    }



    @Test
    fun `routeApprovalAction - ambiguous match returns null`() {
        val profile = EarbudCalibrationProfile(
            profileId = "test",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            calibratedGestures = listOf(
                CalibratedGesture(
                    requestedGesture = GestureType.DOUBLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.DOUBLE_PRESS,
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                ),
                CalibratedGesture(
                    requestedGesture = GestureType.TRIPLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.DOUBLE_PRESS,
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = GestureActionMap(
                mapOf(
                    GestureType.DOUBLE_PRESS to GestureAction.APPROVE,
                    GestureType.TRIPLE_PRESS to GestureAction.REJECT,
                )
            ),
            routeProof = RouteProofResult(isSuccess = true),
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertNull(router.routeApprovalAction(event))
    }

    @Test
    fun `routeApprovalAction - no profile returns null`() {
        val router = router(null)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertNull(router.routeApprovalAction(event))
    }

    @Test
    fun `routeApprovalAction - unready profile returns null`() {
        val profile = EarbudCalibrationProfile(
            profileId = "incomplete",
            deviceModel = "Test Buds",
            deviceAddressHash = "abc",
            phoneModel = "TestPhone",
            androidVersion = "14",
            providerId = "android_media_session",
            calibratedGestures = listOf(
                CalibratedGesture(
                    requestedGesture = GestureType.DOUBLE_PRESS,
                    fingerprint = SignalFingerprint(
                        providerId = "android_media_session",
                        gestureType = GestureType.DOUBLE_PRESS,
                    ),
                    confidence = CalibrationConfidence.PROVEN,
                )
            ),
            gestureActionMap = GestureActionMap(
                mapOf(GestureType.DOUBLE_PRESS to GestureAction.APPROVE)
            ),
            routeProof = null,
        )
        val router = router(profile)
        val event = EarbudSignalEvent.ApprovalGesture(
            providerId = "android_media_session",
            deviceId = "test",
            approved = true,
            gestureType = GestureType.DOUBLE_PRESS,
        )
        assertNull(router.routeApprovalAction(event))
    }
}
