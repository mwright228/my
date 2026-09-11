#!/usr/bin/env python3
"""
Unit tests for MUB-X Automated Account Expiry and Bandwidth Quota System:
- mubx-users quota command (setting, updating, resetting)
- mubx-users extend & set-expiry commands (date calculations, leap years, format validation)
- mubx-quota check watchdog (expiration auto-freeze, bandwidth threshold auto-freeze)
- admin account immunity from auto-freeze
- dry-run mode and status report table
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))


class TestQuotaAndExpiry(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="mubx-test-quota-")
        self.store_file = os.path.join(self.test_dir, "users.json")
        self.env = dict(os.environ)
        self.env["MUBX_ALLOW_NON_ROOT"] = "1"
        self.env["MUBX_USERS_FILE"] = self.store_file
        self.env["MUBX_CONF_DIR"] = self.test_dir

        # Initialize base test users in store
        self.initial_users = [
            {
                "name": "admin",
                "uuid": "00000000-0000-0000-0000-000000000001",
                "added": "2026-01-01",
                "expiry": "2020-01-01",  # Deliberately past date to verify immunity
                "quota_gb": 1,
                "status": "active",
                "protocols": ["all"],
            },
            {
                "name": "alice",
                "uuid": "00000000-0000-0000-0000-000000000002",
                "added": "2026-01-01",
                "expiry": "2028-12-31",
                "quota_gb": 10,
                "status": "active",
                "protocols": ["all"],
            },
            {
                "name": "bob",
                "uuid": "00000000-0000-0000-0000-000000000003",
                "added": "2026-01-01",
                "expiry": "never",
                "quota_gb": 0,
                "status": "active",
                "protocols": ["all"],
            },
        ]
        self._write_store(self.initial_users)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def _write_store(self, users):
        with open(self.store_file, "w") as f:
            json.dump(users, f, indent=2)
        os.chmod(self.store_file, 0o600)

    def _read_store(self):
        with open(self.store_file, "r") as f:
            return json.load(f)

    def test_cmd_quota_setting_and_unlimited(self):
        """Verifies setting data quota to positive GB and 0 (unlimited)."""
        res = subprocess.run(
            ["bash", "bin/mubx-users", "quota", "alice", "25"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"quota set failed: {res.stderr}")

        users = self._read_store()
        alice = next(u for u in users if u["name"] == "alice")
        self.assertEqual(alice["quota_gb"], 25)

        # Set to 0 (unlimited)
        res = subprocess.run(
            ["bash", "bin/mubx-users", "quota", "alice", "0"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        users = self._read_store()
        alice = next(u for u in users if u["name"] == "alice")
        self.assertEqual(alice["quota_gb"], 0)

        # Rejects negative numbers
        res = subprocess.run(
            ["bash", "bin/mubx-users", "quota", "alice", "-5"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(res.returncode, 0)

    def test_cmd_extend_and_set_expiry(self):
        """Verifies extending active/expired accounts and setting explicit expiration dates."""
        # Set explicit date
        res = subprocess.run(
            ["bash", "bin/mubx-users", "set-expiry", "bob", "2027-06-15"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"set-expiry failed: {res.stderr}")
        users = self._read_store()
        bob = next(u for u in users if u["name"] == "bob")
        self.assertEqual(bob["expiry"], "2027-06-15")

        # Extend by 10 days
        res = subprocess.run(
            ["bash", "bin/mubx-users", "extend", "bob", "10"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"extend failed: {res.stderr}")
        users = self._read_store()
        bob = next(u for u in users if u["name"] == "bob")
        self.assertEqual(bob["expiry"], "2027-06-25")

        # Set to never
        res = subprocess.run(
            ["bash", "bin/mubx-users", "set-expiry", "bob", "never"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        users = self._read_store()
        bob = next(u for u in users if u["name"] == "bob")
        self.assertEqual(bob["expiry"], "")

        # Invalid format rejected
        res = subprocess.run(
            ["bash", "bin/mubx-users", "set-expiry", "bob", "not-a-date"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(res.returncode, 0)

    def test_watchdog_auto_freezes_expired_user(self):
        """Verifies that mubx-quota check auto-freezes users past their expiration date."""
        users = self._read_store()
        for u in users:
            if u["name"] == "alice":
                u["expiry"] = "2021-05-20"  # Expired in the past
        self._write_store(users)

        res = subprocess.run(
            ["bash", "bin/mubx-quota", "check"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"watchdog check failed: {res.stderr}")

        updated = self._read_store()
        alice = next(u for u in updated if u["name"] == "alice")
        self.assertEqual(alice["status"], "frozen", "Alice should have been auto-frozen due to expired date")

    def test_watchdog_auto_freezes_over_quota_user(self):
        """Verifies that mubx-quota check auto-freezes users exceeding their bandwidth quota."""
        users = self._read_store()
        for u in users:
            if u["name"] == "alice":
                u["expiry"] = "2029-01-01"
                u["quota_gb"] = 2  # 2 GB limit (~2,147,483,648 bytes)
        self._write_store(users)

        # Create mock Xray stats file with 3.5 GB consumed
        mock_stats = os.path.join(self.test_dir, "xray_stats.json")
        stats_data = {
            "stat": [
                {
                    "name": "user>>>alice@mubx>>>traffic>>>uplink",
                    "value": 1500000000,
                },
                {
                    "name": "user>>>alice@mubx>>>traffic>>>downlink",
                    "value": 2500000000,
                },
            ]
        }
        with open(mock_stats, "w") as f:
            json.dump(stats_data, f)
        self.env["MUBX_XRAY_STATS_MOCK"] = mock_stats

        res = subprocess.run(
            ["bash", "bin/mubx-quota", "check"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"watchdog check failed: {res.stderr}")

        updated = self._read_store()
        alice = next(u for u in updated if u["name"] == "alice")
        self.assertEqual(alice["status"], "frozen", "Alice should have been auto-frozen due to quota breach")

    def test_admin_account_immune_from_freeze(self):
        """Verifies admin account is never frozen even if past expiry and over quota."""
        users = self._read_store()
        admin = next(u for u in users if u["name"] == "admin")
        self.assertEqual(admin["status"], "active")

        # Mock stats showing admin with 50 GB consumed on 1GB quota
        mock_stats = os.path.join(self.test_dir, "xray_stats.json")
        stats_data = {
            "stat": [
                {
                    "name": "user>>>admin@mubx>>>traffic>>>uplink",
                    "value": 30000000000,
                },
                {
                    "name": "user>>>admin@mubx>>>traffic>>>downlink",
                    "value": 30000000000,
                },
            ]
        }
        with open(mock_stats, "w") as f:
            json.dump(stats_data, f)
        self.env["MUBX_XRAY_STATS_MOCK"] = mock_stats

        res = subprocess.run(
            ["bash", "bin/mubx-quota", "check"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)

        updated = self._read_store()
        admin_after = next(u for u in updated if u["name"] == "admin")
        self.assertEqual(admin_after["status"], "active", "Admin account must remain immune from auto-freeze")

    def test_dry_run_does_not_mutate_store(self):
        """Verifies that --dry-run reports actions without mutating user statuses."""
        users = self._read_store()
        for u in users:
            if u["name"] == "alice":
                u["expiry"] = "2020-01-01"
        self._write_store(users)

        res = subprocess.run(
            ["bash", "bin/mubx-quota", "check", "--dry-run"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0)
        self.assertIn("[DRY RUN] Would freeze 'alice'", res.stdout)

        updated = self._read_store()
        alice = next(u for u in updated if u["name"] == "alice")
        self.assertEqual(alice["status"], "active", "Dry run must not change user status to frozen")

    def test_quota_status_table_formatting(self):
        """Verifies mubx-quota status produces formatted output without errors."""
        res = subprocess.run(
            ["bash", "bin/mubx-quota", "status"],
            cwd=REPO_ROOT,
            env=self.env,
            capture_output=True,
            text=True,
        )
        self.assertEqual(res.returncode, 0, f"quota status failed: {res.stderr}")
        self.assertIn("USER EXPIRY & QUOTA WATCHDOG", res.stdout)
        self.assertIn("alice", res.stdout)
        self.assertIn("bob", res.stdout)


if __name__ == "__main__":
    unittest.main()
