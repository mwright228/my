# Security / Network Repair Progress

This is the live audit trail for `security/network-repair`. A change is only marked complete after the affected tests or build checks pass.

## Confirmed in the current branch

- Android VPN teardown is serialized and idempotent, with a synchronous native emergency-stop path for destruction.
- Native tunnel startup fails closed when the native core or local SOCKS listener cannot start.
- Authentication fails closed for missing/unreadable stores and expired users.
- WebSocket upgrades use bounded request/header timeouts and computed RFC 6455 acceptance.
- Server destination controls reject private/special-use IPv4 and IPv6 destinations in normal operation.
- Android backup is disabled for stored relay credentials/configuration.
- Android profile secrets are encrypted at rest with Android Keystore AES-GCM, including migration from the former plaintext preference entry.
- GitHub Actions repository permissions are read-only.
- Android CI runs release unit tests, lint, native compilation, and optimized release APK assembly.
- CI runs shellcheck, Ruff, Go tests, vet, race tests, govulncheck, pytest, and comprehensive repository checks.
- The Go dependency graph is pinned to sing-box `v1.14.0`, sing-tun `v0.9.0-beta.4`, and the matching generated transitive module set.
- The Android TUN path uses the maintained sing-tun gVisor stack rather than a hand-rolled IPv4 TCP implementation.
- The Android VPN builder installs both IPv4 and IPv6 addresses/routes, and sing-tun is configured with both address families.
- TUN-owned file descriptors are duplicated before the Go router takes ownership.
- Outbound socket protection fails closed in the native/Go dial paths; DNS resolution used for physical dialing also checks protection failures.
- Protocol dispatch rejects unsupported protocols instead of silently falling back to an unrelated protocol.
- VLESS Reality requires the explicit profile public-key/short-id parameters.
- TLS verification is not disabled merely because SNI differs from the physical server host; insecure TLS is explicit.
- T-Brutal WebSocket mode uses RFC 6455 framing and handshake; raw T-Brutal remains a separate custom transport.
- SSH host-key verification requires a configured SHA-256 fingerprint unless insecure mode is explicitly enabled.
- The latest exact-head automated CI passed, including Go tests, vet, race, govulncheck, pytest, and repository checks.
- The Android build produced an unsigned optimized release artifact from CI.

## Remaining evidence gates

### 1. Real-device Android verification

Automated builds cannot prove real Android kernel/VpnService behavior. A physical-device test still needs IPv4, IPv6, DNS, reconnect, network transition, tunnel teardown, and leakage checks.

### 2. Real protocol interoperability

Repository tests cannot certify every deployed protocol implementation. The supported VLESS Reality/TCP/WS, VMess WS, Trojan WS, TUIC, Shadowsocks/2022, ShadowTLS, Hysteria2, ZiVPN, and T-Brutal modes still need client/server interoperability runs against their actual deployed servers, including reconnect and negative cases.

### 3. Signed production APK

PR/push CI intentionally produces an unsigned artifact. The protected tag-triggered signing workflow is implemented and verifies the resulting APK with `apksigner`, but it has not been proven here with a real version tag and the production signing secrets.

### 4. Live VPS/deployment verification

Repository tests cannot certify the actual VPS firewall, systemd state, certificates, quotas, external DNS, kernel networking, MTU, carrier behavior, or the deployed user lifecycle. Those require live-host evidence.

### 5. Fresh-deployment regression

Install/update scripts, generated configs, systemd units, quota accounting, user lifecycle, rollback, and upgrade behavior still need a clean-host regression run.

## Important audit correction

Older audit notes claiming that this branch still pins sing-box `v1.9.7`, uses a custom IPv4-only TCP TUN stack, or lacks IPv6 TUN configuration were stale. The current branch has already moved to sing-box `v1.14.0`, sing-tun gVisor, and dual-stack TUN configuration. Those items are no longer code blockers; the remaining work is evidence from real devices, deployed servers, and signed release execution.

## Verification rule

A code edit by itself is not treated as verified. A green CI build proves only the tested build gates. Production certification requires the remaining runtime, deployment, interoperability, and release-integrity evidence above.
