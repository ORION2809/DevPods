# Earbud Compatibility Integration Source Map

Date: 2026-05-13

## Executive Verdict

The fastest path to "works with most earbuds" is not building every hardware path from scratch. It is a layered compatibility strategy:

1. Keep Android's standard headset/media-button path as the universal baseline.
2. Add vendor-specific provider plugins for richer device state and controls.
3. Use proven open-source or licensable protocol implementations as source material, with clean licensing boundaries.
4. Ship only claims proven by a device matrix, not by model-name guessing.

There is no single library that makes all AirPods, Galaxy Buds, Sony, Nothing, Oppo, Realme, Bose, Jabra, JBL, OnePlus, Pixel Buds, and clones behave perfectly. Earbud vendors expose different capabilities, and many gestures are translated by the firmware into ordinary Android media commands rather than a separate tap event. The product can still feel seamless if DevPods treats compatibility as a provider system with a visible "proven on this device" setup flow.

## Current Repo Fit

The repo already has the right architectural direction:

- `android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudSignalProvider.kt` defines a provider boundary.
- `LibrePodsAirPodsProvider.kt` gives an AirPods-class provider for BLE state plus best-effort AACP stem events.
- `AndroidMediaSessionProvider.kt` and `AssistantEntryProvider.kt` give the universal Android input path.
- `GenericBluetoothHeadsetProvider.kt` exists, but at the time of this scan it is not wired into `SignalProviderRegistry.kt`; the registry only starts LibrePods, MediaSession, and Assistant providers.

That means we should not restart the Android relay design. We should extend the existing provider layer.

## Best Candidates To Reuse Or License

| Candidate | Best Use In DevPods | Coverage | License / Access | Recommendation |
| --- | --- | --- | --- | --- |
| LibrePods | AirPods-rich provider: battery, in-ear, ANC/customization, head gestures, AACP knowledge | AirPods Pro 2/3 strongest; other AirPods basic to partial | GPL-3.0 on GitHub; local copy exists but lacks local LICENSE file | Highest-priority AirPods source. Use directly only with compatible licensing/authorization; otherwise keep clean-room provider and validate against LibrePods behavior. |
| CAPod | AirPods BLE scanning, battery, ear detection, popup/device-state behavior | Broad AirPods and Beats list, including AirPods 4/Pro/Max and many Beats models | GPL-3.0, excludes assets/docs/translations | Strong AirPods battery/status reference. Better for robust BLE/device UX than gesture execution. |
| OpenPods | Minimal AirPods monitor implementation | AirPods 1/2/3/Pro/Pro 2/Pro 3/Max and Beats/Powerbeats | GPL-3.0 | Useful as a small reference for AirPods notification/status flows. Less complete than LibrePods/CAPod. |
| Gadgetbridge | Broad vendor-provider library/reference for Samsung, Sony, Nothing, Oppo, Realme, Bose, Google, Xiaomi, etc. | 469 total gadget models across 64 vendors; headphone pages include Samsung Galaxy Buds, Sony WF/WH/LinkBuds, Nothing Ear, Oppo Enco, Realme, Bose QC35, Pixel Buds A | AGPLv3 | Best non-AirPods source. Pursue authorization/commercial terms or use as protocol reference with clean-room reimplementation. |
| GalaxyBudsClient | Deep Samsung Galaxy Buds protocol and features | Galaxy Buds desktop and paid Android version; battery, diagnostics, touch actions, firmware features | GPL-3.0 | Valuable Samsung-specific reference, but Gadgetbridge already includes Android-ready Galaxy Buds support. Use mostly for protocol detail. |
| HyperPods AAP | Root/Xposed AirPods AAP exploration | AirPods AAP features: ear detection, ANC, 1% battery; scheduled key customization | GPL-3.0 | Useful as AAP reference only. Root/Xposed/native-hook approach is not suitable as the default Play Store path. |
| Nordic Android BLE Library | Production-grade BLE GATT connection layer | Any BLE device once protocol is known | BSD-3-Clause | Best permissive BLE infrastructure candidate if our provider code grows beyond simple scanning. |
| RxAndroidBle | Alternative BLE stack with RxJava | Any BLE device once protocol is known | Apache-2.0 | Good library, but less aligned with our coroutine/Flow style than Nordic. Use only if we choose Rx-based BLE operations. |
| Jabra SDKs | Official headset integration for supported Jabra business devices | Jabra Perform and BlueParrott on Android/iOS; broader Jabra via .NET desktop SDK | Partner/commercial SDK | Good licensed route for enterprise headset controls, but not a broad consumer-earbud solution. |
| MagicPods | Commercial AirPods/Windows protocol and UX knowledge | AirPods, Beats, some Sony, fake AirPods/Airoha on Windows/SteamDeck | Proprietary; contact developer | Licensable partnership target, especially for desktop-side AirPods knowledge, but not directly drop-in for Android relay. |
| AndroPods / MaterialPods-class apps | Commercial Android AirPods UX and market proof | AirPods/Powerbeats battery, ear detection, assistant tricks | Proprietary apps | Possible partnership targets, but lower technical confidence than LibrePods/CAPod because source is not public. |

## Source Notes

LibrePods is the most relevant AirPods project because it targets Android/Linux and documents AirPods-specific features including noise modes, ear detection, accurate battery, head gestures, conversational awareness, hearing-aid/accessibility settings, and multi-device connectivity. Its README also says root/no-root depends on phone OS and feature class: Pixel Android 16 QPR3 and ColorOS/OxygenOS 16 can avoid root for most features, but VendorID-hook features still require root, and Android 17 is expected to improve this path. Source: https://github.com/kavishdevar/librepods and https://raw.githubusercontent.com/kavishdevar/librepods/main/LICENSE

CAPod is a strong AirPods/Beats Android implementation for battery, charging state, nearby-device display, ear detection with play/pause, automatic connection, popup, and widgets. It lists broad AirPods and Beats support and is GPL-3.0 for code. Source: https://github.com/d4rken-org/capod

OpenPods is a smaller GPL-3.0 AirPods monitor for Android. It is useful for simple status notification behavior and supported model detection, but its README explicitly warns about redistribution to Google Play, so direct product reuse needs careful permission. Source: https://github.com/adolfintel/OpenPods

Gadgetbridge is the strongest broad-coverage source. Its device catalog says it documents 469 gadget models from 64 vendors, and its headphone pages list support for Samsung, Sony, Nothing, Oppo, Realme, Bose, Google, and others. Its architecture is also a close match to what DevPods needs: per-device `DeviceSupport` implementations behind a communication service. Source: https://gadgetbridge.org/gadgets/ and https://gadgetbridge.org/internals/development/project-overview/

Gadgetbridge's Samsung page is especially valuable: it lists Galaxy Buds 2019, Live, Pro, Buds 2, Buds 2 Pro, and Buds 3 Pro with battery, ANC/ambient, touch options, equalizer, find buds, and settings coverage. Source: https://gadgetbridge.org/gadgets/headphones/samsung/

Gadgetbridge's Sony page is also product-relevant: it lists LinkBuds, WF-1000XM3/4/5, WH-1000XM2/3/4/5, and other Sony headphones with features like ANC/ambient control, EQ, touch sensor control panel, button modes, pause when taken off, quick access double/triple tap, speak-to-chat, firmware/codec/battery info. Source: https://gadgetbridge.org/gadgets/headphones/sony/

Gadgetbridge's Nothing/Oppo pages matter because they show non-big-two consumer earbuds can be controlled through reverse-engineered protocols, including in-ear play/pause, battery, ANC profiles, and touch-option configuration. Sources: https://gadgetbridge.org/gadgets/headphones/nothing/ and https://gadgetbridge.org/gadgets/headphones/oppo/

GalaxyBudsClient is useful for Samsung protocol depth. Its README says it can configure/control Samsung Galaxy Buds and includes detailed battery stats, diagnostics, hidden debugging info, custom long-press touch actions, and firmware flashing/downgrading for some models. Source: https://github.com/timschneeb/GalaxyBudsClient

Android itself should remain the universal baseline. Media3's MediaSession docs say media buttons include headset/Bluetooth-headset controls and Media3 handles media-button events when they reach the session. Android BLE docs describe scanning, service discovery, GATT connections, and data transfer. BluetoothHeadset docs expose the Headset/Handsfree profile but also note Android supports one connected Bluetooth Headset at a time. Sources: https://developer.android.com/media/media3/session/control-playback, https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview, and https://developer.android.com/reference/android/bluetooth/BluetoothHeadset

Companion Device Manager is worth using for pairing UX, not for the continuous connection itself. Android says it can scan nearby Bluetooth/Wi-Fi devices on behalf of the app without `ACCESS_FINE_LOCATION`, but does not create continuous connections by itself. Source: https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing

The Bluetooth SIG Battery Service gives the generic fallback path for devices exposing standard GATT battery data. Source: https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/BAS_v1.1/out/en/index-en.html

Nordic's Android BLE Library is a permissively licensed option for hardening BLE operations. It provides connection retries, service discovery, bonding support, operation queues, MTU/PHY/RSSI handling, timeouts, logging, GATT server support, and coroutine/Flow support. Source: https://github.com/NordicSemiconductor/Android-BLE-Library

Jabra is a real licensed channel but narrow for mobile consumer earbuds. Their developer docs say Android/iOS SDKs support Jabra Perform and BlueParrott devices only; for all other Jabra families they currently point developers to native call-control APIs or ask developers to contact them. Source: https://developer.jabra.com/sdks-and-tools/android-ios

## Integration Plan

### Phase 1: Make The Existing Provider Layer Complete

Wire `GenericBluetoothHeadsetProvider` into `SignalProviderRegistry`. This is the missing universal non-AirPods provider and should run alongside LibrePods, MediaSession, and Assistant providers.

Add a provider priority model:

| Priority | Provider | Purpose |
| --- | --- | --- |
| 1 | Vendor-rich provider | AirPods/Samsung/Sony/etc. state and hardware-specific events |
| 2 | Android MediaSession provider | Universal media-button wake/interrupt/approval mapping |
| 3 | Assistant entry provider | Long-press assistant fallback |
| 4 | Push-to-talk notification/UI | Manual rescue path |

The app should record which provider actually observed a wake gesture and make that visible in setup/device screens.

### Phase 2: AirPods Provider Hardening

Use LibrePods as the main behavior target. Use CAPod/OpenPods as cross-checks for BLE advertisement parsing, model IDs, battery behavior, stale-device cleanup, and edge cases.

Add test fixtures for:

- AirPods Pro 2 / Pro 3 / AirPods 4 / AirPods Max / Beats.
- Lid open/closed.
- Left/right in-ear transitions.
- Case/pod charging states.
- Low battery.
- Unknown Apple model IDs.
- Secondary-bud ear-detection regressions.
- Devices where AACP/L2CAP fails and MediaSession fallback must take over.

If your company has authorization to reuse LibrePods code directly, fold it into an isolated `airpods-provider` module with copyright/license notices. If not, keep the current clean-room path and use LibrePods only as external behavioral reference.

### Phase 3: Gadgetbridge-Derived Vendor Providers

Prioritize providers by product value and protocol maturity:

1. `SamsungGalaxyBudsProvider`: use Gadgetbridge and GalaxyBudsClient as source material. Target Galaxy Buds 2/2 Pro/3 Pro first because they are common and Gadgetbridge has strong feature coverage.
2. `SonyHeadphonesProvider`: target WF-1000XM4/XM5 and LinkBuds/S because Gadgetbridge exposes ANC, touch/button modes, pause-when-removed, quick access, and battery info.
3. `NothingEarProvider`: target Nothing Ear 1/2/a/Stick and CMF where protocol is already represented in Gadgetbridge.
4. `OppoRealmeProvider`: target Oppo Enco Air/Air2/Buds2 and compatible Realme/OPlus families where touch options and battery are documented.
5. `GenericGattBatteryProvider`: read standard Battery Service where available and attach battery confidence without pretending to support proprietary controls.

Each provider should implement the same contract:

- `probe()`: identify device, model, confidence, supported features.
- `events`: wake/interrupt/approval only when actually observed or mapped from standard media controls.
- `deviceState`: battery, in-ear, ANC/profile status only if the provider can prove it.
- `capabilityProfile`: user-facing "supported / observed / unavailable / unknown."
- `diagnostics`: protocol version, permission status, last packet timestamp, last error, fallback path.

### Phase 4: Licensed Partnership Targets

If the goal is "perfect" rather than "good open-source baseline," pursue direct permission or commercial licenses from:

- LibrePods maintainer for AirPods Android protocol work.
- CAPod maintainer for AirPods/Beats BLE status implementation.
- Gadgetbridge project/maintainers for AGPL/commercial-compatible reuse or collaboration.
- GalaxyBudsClient maintainer for Samsung protocol details.
- MagicPods developer for AirPods/AAP protocol and desktop UX lessons.
- Jabra for enterprise headset SDK access if DevPods wants enterprise/call-center headsets.

Do not spend time trying to license decompiled OEM APKs unless the OEM itself authorizes it. That path creates provenance risk and is usually slower than licensing from maintainers who already did clean reverse-engineering.

## What "Perfect" Should Mean

For this product, "perfect" should not mean "every gesture from every earbud brand is decoded." That is not technically true for the Android ecosystem.

It should mean:

- The app captures standard headset/media-button/assistant controls on nearly all Android-recognized earbuds.
- The app gives rich status and settings on proven vendor families.
- The setup wizard proves the actual user's earbuds, on the actual user's phone.
- The UI never claims a gesture works until it has been observed.
- The fallback path is graceful: media button, assistant long-press, notification push-to-talk, phone mic.
- The docs and support matrix say exactly what is supported by model, Android version, and confidence level.

## Immediate Next Steps

1. Wire `GenericBluetoothHeadsetProvider` into `SignalProviderRegistry`.
2. Add `docs/supported-devices-matrix.json` entries for sourced candidates: AirPods, Beats, Galaxy Buds, Sony WF/WH/LinkBuds, Nothing, Oppo/Realme, Pixel Buds, Bose, Jabra.
3. Create `EarbudProviderConformanceTest` so every provider proves `probe`, `deviceState`, fallback behavior, and physical wake confidence.
4. Add a `vendor-protocol-spike` branch for Samsung via Gadgetbridge/GalaxyBudsClient source review.
5. Decide licensing posture before copying any GPL/AGPL code into the app: direct GPL/AGPL app, commercial permission, or clean-room protocol implementation.

## Recommendation

The best sourcing plan is:

1. AirPods/Beats: use LibrePods as the main reference and CAPod/OpenPods as robustness cross-checks.
2. Samsung/Sony/Nothing/Oppo/Realme: use Gadgetbridge as the primary source, with GalaxyBudsClient as Samsung-specific backup.
3. Generic earbuds: rely on Android MediaSession, BluetoothHeadset, Companion Device Manager, standard BLE/GATT battery where available, and visible setup proof.
4. Enterprise/Jabra: use official SDKs only if the product target includes Jabra Perform/BlueParrott or desktop softphone control.

This gives DevPods a realistic path to broad physical earbud compatibility without wasting months rebuilding reverse-engineered protocol knowledge that already exists.
