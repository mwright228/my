#!/usr/bin/env python3
"""
Unit tests for MUB-X configuration rendering (lib/render.sh).
Compatible with pytest and standard python3 -m unittest.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestRenderConfigs(unittest.TestCase):
    """Tests for Xray, HAProxy, and Nginx rendering pipelines."""

    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="mubx-test-render-")

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_haproxy_render_sni_substitution_and_order(self):
        haproxy_cfg_out = os.path.join(self.test_dir, "haproxy.cfg")
        env = dict(os.environ)
        env["DOMAIN"] = "vpn.example.com"
        env["SHADOWTLS_SNI"] = "decoy.microsoft.com"

        cmd = [
            "bash",
            "-c",
            "source lib/render.sh && mubx_haproxy_render configs/haproxy.cfg \"$1\"",
            "_",
            haproxy_cfg_out,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_haproxy_render failed: {res.stderr}")

        with open(haproxy_cfg_out, "r", encoding="utf-8") as f:
            content = f.read()

        # Verify placeholders were substituted
        self.assertNotIn("__DOMAIN__", content)
        self.assertNotIn("__SHADOWTLS_SNI__", content)
        self.assertIn("use_backend srv_singbox if { req.ssl_sni -i decoy.microsoft.com }", content)
        self.assertIn("use_backend srv_nginx if { req_ssl_hello_type 1 }", content)

        # Verify rule ordering: ShadowTLS SNI rule must come BEFORE req_ssl_hello_type 1 catch-all
        pos_stls = content.find("use_backend srv_singbox if { req.ssl_sni -i decoy.microsoft.com }")
        pos_nginx = content.find("use_backend srv_nginx if { req_ssl_hello_type 1 }")
        self.assertNotEqual(pos_stls, -1)
        self.assertNotEqual(pos_nginx, -1)
        self.assertLess(pos_stls, pos_nginx, "ShadowTLS SNI rule must precede Nginx TLS catch-all")

        # Verify no tcp-request rules occur after use_backend (avoids HAProxy parser warnings)
        lines = [line.strip() for line in content.splitlines()]
        first_use_backend = None
        last_tcp_request = None
        for idx, line in enumerate(lines):
            if line.startswith("use_backend") and first_use_backend is None:
                first_use_backend = idx
            if line.startswith("tcp-request"):
                last_tcp_request = idx
        self.assertIsNotNone(first_use_backend)
        self.assertIsNotNone(last_tcp_request)
        self.assertLess(last_tcp_request, first_use_backend, "All tcp-request rules must precede any use_backend rules")

    def test_nginx_render_https_only_subs_and_resolver(self):
        nginx_conf_out = os.path.join(self.test_dir, "nginx.conf")
        env = dict(os.environ)
        env["DOMAIN"] = "vpn.example.com"
        env["SSH_WS_PATH"] = "/ssh-test-path"

        cmd = [
            "bash",
            "-c",
            "source lib/render.sh && mubx_nginx_render configs/nginx.conf \"$1\"",
            "_",
            nginx_conf_out,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_nginx_render failed: {res.stderr}")

        with open(nginx_conf_out, "r", encoding="utf-8") as f:
            content = f.read()

        # Verify DNS resolver is present
        self.assertIn("resolver 1.1.1.1 8.8.8.8 valid=300s;", content)

        # Verify port 80 /sub/ redirects to HTTPS
        port80_block = content.split("server {", 2)[1]  # first server block is port 80
        self.assertIn("return 301 https://$host$request_uri;", port80_block)
        self.assertNotIn("alias /var/www/mubx-sub/;", port80_block)

        # Verify port 20443 serves /sub/
        port20443_block = content.split("server {", 2)[2]
        self.assertIn("alias /var/www/mubx-sub/;", port20443_block)

    def test_mubx_users_apply_empty_clients_on_no_matching_proto(self):
        xray_json_out = os.path.join(self.test_dir, "xray.json")
        users_json_path = os.path.join(self.test_dir, "users.json")

        # Create a user with protocols: ["ssh"] only (no trojan or vmess)
        users = [
            {
                "name": "ssh_only_user",
                "uuid": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "protocols": ["ssh"],
                "status": "active",
                "added": "2026-09-09",
                "quota_gb": 0,
            }
        ]
        with open(users_json_path, "w", encoding="utf-8") as f:
            json.dump(users, f)

        # Copy configs/xray.json base template
        shutil.copyfile(os.path.join(REPO_ROOT, "configs", "xray.json"), xray_json_out)

        env = dict(os.environ)
        env["MUBX_USERS_FILE"] = users_json_path
        env["DOMAIN"] = "vpn.example.com"

        cmd = [
            "bash",
            "-c",
            "source lib/render.sh && mubx_users_apply \"$1\"",
            "_",
            xray_json_out,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_users_apply failed: {res.stderr}")

        with open(xray_json_out, "r", encoding="utf-8") as f:
            rendered = json.load(f)

        # Find trojan inbound
        trojan_inbounds = [i for i in rendered["inbounds"] if i.get("protocol") == "trojan"]
        self.assertTrue(len(trojan_inbounds) > 0)
        for tib in trojan_inbounds:
            clients = tib.get("settings", {}).get("clients", [])
            # Assert empty clients array rather than dummy 00000000-... UUID
            self.assertEqual(clients, [], f"Expected empty clients for trojan, got: {clients}")

    def test_mubx_ss_443_apply_no_duplicate_users_key(self):
        xray_json_out = os.path.join(self.test_dir, "xray.json")
        users_json_path = os.path.join(self.test_dir, "users.json")

        users = [
            {
                "name": "admin",
                "uuid": "11111111-2222-3333-4444-555555555555",
                "protocols": ["all"],
                "status": "active",
                "added": "2026-09-09",
                "quota_gb": 0,
            }
        ]
        with open(users_json_path, "w", encoding="utf-8") as f:
            json.dump(users, f)

        shutil.copyfile(os.path.join(REPO_ROOT, "configs", "xray.json"), xray_json_out)

        env = dict(os.environ)
        env["MUBX_USERS_FILE"] = users_json_path
        env["DOMAIN"] = "vpn.example.com"
        env["UUID"] = "11111111-2222-3333-4444-555555555555"

        cmd = [
            "bash",
            "-c",
            "source lib/render.sh && mubx_ss_443_apply \"$1\" configs/ss-443-inbound.json",
            "_",
            xray_json_out,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_ss_443_apply failed: {res.stderr}")

        with open(xray_json_out, "r", encoding="utf-8") as f:
            rendered = json.load(f)

        # Inbound tag ss-443
        ss443_inbounds = [i for i in rendered["inbounds"] if i.get("tag") == "ss-443"]
        self.assertEqual(len(ss443_inbounds), 1)
        ss_settings = ss443_inbounds[0].get("settings", {})
        self.assertIn("clients", ss_settings)
        # Verify no redundant 'users' key in settings
        self.assertNotIn("users", ss_settings, "settings.users should not be present in shadowsocks inbound")

    def test_singbox_render_tuic_port_8444(self):
        singbox_json_out = os.path.join(self.test_dir, "singbox.json")
        env = dict(os.environ)
        env["SHADOWTLS_PASS"] = "test-stls-password-123"
        env["DOMAIN"] = "vpn.example.com"
        env["UUID"] = "11111111-2222-3333-4444-555555555555"

        cmd = [
            "bash",
            "-c",
            "source lib/render.sh && mubx_singbox_render configs/singbox.json \"$1\"",
            "_",
            singbox_json_out,
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"mubx_singbox_render failed: {res.stderr}")

        with open(singbox_json_out, "r", encoding="utf-8") as f:
            cfg = json.load(f)

        tuics = [i for i in cfg["inbounds"] if i.get("tag") == "tuic-in"]
        self.assertEqual(len(tuics), 1)
        self.assertEqual(tuics[0]["listen_port"], 8444, "TUIC must listen on port 8444")

    def test_proto_def_external_file(self):
        # Verify lib/proto-def.jq is valid jq and evaluates user_has_proto correctly
        cmd = [
            "jq",
            "-n",
            "-L",
            "lib",
            "include \"proto-def\"; {protocols: [\"tuic\"]} | user_has_proto(\"tuic\")",
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"jq evaluation failed: {res.stderr}")
        self.assertEqual(res.stdout.strip(), "true")


if __name__ == "__main__":
    unittest.main()
