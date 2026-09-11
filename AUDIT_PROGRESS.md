# Security / Network Repair Progress

This file is a live audit trail for the private `security/network-repair` branch. It is updated as fixes are actually committed and verified.

## Confirmed changes already committed

- Android VPN teardown was reworked to make disconnect cleanup serialized and idempotent.
- Native tunnel startup now fails closed when the native core is unavailable or the local SOCKS listener does not come up.
- Authentication was changed to fail closed when the user store is missing/unreadable and to reject expired users.
- The server HTTP upgrade path was hardened with request/header timeouts and computed WebSocket handshake acceptance.
- Server-side session/destination controls were hardened against unbounded stream creation and unsafe destinations.
- Android app backup was disabled because stored relay profiles contain credentials/configuration secrets.
- GitHub Actions workflow permissions were reduced to read-only repository access.
- Android profile data is encrypted at rest with Android Keystore AES-GCM, with migration from the former plaintext preference entry.
- The Android service now has a synchronous native emergency-stop entry point for destruction paths that bypass normal disconnect handling.
- Android release configuration now enables R8/minification and resource shrinking.
- Android CI now builds the optimized release variant and labels the uploaded artifact as unsigned rather than pretending it is a signed production APK.
- User authorization no longer relies on filesystem modification-time caching; each authorization decision reloads the current user store.

## Second audit: blockers confirmed and still open

- TUN/native file-descriptor ownership is still unsafe: the Go router currently wraps the Android-owned descriptor directly instead of duplicating and owning a separate descriptor for the reader goroutine.
- Socket-protection failures are still ignored in multiple Go network call sites and therefore are not uniformly fail-closed.
- Protocol identity remains lossy in Android models/parsing (VMess/Trojan/TUIC/standard Shadowsocks are mapped to other enum types).
- Reality configuration still contains a hard-coded placeholder-looking public key/short ID instead of profile-specific values.
- The universal client still has an implicit VLESS fallback for unknown protocols.
- The universal VLESS transport selection still treats a non-empty path as proof that the transport is WebSocket.
- TLS verification is still automatically disabled when SNI differs from the server host in several paths, rather than requiring an explicit insecure setting.
- T-Brutal's HTTP path performs the WebSocket-style handshake but then uses custom framing rather than WebSocket frames; client/server behavior needs to be explicitly consistent and documented.
- Release CI does not sign the APK; a signing configuration using protected CI secrets still needs to be added before calling the published APK production-ready.
- The foreground-service notification/session strings were corrected, but the full Android manifest/FGS policy still needs final verification.
- Server scripts, generated configs, systemd units, install/update paths, and quota/user lifecycle still require complete regression review.
- The threat model currently overstates some guarantees and must be reconciled with actual enforcement.

## Recent commits in this pass

- `e542d837ee9435190d4daa9859d02e785e33510e` — remove stale auth-store cache.
- `2ad0143b43070406de40a3d588d4cce802b49d43` — add synchronous emergency native cleanup entry point.
- `4a0c83863b7c6e0f49ff3eda35d37d53d8e86a1d` — use synchronous emergency cleanup from VPN service destruction.
- `fa6cbbd5fa62e175a5bb194663a1b77f79f813fc` — enable optimized Android release configuration.
- `fc12b204d4463ead873abc1578165b8053821b7e` — build optimized unsigned Android release artifact in CI.

## Verification rule

A change is only marked complete after the affected tests/build checks pass. A code edit by itself is not treated as verified. A green CI build does not prove production safety when important runtime paths remain untested.
