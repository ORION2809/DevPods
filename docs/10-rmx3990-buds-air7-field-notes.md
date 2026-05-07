# RMX3990 + realme Buds Air7 Field Notes

This note records the current real-device state of DevPods Relay on the RMX3990 phone with realme Buds Air7 earbuds.

It is intentionally operational rather than aspirational. The goal is to preserve what actually moved the project from simulated-only success to partial real-hardware success, and to keep the next debugging session from repeating the same dead ends.

## Current State

- Physical earbud input is now partially visible on the Android relay path.
- Real device behavior is still not reliable enough to call the tap workflow solved.
- The strongest confirmed hardware path on this stack is the standard media-button wake route exposed by Android.
- The most reliable fallback path remains assistant long press, which now doubles as an implementation interrupt while work is active.
- Spoken wake acknowledgements can succeed while follow-up STT still degrades on the same device, so audible response alone does not prove the full loop is healthy.

## Breakthrough Summary

The project stopped being simulator-only once the relay owned a real Android media session instead of behaving like a normal app screen with debug-only triggers.

The changes that mattered were:

1. Move the relay onto `MediaSessionService` with a media-style foreground notification.
2. Route Android `MEDIA_BUTTON` deliveries through `RelayMediaSessionController.kt` and handle only `ACTION_DOWN`.
3. Keep a silent Media3 player attached to that session so Android continues to treat the relay as the active media-button target.
4. Add the assistant-entry fallback through `AssistantEntryActivity.kt` and register the app as the device assistant.
5. Keep the bridge reachable at `http://127.0.0.1:4545` through `adb reverse tcp:4545 tcp:4545` and use the relay token `android-emulator-token` during device validation.
6. Chain relay speech back into listening only after the TTS acknowledgement finishes.
7. Preserve the active autonomy state in the relay so a tap or assistant long press during implementation becomes an interrupt-and-replan path instead of a second unrelated wake flow.

## What Is Actually Proven

- Simulated Android media-button dispatch reaches the bridge and produces speech.
- Explicit assistant launch reaches the relay and produces speech.
- The relay UI can distinguish physical media-button wake from manual push-to-talk and debug injection.
- On the RMX3990, at least one real earbud gesture path is now observable through the Android relay flow.
- Running or queued implementation work can now be cancelled from the bridge side instead of always being reported as unstoppable.
- Successful background work can now return a bounded autonomy plan: speak a report, wait briefly for interruption, and continue with a safe next step on silence.

## What Is Not Yet Proven

- Consistent physical delivery for every double tap.
- Reliable triple tap or long press delivery through standard Android media buttons on this earbud stack.
- Reliable STT capture after every physical wake on this device.
- A full zero-touch implementation loop that survives long sessions without any fallback trigger.

## Device-Specific Findings

### Realme Buds Air7 on RMX3990

- The buds do not expose a clean, uniform Android event surface for every gesture.
- Double tap is the most realistic standard media-button candidate.
- Vendor-specific long press behavior is better handled through the assistant role than through assumed AVRCP mappings.
- Treat triple tap and long press as device-dependent until logs prove they arrive through the same routed path.

### Audio Routing

- UI-visible Bluetooth devices must be derived from the merged audio device catalog, not from communication devices alone.
- A valid TTS response does not guarantee the microphone path is routed correctly for the next STT session.
- When STT becomes static or fails to detect speech after a successful wake acknowledgement, suspect route selection or Android speech-recognition state before suspecting the bridge.

## Reliable Validation Recipe

Run this order on the real phone.

1. Start the bridge locally and verify `/health` with the relay token.
2. Run `adb reverse tcp:4545 tcp:4545`.
3. Start DevPods Relay and confirm the media-style foreground notification is visible.
4. Confirm the app is still registered as the assistant.
5. Use the Tap Test button first to verify the TTS output path.
6. Use a physical earbud press and check the hardware-verification card immediately.
7. If the wake response speaks, test STT separately instead of assuming the whole interaction is healthy.
8. If physical taps are flaky, use assistant long press as the fallback interrupt and replan path.

## Logs And Checks That Matter

Use these checks in this order:

1. `adb logcat -d -s OpenClawRelay:I`
2. `adb shell dumpsys media_session`
3. `adb shell settings get secure assistant`
4. `adb shell cmd role get-role-holders android.app.role.ASSISTANT`
5. Relay UI hardware-verification card
6. Relay UI autonomy card

If simulated dispatch works and real taps do not, the missing link is almost always Android delivery or device-specific firmware behavior, not bridge routing.

## New Bounded Autonomy Workflow

The bridge and relay now support a bounded implementation loop instead of only one-shot spoken replies.

When a background task completes successfully:

1. The bridge returns a spoken report.
2. The response can include an `autonomy` object with the next safe intent.
3. The relay speaks the report.
4. If the user says nothing for the configured delay, the relay sends `android_autonomy_continue`.
5. If the user taps during that window, the relay cancels the active implementation context, listens, and sends `android_autonomy_interrupt` with the transcript.
6. The bridge answers with an updated plan and can again continue on silence.

This loop is intentionally bounded by the existing allowlisted intents. It does not create arbitrary shell autonomy.

## Response Contract Additions

The bridge response can now include:

```json
{
  "autonomy": {
    "phase": "report",
    "mode": "continue_on_silence",
    "summary": "Tests finished successfully.",
    "nextStep": "Refresh the repo status.",
    "continueAfterMs": 4000,
    "nextIntent": "quick_status"
  }
}
```

The relay now emits these Android-specific follow-up events when needed:

- `android_autonomy_continue`
- `android_autonomy_interrupt`

## Guidance For Future Debug Sessions

- Do not treat simulated `cmd media_session dispatch ...` success as proof of real earbud delivery.
- Do not treat a successful spoken wake acknowledgement as proof that STT is healthy.
- Do not assume every earbud gesture maps to standard Android media buttons.
- Keep assistant long press available as a fallback control surface while tap reliability is still device-dependent.
- Validate the relay UI state after every physical test, not only logcat.
- Keep the bridge on loopback plus `adb reverse` for device sessions unless there is a specific reason to test LAN routing.

## Next Engineering Moves

1. Capture repeatable real-device traces for successful and failed double-tap wake attempts.
2. Instrument the STT start path more deeply on RMX3990 when wake speech succeeds but speech capture stalls.
3. Decide whether double tap should remain the primary interrupt trigger on this hardware, or whether assistant long press should be the preferred user-facing fallback.
4. Extend the bounded autonomy loop from the current safe next-step chain into the highest-value implementation tasks that remain inside the repo allowlist.