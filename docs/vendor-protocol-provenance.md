# Vendor Protocol Provenance Report

Date: 2026-05-19

Purpose: record the source, license, and reuse status of every vendor-specific earbud protocol implementation in DevPods.

## License Boundary Policy

- GPL/AGPL-derived code is **not** shipped in the proprietary DevPods distribution unless an explicit compatible license or written permission is obtained.
- External projects are used as **protocol reference only** unless explicitly marked as directly incorporated.
- Clean-room reimplementation is the default path for all vendor protocols.

---

## Provider Inventory

### apple_airpods

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/vendor/apple/ApplePodsProvider.kt`

**Status:** Scaffolded. BLE proximity scanning and AACP parsing exist; stem-press event capture is implemented but not yet validated on physical AirPods hardware.

**Protocol reference:**
- Apple BLE advertisement format (manufacturer ID 76) observed via BLE scanning and public Bluetooth SIG documentation.
- AACP UUID `74ec2172-0bad-4d01-8f77-997b2be0722a` is a public Apple service UUID observed in BLE traffic.

**External projects consulted:**
- LibrePods (GPL-3.0) — consulted for AirPods feature behavior matrix and AACP payload structure. No code copied.
- CAPod (GPL-3.0) — consulted for supported model list and BLE advertisement parsing logic. No code copied.
- OpenPods (GPL-3.0) — consulted for basic AirPods notification behavior. No code copied.

**Reuse decision:** Clean-room implementation. No GPL code incorporated.

**Reviewed by:** Architecture audit 2026-05-19.

---

### librepods_airpods

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt`

**Status:** Implemented. Wraps the LibrePods reference workspace for BLE state and best-effort AACP stem events.

**Protocol reference:** LibrePods native implementation.

**External projects used:**
- LibrePods (GPL-3.0) — local reference workspace at `librepods/`. This provider is a thin wrapper around LibrePods APIs.

**Reuse decision:** This provider is **not** shipped in the production bridge bundle. It is available only in development builds where the `librepods/` workspace is present. Before any release, this provider must either:
1. Obtain GPL-compatible licensing or written permission from LibrePods authors, or
2. Be replaced by the clean-room `apple_airpods` provider.

**Reviewed by:** Architecture audit 2026-05-19.

---

### samsung_galaxy_buds

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/vendor/samsung/SamsungGalaxyBudsProvider.kt`

**Status:** Scaffolded. Detects bonded Galaxy Buds devices and parses some battery packets. Wake depends on Android media-button fallback.

**Protocol reference:**
- Samsung Galaxy Buds Bluetooth Classic serial protocol observed via bonded device interaction.

**External projects consulted:**
- Gadgetbridge (AGPLv3) — consulted for Samsung headphone protocol documentation and command structures. No code copied.
- GalaxyBudsClient (GPL-3.0) — consulted for touch-action and firmware feature detail. No code copied.

**Reuse decision:** Clean-room implementation. No GPL/AGPL code incorporated.

**Reviewed by:** Architecture audit 2026-05-19.

---

### sony_headphones

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/vendor/sony/SonyHeadphonesProvider.kt`

**Status:** Scaffolded. Detects Sony devices by bonded name and opens Bluetooth Classic serial transport. Gesture reliability is described as MediaSession fallback.

**Protocol reference:**
- Sony Bluetooth Classic serial protocol (SPP UUID `00001101-0000-1000-8000-00805F9B34FB`).

**External projects consulted:**
- Gadgetbridge (AGPLv3) — consulted for Sony headphone protocol documentation, ANC/ambient command structures, and EQ/touch sensor behavior. No code copied.

**Reuse decision:** Clean-room implementation. No GPL/AGPL code incorporated.

**Reviewed by:** Architecture audit 2026-05-19.

---

### nothing_ear

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/vendor/nothing/NothingEarProvider.kt`

**Status:** Scaffolded. Detection and fallback layer only.

**Protocol reference:**
- Nothing Ear Bluetooth Classic serial protocol.

**External projects consulted:**
- Gadgetbridge (AGPLv3) — consulted for Nothing Ear protocol documentation. No code copied.

**Reuse decision:** Clean-room implementation. No GPL/AGPL code incorporated.

**Reviewed by:** Architecture audit 2026-05-19.

---

### oppo_realme

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/vendor/oppo/OppoRealmeProvider.kt`

**Status:** Scaffolded. Detection and fallback layer only.

**Protocol reference:**
- Oppo/Realme Bluetooth Classic serial protocol.

**External projects consulted:**
- Gadgetbridge (AGPLv3) — consulted for Oppo/Realme headphone protocol documentation. No code copied.

**Reuse decision:** Clean-room implementation. No GPL/AGPL code incorporated.

**Reviewed by:** Architecture audit 2026-05-19.

---

### generic_bluetooth_headset

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt`

**Status:** Implemented. Universal Bluetooth Headset/Handsfree profile detection.

**Protocol reference:**
- Android `BluetoothHeadset` API and standard Bluetooth SIG HSP/HFP profiles.

**External projects consulted:** None.

**Reuse decision:** Android platform API only. No external code.

**Reviewed by:** Architecture audit 2026-05-19.

---

### generic_gatt_battery

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/vendor/genericgatt/GenericGattBatteryProvider.kt`

**Status:** Implemented. Reads standard Bluetooth SIG Battery Service.

**Protocol reference:**
- Bluetooth SIG Battery Service (UUID `0000180f-0000-1000-8000-00805f9b34fb`).
- Battery Level characteristic (UUID `00002a19-0000-1000-8000-00805f9b34fb`).

**External projects consulted:** None.

**Reuse decision:** Public Bluetooth SIG specification. No external code.

**Reviewed by:** Architecture audit 2026-05-19.

---

### android_media_session

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/AndroidMediaSessionProvider.kt`

**Status:** Implemented. Universal Android Media3 media-button path.

**Protocol reference:**
- Android Media3 `MediaSession` and `MediaButton` APIs.

**External projects consulted:** None.

**Reuse decision:** Android platform API only. No external code.

**Reviewed by:** Architecture audit 2026-05-19.

---

### assistant_entry

**Provider file:** `android-relay/app/src/main/java/com/openclaw/relay/signal/AssistantEntryProvider.kt`

**Status:** Implemented. Assistant long-press fallback.

**Protocol reference:**
- Android `VoiceInteractionService` / assistant launch APIs.

**External projects consulted:** None.

**Reuse decision:** Android platform API only. No external code.

**Reviewed by:** Architecture audit 2026-05-19.

---

## Infrastructure Libraries

### Nordic Android BLE Library

**Status:** Not yet incorporated. Evaluated as a candidate for production BLE operations.

**License:** BSD-3-Clause

**Reuse decision:** Permissive license. May be incorporated in a future release for connection retry, bonding, and MTU handling.

**Reviewed by:** Architecture audit 2026-05-19.

### RxAndroidBle

**Status:** Not incorporated. Evaluated but rejected in favor of coroutine/Flow style.

**License:** Apache-2.0

**Reuse decision:** Not used. Architecture prefers coroutines over RxJava.

**Reviewed by:** Architecture audit 2026-05-19.

---

## Action Items

1. **Before release:** Resolve `librepods_airpods` GPL boundary — either obtain permission or replace with `apple_airpods` clean-room provider.
2. **Before release:** Verify no GPL/AGPL-derived comments or inline documentation remain in production source files.
3. **Ongoing:** Update this report when new providers are added or when external code is incorporated.
4. **Ongoing:** Re-audit whenever a provider moves from `scaffolded` to `implemented_unverified` or higher.
