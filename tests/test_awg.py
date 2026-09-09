#!/usr/bin/env python3
"""
Unit tests for MUB-X AmneziaWG (Obfuscated WireGuard) Engine (bin/mubx-awg).
Compatible with pytest and standard python3 -m unittest.
"""

import os
import subprocess
import tempfile
import unittest

AWG_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "bin", "mubx-awg")
)


class TestAmneziaWG(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.env = os.environ.copy()
        self.env["MUBX_ALLOW_NON_ROOT"] = "1"
        self.env["MUBX_AWG_TEST"] = "1"
        self.env["MUBX_AWG_ENV"] = os.path.join(self.temp_dir.name, "awg.env")
        self.env["MUBX_AWG_SERVER_CONF"] = os.path.join(self.temp_dir.name, "awg0.conf")
        self.env["MUBX_AWG_CLIENT_CONF"] = os.path.join(self.temp_dir.name, "client.conf")
        self.env["MUBX_DOMAIN_FILE"] = os.path.join(self.temp_dir.name, "domain")
        with open(self.env["MUBX_DOMAIN_FILE"], "w") as f:
            f.write("test.example.com\n")

    def tearDown(self):
        self.temp_dir.cleanup()

    def run_awg(self, args):
        cmd = [AWG_PATH] + args
        res = subprocess.run(
            cmd,
            env=self.env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return res

    def test_init_creates_obfuscation_environment(self):
        res = self.run_awg(["init"])
        self.assertEqual(res.returncode, 0)
        self.assertTrue(os.path.isfile(self.env["MUBX_AWG_ENV"]))

        with open(self.env["MUBX_AWG_ENV"], "r") as f:
            content = f.read()

        self.assertIn("AWG_PORT=51821", content)
        self.assertIn("AWG_JC=", content)
        self.assertIn("AWG_JMIN=", content)
        self.assertIn("AWG_JMAX=", content)
        self.assertIn("AWG_S1=", content)
        self.assertIn("AWG_S2=", content)
        self.assertIn("AWG_H1=", content)
        self.assertIn("AWG_H2=", content)
        self.assertIn("AWG_H3=", content)
        self.assertIn("AWG_H4=", content)

        # Check permissions: must be 0600 (root only)
        st = os.stat(self.env["MUBX_AWG_ENV"])
        self.assertEqual(oct(st.st_mode & 0o777), "0o600")

    def test_client_config_contains_amnezia_parameters(self):
        res = self.run_awg(["client-config", "alice", "10.9.0.5"])
        self.assertEqual(res.returncode, 0)
        output = res.stdout

        self.assertIn("[Interface]", output)
        self.assertIn("Address = 10.9.0.5/24", output)
        self.assertIn("Jc = 4", output)
        self.assertIn("Jmin = 50", output)
        self.assertIn("Jmax = 1000", output)
        self.assertIn("S1 = 56", output)
        self.assertIn("S2 = 112", output)
        self.assertIn("H1 = ", output)
        self.assertIn("H2 = ", output)
        self.assertIn("H3 = ", output)
        self.assertIn("H4 = ", output)
        self.assertIn("[Peer]", output)
        self.assertIn("Endpoint = test.example.com:51821", output)

    def test_render_server_generates_server_conf(self):
        res = self.run_awg(["render-server"])
        self.assertEqual(res.returncode, 0)
        self.assertTrue(os.path.isfile(self.env["MUBX_AWG_SERVER_CONF"]))

        with open(self.env["MUBX_AWG_SERVER_CONF"], "r") as f:
            content = f.read()

        self.assertIn("[Interface]", content)
        self.assertIn("Address = 10.9.0.1/24", content)
        self.assertIn("ListenPort = 51821", content)
        self.assertIn("H1 = ", content)
        self.assertIn("H4 = ", content)

    def test_show_command(self):
        res = self.run_awg(["show"])
        self.assertEqual(res.returncode, 0)
        self.assertIn("MUB-X AMNEZIAWG OBFUSCATED WIREGUARD", res.stdout)
        self.assertIn("51821 (UDP)", res.stdout)


if __name__ == "__main__":
    unittest.main()
