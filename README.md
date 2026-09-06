# ⚡ MUB-X ⚡
Multi Port VPS Script

> [!TIP]
> Click the copy icon in the top-right corner of the box below to copy the install command:

```bash
curl -fsSL https://raw.githubusercontent.com/mwright228/my/main/install.sh | bash
```

The installer targets fresh Ubuntu 20.04/22.04/24.04 or Debian 11/12/13
systemd VPS hosts on amd64, arm64, or armhf. The domain must already resolve
to the VPS and ports 80/443 must be reachable.

> [!TIP]
> Already running MUB-X? See [UPGRADING.md](UPGRADING.md) — re-running the
> installer upgrades in place, or follow the manual pull-and-re-render path.

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
- **Multi-Protocol Core (Xray-core):** Full support for VLESS-WebSocket, VLESS-HTTPUpgrade, VMess, Trojan, Shadowsocks (per user, WebSocket + plain TCP), and XTLS-Vision Reality — plus a plain **VLESS TCP+TLS** inbound on `8443` serving your own Let's Encrypt certificate, a cert-verifying fallback when Reality fronts or WebSocket paths are blocked or unsupported by a client.
- **Configurable Reality fronts:** Reality camouflages against any real HTTPS site, not just Apple. Add/remove SNI fronts (whatever your SIM carriers allow) with `reality-fronts` or menu option `10`; each front gets its own inbound and HAProxy route, and `link-gen` prints one Reality link per front.
- **Per-user identities & usage:** Every user (`mubx-users` / menu `11`, also auto-created with each SSH account) gets their own UUID across every transport — revoke one user without rekeying the rest. Shadowsocks follows the same model: each user gets their own WS inbound (`127.0.0.1:10006+`) and nginx route (`/ss-<user>` on 80/443) keyed to their UUID, plus a plain Shadowsocks TCP inbound (`0.0.0.0:8388+`, no WS/TLS) so any SS client — v2rayNG included — connects without a plugin, and user add/remove re-renders both Xray and Nginx atomically. Xray's stats API is enabled, so per-user traffic can be read back with `mubx-users usage`. The legacy shared identity survives as the seeded `admin` user, keeping old links valid. `link-gen [bug-host] [user]` prints links for any user.
- **Squid & SSH Ingestion:** Dropbear SSH via direct ports (`2222`, `109`, `53`) and Squid HTTP CONNECT proxies (`8080` & `3128`).
- **Mobile UDP Gaming Bridge:** Multi-port BadVPN UDPGW (`7100–7700`) instances forward low-latency UDP traffic for games and VoIP.
- **ZivPN UDP VPN:** Password-authenticated UDP VPN server on port `5667` (amd64/arm64).
- **Hysteria 2:** Installed from a pinned upstream release and configured with the issued certificate.
- **SSH over WebSocket:** Restricted wstunnel backend for a generated secret path on ports 80 and 443, forwarding only to Dropbear. Use the generated `ws://` or `wss://` command from `link-gen`.
- **Kernel-Level Performance:** Enables IPv4 forwarding and applies high file-descriptor limits to high-throughput services.
- **Automated Self-Healing:** A weekly systemd timer renews Let's Encrypt certificates (restarting Hysteria/ZivPN on real renewals so they never serve an expired cert), updates GeoIP/GeoSite databases, and revives any managed daemon that stopped running (HAProxy, Nginx, Xray, Dropbear, Squid, wstunnel, Hysteria, ZivPN, OpenVPN, WireGuard, BadVPN).
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
| **53 / 109 / 2222** | TCP | SSH | Dropbear | Core SSH Tunnel |
| **10001** | TCP | WebSocket | Xray-core | VLESS-WS Inbound |
| **10004** | TCP | HTTPUpgrade | Xray-core | High-Throughput Streaming |
| **10006+** | TCP | WebSocket | Xray-core | Shadowsocks WS Inbound (one per user) |
| **8388+** | TCP | Shadowsocks | Xray-core | Plain SS TCP Inbound (one per user, no WS/TLS) |
| **8443** | TCP | TLS | Xray-core | VLESS TCP+TLS Inbound (own cert, no Reality/WS) |
| **10443+** | TCP | Vision | Xray-core | VLESS Reality inbound (one per front) |
| **1194 / 2200** | TCP / UDP | OpenVPN | OpenVPN | Dual-Stack VPN Tunnel |
| **51820** | UDP | WireGuard | Kernel | WireGuard L3 Interface |
| **4433** | UDP | Hysteria 2 | Hysteria | High-performance UDP tunnel |
| **80 / 443** | TCP | WebSocket | Nginx → Xray | VLESS/VMess/Trojan/Shadowsocks WS routes (`/vless-ws`, `/vmess-ws`, `/trojan-ws`, `/ss-<user>`) |
| **80 / 443** | TCP | WebSocket | wstunnel → Dropbear | SSH over WebSocket |
| **7100–7700** | UDP | UDPGW | BadVPN | Mobile Gaming Packet Bridge |

---

## 🖥️ Management

Launch the control panel anytime from your terminal by typing:

```bash
menu
```

Menu options cover domain & certificate (`1`), client links for any user
(`2`), SSH user management (`4`/`5`), restarts (`6`), diagnostics (`8`),
Reality SNI fronts (`10`, or `reality-fronts` for `add`/`remove`/`set`),
per-user Xray identities & traffic (`11`, or `mubx-users`), a bug-host
audit (`12`, or `mubx-probe`), an on-demand self-heal that restarts only
the services that are down and explains each failure (`13`, or
`mubx-restart-failed`), and a built-in self-update that pulls the latest
tree and re-renders everything in place (`14`, or `mubx-update`).
`svc-status` prints the reason under any service that is not running (port
conflicts, last journal line).

For mobile-network troubleshooting, run:

```bash
mubx-diagnose
```

The weekly maintenance job runs `mubx-cron` (GeoIP/GeoSite + certificate
renewal + service self-heal).

---

## 📄 License

MIT — see [LICENSE](LICENSE).
