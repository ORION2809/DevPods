# PR: 10-Provider Earbud Mesh, Product UI Shell, and Portable Bridge

## Summary

This PR represents a major milestone: the Android relay evolves from a basic MVP into a product-ready surface with broad earbud compatibility, a polished five-tab UI, secure pairing, and a portable Windows bridge package.

## What's New

### 🎧 10-Provider Earbud Mesh

A layered provider system for physical earbud compatibility across major brands:

| Priority | Provider | Brands | Mechanism |
|----------|----------|--------|-----------|
| 1 | `apple_airpods` | AirPods, Beats | BLE proximity + L2CAP/AACP stem press |
| 2 | `samsung_galaxy_buds` | Galaxy Buds 2/2 Pro/3 Pro/Live/FE/Pro | RFCOMM protocol + battery decode |
| 3 | `sony_headphones` | WF-1000XM4/5, WH-1000XM4/5, LinkBuds | RFCOMM serial + capability detection |
| 4 | `nothing_ear` | Nothing Ear 1/2/a, CMF Buds | RFCOMM serial |
| 5 | `oppo_realme` | Oppo Enco, Realme Buds, OnePlus Buds | RFCOMM serial |
| 6 | `librepods_airpods` | AirPods (legacy) | BLE proximity + AACP |
| 7 | `android_media_session` | ALL Bluetooth audio | Media3 universal fallback |
| 8 | `assistant_entry` | ALL devices | Long-press assistant fallback |
| 9 | `generic_bluetooth_headset` | ALL Bluetooth headsets | Connection + audio route awareness |
| 10 | `generic_gatt_battery` | BLE devices with BAS | Standard GATT battery service |

**Architecture highlights:**
- `SignalProviderRegistry` manages all 10 providers with `SupervisorJob`-isolated startup (one failing provider cannot break others)
- Priority-based dynamic preferred-provider selection
- Per-provider health tracking (`ProviderHealth`) with UI exposure
- Shared transports: `BtClassicSerialTransport` (RFCOMM queue + reconnect backoff) and `L2capAapTransport` (L2CAP with reflection fallback)
- Common `EarbudSignalProvider` contract with `probe()`, capability profiles, and device state

### 📱 Product UI Shell

- **Five-tab Compose UI**: Home, Activity, Device, Help, Developer
- **Onboarding flow**: Gesture education, pairing instructions, setup wizard
- **Setup wizard**: Event-driven proof of wake, STT capture, and audio routing (steps only marked proven when physically observed)
- **Device screen**: Pairing status, QR scan, communication-route summary, supported-device capability matrix, provider health card
- **Diagnostic export**: Redacted diagnostics with consent toggles
- **Activity history**: Privacy-safe storage (excludes URLs, tokens, workspace names, MAC addresses)

### 🔗 Pairing System

- `GET /pairing` endpoint with short-lived codes (5-minute TTL, one-time use)
- Browser-rendered pairing page with QR code
- `devpods://pair` deep-link support with staged import (no silent overwrites)
- QR scan from Android Device tab

### 📦 Portable Windows Bridge

- `npm run package:bridge:windows` creates `artifacts/windows-bridge/DevPodsBridgePortable`
- Includes compiled bridge, Windows launcher, default config, and pairing page flow

### 🧪 Testing

- **123 TypeScript bridge tests** (all passing, 0 vulnerabilities)
- **Android unit tests**: pairing, signal messaging, listening route policy, device capability matrix, speech error policy, provider conformance
- Provider conformance tests are pure JVM (no Robolectric dependency)

## Build Verification

```bash
# TypeScript bridge
npm run typecheck   # ✅
npm run build       # ✅
npm test            # ✅ 123 tests
npm audit           # ✅ 0 vulnerabilities

# Android relay
./gradlew :app:assembleDebug      # ✅
./gradlew :app:assembleRelease    # ✅
./gradlew :app:lintDebug          # ✅ (17 warnings, 0 errors)
./gradlew :app:testDebugUnitTest  # ✅
```

## Files Changed

- `167 files changed, 25,486 insertions(+), 1,337 deletions(-)`
- All vendor protocol references are in `external/` and `vendor-sources/` (gitignored, cloned as needed)

## Validation Status

| Path | Status |
|------|--------|
| MediaSession wake (realme RMX3990 + Buds Air7) | ✅ OBSERVED |
| LibrePods/AACP physical wake | ⚠️ UNPROVEN (awaiting AirPods hardware) |
| Triple-press approval/reject | ⚠️ UNPROVEN (awaiting hardware) |
| Samsung/Sony/Nothing/Oppo providers | ⚠️ Implemented, awaiting physical device fixtures |

## Follow-up Work

- Physical device validation for Apple AirPods, Samsung Galaxy Buds, Sony, Nothing, Oppo
- ADB-connected real-device gesture testing
- Provider-specific settings configuration UI (ANC, touch controls)

---

**Reviewers**: Focus on `SignalProviderRegistry.kt`, `EarbudSignalProvider.kt`, the shared transport layer, and the bridge pairing contract. UI code follows established Compose patterns.
