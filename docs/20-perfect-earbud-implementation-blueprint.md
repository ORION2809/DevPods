# Perfect Earbud Implementation Blueprint

Date: 2026-05-13

## Scope

This document turns the source map in `docs/19-earbud-compatibility-integration-source-map.md` into an implementation blueprint for broad physical earbud compatibility. The referenced repositories were cloned into `external/earbud-protocol-references/` so they can be audited and consumed without disturbing the main DevPods code.

 Even with permission, the safest implementation shape is still modular: import only the protocol, transport, fixtures, and capability-matrix logic we actually need, and keep DevPods' policy and assistant execution model unchanged.

## Reference Snapshots

| Source | Local folder | Snapshot used |
| --- | --- | --- |
| LibrePods | `external/earbud-protocol-references/librepods` | `5bc5079e134a` |
| CAPod | `external/earbud-protocol-references/capod` | `78790f73df7d` |
| OpenPods | `external/earbud-protocol-references/openpods` | `9cd800875f59` |
| Gadgetbridge | `external/earbud-protocol-references/gadgetbridge` | `4490fddb7ed0` |
| GalaxyBudsClient | `external/earbud-protocol-references/galaxybudsclient` | `32adcdaa70f3` |
| HyperPods | `external/earbud-protocol-references/hyperpods` | `9796d947daa1` |
| Nordic Android BLE Library | `external/earbud-protocol-references/android-ble-library` | `4a86e6c6b263` |
| RxAndroidBle | `external/earbud-protocol-references/rxandroidble` | `5f4b84610762` |

## Executive Verdict

The perfect implementation is not "one magic earbud library." The perfect implementation is a layered provider mesh:

- Universal Android input stays the always-on baseline for wake, interrupt, approval, and push-to-talk.
- Vendor providers add rich state and controls only when the device family is proven.
- The setup wizard proves the user's exact device and phone combination before the UI claims anything works.
- Imported code should be isolated per vendor family, with provider conformance tests and packet fixtures.
- DevPods' security boundary must not move: earbuds can trigger intents, but the bridge policy and approval system still decides what happens.

The best source consumption order is:

1. Wire and harden DevPods' existing provider seam first.
2. Build AirPods/Beats from CAPod plus LibrePods, with OpenPods as a small parser sanity check.
3. Build Samsung from Gadgetbridge plus GalaxyBudsClient fixtures.
4. Build Sony, Nothing, Oppo, Realme, Redmi, Soundcore, Pixel, and Bose from Gadgetbridge providers.
5. Use Nordic Android BLE Library only for BLE/GATT infrastructure where a vendor provider actually needs GATT connections.
6. Keep HyperPods root/Xposed and native L2CAP hook ideas as an optional lab/advanced track, not the production default.

## Non-Negotiable Architecture

DevPods already has the correct integration seam. `EarbudSignalProvider` defines the provider contract, including provider identity, physical confidence, capability profile, device state, events, lifecycle, and probing [EarbudSignalProvider.kt:6](../android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudSignalProvider.kt#L6). Device state and capabilities are already modeled separately [EarbudDeviceState.kt:6](../android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudDeviceState.kt#L6), [EarbudCapabilityProfile.kt:15](../android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudCapabilityProfile.kt#L15), [EarbudCapabilityProfile.kt:19](../android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudCapabilityProfile.kt#L19).

The current registry is close but incomplete. It starts MediaSession, Assistant, and LibrePods providers [SignalProviderRegistry.kt:23](../android-relay/app/src/main/java/com/openclaw/relay/signal/SignalProviderRegistry.kt#L23), and merges every provider event stream into `allEvents` [SignalProviderRegistry.kt:37](../android-relay/app/src/main/java/com/openclaw/relay/signal/SignalProviderRegistry.kt#L37). The generic Bluetooth provider already exists but is not in the registry list, so the first real implementation step is to wire it in [GenericBluetoothHeadsetProvider.kt:31](../android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt#L31), [SignalProviderRegistry.kt:25](../android-relay/app/src/main/java/com/openclaw/relay/signal/SignalProviderRegistry.kt#L25).

The provider system must remain a signal and hardware-context layer. RelayService already captures provider provenance and turns signal events into observed provider records and hardware context [RelayService.kt:284](../android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt#L284), [RelayService.kt:308](../android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt#L308), [RelayService.kt:346](../android-relay/app/src/main/java/com/openclaw/relay/RelayService.kt#L346). Do not let imported vendor code call bridge APIs, execute actions, approve actions, or bypass RelayService.

## Target Module Layout

Create isolated implementation modules under the Android relay rather than dropping upstream source into the existing signal package directly:

| Module/package | Purpose |
| --- | --- |
| `signal/provider` | DevPods provider contracts and generic provider registry wiring. |
| `signal/vendor/apple` | AirPods/Beats BLE proximity and AAP/AACP provider. |
| `signal/vendor/samsung` | Galaxy Buds RFCOMM/SPP provider. |
| `signal/vendor/sony` | Sony headphones RFCOMM provider. |
| `signal/vendor/nothing` | Nothing Ear RFCOMM provider. |
| `signal/vendor/oppo` | Oppo/Realme/OPlus headphones provider. |
| `signal/vendor/xiaomi` | Redmi/Xiaomi buds provider, if pulled from Gadgetbridge. |
| `signal/vendor/genericgatt` | Standard BLE/GATT battery and device-info provider. |
| `signal/transport/btclassic` | RFCOMM queue, read loop, reconnection, framed packet IO. |
| `signal/transport/ble` | BLE scanner/GATT helpers; use Nordic here if needed. |
| `signal/fixtures` | Packet fixtures and provider conformance data derived from upstream tests. |

This keeps the imported vendor logic auditable and lets the UI consume one DevPods-native state model.

## Layer 0: Universal Android Baseline

The baseline must work for almost every Android-recognized earbud, even when no proprietary protocol is decoded.

Implementation:

- Add `GenericBluetoothHeadsetProvider` to `SignalProviderRegistry.allProviders`.
- Keep `AndroidMediaSessionProvider` and `AssistantEntryProvider` active as fallback providers.
- Show the winning provider in setup and diagnostics, so the user sees whether their taps arrived through vendor protocol, media session, assistant entry, or notification push-to-talk.
- Never claim vendor-level support unless a vendor provider actually probed and observed the device.

Why this is the baseline:

- `GenericBluetoothHeadsetProvider` is already designed for non-AirPods Bluetooth audio devices through `BluetoothHeadset` and `AudioManager` [GenericBluetoothHeadsetProvider.kt:25](../android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt#L25).
- It already declares standard wake, interrupt, approval, and audio-route capabilities [GenericBluetoothHeadsetProvider.kt:44](../android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt#L44), [GenericBluetoothHeadsetProvider.kt:53](../android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt#L53).
- Its `probe()` can report connected headset status even when proprietary gestures are unproven [GenericBluetoothHeadsetProvider.kt:154](../android-relay/app/src/main/java/com/openclaw/relay/signal/GenericBluetoothHeadsetProvider.kt#L154).

Acceptance criteria:

- Any connected Bluetooth headset can be detected in the Device screen.
- Setup shows a physical tap proof state, not only a model-name guess.
- If no vendor provider is available, the app still supports media-button wake, assistant wake, notification push-to-talk, phone mic fallback, and visible capability limitations.

## Layer 1: AirPods And Beats

### Consume These Parts

Use CAPod as the primary AirPods/Beats device matrix and BLE snapshot pipeline:

- `PodModel` has the strongest AirPods/Beats feature matrix, model numbers, and feature flags including ear detection, ANC, conversation awareness, and stem configuration [PodModel.kt:9](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/PodModel.kt#L9), [PodModel.kt:541](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/PodModel.kt#L541), [PodModel.kt:550](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/PodModel.kt#L550).
- `AppleFactory` shows the correct pipeline from scan result to proximity message, profile lookup, RPA/IRK matching, encrypted payload decryption, and snapshot creation [AppleFactory.kt:29](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/ble/AppleFactory.kt#L29), [AppleFactory.kt:68](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/ble/AppleFactory.kt#L68), [AppleFactory.kt:72](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/ble/AppleFactory.kt#L72), [AppleFactory.kt:95](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/ble/AppleFactory.kt#L95).
- `ProximityMessage.Decrypter` contains the AES/ECB decryption path for encrypted proximity data [ProximityMessage.kt:27](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/ble/protocol/ProximityMessage.kt#L27), [ProximityMessage.kt:31](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/ble/protocol/ProximityMessage.kt#L31).
- `AapConnectionManager`, `AapSessionEngine`, `AapInboundInterpreter`, `AapFramer`, and `StemPressEvent` are the most complete AAP/AACP pieces for live state and stem press events [AapConnectionManager.kt:39](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/aap/AapConnectionManager.kt#L39), [AapConnectionManager.kt:65](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/aap/AapConnectionManager.kt#L65), [AapSessionEngine.kt:35](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/aap/engine/AapSessionEngine.kt#L35), [AapInboundInterpreter.kt:16](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/aap/engine/AapInboundInterpreter.kt#L16), [AapFramer.kt:18](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/aap/protocol/AapFramer.kt#L18), [StemPressEvent.kt:8](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/pods/core/apple/aap/protocol/StemPressEvent.kt#L8).
- `L2capSocketFactory` has a safer Android L2CAP socket creation abstraction than DevPods' current reflection-only attempt [L2capSocketFactory.kt:14](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/common/bluetooth/l2cap/L2capSocketFactory.kt#L14), [L2capSocketFactory.kt:36](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/common/bluetooth/l2cap/L2capSocketFactory.kt#L36), [L2capSocketFactory.kt:83](../external/earbud-protocol-references/capod/app/src/main/java/eu/darken/capod/common/bluetooth/l2cap/L2capSocketFactory.kt#L83).

Use LibrePods for AirPods-specific behavior coverage:

- `BLEManager.AirPodsStatus` already models address, model, batteries, ear state, charging, lid, color, and connection state [BLEManager.kt:46](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt#L46).
- Its scanner uses Apple manufacturer data `76`, the same public proximity filter shape, encrypted payload handling, stale cleanup, and parsing for both decrypted and public proximity messages [BLEManager.kt:127](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt#L127), [BLEManager.kt:169](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt#L169), [BLEManager.kt:229](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt#L229), [BLEManager.kt:335](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt#L335), [BLEManager.kt:392](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BLEManager.kt#L392).
- `AACPManager` has the richest opcode/control-command catalog, including feature flags, battery info, control commands, ear detection, conversation awareness, head tracking, proximity keys, and stem press values [AACPManager.kt:39](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt#L39), [AACPManager.kt:86](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt#L86), [AACPManager.kt:130](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt#L130), [AACPManager.kt:337](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/AACPManager.kt#L337).
- `StemAction` documents default AirPods stem mappings, which are useful for DevPods setup copy and sane defaults [StemAction.kt:33](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/data/StemAction.kt#L33).
- `Packets.kt` has reusable parsers/concepts for battery, ANC, ear detection, conversation awareness, and capability flags [Packets.kt:47](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt#L47), [Packets.kt:68](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt#L68), [Packets.kt:88](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt#L88), [Packets.kt:108](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt#L108), [Packets.kt:224](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/data/Packets.kt#L224).

Use OpenPods only as a compact parser cross-check:

- Its scan callback independently confirms Apple manufacturer ID `76`, filter data, RSSI gating, and result decoding [PodsStatusScanCallback.java:29](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatusScanCallback.java#L29), [PodsStatusScanCallback.java:37](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatusScanCallback.java#L37), [PodsStatusScanCallback.java:75](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatusScanCallback.java#L75), [PodsStatusScanCallback.java:118](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatusScanCallback.java#L118).
- `PodsStatus` explains the public proximity battery, charging, in-ear, flip, and model fields in a small implementation [PodsStatus.java:22](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatus.java#L22), [PodsStatus.java:50](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatus.java#L50), [PodsStatus.java:62](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatus.java#L62), [PodsStatus.java:75](../external/earbud-protocol-references/openpods/app/src/main/java/com/dosse/airpods/pods/PodsStatus.java#L75).

### DevPods Implementation

Replace the current `AirPodsBleScanner` and `AacpConnectionManager` internals with an `ApplePodsProvider` stack:

1. `AppleProximityScanner`: derived from CAPod/LibrePods/OpenPods, emits raw proximity observations and parsed snapshots.
2. `AppleDeviceResolver`: uses CAPod `PodModel`, known model IDs, optional IRK/RPA matching, and user-approved pairing profile data.
3. `AppleAapSession`: derived from CAPod `AapConnectionManager` plus LibrePods opcode coverage.
4. `AppleSignalMapper`: converts stem press events to DevPods `WakeGesture`, `InterruptGesture`, and `ApprovalGesture`, but only after the setup wizard observes the physical gesture.
5. `AppleStateMapper`: maps batteries, in-ear, lid, ANC/listening mode, conversation awareness, and connection state into `EarbudDeviceState`.

The existing DevPods Apple provider already proves the seam: it combines BLE proximity scanning and best-effort L2CAP/AACP stem events [LibrePodsAirPodsProvider.kt:25](../android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt#L25), emits device state [LibrePodsAirPodsProvider.kt:65](../android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt#L65), and forwards AACP events [LibrePodsAirPodsProvider.kt:148](../android-relay/app/src/main/java/com/openclaw/relay/signal/LibrePodsAirPodsProvider.kt#L148). Its current scanner and manager are useful prototypes, but they are thinner than the imported sources [AirPodsBleScanner.kt:31](../android-relay/app/src/main/java/com/openclaw/relay/signal/AirPodsBleScanner.kt#L31), [AacpConnectionManager.kt:33](../android-relay/app/src/main/java/com/openclaw/relay/signal/AacpConnectionManager.kt#L33).

### Product Caveat

Root/Xposed is not the default product path. LibrePods itself uses Xposed service/scope checks for Bluetooth integration [LibrePodsApplication.kt:14](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/LibrePodsApplication.kt#L14), [LibrePodsApplication.kt:28](../external/earbud-protocol-references/librepods/android/app/src/main/java/me/kavishdevar/librepods/LibrePodsApplication.kt#L28), and its README calls out root/VendorID-hook constraints [README.md:77](../external/earbud-protocol-references/librepods/README.md#L77). HyperPods is explicitly an Xposed module [README.md:3](../external/earbud-protocol-references/hyperpods/README.md#L3), uses native L2CAP hooks [hyperpods.cpp:62](../external/earbud-protocol-references/hyperpods/app/src/main/cpp/hyperpods.cpp#L62), and constructs L2CAP sockets through hidden/internal APIs [L2CAPController.kt:338](../external/earbud-protocol-references/hyperpods/app/src/main/java/moe/chenxy/hyperpods/pods/L2CAPController.kt#L338). Treat these as advanced lab references, not normal Play Store behavior.

## Layer 2: Samsung Galaxy Buds

### Consume These Parts

Use Gadgetbridge as the Android-native Samsung implementation source:

- Galaxy Buds coordinators are Bluetooth Classic providers with find-device support [GalaxyBudsGenericCoordinator.java:28](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/galaxy_buds/GalaxyBudsGenericCoordinator.java#L28), [GalaxyBudsGenericCoordinator.java:40](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/galaxy_buds/GalaxyBudsGenericCoordinator.java#L40).
- `GalaxyBudsDeviceSupport` creates `GalaxyBudsProtocol` and selects Galaxy Buds control UUIDs [GalaxyBudsDeviceSupport.java:26](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsDeviceSupport.java#L26), [GalaxyBudsDeviceSupport.java:33](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsDeviceSupport.java#L33).
- `GalaxyBudsProtocol` includes control UUIDs, find-device commands, ambient/ANC commands, touch lock/options, conversation detection, one-earbud noise controls, full touch controls for Buds3 Pro, and battery decoding [GalaxyBudsProtocol.java:46](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsProtocol.java#L46), [GalaxyBudsProtocol.java:49](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsProtocol.java#L49), [GalaxyBudsProtocol.java:68](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsProtocol.java#L68), [GalaxyBudsProtocol.java:174](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsProtocol.java#L174), [GalaxyBudsProtocol.java:341](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsProtocol.java#L341), [GalaxyBudsProtocol.java:556](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/galaxy_buds/GalaxyBudsProtocol.java#L556).
- Buds2 and Buds3 Pro coordinators model left, right, and case batteries [GalaxyBuds2DeviceCoordinator.java:39](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/galaxy_buds/GalaxyBuds2DeviceCoordinator.java#L39), [GalaxyBuds2DeviceCoordinator.java:44](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/galaxy_buds/GalaxyBuds2DeviceCoordinator.java#L44), [GalaxyBuds3ProDeviceCoordinator.java:39](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/galaxy_buds/GalaxyBuds3ProDeviceCoordinator.java#L39), [GalaxyBuds3ProDeviceCoordinator.java:44](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/galaxy_buds/GalaxyBuds3ProDeviceCoordinator.java#L44).

Use GalaxyBudsClient for protocol notes and fixtures:

- Its protocol notes define the RFCOMM packet fields, CRC, message IDs, battery bytes, touch lock, and touch options [GalaxyBudsRFCommProtocol.md:1](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsRFCommProtocol.md#L1), [GalaxyBudsRFCommProtocol.md:19](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsRFCommProtocol.md#L19), [GalaxyBudsRFCommProtocol.md:22](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsRFCommProtocol.md#L22), [GalaxyBudsRFCommProtocol.md:183](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsRFCommProtocol.md#L183), [GalaxyBudsRFCommProtocol.md:193](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsRFCommProtocol.md#L193).
- Buds+ notes independently document battery and touch option payloads [Galaxy Buds Plus RFComm Protocol Notes.md:7](../external/earbud-protocol-references/galaxybudsclient/Galaxy%20Buds%20Plus%20RFComm%20Protocol%20Notes.md#L7), [Galaxy Buds Plus RFComm Protocol Notes.md:50](../external/earbud-protocol-references/galaxybudsclient/Galaxy%20Buds%20Plus%20RFComm%20Protocol%20Notes.md#L50), [Galaxy Buds Plus RFComm Protocol Notes.md:55](../external/earbud-protocol-references/galaxybudsclient/Galaxy%20Buds%20Plus%20RFComm%20Protocol%20Notes.md#L55), [Galaxy Buds Plus RFComm Protocol Notes.md:60](../external/earbud-protocol-references/galaxybudsclient/Galaxy%20Buds%20Plus%20RFComm%20Protocol%20Notes.md#L60).
- GalaxyBudsClient has Android RFCOMM connection code and real extended-status test fixtures for Buds2, Buds2 Pro, and Buds FE [BluetoothService.cs:88](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Android/Impl/BluetoothService.cs#L88), [BluetoothService.cs:103](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Android/Impl/BluetoothService.cs#L103), [BluetoothService.cs:237](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Android/Impl/BluetoothService.cs#L237), [BluetoothService.cs:269](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Android/Impl/BluetoothService.cs#L269), [Buds2/ExtendedStatusUpdateTests.cs:16](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Tests/Buds2/ExtendedStatusUpdateTests.cs#L16), [Buds2Pro/ExtendedStatusUpdateTests.cs:16](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Tests/Buds2Pro/ExtendedStatusUpdateTests.cs#L16), [BudsFe/ExtendedStatusUpdateTests.cs:17](../external/earbud-protocol-references/galaxybudsclient/GalaxyBudsClient.Tests/BudsFe/ExtendedStatusUpdateTests.cs#L17).

### DevPods Implementation

Create `SamsungGalaxyBudsProvider`:

1. `SamsungDeviceMatcher`: detects Galaxy Buds models by bonded Bluetooth name, service UUID, and optional known coordinator patterns.
2. `SamsungRfcommTransport`: reusable Bluetooth Classic socket queue modeled after Gadgetbridge `BtBRQueue`, with one read loop, one write queue, reconnect backoff, and lifecycle ownership [BtBRQueue.java:45](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/BtBRQueue.java#L45), [BtBRQueue.java:69](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/BtBRQueue.java#L69), [BtBRQueue.java:254](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/BtBRQueue.java#L254), [BtBRQueue.java:278](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/BtBRQueue.java#L278), [BtBRQueue.java:329](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/BtBRQueue.java#L329).
3. `SamsungPacketCodec`: derived from Gadgetbridge and cross-checked against GalaxyBudsClient docs/tests.
4. `SamsungStateMapper`: emits battery, ANC/ambient, touch lock/options, and in-ear/conversation-capable state where available.
5. `SamsungSettingsAdapter`: optionally lets DevPods set long-press/touch action to a media or voice-assistant action that DevPods can capture through Layer 0.

Important limitation: most Samsung "touch" support in upstream projects is configuration of the earbud's own touch actions, not a guaranteed stream of raw tap events for third-party apps. DevPods should use the configuration path to make the hardware send Android media or assistant events, then prove that physical event via setup.

## Layer 3: Sony Headphones And Earbuds

### Consume These Parts

Use Gadgetbridge's Sony provider:

- `SonyHeadphonesCapabilities` is an explicit capability enum for ambient control, battery layouts, button modes, pause-when-taken-off, quick access, touch sensor, and wind noise reduction [SonyHeadphonesCapabilities.java:19](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/SonyHeadphonesCapabilities.java#L19), [SonyHeadphonesCapabilities.java:21](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/SonyHeadphonesCapabilities.java#L21), [SonyHeadphonesCapabilities.java:30](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/SonyHeadphonesCapabilities.java#L30), [SonyHeadphonesCapabilities.java:34](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/SonyHeadphonesCapabilities.java#L34), [SonyHeadphonesCapabilities.java:39](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/SonyHeadphonesCapabilities.java#L39), [SonyHeadphonesCapabilities.java:41](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/SonyHeadphonesCapabilities.java#L41).
- `AbstractSonyProtocolImpl` defines the get/set protocol surface for ambient control, battery, button function, button modes, quick access, pause-when-taken-off, touch sensor, and payload handling [AbstractSonyProtocolImpl.java:49](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/AbstractSonyProtocolImpl.java#L49), [AbstractSonyProtocolImpl.java:64](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/AbstractSonyProtocolImpl.java#L64), [AbstractSonyProtocolImpl.java:85](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/AbstractSonyProtocolImpl.java#L85), [AbstractSonyProtocolImpl.java:109](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/AbstractSonyProtocolImpl.java#L109), [AbstractSonyProtocolImpl.java:121](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/AbstractSonyProtocolImpl.java#L121), [AbstractSonyProtocolImpl.java:139](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/protocol/impl/AbstractSonyProtocolImpl.java#L139).
- `SonyHeadphonesProtocol` queues requests by ACK, selects V1/V2 protocol implementations, decodes responses, and maps settings to requests [SonyHeadphonesProtocol.java:65](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java#L65), [SonyHeadphonesProtocol.java:70](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java#L70), [SonyHeadphonesProtocol.java:82](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java#L82), [SonyHeadphonesProtocol.java:155](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java#L155), [SonyHeadphonesProtocol.java:192](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesProtocol.java#L192).
- `SonyHeadphonesSupport` is already a serial headphone device support class [SonyHeadphonesSupport.java:40](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesSupport.java#L40), [SonyHeadphonesSupport.java:81](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesSupport.java#L81), [SonyHeadphonesSupport.java:174](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/sony/headphones/SonyHeadphonesSupport.java#L174).
- WF-1000XM4/XM5 and LinkBuds coordinators give specific battery and capability sets [SonyWF1000XM4Coordinator.java:40](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWF1000XM4Coordinator.java#L40), [SonyWF1000XM4Coordinator.java:49](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWF1000XM4Coordinator.java#L49), [SonyWF1000XM5Coordinator.java:40](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWF1000XM5Coordinator.java#L40), [SonyWF1000XM5Coordinator.java:49](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyWF1000XM5Coordinator.java#L49), [SonyLinkBudsCoordinator.java:40](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/sony/headphones/coordinators/SonyLinkBudsCoordinator.java#L40).

### DevPods Implementation

Create `SonyHeadphonesProvider`:

1. Match Sony model families from bonded device names and known coordinator patterns.
2. Use the shared `BtClassicSerialTransport`.
3. Port the Sony message framing, ACK queue, V1/V2 protocol selection, and capability set.
4. Expose Sony capabilities as DevPods capability profile entries: battery, case battery where present, ANC/ambient, touch sensor setting, quick access, pause-when-taken-off.
5. Use Sony button/touch settings to steer hardware toward standard Android media/assistant events when possible, then validate in setup.

## Layer 4: Nothing, Oppo, Realme, OPlus

### Consume These Parts

Use Gadgetbridge:

- Nothing Ear coordinator exposes find-device, left/right/case battery, in-ear detection, ANC/transparency/adaptive ANC feature flags, low-latency, and ultra-bass capability hooks [AbstractEarCoordinator.java:33](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/nothing/AbstractEarCoordinator.java#L33), [AbstractEarCoordinator.java:40](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/nothing/AbstractEarCoordinator.java#L40), [AbstractEarCoordinator.java:55](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/nothing/AbstractEarCoordinator.java#L55), [AbstractEarCoordinator.java:97](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/nothing/AbstractEarCoordinator.java#L97), [AbstractEarCoordinator.java:101](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/nothing/AbstractEarCoordinator.java#L101).
- Nothing `Ear1Support` decodes battery, audio mode, in-ear status, equalizer, low latency, firmware, and find-device commands [Ear1Support.java:49](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/nothing/Ear1Support.java#L49), [Ear1Support.java:244](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/nothing/Ear1Support.java#L244), [Ear1Support.java:278](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/nothing/Ear1Support.java#L278), [Ear1Support.java:287](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/nothing/Ear1Support.java#L287), [Ear1Support.java:491](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/nothing/Ear1Support.java#L491), [Ear1Support.java:503](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/nothing/Ear1Support.java#L503).
- Oppo/Realme coordinators expose touch option matrices for play/pause, previous, next, voice assistant, game mode, volume up/down, and Realme voice assistant values [OppoEncoAirCoordinator.java:52](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/oppo/OppoEncoAirCoordinator.java#L52), [OppoEncoAirCoordinator.java:56](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/oppo/OppoEncoAirCoordinator.java#L56), [RealmeBudsAir5ProCoordinator.java:52](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/realme/RealmeBudsAir5ProCoordinator.java#L52), [RealmeBudsAir5ProCoordinator.java:56](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/realme/RealmeBudsAir5ProCoordinator.java#L56).
- Oppo protocol decodes touch config and battery, and encodes find-device plus touch configuration [OppoHeadphonesProtocol.java:47](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/oppo/OppoHeadphonesProtocol.java#L47), [OppoHeadphonesProtocol.java:245](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/oppo/OppoHeadphonesProtocol.java#L245), [OppoHeadphonesProtocol.java:293](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/oppo/OppoHeadphonesProtocol.java#L293), [OppoHeadphonesProtocol.java:320](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/oppo/OppoHeadphonesProtocol.java#L320), [OppoHeadphonesProtocol.java:325](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/oppo/OppoHeadphonesProtocol.java#L325).
- `TouchConfigValue` gives a compact enum for app-facing touch choices [TouchConfigValue.java:21](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/oppo/commands/TouchConfigValue.java#L21).

### DevPods Implementation

Create two providers first: `NothingEarProvider` and `OppoRealmeProvider`.

The most valuable DevPods use is not raw tap streaming. It is configuring/confirming hardware behavior so the buds send standard media or assistant events that Layer 0 can capture. Therefore:

- Expose "optimize touch controls for DevPods" in setup only for devices whose upstream provider supports touch configuration.
- Prefer mapping a long press or double tap to voice assistant/media control, then run the actual wake-test.
- Store the proven mapping in `DeviceCapabilityMatrix`.
- Show a reversible settings card: "DevPods changed left long press to Assistant. Restore previous setting."

## Layer 5: Xiaomi/Redmi, Soundcore, Pixel, Bose, Generic Headphones

Use Gadgetbridge for breadth, but treat these as second-wave providers unless the product target demands them immediately:

- Gadgetbridge has provider folders for `generic_headphones`, `pixel`, `qc35`, `soundcore`, `redmibuds`, `earfun`, `moondrop`, and more [devices folder list](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices).
- Pixel Buds A coordinator models case, left, and right batteries and has a dedicated support class [PixelBudsACoordinator.java:43](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/pixel/PixelBudsACoordinator.java#L43), [PixelBudsACoordinator.java:59](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/pixel/PixelBudsACoordinator.java#L59).
- Bose QC35 coordinator provides a Bluetooth Classic support path and battery config [QC35Coordinator.java:47](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/qc35/QC35Coordinator.java#L47), [QC35Coordinator.java:62](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/qc35/QC35Coordinator.java#L62).
- Soundcore and Redmi providers already model multiple current models, batteries, settings, and gesture/touch screens [SoundcoreLiberty4NCCoordinator.java:50](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/soundcore/liberty4_nc/SoundcoreLiberty4NCCoordinator.java#L50), [SoundcoreLiberty4NCCoordinator.java:62](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/soundcore/liberty4_nc/SoundcoreLiberty4NCCoordinator.java#L62), [AbstractRedmiBudsCoordinator.java:52](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/redmibuds/AbstractRedmiBudsCoordinator.java#L52), [RedmiBuds3ProCoordinator.java:35](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/redmibuds/RedmiBuds3ProCoordinator.java#L35).

Implementation stance:

- Start with `GenericBluetoothHeadsetProvider` for these brands.
- Add rich provider support brand by brand only when a test fixture or physical device is available.
- Use proprietary providers for battery/settings and standard Android events for wake/approval unless raw events are proven.

## Layer 6: BLE/GATT Infrastructure

Use Nordic Android BLE Library for DevPods' coroutine/Flow-style BLE infrastructure if provider code grows beyond simple scans:

- It supports BLE scan results as `Flow`, mocking BLE devices, suspend/Flow operations, queues, bonding, MTU/PHY/RSSI, GATT server support, and coroutine extensions [android-ble-library README.md:17](../external/earbud-protocol-references/android-ble-library/README.md#L17), [android-ble-library README.md:19](../external/earbud-protocol-references/android-ble-library/README.md#L19), [android-ble-library README.md:20](../external/earbud-protocol-references/android-ble-library/README.md#L20), [android-ble-library README.md:86](../external/earbud-protocol-references/android-ble-library/README.md#L86), [android-ble-library README.md:89](../external/earbud-protocol-references/android-ble-library/README.md#L89), [android-ble-library README.md:91](../external/earbud-protocol-references/android-ble-library/README.md#L91), [android-ble-library README.md:99](../external/earbud-protocol-references/android-ble-library/README.md#L99).
- `BleManagerExt` exposes connection and bonding state as Flows, which matches DevPods' StateFlow style [BleManagerExt.kt:49](../external/earbud-protocol-references/android-ble-library/ble-ktx/src/main/java/no/nordicsemi/android/ble/ktx/BleManagerExt.kt#L49), [BleManagerExt.kt:64](../external/earbud-protocol-references/android-ble-library/ble-ktx/src/main/java/no/nordicsemi/android/ble/ktx/BleManagerExt.kt#L64).
- `BleManager` already has request queue, callbacks, notification callbacks, bonding, connect requests, and MTU/PHY/RSSI support [BleManager.java:108](../external/earbud-protocol-references/android-ble-library/ble/src/main/java/no/nordicsemi/android/ble/BleManager.java#L108), [BleManager.java:193](../external/earbud-protocol-references/android-ble-library/ble/src/main/java/no/nordicsemi/android/ble/BleManager.java#L193), [BleManager.java:876](../external/earbud-protocol-references/android-ble-library/ble/src/main/java/no/nordicsemi/android/ble/BleManager.java#L876), [Request.java:955](../external/earbud-protocol-references/android-ble-library/ble/src/main/java/no/nordicsemi/android/ble/Request.java#L955), [Request.java:1001](../external/earbud-protocol-references/android-ble-library/ble/src/main/java/no/nordicsemi/android/ble/Request.java#L1001), [Request.java:1046](../external/earbud-protocol-references/android-ble-library/ble/src/main/java/no/nordicsemi/android/ble/Request.java#L1046).

RxAndroidBle is good, but it is RxJava-first. DevPods is Kotlin coroutines/Flow-first. Use RxAndroidBle only if a specific upstream provider or fixture requires it. Its README confirms the Rx model and BLE scan/connect/read/write/notification APIs [rxandroidble README.md:13](../external/earbud-protocol-references/rxandroidble/README.md#L13), [rxandroidble README.md:150](../external/earbud-protocol-references/rxandroidble/README.md#L150), [rxandroidble README.md:215](../external/earbud-protocol-references/rxandroidble/README.md#L215), [rxandroidble README.md:242](../external/earbud-protocol-references/rxandroidble/README.md#L242), [rxandroidble README.md:323](../external/earbud-protocol-references/rxandroidble/README.md#L323).

## Provider Contract Every Vendor Must Satisfy

Every provider, imported or custom, must implement the same DevPods-facing contract:

| Contract method/state | Requirement |
| --- | --- |
| `providerId` | Stable ID such as `apple-airpods`, `samsung-galaxy-buds`, `sony-headphones`. |
| `providerLabel` | User-visible label. |
| `isPhysicalInput` | `true` only if it can observe or configure physical earbud input. |
| `defaultConfidence` | `PROVEN_PHYSICAL` only after setup observes actual hardware input. |
| `capabilityProfile` | Must distinguish supported, observed, unavailable, and unknown. |
| `deviceState` | Battery, in-ear, ANC, lid, model, route state, and last packet timestamp only when real data exists. |
| `events` | Wake, interrupt, approval, battery, ear state, connection, and diagnostics events. |
| `probe()` | Must return model, confidence, setup guidance, permission blockers, and fallback recommendation. |
| `start()`/`stop()` | Must own sockets/scans cleanly and avoid leaked foreground/background work. |

Do not add vendor-specific behavior directly to RelayService or Compose screens. The UI should render capabilities and state generically.

## Implementation Phases

### Phase 1: Provider Registry And Generic Baseline

Steps:

1. Add `GenericBluetoothHeadsetProvider` to `SignalProviderRegistry`.
2. Add a provider health row for every active provider: running, blocked by permission, connected, last event, last error.
3. Add conformance tests for `probe()`, capability profile, and provider startup failure isolation.
4. Fix the typo `Gesturedetected` while touching the provider contract, if it is not already used externally [EarbudSignalProvider.kt:27](../android-relay/app/src/main/java/com/openclaw/relay/signal/EarbudSignalProvider.kt#L27).

Definition of done:

- Non-AirPods earbuds show up as connected Bluetooth headsets.
- Physical tap proof can pass through MediaSession or assistant path.
- A failing vendor provider cannot break other providers.

### Phase 2: Shared Transports

Steps:

1. Create `BtClassicSerialTransport` from Gadgetbridge's `AbstractBTBRDeviceSupport` and `BtBRQueue` concepts [AbstractBTBRDeviceSupport.java:41](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/AbstractBTBRDeviceSupport.java#L41), [AbstractBTBRDeviceSupport.java:63](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/AbstractBTBRDeviceSupport.java#L63), [BtBRQueue.java:65](../external/earbud-protocol-references/gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/btbr/BtBRQueue.java#L65).
2. Create `L2capAapTransport` from CAPod `L2capSocketFactory`, with DevPods-safe feature flags and graceful fallback.
3. Add a reconnect/backoff policy shared by Samsung, Sony, Nothing, Oppo/Realme, and AirPods AAP.
4. Log transport failures into diagnostics, not just Android logcat.

Definition of done:

- Vendor transports can be tested without RelayService.
- Socket lifecycle is deterministic.
- Every connection attempt has timeout, cancellation, and failure reason.

### Phase 3: AirPods/Beats Rich Provider

Steps:

1. Import/port CAPod `PodModel`, proximity message parsing, encryption/decryption, and AAP session engine.
2. Import/port LibrePods opcode/control-command knowledge that CAPod lacks.
3. Keep OpenPods parser cases as minimal fixtures.
4. Replace DevPods' current thin Apple scanner and AACP manager internals.
5. Map AirPods state into `EarbudDeviceState` and `DeviceCapabilityMatrix`.
6. Add setup proof for single, double, triple, and long press where AAP stem events are available.
7. Add fallback guidance when AAP/L2CAP fails: continue through MediaSession/Assistant.

Definition of done:

- AirPods Pro 2/3, AirPods 4, AirPods Max, and at least one Beats model have fixture coverage.
- The UI can show left/right/case battery, charging, lid, in-ear, ANC/listening state, and last observed stem event when supported.
- AAP failure does not block standard media-button use.

### Phase 4: Samsung Galaxy Buds Provider

Steps:

1. Port Gadgetbridge Galaxy Buds protocol and model coordinators needed for Buds2, Buds2 Pro, Buds3 Pro, Buds FE, Buds Live, and Buds Pro.
2. Import GalaxyBudsClient fixture binaries into DevPods test resources.
3. Implement `SamsungPacketCodec` with tests against GalaxyBudsClient expected values.
4. Implement optional touch-setting optimization, but require setup proof after every setting change.
5. Expose battery, ANC/ambient/noise, touch lock/options, firmware/protocol diagnostics, and find-buds where safe.

Definition of done:

- Samsung provider can connect, decode status, and survive official app contention.
- At least Buds2, Buds2 Pro, Buds3 Pro, and Buds FE have packet fixtures.
- The app clearly labels touch configuration versus live tap detection.

### Phase 5: Sony Provider

Steps:

1. Port Gadgetbridge Sony transport/protocol framing and ACK request queue.
2. Port capability enum and per-model coordinators for WF-1000XM4, WF-1000XM5, LinkBuds, LinkBuds S, WH-1000XM4, and WH-1000XM5.
3. Map capabilities to DevPods state and setup suggestions.
4. Use button/touch setting support to make DevPods-friendly media/assistant paths where possible.

Definition of done:

- Sony provider reports accurate capability limitations.
- Battery, ANC/ambient, pause-when-taken-off, quick access, and touch sensor settings are visible when supported.
- Wake/approval still uses proven Android input unless raw events are proven.

### Phase 6: Nothing/Oppo/Realme Provider Family

Steps:

1. Port Nothing Ear status decode, find-device, in-ear, ANC/audio mode, and battery mapping.
2. Port Oppo/Realme touch option matrix and battery decoder.
3. Add a reversible "optimize for DevPods" touch configuration flow.
4. Run setup proof immediately after configuration.

Definition of done:

- Nothing Ear and Oppo/Realme devices can show battery/capability state.
- Touch mappings are transparent and reversible.
- DeviceCapabilityMatrix records whether physical wake was observed through MediaSession, Assistant, or vendor path.

### Phase 7: Second-Wave Gadgetbridge Providers

Steps:

1. Prioritize Redmi/Xiaomi, Soundcore, Pixel Buds, Bose QC35, and generic headphones based on available physical devices.
2. Port only coordinator, protocol, and fixtures needed for the first validated models.
3. Avoid huge wholesale imports until there is a device-validation reason.

Definition of done:

- Each second-wave provider has at least one physical-device proof before being surfaced as supported.
- Unproven models remain "generic Bluetooth headset" with vendor name detection only.

### Phase 8: UI And Setup Integration

Steps:

1. Device setup must show a natural flow: Pair -> Detect -> Prove tap -> Configure fallback -> Complete.
2. The Device screen must show active provider, fallback provider, last physical signal, battery/state confidence, and supported gestures.
3. Settings must include "optimize earbud controls for DevPods", "restore previous earbud controls", "prefer headset mic", "phone mic fallback", "assistant fallback", and "disable vendor protocol provider".
4. Diagnostics export must include provider health, capability matrix, recent provider events, and transport errors only with explicit user consent.

Definition of done:

- The user knows exactly why the app says their earbuds will work.
- There is no model-name-only support claim.
- Every risky setting change is reversible.

### Phase 9: Product Validation Matrix

Create `docs/device-validation/earbud-provider-matrix.md` and require evidence per device:

| Field | Required evidence |
| --- | --- |
| Device model | Photo/screenshot or exact Bluetooth name/model ID. |
| Phone model | Android version, vendor skin, Bluetooth stack behavior. |
| Provider path | Vendor provider, MediaSession, Assistant, notification, phone mic fallback. |
| Physical wake | Single/double/triple/long press result with timestamp. |
| Approval gesture | Pass/fail and fallback path. |
| Interrupt gesture | Pass/fail and fallback path. |
| Battery/state | Left/right/case, in-ear, ANC if applicable. |
| Settings changed | Before/after values and restore proof. |
| Failure modes | Official app conflict, permissions, background restrictions, connection loss. |
| Release claim | Supported, partial, generic-only, or unsupported. |

Minimum "perfect product" launch matrix:

- Apple: AirPods 2, AirPods 3, AirPods 4, AirPods Pro 1, AirPods Pro 2, AirPods Max, one Beats model.
- Samsung: Buds2, Buds2 Pro, Buds3 Pro, Buds FE, Buds Live.
- Sony: WF-1000XM4, WF-1000XM5, LinkBuds S, WH-1000XM4 or WH-1000XM5.
- Nothing/OPlus: Nothing Ear 2 or Ear a, Oppo Enco, Realme Buds Air family.
- Generic: at least five random Bluetooth headsets that only use MediaSession.

## Dependency Graph

```text
Phase 1 provider registry
  -> Phase 2 shared transports
  -> Phase 3 AirPods rich provider
  -> Phase 4 Samsung provider
  -> Phase 5 Sony provider
  -> Phase 6 Nothing/Oppo/Realme providers
  -> Phase 7 second-wave providers

Phase 1 provider registry
  -> Phase 8 UI/setup integration

Phase 3/4/5/6 provider outputs
  -> Phase 8 UI/setup integration
  -> Phase 9 validation matrix

Phase 9 validation matrix
  -> public support claims
```

Parallelism:

- After Phase 2, AirPods, Samsung, Sony, and Nothing/Oppo/Realme can be implemented by separate owners because write scopes are isolated.
- UI/setup can proceed in parallel after Phase 1 using fake provider fixtures, then bind to real provider states as they land.
- Validation can start as soon as each provider reaches fixture-pass status.

## Recommended First Five Engineering Tasks

1. Wire `GenericBluetoothHeadsetProvider` into the registry and add provider conformance tests.
2. Create `BtClassicSerialTransport` and migrate no vendor yet; prove it with a fake socket/packet test.
3. Replace current AirPods scanner/AACP internals with CAPod/LibrePods-derived modules behind the same `LibrePodsAirPodsProvider` external contract.
4. Create `SamsungGalaxyBudsProvider` with packet fixtures from GalaxyBudsClient before connecting to real buds.
5. Add setup UI copy/state for "observed physical input path" so the product stops relying on hopeful compatibility claims.

## Risk Register

| Risk | Mitigation |
| --- | --- |
| Imported apps are large and UI-heavy | Import protocol/transport/test pieces only; keep DevPods UI/state model. |

| Vendor protocol is confused with raw gesture detection | Separate "can configure touch action" from "can observe live tap event." |

| Official vendor apps contend for sockets | Detect connection failure reason, show user guidance, fall back to Layer 0. |
| Android background restrictions kill reliability | Foreground notification controls and setup proof must be treated as product features, not polish. |
| Support matrix overclaims | Public support is generated only from validation matrix entries. |

## Final Product Standard

DevPods is perfect when a user can install the Android app, connect their earbuds, run setup, and get a truthful answer:

- "Your AirPods Pro 2 are fully supported through Apple provider plus MediaSession fallback."
- "Your Galaxy Buds2 Pro are supported for battery/state and DevPods wake through configured touch/media button."
- "Your Sony WF-1000XM5 are supported for state/settings; wake uses standard media controls."
- "Your generic earbuds work through Android media-button fallback; vendor state is unavailable."

That is the standard that will feel seamless in real life: not pretending every brand exposes the same protocol, but using the best available source code per family and proving the user's exact hardware path before DevPods relies on it.
