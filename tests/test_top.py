#!/usr/bin/env python3
"""
Unit tests for MUB-X Real-Time Interactive Telemetry Dashboard (bin/mubx-top).
Compatible with pytest and standard python3 -m unittest.
"""

import http.server
import json
import os
import runpy
import socketserver
import tempfile
import threading
import unittest

TOP_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "bin", "mubx-top")
)
top_module = runpy.run_path(TOP_PATH)

format_bytes = top_module["format_bytes"]
format_uptime = top_module["format_uptime"]
fetch_chameleon_stats = top_module["fetch_chameleon_stats"]
send_control_action = top_module["send_control_action"]
fetch_bughost_status = top_module["fetch_bughost_status"]
print_snapshot = top_module["print_snapshot"]


class TestTopFormatting(unittest.TestCase):
    def test_format_bytes(self):
        self.assertEqual(format_bytes(500), "500 B")
        self.assertEqual(format_bytes(1024), "1.0 KB")
        self.assertEqual(format_bytes(1024 * 1024), "1.0 MB")
        self.assertEqual(format_bytes(5 * 1024 * 1024 * 1024), "5.00 GB")

    def test_format_uptime(self):
        self.assertEqual(format_uptime(45), "45s")
        self.assertEqual(format_uptime(125), "2m 5s")
        self.assertEqual(format_uptime(3665), "1h 1m 5s")
        self.assertEqual(format_uptime(90000), "1d 1h 0m")


class TestTopBughostStatus(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.status_file = os.path.join(self.temp_dir.name, "bughost_status.json")
        self.active_file = os.path.join(self.temp_dir.name, "active_bughost")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_fetch_bughost_status_empty(self):
        data = fetch_bughost_status(status_file=self.status_file, active_file=self.active_file)
        self.assertEqual(data["winning_bughost"], "None")
        self.assertEqual(data["winning_score"], 0.0)

    def test_fetch_bughost_status_from_active_file(self):
        with open(self.active_file, "w", encoding="utf-8") as f:
            f.write("free.facebook.com\n")
        data = fetch_bughost_status(status_file=self.status_file, active_file=self.active_file)
        self.assertEqual(data["winning_bughost"], "free.facebook.com")
        self.assertEqual(data["winning_score"], 100.0)

    def test_fetch_bughost_status_from_json(self):
        payload = {
            "winning_bughost": "v.whatsapp.net",
            "winning_score": 98.4,
            "candidates": [{"host": "v.whatsapp.net", "score": 98.4}],
        }
        with open(self.status_file, "w", encoding="utf-8") as f:
            json.dump(payload, f)
        data = fetch_bughost_status(status_file=self.status_file, active_file=self.active_file)
        self.assertEqual(data["winning_bughost"], "v.whatsapp.net")
        self.assertEqual(data["winning_score"], 98.4)
        self.assertEqual(len(data["candidates"]), 1)


class TestTopHttpInteraction(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        class MockHandler(http.server.BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def do_GET(self):
                if self.path == "/stats":
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    data = {
                        "uptime_seconds": 120,
                        "carrier_profile": "204_nocontent",
                        "connections_accepted": 42,
                        "connections_active": 3,
                        "bytes_in": 2048,
                        "bytes_out": 4096,
                        "tarpitted_connections": 5,
                        "jailed_ips_active": 1,
                        "ips_jailed_total": 2,
                        "jailed_ips_list": ["198.51.100.1"],
                        "udpgw_connections": 4,
                        "udpgw_bytes_in": 1024,
                        "udpgw_bytes_out": 2048,
                    }
                    self.wfile.write(json.dumps(data).encode("utf-8"))
                elif self.path.startswith("/profile"):
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"status":"ok","carrier_profile":"302_spoof"}')
                elif self.path == "/unjail":
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.end_headers()
                    self.wfile.write(b'{"status":"ok","message":"unjailed"}')
                else:
                    self.send_response(404)
                    self.end_headers()

        cls.server = socketserver.TCPServer(("127.0.0.1", 0), MockHandler)
        cls.port = cls.server.server_address[1]
        cls.server_thread = threading.Thread(target=cls.server.serve_forever)
        cls.server_thread.daemon = True
        cls.server_thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def test_fetch_stats_and_send_control(self):
        stats_url = f"http://127.0.0.1:{self.port}/stats"
        ctrl_url = f"http://127.0.0.1:{self.port}"

        stats = fetch_chameleon_stats(stats_url=stats_url)
        self.assertIsNotNone(stats)
        self.assertEqual(stats["carrier_profile"], "204_nocontent")
        self.assertEqual(stats["connections_accepted"], 42)
        self.assertEqual(stats["udpgw_connections"], 4)

        # Test control action /profile
        res = send_control_action("/profile?name=302_spoof", base_url=ctrl_url)
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["carrier_profile"], "302_spoof")

        # Test control action /unjail
        res_unjail = send_control_action("/unjail", base_url=ctrl_url)
        self.assertEqual(res_unjail["status"], "ok")


if __name__ == "__main__":
    unittest.main()
