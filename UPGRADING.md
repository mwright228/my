# Upgrading MUB-X

Existing servers upgrade **in place**: nothing is wiped, every secret,
certificate, user, and per-user identity survives.

> [!NOTE]
> DNSTT / SlowDNS was removed from MUB-X - ZivPN covers the UDP-tunnel
> role. The next update disables and deletes the old `dnstt` unit,
> binaries and `/etc/dnstt` keys automatically.

> [!NOTE]
> Reality (XTLS-Vision) was removed from MUB-X - it needs a reachable
> third-party SNI front, and the raw **Shadowsocks TCP on 443** route
> replaced it. The next update deletes `reality-fronts` and re-renders the
> Xray/HAProxy configs without Reality inbounds; port 443 now splits TLS
> (Nginx / WebSocket transports) from non-TLS (raw Shadowsocks). Any
> `REALITY_*` keys left in `/etc/telecom-engine.env` are ignored and
> stripped on the next installer run.

## Fastest: menu option `13` (self-update)

Once `mubx-update` is installed, updates are one keypress from the control
panel — menu option `13` — or directly:

```bash
mubx-update
```

It pulls the latest tree from the same origin `install.sh` uses, installs
the new `bin/`/`systemd/`/`lib` files, re-renders every generated config
from the new templates (Xray, HAProxy, Nginx — including the per-user
Shadowsocks WS routes), restarts whatever was running, and reports any
service that did not come back. If anything fails before the final restart
it rolls the touched files and service states back from a timestamped
snapshot under `/etc/mubx/update-backups`. It refuses to run when the
install root has uncommitted local changes or points at a different origin.

## Full path: re-run the installer

The installer is the bootstrap for new hosts and the fallback when an
update needs more than the script refresh (OS packages, new binaries,
certificates, first-time secrets). It snapshots files, firewall and
service activity to `/etc/mubx/backup<path>` and restores them on failure.

```bash
curl -fsSL https://raw.githubusercontent.com/mwright228/my/main/install.sh | bash
```

Run as root on the VPS. Ports 80/443 must be free while it runs (it stops
nginx/HAProxy briefly for certificate handling), and the domain must keep
resolving to the server. Re-runs are idempotent: they keep `DOMAIN`, `SSH_WS_PATH`, all secrets in
`/etc/telecom-engine.env` (stale `REALITY_*` keys are dropped), the user
store `/etc/mubx/users.json`, WireGuard / OpenVPN keys, and the
Let's Encrypt certificate.

> [!NOTE]
> `mubx-update` itself arrives with the first update that carries it: run
> menu `13`-ready code once by re-running the installer (or copying the
> repo) after the feature ships, and option `13` is available from then on.

---

## Manual path: pull + re-render (template-only changes)

If the only changes you care about are configs/rendering (no `bin/` or
`systemd/` updates), you can skip the full install. Otherwise just re-run
`install.sh` — it does everything below with rollback.

```bash
ROOT="$(cat /etc/mubx/install-root)"           # usually /root/mub-x
cd "$ROOT"
git pull --ff-only origin main

# Load secrets into shell vars, then render candidates into a temp dir.
source lib/common.sh && load_mubx_env
source lib/render.sh && mubx_users_seed
tmp="$(mktemp -d)"
mubx_xray_render   configs/xray.json     "$tmp/xray.json"
mubx_haproxy_render configs/haproxy.cfg  "$tmp/haproxy.cfg"
mubx_nginx_render  configs/nginx.conf    "$tmp/nginx.conf"

# Validate before touching the live files.
xray run -test -config "$tmp/xray.json"
nginx -t -c "$tmp/nginx.conf"
haproxy -c -f "$tmp/haproxy.cfg"

# Swap in and reload.
install -m 0600 "$tmp/xray.json" /usr/local/etc/xray/config.json
install -m 0644 "$tmp/haproxy.cfg" /etc/haproxy/haproxy.cfg
install -m 0644 "$tmp/nginx.conf" /etc/nginx/nginx.conf
systemctl restart xray haproxy
systemctl reload nginx
rm -rf "$tmp"
```

This refreshes configs only. Scripts in `bin/` and units in `systemd/`
change too when the menu gains options or units are hardened — pick them up
with `install -m 0755 bin/* /usr/local/bin/` (use a temp file + `mv` if a
script is running) and
`install -m 0644 systemd/*.service systemd/*.timer /etc/systemd/system/`
followed by `systemctl daemon-reload`. When in doubt, re-run `install.sh`.

---

## Verify after upgrading

1. **Services up** — run `mubx-restart-failed` (menu `12`); every service
   should report running. Anything still down prints its last journal line
2. **Full status** — `svc-status` (menu `7`): all rows `Running`. The Xray
   transports list should now include one `Xray Shadowsocks WS (<user>)` and
   one `Xray Shadowsocks TCP (<user>)` row per user (admin plus any others
   you created), a shared `Xray Shadowsocks TCP (443 shared)` row, and an
   `Xray VLESS TCP TLS (8443)` row, alongside VLESS/VMess/Trojan WS,
   HTTPUpgrade and xHTTP. No Reality rows remain.
3. **New protocol smoke test** — `link-gen` (menu `2`, user `admin`):
   the Xray transports card must print a VLESS TCP TLS link on `8443`
   (`#MUBX-VLESS-TLS`) plus plain (port 80) twins of HTTPUpgrade, xHTTP,
   VMess-WS and Trojan-WS, and the Shadowsocks card must print a TLS (443),
   plain WS (80), plain TCP on 443 (`#MUBX-SS-443`) and plain TCP (8388)
   `ss://` link. Import the plain TCP ones into v2rayNG / Shadowrocket /
   sing-box — no plugin or TLS toggles required — and confirm a connection.
4. **Per-user lifecycle** — `add-user alice` then `link-gen` for alice
   shows `/ss-alice`; after `delete-user alice`, alice's Xray inbound and
   nginx route are gone (`grep -c 'ss-alice' /etc/nginx/nginx.conf` → 0)
   and everyone else's links still work.
5. **Config validity** — `xray run -test -config /usr/local/etc/xray/config.json`,
   `nginx -t`, and `haproxy -c -f /etc/haproxy/haproxy.cfg` all pass.
6. **State preserved** — `/etc/telecom-engine.env` still carries your
   domain and secret WS path (any stale `REALITY_*` keys are ignored and
   are stripped on the next installer run); `ls /etc/mubx/users.json`
   exists with `admin` as the first entry; your certificate did not change
   (renewals only happen close to expiry).

Roll back at any time from the pre-run snapshots, e.g.
`cp -a /etc/mubx/backup/etc/nginx/nginx.conf /etc/nginx/nginx.conf`
(note the doubled `/etc/mubx/backup<path>` layout) — or simply re-run the
installer, which snapshots and restores automatically.

## Adopting the subscription server (menu 14)

After any update that ships `mubx-sub`, run it once to mint the per-user
tokens and publish the files:

```bash
mubx-sub          # or: menu 14
```

Each line prints `https://<domain>/sub/<token>/index.txt` — import that URL
into v2rayNG / NekoBox / Shadowrocket (`clash.yaml` for Clash, `singbox.json`
for sing-box). Regeneration afterwards is automatic: user add/remove,
`mubx-update`, `mubx-cron` (weekly) and `set-domain` all refresh the files.
The URLs are HTTPS-only; the token in the path is the only credential.

## Optional features after an upgrade

- **Hysteria2 port hopping** is set at install time via
  `MUBX_HY2_HOPPING=START:END`. To add it later, write
  `/etc/mubx/hy2-hopping.conf` with `HY2_HOPPING_RANGE=<start>:<end>` and add
  the DNAT rule manually (`iptables -t nat -A PREROUTING -i <wan> -p udp
  --dport <start>:<end> -j DNAT --to-destination :4433`), then
  `iptables-save > /etc/iptables/rules.v4` — or re-run the installer with
  the env var set.
- **SS-2022** keys are minted per user the first time `mubx-sub` runs; the
  matching inbounds appear on the next Xray re-render (menu 13, or any
  user add/remove).
- **ShadowTLS v3** ships as a new optional daemon (`singbox.service`, pinned
  sing-box binary). After an update, run `menu 13` once: the installer and
  `mubx-update` both stage the binary and render `/etc/sing-box/config.json`
  from `SHADOWTLS_PASS` (added to the env file automatically by
  `generate-secrets`) and the admin SS-2022 key. Verify with
  `systemctl status singbox` and `ss -ltnp | grep 8448`. Client configs are
  in the subscription files (`MUBX-ShadowTLS` node).
