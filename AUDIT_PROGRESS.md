# Security / Network Repair Progress

This file is the live audit trail for the private `security/network-repair` branch. A change is only marked complete after the affected tests or build checks pass.

## Confirmed changes already committed and verified

- Android VPN teardown was reworked to make disconnect cleanup serialized and idempotent.
- Native tunnel startup fails closed when the native core is unavailable or the local SOCKS listener does not come up.
- Authentication fails closed when the user store is missing/unreadable and rejects expired users.
- The server HTTP upgrade path uses request/header timeouts and computed WebSocket handshake acceptance.
- Server session limits and destination controls reject private/special-use IPv4 destinations, with IPv6 special-use filtering now covered as well.
- Android app backup is disabled because stored relay profiles contain credentials/configuration secrets.
- GitHub Actions workflow permissions are read-only for repository contents.
- Android profile data is encrypted at rest with Android Keystore AES-GCM, with migration from the former plaintext preference entry.
- The Android service has a synchronous native emergency-stop entry point for destruction paths that bypass normal disconnect handling.
- Android release configuration enables R8/minification and resource shrinking.
- Android CI builds the optimized release variant and publishes it only as an explicitly unsigned artifact on PR/push builds.
- User authorization no longer relies on filesystem modification-time caching.
- Android-owned TUN descriptors are duplicated before the Go router takes ownership, so the reader goroutine does not own the application's original descriptor.
- Outbound socket protection now fails closed in the main native/Go dial paths; DNS resolution used for physical dialing also checks protection failures.
- Android protocol models/parsing preserve the explicit protocol variants supported by the native bridge instead of silently falling back to an unrelated protocol.
- VLESS Reality uses profile-specific public-key/short-id fields and the native bridge rejects missing Reality parameters.
- Universal protocol dispatch rejects unsupported protocols instead of silently falling back to VLESS.
- Universal VLESS WebSocket selection is based on the explicit `VLESS_WS` protocol rather than path heuristics.
- TLS verification is not automatically disabled because SNI differs from the physical server host; insecure TLS is an explicit configuration choice.
- T-Brutal WebSocket mode performs an RFC 6455 handshake and uses RFC 6455 frames; raw T-Brutal remains a separate custom-framing transport.
- SSH host-key verification uses a configured SHA-256 fingerprint unless the user explicitly enables insecure mode.
- CI runs shellcheck, Ruff, Go tests, vet, race tests, govulncheck, pytest, and comprehensive repository checks.
- Android CI runs release unit tests, lint, native compilation, and optimized release APK assembly.
- Latest exact-head CI and Android builds passed on commit `377b107879441fb2cbe8d0337ae2781bb9abf490`.
- The latest Android workflow produced an unsigned optimized release artifact from that exact commit.

## Current production blockers

### 1. Custom TUN TCP stack
The Android VPN path still contains a hand-rolled IPv4 TCP implementation. It does not yet have the completeness and interoperability evidence expected of a production TCP/IP stack, including robust retransmission, duplicate and out-of-order handling, complete FIN/ACK state management, advertised receive windows, and broad real-world interoperability testing. This remains a major blocker.

### 2. IPv6 TUN support
The custom TUN router is still IPv4-oriented. Full IPv6 VPN behavior is not yet implemented and verified.

### 3. Protocol end-to-end interoperability
Repository unit tests are not sufficient to certify VLESS Reality/TCP/WS, VMess WS, Trojan WS, TUIC, Shadowsocks/2022, ShadowTLS, Hysteria2, ZiVPN, and T-Brutal against their deployed server implementations. Each supported protocol needs a real client/server interoperability run, including failure and reconnect behavior.

### 4. Signed production APK
PR/push CI intentionally produces an unsigned APK. The protected `production` environment signing job is implemented but has not yet been proven with a real version tag and the actual signing secrets. A production release must be signed and verified with `apksigner` before release certification.

### 5. Sing-box dependency age
The repository still pins sing-box `v1.9.7`. This dependency needs a deliberate generated upgrade or a documented, tested reason to remain pinned. No manual `go.mod` edits should be used as a substitute for a real dependency update.

### 6. Live VPS/deployment verification
Repository tests cannot certify the actual VPS firewall, systemd state, certificates, quotas, user lifecycle, external DNS, kernel networking, MTU behavior, or carrier-specific transport behavior. Those require live-host verification.

### 7. Release/deployment regression review
Server scripts, generated configs, systemd units, install/update paths, quota accounting, and user lifecycle still need a final end-to-end regression pass against a fresh deployment.

## Recent commits in this pass

- `377b107879441fb2cbe8d0337ae2781bb9abf490` — restore valid `libdns/cloudflare` module version.
- `5783b72ecf08aaaab1023cd82fd66a6c0d9c8ace` — block IPv6 special-use destinations in the T-Brutal server filter.
- `d6725db448869ac974b66453cbbf0308e1a3dea9` — add IPv6 SSRF special-use regression tests.

## Verification rule

A code edit by itself is not treated as verified. A green CI build proves the tested build gates, not production safety when important runtime paths or deployment conditions remain untested.
