# MUB-X Threat Model & Security Architecture

## 1. Scope

MUB-X is a multi-protocol VPN/proxy system consisting of an Android `VpnService`, a native Go networking core, server-side proxy services, and configuration/user-management scripts.

This document describes the security controls that are intended to be enforced by the repository. It does **not** certify a deployed VPS, Android device, certificate set, firewall policy, or external carrier behavior. Production deployment still requires live-host verification.

## 2. Threat actors

| Threat actor | Capability | Primary concern |
| :--- | :--- | :--- |
| Mobile-network observer | SNI/Host inspection, resets, traffic classification | Transport blocking or fingerprinting |
| Active network scanner | Sends malformed HTTP/TLS/protocol traffic | Service fingerprinting and resource exhaustion |
| Open-relay abuser | Scans public proxy ports and sends arbitrary destinations | Unauthorized egress and bandwidth abuse |
| Untrusted tenant | Possesses a valid user credential | Quota bypass, cross-user access, unauthorized protocols |
| Local device attacker | Can inspect app storage or influence the Android process | Credential disclosure or tunnel disruption |
| VPS attacker | Gains control of the server or its root account | Full compromise of in-memory credentials and traffic |

## 3. Current security controls

### 3.1 Authentication and tenant isolation

- The server user store fails closed when the file is missing or malformed.
- Frozen and expired users are rejected at authentication time.
- Unknown protocols are rejected rather than silently mapped to a different transport.
- Server sessions cap concurrent streams per session.
- Server-side destination validation rejects loopback, private, link-local, multicast, and unspecified destination addresses.
- User/configuration changes are rendered and validated before service reloads.

These controls reduce open-relay and cross-tenant risks; they do not replace host firewall policy or service-level authentication at every separately exposed daemon.

### 3.2 Android credential storage

- VPN profiles are encrypted at rest using an Android Keystore-backed AES-GCM key.
- Legacy plaintext profile data is migrated into the encrypted store and removed.
- Application backup is disabled because profiles may contain credentials, UUIDs, SSH secrets, and transport parameters.
- Encryption and persistence failures are treated as errors rather than silently discarding changes.

### 3.3 VPN lifecycle and native boundary

- Native tunnel startup fails closed when the native library or loopback SOCKS listener is unavailable.
- Connect/disconnect operations are serialized and cancellation-aware.
- Destruction paths invoke emergency native cleanup.
- Android's TUN descriptor is duplicated before the Go router takes ownership; Go closes only its own duplicate during shutdown.
- Socket-protection failures on VPN-critical paths are treated as errors rather than ignored.

The application does **not** itself control Android's global "block connections without VPN" policy. A true device-wide lockdown must be enabled through the Android VPN/always-on system settings.

### 3.4 Transport and TLS handling

- VLESS WebSocket, VLESS TCP, VLESS Reality, VMess WebSocket, Trojan WebSocket, Shadowsocks, Shadowsocks 2022, Hysteria 2, TUIC, ShadowTLS, ZiVPN, SSH/custom payloads, and T-Brutal are represented explicitly where supported.
- Reality requires profile-specific public key and short ID values; the client must not fall back to a placeholder.
- TLS certificate verification is controlled by the explicit insecure-TLS profile setting. An SNI/host mismatch alone does not disable verification.
- WebSocket handshakes validate the `Sec-WebSocket-Accept` value on the custom client path.

MUB-X's T-Brutal HTTP transport uses a WebSocket-style HTTP upgrade for camouflage and then continues with its own framing protocol. It must not be described as a conventional RFC 6455 message-framed WebSocket tunnel.

### 3.5 Server and process hardening

Repository systemd units use controls such as `NoNewPrivileges`, `PrivateTmp`, `ProtectSystem`, and `ProtectHome`; individual services may also have capability and resource restrictions. These settings must be verified against the actual generated unit files on the deployed VPS.

### 3.6 CI and release artifacts

CI runs shell linting, Python linting/tests, Go tests, `go vet`, Go race tests, and repository consistency checks. Android CI builds the optimized release variant and runs release unit tests and lint before producing an unsigned APK artifact.

An unsigned APK artifact is **not** a production release. A production distribution requires a controlled signing process with protected signing material and a verified release/install path.

## 4. Explicit non-guarantees

1. A root compromise of the VPS or Android device can expose in-memory credentials and traffic state.
2. Global passive traffic-analysis resistance cannot be guaranteed by this software.
3. Carrier-specific SNI/zero-rating behavior is external to MUB-X and can change at any time.
4. The app cannot programmatically force Android's device-wide always-on VPN lockdown policy.
5. Unsupported or incomplete protocol features must fail closed rather than silently falling back to another protocol.
6. A repository CI pass does not prove that a particular live VPS has correct firewall rules, certificates, DNS, kernel settings, systemd state, or generated configuration.

## 5. Production-readiness gate

MUB-X should only be called **production ready** after all of the following are true:

- Go tests, vet, race tests, Android release tests, and Android lint pass on the final commit.
- Native/TUN lifecycle has been exercised on an Android device or emulator with repeated connect/disconnect/crash scenarios.
- Every advertised protocol has an end-to-end interoperability test against its corresponding server configuration.
- No security-critical socket-protection path ignores failure.
- User/config changes roll back cleanly if any dependent service cannot reload.
- The released APK is signed through a protected release process.
- The deployed VPS is separately checked for firewall, certificates, secrets permissions, systemd hardening, and generated configuration.

The repository itself does not claim stronger guarantees than these gates can demonstrate.
