# 🛡️ MUB-X Threat Model & Security Architecture

## 1. Executive Summary

MUB-X is a telecom-grade multi-protocol VPS proxy suite engineered to operate across adverse cellular networks, bypass Deep Packet Inspection (DPI), and withstand active probing by network censors. This document defines the security boundaries, threat actors, defensive mechanisms, and explicit non-guarantees of the system.

---

## 2. Threat Actors & Attack Vectors

| Threat Actor | Capabilities | Target Vectors |
| :--- | :--- | :--- |
| **Mobile Carrier DPI** | Passive SNI inspection, Host header filtering, TCP connection resets, payload manipulation (`[split]`, `[delay_split]`). | Censoring user traffic, billing manipulation, identifying circumvention proxies via error responses (e.g. `400 Bad Request`). |
| **Active Probing Scanners** | Automated scanners sending malformed TLS ClientHello packets, HTTP requests, or random byte probes to discovered server ports. | Fingerprinting proxy daemons, identifying unauthorized server configurations, triggering censor blocklists. |
| **Open-Relay Abusers** | Internet-wide port scanners seeking open HTTP `CONNECT` proxies on common ports (`8080`, `3128`, `8888`). | Laundering malicious traffic, launching DDoS attacks, exploiting unauthenticated egress bandwidth. |
| **Eavesdroppers on Plaintext Path** | Sniffers on public Wi-Fi or unencrypted carrier backhauls. | Intercepting user subscription tokens or credentials transmitted over unencrypted HTTP. |
| **Untrusted Multi-User Tenants** | Users sharing the same MUB-X server instance. | Attempting to exceed data quotas, snoop on other users' traffic, or bypass accounting. |

---

## 3. Defense Mechanisms & Security Guarantees

### 3.1. Chameleon Universal Payload Engine & Open-Relay Prevention
- **Local Bridge Isolation**: Unauthenticated HTTP payloads entering Chameleon on ports `8080`, `3128`, `8888`, or loopback `18088` are strictly restricted to local MUB-X tunnel services (`is_local_target`: Dropbear SSH `:2222`, Sing-box ShadowTLS `:8448`, SS-2022 `:18500`, wstunnel `:18080`, SS-443 `:17000`, ZivPN `:5667`, OpenVPN `:1194`, BadVPN `:7100+`). These local daemons enforce their own cryptographic credentials (SSH keys/passwords, AEAD ciphers).
- **Outbound Relay Authentication**: Any `CONNECT` or HTTP request attempting to egress to an external internet host/port requires HTTP `Proxy-Authorization: Basic <base64(user:uuid)>` verified against `/etc/mubx/users.json`. Unauthenticated requests are immediately rejected with `407 Proxy Authentication Required`.
- **Zero-Rejection State Machine**: Lenient parsing tolerates front-injected headers (`X-Online-Host`, `Host:`), delay-split streams, and leading CRLFs without ever emitting a `400 Bad Request` fingerprint to probing DPI middleboxes.

### 3.2. Active Probing Resistance & Dynamic Camouflage
- **Authentic Reverse-Proxy Camouflage**: All unmatched HTTP and HTTPS requests arriving on public ports (`80`, `443` -> Nginx `20443`) are reverse-proxied to a real external site (`https://www.apple.com`) with subfilter rewrites and transparent caching. Scanners observing the site see legitimate web server responses.
- **HAProxy Layer-4 Content Demultiplexing**: HAProxy inspects the initial connection payload at Layer 4 without decrypting TLS:
  - TLS ClientHello with the configured ShadowTLS decoy SNI (`__SHADOWTLS_SNI__`) routes to sing-box (`127.0.0.1:8448`).
  - All other TLS ClientHello handshakes route to Nginx (`127.0.0.1:20443`).
  - Raw non-TLS byte streams (Shadowsocks TCP) route directly to Xray's SS-443 inbound (`127.0.0.1:17000`).
  - HTTP injection requests route to Chameleon (`127.0.0.1:18088`).

### 3.3. Credential Protection & HTTPS Enforcement
- **Subscription Tokens**: Subscriptions (`/sub/<token>/...`) contain full client connection profiles and tokens. The Nginx server block on port 80 enforces an immediate `301 Moved Permanently` redirect to `https://$host$request_uri`, preventing plaintext credential theft over unencrypted channels.
- **Secrets Storage**: Server secrets in `/etc/telecom-engine.env` and `/etc/mubx/users.json` are created with `umask 077` and restricted to `chmod 600` (root-only access).

### 3.4. Host & Process Sandboxing
- **systemd Hardening**: Daemon units utilize sandboxing controls:
  - `NoNewPrivileges=yes` prevents privilege escalation.
  - `PrivateTmp=yes` isolates temporary files.
  - `ProtectSystem=strict` and `ProtectHome=yes` enforce read-only access to host system hierarchies.
  - `ProtectKernelTunables=yes` and `ProtectControlGroups=yes` block kernel modification.
  - `CapabilityBoundingSet=` drops unnecessary Linux capabilities.

---

## 4. Operational Boundaries & Non-Guarantees

1. **Host-Level Root Compromise**: If an attacker gains root access to the underlying VPS, all in-memory keys and process states are compromised.
2. **Side-Channel Traffic Analysis**: Advanced censors possessing full traffic timing and packet-size telemetry across international transit points may perform statistical flow correlation. While ShadowTLS, SS-2022, Hysteria 2, and BBR mitigate coarse heuristics, absolute anonymity against global passive adversaries requires application-layer mixnet protocols (e.g. Tor).
3. **Carrier Bug-Host Longevity**: SNI and Host spoofing depend on third-party mobile network operator zero-rating configurations. MUB-X provides the transport agility to change bug-hosts instantly via `mubx-probe` and `link-gen`, but does not guarantee unblockability of specific carrier domains.
