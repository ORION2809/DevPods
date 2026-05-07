# TDD Action Plan: Fix Bluetooth Media Button Routing

## 🎯 Goal
Make physical Bluetooth headset media buttons reliably reach `RelayMediaSessionController` and emit `RelayWakeSignal` with trigger `headset_button_single`.

## 🔴 Current Problem
- MediaSession exists ✅
- Callback is registered ✅  
- **PlaybackState is NONE** ❌ ← Android ignores the session

## ✅ TDD Solution (Test-First Approach)

---

## Phase 1: Write RED Tests (30 minutes)

### Step 1.1: Add test dependencies
**File:** `android-relay/app/build.gradle.kts`

```kotlin
dependencies {
    // ... existing ...
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("io.mockk:mockk:1.13.8")
}
```

**Run:** `cd android-relay && ./gradlew :app:dependencies`

### Step 1.2: Create test file
**File:** `android-relay/app/src/test/java/com/openclaw/relay/RelayMediaSessionControllerTest.kt`

Already created ✅ (see file in workspace)

### Step 1.3: Run tests (they FAIL)
```bash
cd android-relay
./gradlew :app:testDebugUnitTest --tests "RelayMediaSessionControllerTest"
```

**Expected output:**
```
RelayMediaSessionControllerTest > media session player is prepared and ready for button events FAILED
    java.lang.AssertionError: Player state must not be STATE_IDLE
    
7 tests, 7 failed
```

---

## Phase 2: Make Tests GREEN (1 hour)

### Step 2.1: Fix PlaybackState issue
**File:** `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt`

**Add after player creation:**
```kotlin
init {
    // CRITICAL FIX: Prepare player to set STATE_READY
    player.prepare()
    player.playWhenReady = false
}
```

### Step 2.2: Add test helper methods
**Add to RelayMediaSessionController:**

```kotlin
@VisibleForTesting
internal fun getPlayerState(): Int = player.playbackState

@VisibleForTesting
internal fun simulateMediaButtonPress(keyCode: Int) {
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
    // For testing: directly invoke callback
    val mockInfo = mockk<MediaSession.ControllerInfo> {
        every { packageName } returns ""
    }
    mediaSession.callback?.onMediaButtonEvent(mediaSession, mockInfo, intent)
}
```

### Step 2.3: Import VisibleForTesting
```kotlin
import androidx.annotation.VisibleForTesting
```

### Step 2.4: Update test to use MockK
**File:** `RelayMediaSessionControllerTest.kt`

```kotlin
import io.mockk.*
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
class RelayMediaSessionControllerTest {
    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        capturedWakeSignal = null
        controller = RelayMediaSessionController(mockContext) { signal ->
            capturedWakeSignal = signal
        }
    }
}
```

### Step 2.5: Run tests again
```bash
./gradlew :app:testDebugUnitTest --tests "RelayMediaSessionControllerTest"
```

**Expected output:**
```
RelayMediaSessionControllerTest > media session player is prepared and ready for button events PASSED
RelayMediaSessionControllerTest > onMediaButtonEvent callback is invoked for HEADSETHOOK PASSED
... (all tests pass)

7 tests, 7 passed ✅
```

---

## Phase 3: Device Validation (30 minutes)

### Step 3.1: Deploy to device
```bash
cd android-relay
./gradlew installDebug
adb shell am start -n com.openclaw.relay/.MainActivity
```

### Step 3.2: Verify MediaSession state
```bash
adb shell dumpsys media_session | grep -A 30 "com.openclaw.relay"
```

**Look for:**
```
state=PlaybackState {state=3, position=0, ...}  # state=3 is STATE_READY ✅
```

**NOT:**
```
state=PlaybackState {state=1, ...}  # state=1 is STATE_IDLE ❌
```

### Step 3.3: Test via ADB
```bash
# Simulate headset button press
adb shell input keyevent 79
```

**Expected:** App UI shows "Last Wake Signal: headset_button_single"

### Step 3.4: Test with physical Bluetooth headset
1. Connect Bluetooth headphones to Android device
2. Press play/pause button
3. Verify wake signal appears in app UI within 1 second

### Step 3.5: Test priority (optional)
1. Start Spotify, play music, then pause
2. Press Bluetooth button
3. **Expected:** Your app receives the event (not Spotify)

---

## 📊 Coverage Targets

### Unit Tests (Local JVM)
- Player state initialization: 100%
- Callback registration: 100%
- Wake signal creation: 100%
- Key code filtering: 100%
- Action filtering (UP vs DOWN): 100%

**Overall target: 80%+ branch coverage**

### Device Tests (Manual)
- [ ] Button routing to your session
- [ ] Bluetooth transport works
- [ ] Priority over music apps
- [ ] Audio focus doesn't break it

---

## 🚨 Common Issues & Fixes

### Issue 1: Tests fail with "Context not mocked"
**Fix:** Add Robolectric runner:
```kotlin
@RunWith(RobolectricTestRunner::class)
```

### Issue 2: "MediaSession.ControllerInfo cannot be mocked"
**Fix:** Use MockK with relaxed mode:
```kotlin
val mockInfo = mockk<MediaSession.ControllerInfo>(relaxed = true)
```

### Issue 3: Button press on device does nothing
**Fix:** Check `dumpsys media_session` for state=3 (READY)
If still STATE_IDLE, player.prepare() wasn't called.

### Issue 4: Spotify steals the button event
**Fix:** Your session needs higher priority:
```kotlin
// In MediaSession.Builder:
.setSessionActivity(pendingIntent)  // Makes session more "active"
```

---

## 📝 Test Execution Order

| Step | Command | Expected Result | Time |
|------|---------|----------------|------|
| 1 | `./gradlew :app:dependencies` | Dependencies downloaded | 2m |
| 2 | `./gradlew :app:testDebugUnitTest` | 7 tests FAIL ❌ | 10s |
| 3 | (Add `player.prepare()`) | — | — |
| 4 | `./gradlew :app:testDebugUnitTest` | 7 tests PASS ✅ | 10s |
| 5 | `./gradlew installDebug` | App installed on device | 30s |
| 6 | `adb shell dumpsys media_session` | state=3 (READY) | 5s |
| 7 | `adb shell input keyevent 79` | Wake signal in UI | 1s |
| 8 | (Physical button press) | Wake signal in UI | 1s |

**Total time: ~45 minutes** (including test writing)

---

## ✅ Definition of Done

### Unit Tests
- [x] Test file created: `RelayMediaSessionControllerTest.kt`
- [ ] All 7 tests pass locally
- [ ] Coverage report shows 80%+ on controller class
- [ ] Tests run in CI (if applicable)

### Implementation
- [ ] `player.prepare()` called in constructor
- [ ] `getPlayerState()` test helper added
- [ ] `simulateMediaButtonPress()` test helper added
- [ ] `@VisibleForTesting` annotations added

### Device Validation
- [ ] `dumpsys media_session` shows state=3
- [ ] `adb shell input keyevent 79` triggers wake signal
- [ ] Physical Bluetooth button triggers wake signal
- [ ] Wake signal has correct metadata (trigger, source, keyLabel)

---

## 🔄 Iteration Cycle

```
1. RED:   Write test → Run → FAIL ❌
2. GREEN: Write code → Run → PASS ✅
3. REFACTOR: Clean code → Run → PASS ✅
4. DEVICE: Deploy → Test → Verify 📱
```

**Current status:** Step 1 (RED) ← Tests are written and failing

**Next action:** Step 2 (GREEN) ← Add `player.prepare()` and test helpers

---

## 📚 Reference Files

- **Tests:** `android-relay/app/src/test/java/com/openclaw/relay/RelayMediaSessionControllerTest.kt`
- **Implementation:** `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt`
- **Dependencies:** `android-relay/app/build.gradle.kts`
- **TDD Plan:** `android-relay/TDD-MEDIA-SESSION-PLAN.md`

---

## 🎓 Key TDD Lessons

1. **Write tests first** — Forces you to think about the API
2. **Test behavior, not implementation** — "Button press triggers wake signal" not "Callback is called"
3. **Unit tests for logic, device tests for integration** — 80% can be tested locally
4. **Use test doubles (mocks)** — Don't require real Context/MediaSession in unit tests
5. **Keep tests fast** — Local JVM tests run in <1 second

---

## Next Steps

1. ✅ Read this document
2. ⏳ Add test dependencies to `build.gradle.kts`
3. ⏳ Run tests to confirm they FAIL
4. ⏳ Add `player.prepare()` to implementation
5. ⏳ Run tests to confirm they PASS
6. ⏳ Deploy to device and validate

**Estimated total time: 2 hours** (including learning curve)
