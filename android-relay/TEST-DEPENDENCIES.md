# Test Dependencies for TDD Media Session Testing

Add these dependencies to `android-relay/app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    
    // Robolectric for Android framework simulation in JVM tests
    testImplementation("org.robolectric:robolectric:4.11.1")
    
    // MockK for mocking Media3 classes (ControllerInfo, etc.)
    testImplementation("io.mockk:mockk:1.13.8")
    
    // Kotlin coroutines test support (if testing async behavior later)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    
    // ... existing androidTestImplementation dependencies ...
}
```

## Why Each Dependency?

1. **Robolectric** — Simulates Android framework (Context, Intent, KeyEvent) in local JVM tests
   - Without this, you'd need device tests for everything
   - Allows testing Android APIs without slow emulator/device

2. **MockK** — Kotlin-friendly mocking for Media3 classes
   - `MediaSession.ControllerInfo` is a final class that needs mocking
   - Better Kotlin DSL than Mockito

3. **Coroutines Test** — For future async tests (optional now)
   - Media3 uses coroutines internally
   - Useful for testing timeout behavior

## Alternative: Minimal Setup (No Robolectric)

If you want to start even simpler, you can skip Robolectric initially and test only pure logic:

```kotlin
// Minimal test without Robolectric
@Test
fun `wake signal has correct trigger value`() {
    val signal = RelayWakeSignal(
        trigger = "headset_button_single",
        source = "physical_media_button",
        sourceLabel = "Physical headset media button",
        keyLabel = "Headset hook",
    )
    
    assertEquals("headset_button_single", signal.trigger)
    assertEquals("physical_media_button", signal.source)
}
```

But for testing the **MediaSession callback integration**, you need Robolectric.

## After Adding Dependencies

1. Sync Gradle: `./gradlew :app:dependencies`
2. Run tests: `./gradlew :app:testDebugUnitTest`
3. Check coverage: `./gradlew :app:testDebugUnitTest jacocoTestReport`
