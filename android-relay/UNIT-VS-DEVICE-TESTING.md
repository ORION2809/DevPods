# Unit Tests vs Device Tests: What to Test Where

## Core Principle
**Unit tests validate YOUR logic. Device tests validate ANDROID's integration.**

---

## ✅ Unit Testable (Local JVM with Robolectric)

### 1. Session Readiness State
```kotlin
@Test
fun `player is in STATE_READY not STATE_IDLE`() {
    val controller = RelayMediaSessionController(mockContext) { }
    assertEquals(Player.STATE_READY, controller.getPlayerState())
}
```
**Why unit testable:** Player state is a direct property of ExoPlayer, no Android routing involved.

---

### 2. Callback Logic
```kotlin
@Test
fun `HEADSETHOOK keycode triggers wake signal`() {
    var signal: RelayWakeSignal? = null
    val controller = RelayMediaSessionController(mockContext) { signal = it }
    
    controller.simulateMediaButtonPress(KeyEvent.KEYCODE_HEADSETHOOK)
    
    assertNotNull(signal)
    assertEquals("headset_button_single", signal?.trigger)
}
```
**Why unit testable:** You're testing the callback's internal logic, not Android's routing decision.

---

### 3. Wake Signal Metadata
```kotlin
@Test
fun `wake signal contains correct source and keyLabel`() {
    var signal: RelayWakeSignal? = null
    val controller = RelayMediaSessionController(mockContext) { signal = it }
    
    controller.simulateMediaButtonPress(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    
    assertEquals("physical_media_button", signal?.source)
    assertEquals("Play or pause", signal?.keyLabel)
}
```
**Why unit testable:** Pure data transformation logic.

---

### 4. Action Filtering (UP vs DOWN)
```kotlin
@Test
fun `ACTION_DOWN does not trigger wake signal`() {
    var signal: RelayWakeSignal? = null
    val controller = RelayMediaSessionController(mockContext) { signal = it }
    
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        putExtra(Intent.EXTRA_KEY_EVENT, 
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
    }
    controller.simulateMediaButtonEvent(intent)
    
    assertNull(signal)  // Only ACTION_UP should trigger
}
```
**Why unit testable:** Conditional logic inside your callback.

---

### 5. Keycode Filtering
```kotlin
@Test
fun `KEYCODE_MEDIA_NEXT does not trigger wake signal`() {
    var signal: RelayWakeSignal? = null
    val controller = RelayMediaSessionController(mockContext) { signal = it }
    
    controller.simulateMediaButtonPress(KeyEvent.KEYCODE_MEDIA_NEXT)
    
    assertNull(signal)  // Only supported keycodes trigger
}
```
**Why unit testable:** Switch/when statement inside your callback.

---

### 6. Controller Package Capture
```kotlin
@Test
fun `controller package is captured from ControllerInfo`() {
    var signal: RelayWakeSignal? = null
    val controller = RelayMediaSessionController(mockContext) { signal = it }
    
    val mockInfo = mockk<MediaSession.ControllerInfo> {
        every { packageName } returns "com.android.bluetooth"
    }
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        putExtra(Intent.EXTRA_KEY_EVENT, 
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
    }
    
    controller.simulateMediaButtonEventWithController(intent, mockInfo)
    
    assertEquals("com.android.bluetooth", signal?.controllerPackage)
}
```
**Why unit testable:** Data extraction from a mocked parameter.

---

## ❌ NOT Unit Testable (Requires Device)

### 1. Android Routes Button to Your Session
```kotlin
// CANNOT unit test this:
// "When user presses Bluetooth button, Android MediaButtonReceiver 
//  routes it to my session instead of Spotify's session"
```
**Why device-only:** Requires Android's MediaSessionManager priority logic, which involves:
- Active MediaSession priority queue
- PlaybackState comparison across all sessions
- Audio focus state
- Foreground service status
- Real Bluetooth HID input events

**How to device test:**
```bash
# Check session is registered with correct state
adb shell dumpsys media_session

# Simulate button press
adb shell input keyevent 79

# Check app received the event
adb logcat -d | grep "RelayWakeSignal"
```

---

### 2. Bluetooth Transport Delivers KeyEvent
```kotlin
// CANNOT unit test this:
// "Physical Bluetooth headset button press generates KeyEvent.KEYCODE_HEADSETHOOK"
```
**Why device-only:** Requires real Bluetooth stack:
- HID (Human Interface Device) profile
- Bluetooth SDP (Service Discovery Protocol)
- Kernel input subsystem
- Android InputReader service

**How to device test:**
1. Connect Bluetooth headphones
2. Press physical button
3. Run `adb shell getevent` to see raw input events
4. Verify app receives wake signal

---

### 3. Session Priority Over Music Apps
```kotlin
// CANNOT unit test this:
// "My session wins priority when Spotify is paused but still has a session"
```
**Why device-only:** Requires Android's session priority algorithm:
- Playback state comparison (PLAYING > PAUSED > READY > NONE)
- App foreground status
- Recent activity timestamp
- Audio focus holder

**How to device test:**
1. Start Spotify, play music, pause
2. Open your app (creates session)
3. Press Bluetooth button
4. Verify YOUR app receives event, not Spotify

---

### 4. Race Conditions with Other Media Apps
```kotlin
// CANNOT unit test this:
// "Button press while YouTube Music is loading doesn't cause conflict"
```
**Why device-only:** Timing-dependent multi-process interaction.

**How to device test:**
1. Install multiple media apps (Spotify, YouTube Music, etc.)
2. Start/stop them in various sequences
3. Press button during transitions
4. Verify consistent routing

---

### 5. Audio Focus Interactions
```kotlin
// CANNOT unit test this:
// "Session receives button events even when another app has audio focus"
```
**Why device-only:** Requires AudioManager and AudioFocusManager interaction.

**How to device test:**
1. Start Google Maps navigation (takes audio focus)
2. Press Bluetooth button
3. Verify your app still receives event

---

### 6. Foreground Service Lifecycle
```kotlin
// CANNOT unit test this:
// "Session survives app going to background"
```
**Why device-only:** Requires real Android process management.

**How to device test:**
1. Start app, trigger wake signal (works)
2. Press Home button (app goes to background)
3. Press Bluetooth button
4. Verify app still receives event

---

## Summary Table

| Test Scenario | Unit Test? | Device Test? | Why? |
|---------------|-----------|--------------|------|
| Player state is READY | ✅ Yes | Optional | Direct property check |
| Callback triggers wake signal | ✅ Yes | Optional | Internal logic |
| Correct metadata in wake signal | ✅ Yes | Optional | Data transformation |
| ACTION_UP vs ACTION_DOWN | ✅ Yes | Optional | Conditional logic |
| Keycode filtering | ✅ Yes | Optional | Switch statement |
| Controller package capture | ✅ Yes | Optional | Data extraction |
| Android routes event to session | ❌ No | ✅ Required | Multi-session priority |
| Bluetooth HID transport | ❌ No | ✅ Required | Hardware driver |
| Priority over Spotify | ❌ No | ✅ Required | System policy |
| Race conditions | ❌ No | ✅ Required | Timing + multi-process |
| Audio focus conflicts | ❌ No | ✅ Required | AudioManager |
| Background survival | ❌ No | ✅ Required | Process lifecycle |

---

## Test Coverage Target

### Unit Tests (Local JVM)
- **Aim for:** 90%+ line coverage, 80%+ branch coverage
- **Focus:** All callback logic, state management, data transformation
- **Speed:** <5 seconds for full suite
- **CI:** Run on every commit

### Device Tests (Hardware)
- **Aim for:** Critical user flows covered
- **Focus:** Integration points with Android system
- **Speed:** 30-60 seconds per scenario
- **CI:** Run nightly or on PR

---

## Practical Workflow

### Day 1: Unit Tests
```bash
# Write tests
vim RelayMediaSessionControllerTest.kt

# Run tests (fast feedback loop)
./gradlew :app:testDebugUnitTest --tests "RelayMediaSessionControllerTest"

# Check coverage
./gradlew :app:testDebugUnitTest jacocoTestReport
open app/build/reports/jacoco/testDebugUnitTest/html/index.html
```

### Day 2: Device Tests
```bash
# Deploy
./gradlew installDebug

# Manual validation checklist
# - adb shell dumpsys media_session (check state)
# - adb shell input keyevent 79 (simulate press)
# - Physical button press (real test)
# - Spotify running + button press (priority test)
```

---

## Key Insight

**Unit tests prove your logic is correct.**  
**Device tests prove Android agrees with you.**

Both are necessary. Unit tests catch 90% of bugs in 1% of the time. Device tests catch the remaining 10% that involve OS integration.

---

## Quick Decision Tree

```
Q: Does this test require Android system services?
   ├─ No  → Unit test (fast, local JVM)
   └─ Yes → Device test (slow, requires hardware)

Q: Does this test involve multiple processes?
   ├─ No  → Unit test
   └─ Yes → Device test

Q: Does this test require real hardware (Bluetooth, GPS, etc.)?
   ├─ No  → Unit test
   └─ Yes → Device test

Q: Can I mock all dependencies with reasonable behavior?
   ├─ Yes → Unit test
   └─ No  → Device test
```

---

## Real Example: This Project

### Unit Test (6 tests, 3 seconds)
- ✅ Player state is READY
- ✅ Callback registered
- ✅ Wake signal metadata correct
- ✅ ACTION_UP processed
- ✅ Unsupported keycodes ignored
- ✅ Controller package captured

### Device Test (4 scenarios, 2 minutes)
- ✅ `adb input keyevent` works
- ✅ Physical Bluetooth button works
- ✅ Priority over Spotify
- ✅ Survives background transition

**Result:** 10 total test cases, 80% testable without device, 20% requires hardware.
