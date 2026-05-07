# TDD Media Button Fix - Quick Start

## Problem
Physical Bluetooth headset buttons don't reach your app because MediaSession PlaybackState is NONE.

## Solution
Follow TDD: Write tests first (RED), then fix implementation (GREEN), then validate on device.

---

## Quick Start (3 Steps)

### Step 1: Add Test Dependencies (2 minutes)

Edit `android-relay/app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing ...
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("io.mockk:mockk:1.13.8")
}
```

Then sync:
```bash
cd android-relay
./gradlew :app:dependencies
```

### Step 2: Run RED Tests (1 minute)

Tests are already written in `RelayMediaSessionControllerTest.kt`. Run them:

```bash
./gradlew :app:testDebugUnitTest --tests "RelayMediaSessionControllerTest"
```

**Expected:** 7 tests FAIL ❌ (because PlaybackState is NONE)

### Step 3: Fix Implementation (5 minutes)

Edit `android-relay/app/src/main/java/com/openclaw/relay/RelayMediaSessionController.kt`:

**Add this after player creation:**
```kotlin
import androidx.annotation.VisibleForTesting

class RelayMediaSessionController(...) {
    private val player = ExoPlayer.Builder(context).build()
    
    // ADD THIS:
    init {
        player.prepare()
        player.playWhenReady = false
    }
    
    private val mediaSession = MediaSession.Builder(context, player)
        // ... rest of code ...
    
    // ADD THESE TEST HELPERS:
    @VisibleForTesting
    internal fun getPlayerState(): Int = player.playbackState
    
    @VisibleForTesting
    internal fun simulateMediaButtonPress(keyCode: Int) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        val mockInfo = mockk<MediaSession.ControllerInfo>(relaxed = true) {
            every { packageName } returns ""
        }
        mediaSession.callback?.onMediaButtonEvent(mediaSession, mockInfo, intent)
    }
}
```

**Run tests again:**
```bash
./gradlew :app:testDebugUnitTest --tests "RelayMediaSessionControllerTest"
```

**Expected:** 7 tests PASS ✅

---

## Device Validation

After tests pass, validate on real device:

```bash
# Deploy app
./gradlew installDebug

# Check MediaSession state (should show state=3 READY)
adb shell dumpsys media_session | grep -A 20 "com.openclaw.relay"

# Simulate button press
adb shell input keyevent 79
# → App UI should show "Last Wake Signal: headset_button_single"

# Or use the PowerShell script:
powershell -ExecutionPolicy Bypass -File .\validate-media-buttons.ps1
```

---

## Files Reference

| File | Purpose |
|------|---------|
| `TDD-ACTION-PLAN.md` | Detailed step-by-step plan |
| `TDD-MEDIA-SESSION-PLAN.md` | Technical deep dive |
| `RelayMediaSessionControllerTest.kt` | Unit tests (RED phase) |
| `RelayMediaSessionController_TDD.kt` | Reference implementation (GREEN phase) |
| `validate-media-buttons.ps1` | Device validation script |
| `TEST-DEPENDENCIES.md` | Dependency setup guide |

---

## Test Coverage Breakdown

### Unit Tests (Local JVM) ✅ Testable
- Player state is READY (not IDLE)
- Callback triggers wake signal
- Wake signal has correct metadata
- Only ACTION_UP events processed
- Unknown keycodes ignored
- Controller package captured

### Device Tests (Hardware) 📱 Must Validate
- Android routes button to your session
- Bluetooth transport works
- Priority over music apps
- Audio focus doesn't interfere

---

## Common Issues

| Issue | Symptom | Fix |
|-------|---------|-----|
| Tests fail with "Context not mocked" | ClassCastException | Add `@RunWith(RobolectricTestRunner::class)` |
| "Cannot mock ControllerInfo" | MockitoException | Use MockK: `mockk<MediaSession.ControllerInfo>(relaxed = true)` |
| Button press does nothing on device | No wake signal in UI | Check `dumpsys media_session` shows state=3 |
| Spotify steals button event | Wrong app opens | Add `.setSessionActivity(pendingIntent)` to session builder |

---

## Success Criteria

**Unit Tests:**
- [x] Test file created
- [ ] All 7 tests pass
- [ ] 80%+ branch coverage
- [ ] Tests run in <5 seconds

**Device:**
- [ ] `dumpsys` shows state=3 (READY)
- [ ] ADB keyevent triggers wake signal
- [ ] Physical Bluetooth button works
- [ ] Wake signal metadata is correct

---

## Timeline

- **Setup:** 2 minutes (dependencies)
- **RED:** 1 minute (run failing tests)
- **GREEN:** 10 minutes (implement fix + test helpers)
- **Device:** 5 minutes (deploy + validate)

**Total: ~20 minutes**

---

## Next Steps

1. ✅ Read this README
2. ⏳ Add test dependencies
3. ⏳ Run tests (they FAIL)
4. ⏳ Add `player.prepare()` to implementation
5. ⏳ Add test helper methods
6. ⏳ Run tests (they PASS)
7. ⏳ Deploy and validate on device

**You are here:** Step 1 ← Read documentation complete

**Next action:** Add Robolectric + MockK to `build.gradle.kts`
