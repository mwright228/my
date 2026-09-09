#!/usr/bin/env python3
"""
Unit tests for MUB-X UDP 443 Ingress Management & Hysteria 2 / TUIC Carrier Bug-Host Generation.
Compatible with pytest and standard python3 -m unittest.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestUdp443Manager(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="mubx-test-udp443-")
        self.env = dict(os.environ)
        self.env["MUBX_CONF_DIR"] = self.test_dir
        self.env["MUBX_DRY_RUN"] = "1"

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_status_default_when_no_config(self):
        cmd = ["bash", "bin/mubx-udp443", "status"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("Disabled / Direct port only", res.stdout)

    def test_set_hy2(self):
        cmd = ["bash", "bin/mubx-udp443", "hy2"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("Successfully mapped UDP 443 -> Hysteria 2", res.stdout)

        # Check state file
        conf_file = os.path.join(self.test_dir, "udp443.conf")
        self.assertTrue(os.path.exists(conf_file))
        with open(conf_file, "r") as f:
            content = f.read()
        self.assertIn("TARGET=hy2", content)
        self.assertIn("PORT=4433", content)

        # Check status reflects hy2
        status_res = subprocess.run(["bash", "bin/mubx-udp443", "status"], cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertIn("Hysteria 2", status_res.stdout)

    def test_set_tuic(self):
        cmd = ["bash", "bin/mubx-udp443", "tuic"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("Successfully mapped UDP 443 -> TUIC v5", res.stdout)

        conf_file = os.path.join(self.test_dir, "udp443.conf")
        with open(conf_file, "r") as f:
            content = f.read()
        self.assertIn("TARGET=tuic", content)
        self.assertIn("PORT=8444", content)

        status_res = subprocess.run(["bash", "bin/mubx-udp443", "status"], cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertIn("TUIC v5", status_res.stdout)

    def test_set_off(self):
        cmd = ["bash", "bin/mubx-udp443", "off"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("UDP port 443 redirection removed", res.stdout)

        conf_file = os.path.join(self.test_dir, "udp443.conf")
        with open(conf_file, "r") as f:
            content = f.read()
        self.assertIn("TARGET=none", content)


class TestLinkGenBugHost(unittest.TestCase):
    def setUp(self):
        self.env = dict(os.environ)
        self.env["DOMAIN"] = "vpn.example.com"
        self.env["UUID"] = "00000000-0000-0000-0000-000000000001"
        self.env["HY2_PASS"] = "hy2secret"
        self.env["SHADOWTLS_PASS"] = "stlspass"

    def test_link_gen_direct_when_no_bughost(self):
        cmd = ["bash", "bin/link-gen", "vpn.example.com", "admin"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        # Direct links should be present
        self.assertIn("hysteria2://hy2secret@vpn.example.com:4433/?sni=vpn.example.com#MUBX-Hysteria2", res.stdout)
        self.assertIn("tuic://00000000-0000-0000-0000-000000000001:00000000-0000-0000-0000-000000000001@vpn.example.com:8444", res.stdout)
        self.assertIn("allow_insecure=0", res.stdout)
        # Bug-host nodes should NOT be present when bug == domain
        self.assertNotIn("HY2-BUGHOST", res.stdout)
        self.assertNotIn("TUIC-BUGHOST", res.stdout)

    def test_link_gen_with_carrier_bughost(self):
        carrier_bug = "downloads.vodafone.co.uk"
        cmd = ["bash", "bin/link-gen", carrier_bug, "admin"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)

        # Must include HY2-BUGHOST and TUIC-BUGHOST
        self.assertIn("[HY2-BUGHOST]", res.stdout)
        self.assertIn(f"sni={carrier_bug}&insecure=1", res.stdout)
        self.assertIn("Salamander obfuscation MUST remain DISABLED", res.stdout)

        self.assertIn("[TUIC-BUGHOST]", res.stdout)
        self.assertIn(f"sni={carrier_bug}&allow_insecure=1", res.stdout)
        self.assertIn("Insecure=1 required due to cert SAN mismatch", res.stdout)


class TestSubscribeBugHostNodes(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="mubx-test-sub-")
        self.env = dict(os.environ)
        self.env["DOMAIN"] = "vpn.example.com"
        self.env["UUID"] = "00000000-0000-0000-0000-000000000001"
        self.env["HY2_PASS"] = "hy2secret"
        self.active_file = os.path.join(self.test_dir, "active_bughost")
        self.env["MUBX_ACTIVE_BUGHOST"] = self.active_file

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_clash_subscription_includes_bughost_when_active(self):
        carrier_bug = "portal.ncnd.jazz.com.pk"
        with open(self.active_file, "w") as f:
            f.write(carrier_bug + "\n")

        cmd = [
            "bash",
            "-c",
            "source lib/subscribe.sh && mubx_sub_clash admin 00000000-0000-0000-0000-000000000001 0",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_sub_clash failed: {res.stderr}")

        parsed = json.loads(res.stdout)
        proxies = parsed["proxies"] if isinstance(parsed, dict) and "proxies" in parsed else parsed
        proxy_names = [p["name"] for p in proxies]
        self.assertIn("MUBX-Hysteria2", proxy_names)
        self.assertIn("MUBX-HY2-BugHost", proxy_names)
        self.assertIn("MUBX-TUIC-v5", proxy_names)
        self.assertIn("MUBX-TUIC-BugHost", proxy_names)

        # Check BugHost properties
        hy2_bg = next(p for p in proxies if p["name"] == "MUBX-HY2-BugHost")
        self.assertEqual(hy2_bg["port"], 443)
        self.assertEqual(hy2_bg["sni"], carrier_bug)
        self.assertTrue(hy2_bg["skip-cert-verify"])

        tuic_bg = next(p for p in proxies if p["name"] == "MUBX-TUIC-BugHost")
        self.assertEqual(tuic_bg["port"], 443)
        self.assertEqual(tuic_bg["sni"], carrier_bug)
        self.assertTrue(tuic_bg["skip-cert-verify"])

    def test_singbox_subscription_includes_bughost_when_active(self):
        carrier_bug = "portal.ncnd.jazz.com.pk"
        with open(self.active_file, "w") as f:
            f.write(carrier_bug + "\n")

        cmd = [
            "bash",
            "-c",
            "source lib/subscribe.sh && mubx_sub_singbox admin 00000000-0000-0000-0000-000000000001 0",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_sub_singbox failed: {res.stderr}")

        cfg = json.loads(res.stdout)
        outbounds = cfg.get("outbounds", [])
        tags = [o.get("tag") for o in outbounds]
        self.assertIn("MUBX-Hysteria2", tags)
        self.assertIn("MUBX-HY2-BugHost", tags)
        self.assertIn("MUBX-TUIC-v5", tags)
        self.assertIn("MUBX-TUIC-BugHost", tags)

        hy2_bg = next(o for o in outbounds if o.get("tag") == "MUBX-HY2-BugHost")
        self.assertEqual(hy2_bg["server_port"], 443)
        self.assertEqual(hy2_bg["tls"]["server_name"], carrier_bug)
        self.assertTrue(hy2_bg["tls"]["insecure"])

        tuic_bg = next(o for o in outbounds if o.get("tag") == "MUBX-TUIC-BugHost")
        self.assertEqual(tuic_bg["server_port"], 443)
        self.assertEqual(tuic_bg["tls"]["server_name"], carrier_bug)
        self.assertTrue(tuic_bg["tls"]["insecure"])

    def test_links_subscription_includes_bughost_when_active(self):
        carrier_bug = "portal.ncnd.jazz.com.pk"
        with open(self.active_file, "w") as f:
            f.write(carrier_bug + "\n")

        cmd = [
            "bash",
            "-c",
            "source lib/subscribe.sh && mubx_sub_links admin 00000000-0000-0000-0000-000000000001 0",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=self.env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_sub_links failed: {res.stderr}")

        links = res.stdout
        self.assertIn(f"/?sni={carrier_bug}&insecure=1#MUBX-HY2-BugHost", links)
        self.assertIn(f"&sni={carrier_bug}&allow_insecure=1#MUBX-TUIC-BugHost", links)


class TestMubxMasterCli(unittest.TestCase):
    def test_mubx_cli_routes_to_subcommand(self):
        cmd = ["bash", "bin/mubx", "udp443", "status"]
        env = dict(os.environ)
        env["MUBX_DRY_RUN"] = "1"
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("UDP 443 Ingress:", res.stdout)

    def test_mubx_cli_unknown_command_shows_usage(self):
        cmd = ["bash", "bin/mubx", "invalidcommandxyz"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 1)
        self.assertIn("Usage: mubx", res.stderr)


if __name__ == "__main__":
    unittest.main()
