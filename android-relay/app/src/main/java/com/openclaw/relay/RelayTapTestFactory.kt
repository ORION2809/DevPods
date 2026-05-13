package com.openclaw.relay

object RelayTapTestFactory {
    const val EVENT_NAME = "tap_test_button"

    fun createWakeSignal(): RelayWakeSignal {
        return RelayWakeSignal(
            trigger = EVENT_NAME,
            source = "manual_tap_test",
            sourceLabel = "Tap test button",
            provider = ManualTapTestSignalProvider.observe(),
            keyLabel = "Manual UI trigger",
        )
    }
}