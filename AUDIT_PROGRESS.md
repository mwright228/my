# Security / Network Repair Progress

This file is a live audit trail for the private `security/network-repair` branch. It is updated as fixes are actually committed and verified.

## Confirmed changes already committed

- Android VPN teardown was reworked to make disconnect cleanup serialized and idempotent.
- Native tunnel startup now fails closed when the native core is unavailable or the local SOCKS listener does not come up.
- Authentication was changed to fail closed when the user store is missing/unreadable and to reject expired users.
- The server HTTP upgrade path was hardened with request/header timeouts and computed WebSocket handshake acceptance.
- Server-side session/destination controls were hardened against unbounded stream creation and unsafe destinations.
- Android app backup was disabled because stored relay profiles contain credentials/configuration secrets.
- GitHub Actions workflow permissions were reduced from repository write access to the minimum needed for CI/build publication.

## Still under active repair

- Complete TUN/native file-descriptor lifecycle and shutdown correctness.
- Make every socket-protection failure explicit and fail closed on VPN-critical paths.
- Correct protocol identity and transport parsing across Android and Go (VLESS, VMess, Trojan, Shadowsocks, TUIC, Reality, WebSocket/TCP).
- Remove unsafe implicit protocol fallbacks.
- Finish secure Android credential/profile encryption with migration from existing stored profiles.
- Review release signing/minification, ABI/build configuration, and Android release CI.
- Audit server scripts, generated configs, systemd hardening, and user/quota lifecycle for security regressions.
- Expand regression tests and complete CI verification.
- Reconcile `THREAT_MODEL.md` with the actual enforced behavior.

## Verification rule

A change is only marked complete after the affected tests/build checks pass. A code edit by itself is not treated as verified.
