# Upgrading MUB-X

Existing servers upgrade **in place**: nothing is wiped, every secret,
certificate, user, Reality front, and per-user identity survives.

> [!NOTE]
> DNSTT / SlowDNS was removed from MUB-X - ZivPN covers the UDP-tunnel
> role. The next update disables and deletes the old `dnstt` unit,
> binaries and `/etc/dnstt` keys automatically.

## Fastest: menu option `14` (self-update)

Once `mubx-update` is installed, updates are one keypress from the control
panel — menu option `14` — or directly:

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
resolving to the server. Re-runs are idempotent: they keep `REALITY_FRONTS`,
`SSH_WS_PATH`, `DOMAIN`, all secrets in `/etc/telecom-engine.env`, the user
store `/etc/mubx/users.json`, WireGuard / OpenVPN keys, and the
Let's Encrypt certificate.

> [!NOTE]
> `mubx-update` itself arrives with the first update that carries it: run
> menu `14`-ready code once by re-running the installer (or copying the
> repo) after the feature ships, and option `14` is available from then on.

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
source lib/reality-build.sh && mubx_users_seed
tmp="$(mktemp -d)"
mubx_xray_render   configs/xray.json     configs/reality-inbound.json "$tmp/xray.json"
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

1. **Services up** — run `mubx-restart-failed` (menu `13`); every service
   should report running. Anything still down prints its last journal line
2. **Full status** — `svc-status` (menu `7`): all rows `Running`. The Xray
   transports list should now include one `Xray Shadowsocks WS (<user>)`
   row per user (admin plus any others you created), alongside Reality,
   VLESS/VMess/Trojan WS, HTTPUpgrade and xHTTP.
3. **New protocol smoke test** — `link-gen` (menu `2`, user `admin`):
   section `[8] Shadowsocks` must print a TLS (443) and plain (80)
   `ss://` link ending in `#MUBX-SS-admin`. Import the TLS one into
   v2rayNG / Shadowrocket / sing-box and confirm a connection.
4. **Per-user lifecycle** — `add-user alice` then `link-gen` for alice
   shows `/ss-alice`; after `delete-user alice`, alice's Xray inbound and
   nginx route are gone (`grep -c 'ss-alice' /etc/nginx/nginx.conf` → 0)
   and everyone else's links still work.
5. **Config validity** — `xray run -test -config /usr/local/etc/xray/config.json`,
   `nginx -t`, and `haproxy -c -f /etc/haproxy/haproxy.cfg` all pass.
6. **State preserved** — `/etc/telecom-engine.env` still carries your
   domain, Reality keys/fronts and secret WS path; `ls /etc/mubx/users.json`
   exists with `admin` as the first entry; your certificate did not change
   (renewals only happen close to expiry).

Roll back at any time from the pre-run snapshots, e.g.
`cp -a /etc/mubx/backup/etc/nginx/nginx.conf /etc/nginx/nginx.conf`
(note the doubled `/etc/mubx/backup<path>` layout) — or simply re-run the
installer, which snapshots and restores automatically.
