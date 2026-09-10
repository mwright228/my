#!/usr/bin/env python3
"""
Unit tests for T-Brutal protocol management and subscription integration.
"""

import os
import subprocess
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestTBrutalCLI(unittest.TestCase):
    def test_tbrutal_cli_config(self):
        cmd = ["bash", "bin/mubx-tbrutal", "config", "admin", "downloads.vodafone.co.uk", "80", "4"]
        res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("MUB-X T-BRUTAL ZERO-RATED CLIENT CONFIGURATION", res.stdout)
        self.assertIn("downloads.vodafone.co.uk", res.stdout)
        self.assertIn("80 Mbps", res.stdout)
        self.assertIn("127.0.0.1:10808", res.stdout)
        self.assertIn("tbrutal-client", res.stdout)

    def test_proto_def_includes_tbrutal(self):
        # Verify tbrutal is parsed by jq proto definition
        cmd = [
            "jq", "-n", "-L", "lib",
            'include "proto-def"; {"protocols":["tbrutal"]} | user_has_proto("tbrutal")'
        ]
        res = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertEqual(res.stdout.strip(), "true")


if __name__ == "__main__":
    unittest.main()
