# Private Network Remote Mode

The DevPods Android relay supports three network modes for connecting to the desktop bridge. This document covers configuration, security requirements, and troubleshooting for each mode.

## 1. Same-Machine LAN Mode

**Use case:** Phone and computer are on the same local Wi-Fi network.

- The relay discovers the bridge via mDNS/Bonjour when `BridgeDiscoveryManager` is active.
- No manual URL entry is required if discovery succeeds.
- Release builds require HTTPS for auto-discovered bridges. HTTP is allowed only in debug builds for trusted LAN testing.

## 2. Private Mesh / VPN Mode

**Use case:** Phone and computer are on different networks but reachable via VPN, Tailscale, ZeroTier, or a private mesh.

- mDNS discovery typically does not work across subnet or VPN boundaries.
- In the setup UI, select **Remote private network** and paste the bridge URL manually (e.g., `https://bridge.tailnet-name.ts.net:4545`).
- The relay runs a health check to validate reachability before saving the pairing.
- Release builds require HTTPS. Cleartext HTTP is blocked in release builds.
- If the bridge is behind a reverse proxy, ensure the proxy forwards WebSocket upgrades and does not buffer SSE streams.

## 3. USB Reverse / Debug Mode

**Use case:** Developing on an emulator or a device connected via USB with `adb reverse`.

- Use a debug build (`FLAG_DEBUGGABLE`) to allow HTTP bridges.
- Typical URL: `http://localhost:4545` after running `adb reverse tcp:4545 tcp:4545`.
- This mode is intended for local development only and is disabled in release builds.

## Security Requirements

| Build Type | Discovered Bridges | Manual URL |
|------------|-------------------|------------|
| Debug      | HTTP or HTTPS     | HTTP or HTTPS |
| Release    | HTTPS only        | HTTPS only    |

- Release builds block cleartext HTTP to prevent accidental exposure over untrusted networks.
- Always use a strong relay token. Tokens are exchanged during pairing and must be kept secret.

## Troubleshooting

- **mDNS discovery unavailable:** If the app warns that mDNS discovery is unavailable, the phone is likely on a network that blocks multicast (e.g., corporate guest Wi-Fi, VPN). Switch to manual URL entry.
- **Health check fails after pairing:** Verify the bridge URL is reachable from the phone’s browser. Check firewall rules, Tailscale ACLs, or reverse-proxy configuration.
- **HTTPS required error:** If you see this in a release build, ensure the bridge is started with `--pairing-base-url https://...` and a valid TLS certificate.
