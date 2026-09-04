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
identify a stable, verifiable upstream source for its server binary. The
included unit is a configuration template only and is not enabled by default.
ZivPN is a separate protocol and is installed from the pinned `1.4.9` upstream
release; the upstream project does not publish an armhf binary.

---

## 🎯 Features & Architecture

- **HAProxy L4 SNI Router:** Listens on port 443 with zero decryption. Directs Apple TLS handshakes straight to Xray Reality while routing carrier bug-host traffic into Nginx.
- **Universal Bug-Host Multiplexer:** Terminates TLS on loopback (`127.0.0.1:20443`) via Nginx, accepting connections regardless of custom carrier SNI mismatches.
- **Multi-Protocol Core (Xray-core):** Full support for VLESS-WebSocket, VLESS-HTTPUpgrade, VMess, Trojan, and XTLS-Vision Reality.
- **Squid & SSH Ingestion:** Dropbear SSH via direct port (`2222`) and Squid HTTP CONNECT proxies (`8080` & `3128`).
- **Mobile UDP Gaming Bridge:** Multi-port BadVPN UDPGW (`7100–7700`) instances forward low-latency UDP traffic for games and VoIP.
- **ZivPN UDP VPN:** Password-authenticated UDP VPN server on port `5667` (amd64/arm64).
- **Hysteria 2:** Installed from a pinned upstream release and configured with the issued certificate.
- **DNSTT:** Built from a pinned upstream commit; requires DNS NS delegation for `dns.<your-domain>`.
- **Kernel-Level Performance:** Enables IPv4 forwarding and applies high file-descriptor limits to high-throughput services.
- **Emergency SlowDNS Tunnel:** Built-in `dnstt` server running on port 53. DNS NS delegation for `dns.<your-domain>` is still required.
- **Automated Self-Healing:** A weekly systemd timer renews Let's Encrypt certificates, updates GeoIP/GeoSite databases, and auto-restarts failed daemons.

---

## 🗺️ Port Allocation Matrix

| Port | Transport | Protocol | Service | Role |
| :--- | :--- | :--- | :--- | :--- |
| **443** | TCP | TLS / SNI | HAProxy | Public L4 Entrypoint |
| **80** | TCP | HTTP | Nginx | Plain Payloads & ACME |
| **20443** | TCP | TLS | Nginx | Local SSL Termination |
| **8080 / 3128** | TCP | HTTP | Squid | Injector CONNECT Proxy |
| **2222** | TCP | SSH | Dropbear | Core SSH Tunnel |
| **10001** | TCP | WebSocket | Xray-core | VLESS-WS Inbound |
| **10004** | TCP | HTTPUpgrade | Xray-core | High-Throughput Streaming |
| **10443** | TCP | Vision | Xray-core | VLESS Reality (Anti-DPI) |
| **1194 / 2200** | TCP / UDP | OpenVPN | OpenVPN | Dual-Stack VPN Tunnel |
| **51820** | UDP | WireGuard | Kernel | WireGuard L3 Interface |
| **53** | UDP | DNS | DNSTT | SlowDNS Sub-Resolver |
| **4433** | UDP | Hysteria 2 | Hysteria | High-performance UDP tunnel |
| **7100–7700** | UDP | UDPGW | BadVPN | Mobile Gaming Packet Bridge |

---

## 🖥️ Management

Launch the control panel anytime from your terminal by typing:

```bash
menu
```
