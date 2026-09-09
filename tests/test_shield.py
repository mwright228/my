#!/usr/bin/env python3
"""
Unit tests for MUB-X Kernel Scanner Auto-Shield (bin/mubx-shield).
Compatible with pytest and standard python3 -m unittest.
"""

import os
import subprocess
import tempfile
import unittest

SHIELD_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "bin", "mubx-shield")
)


class TestShieldCli(unittest.TestCase):
    def setUp(self):
        self.env = os.environ.copy()
        self.env["MUBX_ALLOW_NON_ROOT"] = "1"
        self.env["MUBX_SHIELD_DRY_RUN"] = "1"

    def run_shield(self, args):
        cmd = [SHIELD_PATH] + args
        res = subprocess.run(
            cmd,
            env=self.env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        return res

    def test_help_and_unknown_command(self):
        res = self.run_shield(["unknown_cmd"])
        self.assertEqual(res.returncode, 1)
        self.assertIn("Usage: mubx-shield", res.stderr)

    def test_block_valid_ipv4(self):
        res = self.run_shield(["block", "198.51.100.22", "test_scanner", "600"])
        self.assertEqual(res.returncode, 0)
        self.assertIn("Kernel-shielded", res.stdout)
        self.assertIn("198.51.100.22", res.stdout)

    def test_block_valid_cidr(self):
        res = self.run_shield(["block", "203.0.113.0/24", "scanner_net", "300"])
        self.assertEqual(res.returncode, 0)
        self.assertIn("Kernel-shielded", res.stdout)
        self.assertIn("203.0.113.0/24", res.stdout)

    def test_block_invalid_ip(self):
        res = self.run_shield(["block", "invalid_ip_address"])
        self.assertEqual(res.returncode, 1)
        self.assertIn("Invalid IP or CIDR", res.stderr)

    def test_block_injection_attempt(self):
        res = self.run_shield(["block", "1.2.3.4; rm -rf /"])
        self.assertEqual(res.returncode, 1)
        self.assertIn("Invalid IP or CIDR", res.stderr)

    def test_unblock_ip(self):
        res = self.run_shield(["unblock", "198.51.100.22"])
        self.assertEqual(res.returncode, 0)
        self.assertIn("Unblocked: 198.51.100.22", res.stdout)

    def test_update_scanners_loads_known_subnets(self):
        res = self.run_shield(["update-scanners"])
        self.assertEqual(res.returncode, 0)
        self.assertIn("Loaded", res.stdout)
        self.assertIn("mass-scanner subnets into kernel filter", res.stdout)

    def test_status_output(self):
        res = self.run_shield(["status"])
        self.assertEqual(res.returncode, 0)
        self.assertIn("MUB-X KERNEL SHIELD STATUS", res.stdout)


if __name__ == "__main__":
    unittest.main()
