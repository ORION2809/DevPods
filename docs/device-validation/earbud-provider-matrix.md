# Earbud Provider Validation Matrix

Date: 2026-05-13

## Coverage Summary

| Provider | Brands | Detection | Battery | Gestures | Transport |
|----------|--------|-----------|---------|----------|-----------|
| `apple_airpods` | AirPods, Beats | BLE proximity + model ID | Left/Right/Case | AACP stem press | BLE scan + L2CAP |
| `samsung_galaxy_buds` | Galaxy Buds 2/2 Pro/3 Pro/Live/FE/Pro | Bonded name | Left/Right/Case | Media fallback | RFCOMM |
| `sony_headphones` | WF-1000XM4/5, WH-1000XM4/5, LinkBuds | Bonded name | Vendor protocol | Media fallback | RFCOMM |
| `nothing_ear` | Nothing Ear 1/2/a, CMF Buds | Bonded name | Vendor protocol | Media fallback | RFCOMM |
| `oppo_realme` | Oppo Enco, Realme Buds, OnePlus Buds | Bonded name | Vendor protocol | Media fallback | RFCOMM |
| `librepods_airpods` | AirPods (legacy) | BLE proximity | Left/Right/Case | AACP stem press | BLE scan + L2CAP |
| `android_media_session` | ALL Bluetooth audio | Media3 session | — | Single/Double/Triple/Long press | MediaSession |
| `assistant_entry` | ALL devices | Always available | — | Long press | Assistant intent |
| `generic_bluetooth_headset` | ALL Bluetooth headsets | BluetoothHeadset profile | — | Connection aware | BluetoothHeadset |
| `generic_gatt_battery` | BLE devices with BAS | GATT service | Single level | — | BLE GATT |

## Validation Criteria

| Field | Required evidence |
|-------|-------------------|
| Device model | Photo/screenshot or exact Bluetooth name/model ID |
| Phone model | Android version, vendor skin, Bluetooth stack |
| Provider path | Vendor provider, MediaSession, Assistant, notification, phone mic |
| Physical wake | Single/double/triple/long press result with timestamp |
| Approval gesture | Pass/fail and fallback path |
| Interrupt gesture | Pass/fail and fallback path |
| Battery/state | Left/right/case, in-ear, ANC if applicable |
| Settings changed | Before/after values and restore proof |
| Failure modes | Official app conflict, permissions, background restrictions |
| Release claim | Supported, partial, generic-only, or unsupported |

## Implementation Status by Provider

### Apple AirPods / Beats — `apple_airpods`
- **Detection**: BLE proximity scanning with Apple manufacturer ID 0x004C
- **Model resolution**: 30+ models including AirPods 1-4, Pro 1-3, Max, all Beats families
- **Battery**: Left/Right/Case via proximity advertisement parsing
- **Ear state**: In-ear detection from proximity data
- **AACP**: L2CAP CoC socket with RFCOMM fallback; stem press opcodes mapped to gestures
- **Fallback**: MediaSession/Assistant if L2CAP fails
- **Validation status**: Code-complete; needs physical-device proof

### Samsung Galaxy Buds — `samsung_galaxy_buds`
- **Detection**: Bonded device name matching (Galaxy Buds/Live/Pro/2/2 Pro/3 Pro/FE)
- **Protocol**: RFCOMM with Galaxy Buds frame format (SOM 0xFE/0xFD)
- **Battery**: Left/Right/Case via battery_status (0x60/0x61) commands
- **ANC/Ambient**: Protocol supports noise control commands
- **Touch**: Can configure touchpad options to emit media events
- **Fallback**: MediaSession if RFCOMM fails
- **Validation status**: Code-complete; needs physical-device proof

### Sony Headphones — `sony_headphones`
- **Detection**: Bonded name matching (WF-1000XM, WH-1000XM, LinkBuds)
- **Protocol**: RFCOMM serial with Sony protocol framing
- **Battery**: Vendor protocol decode
- **Capabilities**: ANC, ambient, button modes, quick access
- **Fallback**: MediaSession/Assistant
- **Validation status**: Detection + transport scaffolded; protocol decode needs fixtures

### Nothing Ear — `nothing_ear`
- **Detection**: Bonded name matching (Nothing Ear, CMF Buds)
- **Protocol**: RFCOMM serial
- **Fallback**: MediaSession
- **Validation status**: Detection + transport scaffolded

### Oppo / Realme / OnePlus — `oppo_realme`
- **Detection**: Bonded name matching (Oppo Enco, Realme Buds, OnePlus Buds)
- **Protocol**: RFCOMM serial
- **Fallback**: MediaSession
- **Validation status**: Detection + transport scaffolded

### Generic Bluetooth Headset — `generic_bluetooth_headset`
- **Detection**: BluetoothHeadset profile connected devices
- **Capabilities**: Connection state, audio route control
- **Fallback**: Self — this IS the fallback
- **Validation status**: Complete and wired into registry

### Android MediaSession — `android_media_session`
- **Detection**: Always active via Media3 silent playback
- **Capabilities**: Single/Double/Triple/Long press via media buttons
- **Validation status**: Complete; OBSERVED on realme RMX3990

## Risk Register

| Risk | Mitigation |
|------|------------|
| AACP/L2CAP hidden API breakage | RFCOMM fallback + MediaSession fallback |
| Samsung official app contention | Detect connection failure, show guidance, fall back |
| Background restrictions kill BLE scan | Foreground service with FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK |
| Support matrix overclaims | Public support generated only from validation matrix entries |
| Root/Xposed temptation | Lab/advanced track only; never default product |
