# Private Network Remote Mode

DevPods is local-first by design. There is no cloud command relay. This document explains how to use DevPods when your phone is not on the same LAN as your computer.

## Supported Modes

### Same-machine LAN mode
- Phone and computer on the same Wi-Fi network
- mDNS discovery works automatically
- Both debug and release builds can pair via QR scan

### Private mesh/VPN mode
- Phone and computer connected via a private VPN or mesh network (e.g., Tailscale, WireGuard, Nebula)
- **mDNS discovery does not work across most VPNs**
- Paste the bridge URL manually in the DevPods Relay pairing field
- The bridge URL must be reachable from the phone over the private network
- The relay fetches the unauthenticated `/pairing` endpoint first to extract the bearer token
- It then validates the authenticated `/health` endpoint with the token to confirm the bridge is ready

### USB reverse/debug mode
- Use Android `adb reverse` to tunnel the bridge port over USB:
  ```bash
  adb reverse tcp:4545 tcp:4545
  ```
- Then pair using `http://localhost:4545` on the phone
- Useful for development when Wi-Fi is unreliable

### Release HTTPS requirements
- **Release builds require HTTPS for discovered bridges**
- This applies to bridges discovered via mDNS or QR scan
- For private-network use in release builds, you must either:
  1. Provide a trusted TLS certificate for the bridge
  2. Use an explicit product-supported private-network tunnel mode
- Debug builds allow HTTP for local development

## Pairing Instructions

1. Open DevPods Relay on your phone
2. Tap "Pair with bridge"
3. Choose the appropriate mode:
   - **LAN discovery**: scan the QR code from the desktop bridge
   - **Remote private network**: paste the bridge URL manually
4. If using private network, ensure the URL is reachable and tap "Health" to validate

## Security Notes

- The bridge uses bearer-token authentication for all endpoints except pairing
- Pairing endpoints are intentionally unauthenticated so new devices can bootstrap
- Only expose pairing endpoints on trusted networks
- All command execution, file access, and git operations remain local to the bridge machine
- No audio, transcripts, git state, or file paths are sent to any third-party backend
