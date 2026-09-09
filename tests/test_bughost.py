#!/usr/bin/env python3
"""
Unit tests for MUB-X Autonomous Bug Host Fleet Evaluator (bin/mubx-bughost-eval).
Compatible with pytest and standard python3 -m unittest.
"""

import asyncio
import json
import os
import runpy
import tempfile
import unittest

EVAL_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "bin", "mubx-bughost-eval")
)
bughost_module = runpy.run_path(EVAL_PATH)

load_candidates = bughost_module["load_candidates"]
save_candidates = bughost_module["save_candidates"]
probe_bughost = bughost_module["probe_bughost"]
evaluate_fleet = bughost_module["evaluate_fleet"]
save_evaluation_results = bughost_module["save_evaluation_results"]
DEFAULT_CANDIDATES = bughost_module["DEFAULT_CANDIDATES"]
is_valid_hostname = bughost_module["is_valid_hostname"]


class TestBugHostLoadingAndSaving(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.candidates_file = os.path.join(self.temp_dir.name, "bughosts.json")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_load_fallback_when_file_missing(self):
        hosts = load_candidates(path=self.candidates_file)
        self.assertEqual(hosts, list(DEFAULT_CANDIDATES))

    def test_save_and_load_roundtrip(self):
        test_hosts = ["cdn.whatsapp.net", "m.facebook.com", "zoom.us"]
        save_candidates(test_hosts, path=self.candidates_file)
        loaded = load_candidates(path=self.candidates_file)
        self.assertEqual(sorted(loaded), sorted(test_hosts))

    def test_load_handles_dict_with_candidates_key(self):
        with open(self.candidates_file, "w", encoding="utf-8") as f:
            json.dump({"candidates": ["test1.com", "test2.com"]}, f)
        loaded = load_candidates(path=self.candidates_file)
        self.assertEqual(loaded, ["test1.com", "test2.com"])

    def test_load_handles_malformed_json_gracefully(self):
        with open(self.candidates_file, "w", encoding="utf-8") as f:
            f.write("invalid json {{{")
        loaded = load_candidates(path=self.candidates_file)
        self.assertEqual(loaded, list(DEFAULT_CANDIDATES))

    def test_hostname_validation_and_crlf_defense(self):
        self.assertTrue(is_valid_hostname("free.facebook.com"))
        self.assertTrue(is_valid_hostname("zoom.us"))
        self.assertFalse(is_valid_hostname("bad\r\nhost.com"))
        self.assertFalse(is_valid_hostname("bad host.com"))
        self.assertFalse(is_valid_hostname("bad/path.com"))
        self.assertFalse(is_valid_hostname("bad:443"))
        self.assertFalse(is_valid_hostname(""))
        self.assertFalse(is_valid_hostname("a" * 255))

        # Ensure load_candidates filters out malicious entries
        with open(self.candidates_file, "w", encoding="utf-8") as f:
            json.dump(["valid.com", "inject\r\nsmuggle.com", "evil/path"], f)
        loaded = load_candidates(path=self.candidates_file)
        self.assertEqual(loaded, ["valid.com"])


class TestBugHostProbingAndScoring(unittest.IsolatedAsyncioTestCase):
    async def test_probe_bughost_healthy_200(self):
        async def mock_handler(reader, writer):
            await reader.readuntil(b"\r\n\r\n")
            writer.write(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        server = await asyncio.start_server(mock_handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            res = await probe_bughost("free.facebook.com", chameleon_port=port, timeout=2.0)
            self.assertEqual(res["status_code"], 200)
            self.assertEqual(res["verdict"], "HEALTHY")
            self.assertGreater(res["score"], 80.0)
        finally:
            server.close()
            await server.wait_closed()

    async def test_probe_bughost_redirect_302(self):
        async def mock_handler(reader, writer):
            await reader.readuntil(b"\r\n\r\n")
            writer.write(b"HTTP/1.1 302 Found\r\nLocation: /login\r\n\r\n")
            await writer.drain()
            writer.close()
            await writer.wait_closed()

        server = await asyncio.start_server(mock_handler, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        try:
            res = await probe_bughost("redirector.com", chameleon_port=port, timeout=2.0)
            self.assertEqual(res["status_code"], 302)
            self.assertEqual(res["verdict"], "REDIRECT")
            self.assertGreaterEqual(res["score"], 40.0)
            self.assertLessEqual(res["score"], 65.0)
        finally:
            server.close()
            await server.wait_closed()

    async def test_probe_bughost_connection_refused(self):
        # Unallocated loopback port
        res = await probe_bughost("refused.com", chameleon_port=64123, timeout=0.5)
        self.assertEqual(res["verdict"], "REFUSED")
        self.assertEqual(res["score"], 0.0)

    async def test_evaluate_fleet_ranking(self):
        candidates = ["slow.com", "fast_healthy.com", "blocked.com"]

        async def fake_probe(host):
            if host == "fast_healthy.com":
                return {"host": host, "status_code": 200, "rtt_ms": 15.0, "verdict": "HEALTHY", "score": 99.2}
            elif host == "slow.com":
                return {"host": host, "status_code": 200, "rtt_ms": 350.0, "verdict": "HEALTHY", "score": 82.5}
            else:
                return {"host": host, "status_code": 403, "rtt_ms": 50.0, "verdict": "BLOCKED", "score": 7.5}

        ranked = await evaluate_fleet(candidates, probe_fn=fake_probe)
        self.assertEqual(len(ranked), 3)
        self.assertEqual(ranked[0]["host"], "fast_healthy.com")
        self.assertEqual(ranked[1]["host"], "slow.com")
        self.assertEqual(ranked[2]["host"], "blocked.com")


class TestBugHostPersistence(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.active_file = os.path.join(self.temp_dir.name, "active_bughost")
        self.status_file = os.path.join(self.temp_dir.name, "bughost_status.json")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_save_evaluation_results(self):
        results = [
            {"host": "top.winner.com", "score": 98.5, "rtt_ms": 22.0, "status_code": 200, "verdict": "HEALTHY"},
            {"host": "second.com", "score": 80.0, "rtt_ms": 50.0, "status_code": 200, "verdict": "HEALTHY"},
        ]
        winner = save_evaluation_results(results, active_file=self.active_file, status_file=self.status_file)
        self.assertEqual(winner, "top.winner.com")

        # Verify active_bughost content
        with open(self.active_file, "r", encoding="utf-8") as f:
            content = f.read().strip()
        self.assertEqual(content, "top.winner.com")

        # Verify status report JSON
        with open(self.status_file, "r", encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(data["winning_bughost"], "top.winner.com")
        self.assertEqual(data["winning_score"], 98.5)
        self.assertEqual(data["candidates_count"], 2)


if __name__ == "__main__":
    unittest.main()
