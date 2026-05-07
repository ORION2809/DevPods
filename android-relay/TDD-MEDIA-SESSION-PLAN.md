# TDD Plan: Fix Bluetooth Media Button Routing

## Problem Statement
Physical Bluetooth headset media buttons are **not** reaching `RelayMediaSessionController` because the MediaSession's PlaybackState is `NONE`. Android only routes media button events to sessions with an active playback state.

## Root Cause
`RelayMediaSessionController` creates a MediaSession but never calls `player.prepare()`, leaving the player in `STATE_IDLE` (equivalent to PlaybackState.NONE). Android's MediaButtonReceiver skips such sessions.

---

## TDD Approach: Red → Green → Refactor

### Phase 1: Write RED Tests (Local JVM)
**File:** `android-relay/app/src/test/java/com/openclaw/relay/RelayMediaSessionControllerTest.kt`

✅ **What to test locally:**
- [ ] Player is in STATE_READY (not STATE_IDLE)
- [ ] MediaSession callback is registered
- [ ] Wake signal has correct trigger/source/keyLabel
- [ ] Only ACTION_UP events trigger wake signal
- [ ] Unknown keycodes are ignored
- [ ] Controller package is captured

❌ **What CANNOT be tested locally:**
- Android actually routing button events to your session
- Bluetooth transport delivering KeyEvent
- Race conditions with Spotify/YouTube Music
- Audio focus interactions

---

### Phase 2: Make Tests GREEN

**Changes to `RelayMediaSessionController.kt`:**

1. **Prepare player to set STATE_READY:**
   ```kotlin
   init {
       player.prepare()
       player.playWhenReady = false  // Ready but not playing
   }
   ```

2. **Add test-only methods** (marked with `@VisibleForTesting`):
   ```kotlin
   @VisibleForTesting
   internal fun getPlayerState(): Int = player.playbackState

   @VisibleForTesting
   internal fun simulateMediaButtonPress(keyCode: Int) {
       val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
           putExtra(Intent.EXTRA_KEY_EVENT, 
               KeyEvent(KeyEvent.ACTION_UP, keyCode))
       }
       simulateMediaButtonEvent(intent)
   }

   @VisibleForTesting
   internal fun simulateMediaButtonEvent(intent: Intent) {
       // Create mock ControllerInfo for testing
       val mockInfo = /* ... */
       mediaSession.callback?.onMediaButtonEvent(mediaSession, mockInfo, intent)
   }
   ```

3. **Add test dependencies to `build.gradle.kts`:**
   ```kotlin
   testImplementation("org.robolectric:robolectric:4.11.1")
   testImplementation("io.mockk:mockk:1.13.8")
   ```

---

### Phase 3: Device Validation (Must Test on Hardware)

**Manual validation steps:**

1. **Deploy app to Android device:**
   ```bash
   cd android-relay
   ./gradlew installDebug
   ```

2. **Check MediaSession state:**
   ```bash
   adb shell dumpsys media_session | grep -A 20 "OpenClawRelay"
   # Look for: state=PlaybackState {state=2, position=0, ...}
   # State 2 = STATE_PAUSED/READY (good)
   # State 0 = STATE_NONE (bad - buttons won't work)
   ```

3. **Simulate button press via ADB:**
   ```bash
   adb shell input keyevent 79  # KEYCODE_HEADSETHOOK
   ```
   
   **Expected:** App UI shows "Last Wake Signal: headset_button_single"

4. **Test with physical Bluetooth headset:**
   - Connect Bluetooth headphones
   - Press play/pause button
   - Verify wake signal appears in UI

5. **Test priority over music apps:**
   - Start Spotify playback, then pause
   - Press headset button
   - Verify YOUR app receives the event (not Spotify)

---

## Success Criteria

### Unit Tests (80%+ coverage)
- ✅ All 7 tests in `RelayMediaSessionControllerTest` pass
- ✅ Test coverage on callback logic: 100%
- ✅ Test coverage on state initialization: 100%

### Device Tests
- ✅ `adb shell dumpsys media_session` shows STATE_READY
- ✅ `adb shell input keyevent 79` triggers wake signal
- ✅ Physical Bluetooth button triggers wake signal
- ✅ App wins priority when no music is playing

---

## What to Implement First

**Day 1 (RED):**
1. Create `RelayMediaSessionControllerTest.kt` (already done ✅)
2. Add Robolectric + MockK dependencies to `build.gradle.kts`
3. Run tests — they FAIL ❌

**Day 2 (GREEN):**
4. Add `player.prepare()` to `RelayMediaSessionController` constructor
5. Add `@VisibleForTesting` helper methods
6. Implement mock Context/ControllerInfo in tests
7. Run tests — they PASS ✅

**Day 3 (DEVICE):**
8. Deploy to Android device
9. Run manual validation checklist
10. Fix any device-specific issues (priority, audio focus, etc.)

---

## Edge Cases to Consider (Later)

After core functionality works:
- [ ] Multiple headset button presses (debouncing)
- [ ] Button press while TTS is speaking
- [ ] Button press while listening to user
- [ ] App killed and restarted (session recreation)
- [ ] Bluetooth disconnect/reconnect
- [ ] Audio focus conflicts with music apps

---

## Reference: Media3 Player States

```kotlin
Player.STATE_IDLE       = 1  // Not prepared (bad for button routing)
Player.STATE_BUFFERING  = 2  // Preparing content
Player.STATE_READY      = 3  // Ready to play (good for button routing)
Player.STATE_ENDED      = 4  // Playback completed
```

**Goal:** Keep player in STATE_READY so Android always routes buttons to us.

---

## Files Modified

- ✅ `android-relay/app/src/test/java/com/openclaw/relay/RelayMediaSessionControllerTest.kt` (created)
- ⏳ `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt` (needs update)
- ⏳ `android-relay/app/build.gradle.kts` (needs test dependencies)

---

## Next Steps

1. **Add test dependencies** — without Robolectric, tests can't run
2. **Update RelayMediaSessionController** — add `player.prepare()` and test hooks
3. **Run unit tests** — should all pass
4. **Device validation** — manual checklist

Once unit tests pass, device validation is straightforward because the logic is proven correct.
