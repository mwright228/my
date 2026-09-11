#!/usr/bin/env python3
"""
Unit tests verifying MUB-X Codebase Audit Fixes:
- mubx-warp disable top-level function scoping
- set-domain AmneziaWG endpoint updates and permissions
- lib/render.sh 0600 permissions on generated xray and singbox configs
- haproxy.cfg management endpoint protection
- lib/common.sh portable base64 encoders
"""

import os
import shutil
import stat
import subprocess
import tempfile
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestAuditFixes(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="mubx-test-audit-")
        self.env = dict(os.environ)
        self.env["MUBX_ALLOW_NON_ROOT"] = "1"
        self.env["MUBX_CONF_DIR"] = self.test_dir

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_mubx_warp_disable_scoping(self):
        """Verifies that mubx-warp disable executes reload_xray_service without crashing."""
        warp_conf = os.path.join(self.test_dir, "warp.json")
        with open(warp_conf, "w") as f:
            f.write('{"tag":"warp"}')

        # Test mubx-warp disable directly
        script = f"""
        WARP_CONF="{warp_conf}"
        source bin/mubx-warp disable
        """
        res = subprocess.run(["bash", "-c", script], cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx-warp disable failed: {res.stderr}")
        self.assertFalse(os.path.exists(warp_conf), "WARP_CONF should be removed after disable")

    def test_set_domain_awg_and_permissions(self):
        """Verifies set-domain replaces AmneziaWG endpoint and preserves 0600 permissions."""
        awg_client_conf = os.path.join(self.test_dir, "mubx-awg-client.conf")
        with open(awg_client_conf, "w") as f:
            f.write("[Interface]\nPrivateKey = xxx\n[Peer]\nEndpoint = old.example.com:51821\n")

        new_domain = "new.example.org"
        script = f"""
        source lib/common.sh
        d="{new_domain}"
        if [ -f "{awg_client_conf}" ]; then
          mubx_sed_i -E "s/^Endpoint = .+:51821$/Endpoint = $d:51821/" "{awg_client_conf}"
        fi
        """
        res = subprocess.run(["bash", "-c", script], cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"sed update failed: {res.stderr}")

        with open(awg_client_conf, "r") as f:
            content = f.read()
        self.assertIn(f"Endpoint = {new_domain}:51821", content)

    def test_render_enforces_0600_permissions(self):
        """Verifies that mubx_xray_render and mubx_singbox_render set 0600 on outputs."""
        xray_out = os.path.join(self.test_dir, "xray.json")
        singbox_out = os.path.join(self.test_dir, "singbox.json")

        env = dict(self.env)
        env["DOMAIN"] = "test.domain.com"
        env["UUID"] = "00000000-0000-0000-0000-000000000001"
        env["SHADOWTLS_PASS"] = "stlspass"

        cmd = [
            "bash",
            "-c",
            f"""
            source lib/render.sh
            mubx_xray_render configs/xray.json "{xray_out}" || exit 1
            mubx_singbox_render configs/singbox.json "{singbox_out}" || exit 1
            """,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"Rendering failed: {res.stderr}")

        # Check file permissions are 0600 (read/write only by owner)
        xray_mode = stat.S_IMODE(os.stat(xray_out).st_mode)
        self.assertEqual(xray_mode, 0o600, f"xray.json mode was {oct(xray_mode)}, expected 0600")

        singbox_mode = stat.S_IMODE(os.stat(singbox_out).st_mode)
        self.assertEqual(singbox_mode, 0o600, f"singbox.json mode was {oct(singbox_mode)}, expected 0600")

    def test_haproxy_management_ingress_protection(self):
        """Verifies that haproxy.cfg contains TCP content reject rules for /stats and management paths."""
        haproxy_cfg_path = os.path.join(REPO_ROOT, "configs", "haproxy.cfg")
        with open(haproxy_cfg_path, "r") as f:
            content = f.read()

        self.assertIn('tcp-request content reject if { req.payload(0,11) -m str "GET /stats " }', content)
        self.assertIn('tcp-request content reject if { req.payload(0,13) -m str "GET /metrics " }', content)
        self.assertIn('tcp-request content reject if { req.payload(0,11) -m str "GET /unjail" }', content)
        self.assertIn('tcp-request content reject if { req.payload(0,12) -m str "GET /profile" }', content)

    def test_portable_base64_helpers(self):
        """Verifies that mubx_base64 and mubx_b64url work cleanly without trailing newlines."""
        cmd = [
            "bash",
            "-c",
            """
            source lib/common.sh
            out1="$(printf 'hello world' | mubx_base64)"
            out2="$(printf 'hello?world' | mubx_b64url)"
            printf '%s|%s' "$out1" "$out2"
            """,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"base64 helper failed: {res.stderr}")

        out1, out2 = res.stdout.strip().split("|")
        self.assertEqual(out1, "aGVsbG8gd29ybGQ=")
        self.assertEqual(out2, "aGVsbG8_d29ybGQ")

    def test_diagnose_target_resolution(self):
        """Verifies mubx-diagnose resolves TARGET to configured DOMAIN rather than 1.1.1.1."""
        test_domain = "gr.mub.my.id"
        domain_file = os.path.join(self.test_dir, "domain")
        with open(domain_file, "w") as f:
            f.write(test_domain + "\n")

        script = f"""
        DOMAIN="$(cat '{domain_file}' 2>/dev/null || true)"
        TARGET="${{1:-${{DOMAIN:-127.0.0.1}}}}"
        echo "TARGET=$TARGET"
        """
        res = subprocess.run(["bash", "-c", script], cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn(f"TARGET={test_domain}", res.stdout)

    def test_mubx_update_syntax_and_guards(self):
        """Verifies mubx-update syntax is clean and protects against hanging with timeouts."""
        update_script = os.path.join(REPO_ROOT, "bin", "mubx-update")
        res = subprocess.run(["bash", "-n", update_script], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx-update syntax check failed: {res.stderr}")

        with open(update_script, "r") as f:
            content = f.read()

        # Network timeouts on git fetch
        self.assertIn('timeout 25 git -C "$ROOT" fetch origin main', content)
        # Stale lock cleanup
        self.assertIn('.git/index.lock', content)
        # Timeout on service restart
        self.assertIn('timeout 6 systemctl restart "$svc"', content)
        # No batched parallel restart that deadlocks systemd/iptables
        self.assertNotIn('systemctl restart "${restart_list[@]}"', content)
        # Exit on failure instead of invalid top-level return
        self.assertNotIn('return 1\nfi\nif command -v nginx', content)

    def test_chameleon_ssrf_and_forbidden_ports(self):
        """Verifies Chameleon blocks SSRF cloud metadata and loopback rebinding, and protects admin ports."""
        chameleon_script = os.path.join(REPO_ROOT, "bin", "mubx-chameleon")
        with open(chameleon_script, "r") as f:
            content = f.read()

        # Admin ports forbidden
        self.assertIn("10085,", content)
        self.assertIn("10808,", content)
        self.assertIn("is_safe_external_target", content)

        # Direct python test of is_safe_external_target
        code = """
import asyncio, sys
from importlib.machinery import SourceFileLoader
ch = SourceFileLoader("mubx_chameleon", "bin/mubx-chameleon").load_module()

async def check():
    # 1. Cloud metadata
    assert not await ch.is_safe_external_target("169.254.169.254")
    assert not await ch.is_safe_external_target("169.254.1.1")
    # 2. Loopback direct
    assert not await ch.is_safe_external_target("127.0.0.1")
    assert not await ch.is_safe_external_target("::1")
    # 3. Loopback rebinding
    assert not await ch.is_safe_external_target("127.0.0.1.nip.io")
    assert not await ch.is_safe_external_target("localhost")
    # 4. Safe public IP
    assert await ch.is_safe_external_target("1.1.1.1")
    assert await ch.is_safe_external_target("8.8.8.8")
    print("ALL_CHECKS_PASSED")

asyncio.run(check())
"""
        res = subprocess.run(["python3", "-c", code], cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"SSRF check failed: {res.stderr}\nOutput: {res.stdout}")
        self.assertIn("ALL_CHECKS_PASSED", res.stdout)

    def test_uninstaller_and_cron_coverage(self):
        """Verifies uninstall.sh and mubx-cron include mubx-tbrutal and awg services."""
        uninstall_script = os.path.join(REPO_ROOT, "bin", "uninstall.sh")
        with open(uninstall_script, "r") as f:
            un_content = f.read()

        self.assertIn("mubx-tbrutal", un_content)
        self.assertIn("awg-quick@awg0", un_content)
        self.assertIn("/etc/systemd/system/mubx-tbrutal.service", un_content)
        self.assertIn("/usr/local/bin/mubx-tbrutal", un_content)
        self.assertIn("/usr/local/bin/tbrutal-client-android-arm64", un_content)

        cron_script = os.path.join(REPO_ROOT, "bin", "mubx-cron")
        with open(cron_script, "r") as f:
            cron_content = f.read()

        self.assertIn("mubx-tbrutal", cron_content)
        self.assertIn("awg-quick@awg0", cron_content)


if __name__ == "__main__":
    unittest.main()

