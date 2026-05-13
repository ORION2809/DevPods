# Security

## Dependency Audit

### Current Status

```bash
npm audit
```

Reports **0 vulnerabilities**.

```bash
npm audit --audit-level=moderate
```

Also reports **0 vulnerabilities**.

The previous advisory for `@mistralai/mistralai` (upstream false positive for version 2.2.1) no longer appears in audit output. The `overrides` pin remains in `package.json` as a defensive measure.

### Historical Advisory (Resolved)

- **GHSA**: `GHSA-3q49-cfcf-g5fm`
- **Package**: `@mistralai/mistralai`
- **Status**: Resolved in current working tree — the installed version `2.2.1` predates the compromise, and `npm audit` no longer flags it.

If the advisory reappears, verify the installed version:

```bash
npm ls @mistralai/mistralai
# Should show: @mistralai/mistralai@2.2.1
```

If you do not use OpenClaw: the vulnerable dependency path is only reachable if you enable OpenClaw rewrite mode (`--brain openclaw`). If you use the default local bridge mode, the `openclaw` package and its transitive dependencies are never loaded at runtime.

## Android Component Security

### Exported RelayService

`RelayService` is declared as exported in `AndroidManifest.xml` because it extends `MediaSessionService`. MediaSession integration requires the service to be discoverable by the Android system and headset controllers so that physical media-button events can be captured. Without the exported attribute, the system cannot bind to the service for headset button delivery.

- The service performs its own relay-token authentication on every bridge request.
- Automation extras are ignored in release builds.
- Treat debug builds as developer-only surfaces.

### Exported AssistantEntryActivity

`AssistantEntryActivity` is exported to serve as a fallback entry point when the user long-presses the system assistant trigger. This allows the relay to intercept assistant-style wake gestures and route them into the DevPods flow rather than launching the default device assistant.

### Debug Cleartext Network Config

The cleartext network allowance (`android:usesCleartextTraffic="true"`) is applied **only** from the debug source set (`res/xml/network_security_config.xml`). Release builds do not inherit this config. The allowance exists solely to support emulator and trusted-LAN debugging against a local HTTP bridge. Release builds require HTTPS or a secure LAN tunnel.

## Reporting Security Issues

If you discover a security vulnerability in DevPods, please report it responsibly. Do not open a public issue until the vulnerability has been addressed.

## Security Features

- **Deny-by-default policy**: All developer actions require explicit workspace allowlisting.
- **Approval gates**: Sensitive actions (commit, push, deploy, delete) require explicit user approval.
- **Audit logging**: All bridge events and actions are logged with timestamps and hardware context.
- **Redaction**: Diagnostic exports strip URLs, tokens, workspace names, and IP addresses from all error fields before sharing.
- **Pairing-code exchange**: Short-lived 5-minute pairing codes prevent long-lived token exposure.
- **No cloud audio streaming**: Speech recognition happens on-device (Android). Raw audio never leaves the phone.
