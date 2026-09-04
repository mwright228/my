# ⚡ MUB-X ⚡
Multi Port VPS Script

> [!TIP]
> Click the copy icon in the top-right corner of the box below to copy the install command:

```bash
curl -fsSL https://raw.githubusercontent.com/mwright228/my/main/install.sh | bash
```

The installer targets fresh Ubuntu 20.04/22.04/24.04 or Debian 11/12/13
systemd VPS hosts on amd64, arm64, or armhf. The domain must already resolve
to the VPS and ports 53/80/443 must be reachable. DNS NS delegation for
`dns.<your-domain>` is required for DNSTT.

UDP Custom is not installed automatically because this repository does not
identify a stable, verifiable upstream source for its server binary. An
untested example unit lives in `configs/` and is not enabled by default.
`examples/xray-warp.json` is likewise a staging template for a WARP-out
setup and is not installed.
ZivPN is a separate protocol and is installed from the pinned `1.4.9` upstream
release; the upstream project does not publish an armhf binary.

---

## 🎯 Features & Architecture

- **HAProxy L4 SNI Router:** Listens on port 443 with zero decryption. Each configured Reality front SNI (default `www.apple.com`, changeable per carrier) is routed straight to Xray Reality; every other SNI — your domain, carrier bug-hosts, mismatched hosts — falls through to Nginx.
- **Universal Bug-Host Multiplexer:** Terminates TLS on loopback (`127.0.0.1:20443`) via Nginx, accepting connections regardless of custom carrier SNI mismatches.
- **Multi-Protocol Core (Xray-core):** Full support for VLESS-WebSocket, VLESS-HTTPUpgrade, VMess, Trojan, and XTLS-Vision Reality.
- **Configurable Reality fronts:** Reality camouflages against any real HTTPS site, not just Apple. Add/remove SNI fronts (whatever your SIM carriers allow) with `reality-fronts` or menu option `10`; each front gets its own inbound and HAProxy route, and `link-gen` prints one Reality link per front.
- **Squid & SSH Ingestion:** Dropbear SSH via direct port (`2222`) and Squid HTTP CONNECT proxies (`8080` & `3128`).
- **Mobile UDP Gaming Bridge:** Multi-port BadVPN UDPGW (`7100–7700`) instances forward low-latency UDP traffic for games and VoIP.
- **ZivPN UDP VPN:** Password-authenticated UDP VPN server on port `5667` (amd64/arm64).
- **Hysteria 2:** Installed from a pinned upstream release and configured with the issued certificate.
- **DNSTT:** Built from a pinned upstream commit; requires DNS NS delegation for `dns.<your-domain>`.
- **SSH over WebSocket:** Restricted wstunnel backend for a generated secret path on ports 80 and 443, forwarding only to Dropbear. Use the generated `ws://` or `wss://` command from `link-gen`.
- **Kernel-Level Performance:** Enables IPv4 forwarding and applies high file-descriptor limits to high-throughput services.
- **Emergency SlowDNS Tunnel:** Built-in `dnstt` server running on port 53. DNS NS delegation for `dns.<your-domain>` is still required.
- **Automated Self-Healing:** A weekly systemd timer renews Let's Encrypt certificates (restarting Hysteria/ZivPN on real renewals so they never serve an expired cert), updates GeoIP/GeoSite databases, and revives any managed daemon that stopped running (HAProxy, Nginx, Xray, Dropbear, Squid, wstunnel, Hysteria, ZivPN, DNSTT, OpenVPN, WireGuard, BadVPN).
- **Adaptive transport monitor:** Periodically measures installed transport reachability and records a recommendation in `/var/lib/mubx/adaptive-recommendation`.
- **Mobile diagnostics:** `mubx-diagnose` reports public IPs, latency/loss, DNS, TCP reachability, path-MTU probes, kernel congestion control, and service health.
- **Congestion tuning:** `mubx-tune` enables BBR and `fq` only when supported and applies conservative TCP keepalive/socket-buffer defaults.

---

## 🗺️ Port Allocation Matrix

| Port | Transport | Protocol | Service | Role |
| :--- | :--- | :--- | :--- | :--- |
| **443** | TCP | TLS / SNI | HAProxy | Public L4 Entrypoint |
| **80** | TCP | HTTP / WebSocket | Nginx → Xray | Non-TLS VLESS-WS, plain payloads & ACME |
| **20443** | TCP | TLS | Nginx | Local SSL Termination |
| **8080 / 3128** | TCP | HTTP | Squid | Injector CONNECT Proxy |
| **109 / 2222** | TCP | SSH | Dropbear | Core SSH Tunnel |
| **10001** | TCP | WebSocket | Xray-core | VLESS-WS Inbound |
| **10004** | TCP | HTTPUpgrade | Xray-core | High-Throughput Streaming |
| **10443+** | TCP | Vision | Xray-core | VLESS Reality inbound (one per front) |
| **1194 / 2200** | TCP / UDP | OpenVPN | OpenVPN | Dual-Stack VPN Tunnel |
| **51820** | UDP | WireGuard | Kernel | WireGuard L3 Interface |
| **53** | UDP | DNS | DNSTT | SlowDNS Sub-Resolver |
| **4433** | UDP | Hysteria 2 | Hysteria | High-performance UDP tunnel |
| **80 / 443** | TCP | WebSocket | wstunnel → Dropbear | SSH over WebSocket |
| **7100–7700** | UDP | UDPGW | BadVPN | Mobile Gaming Packet Bridge |

---

## 🖥️ Management

Launch the control panel anytime from your terminal by typing:

```bash
menu
```

Menu options cover domain & certificate (`1`), client links (`2`), user
management (`4`/`5`), restarts (`6`), diagnostics (`8`) and Reality SNI
fronts (`10`, or run `reality-fronts` directly for `add`/`remove`/`set`).

For mobile-network troubleshooting, run:

```bash
mubx-diagnose
```

The weekly maintenance job runs `mubx-cron` (GeoIP/GeoSite + certificate
renewal + service self-heal).

