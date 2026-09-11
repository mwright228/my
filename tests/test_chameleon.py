#!/usr/bin/env python3
"""
Unit tests for MUB-X Chameleon Universal Payload Engine (bin/mubx-chameleon).
Compatible with pytest and standard python3 -m unittest.
"""

import asyncio
import base64
import json
import os
import runpy
import socket
import tempfile
import time
import unittest
from unittest import mock

# Load module under test
CHAMELEON_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "bin", "mubx-chameleon")
)
chameleon = runpy.run_path(CHAMELEON_PATH)
parse_target = chameleon["parse_target"]
is_local_target = chameleon["is_local_target"]
parse_basic_auth = chameleon["parse_basic_auth"]
check_auth = chameleon["check_auth"]
load_local_identifiers = chameleon["load_local_identifiers"]
load_valid_users = chameleon["load_valid_users"]
load_response_profiles = chameleon["load_response_profiles"]
render_response = chameleon["render_response"]
DEFAULT_PROFILES = chameleon["DEFAULT_PROFILES"]
sniff_protocol = chameleon["sniff_protocol"]
rewrite_absolute_uri = chameleon["rewrite_absolute_uri"]
build_ack = chameleon["build_ack"]
handle_client = chameleon["handle_client"]
get_stats = chameleon["get_stats"]
load_carrier_profile = chameleon["load_carrier_profile"]
_sanitize_header_field = chameleon["_sanitize_header_field"]
get_carrier_profile = chameleon.get("get_carrier_profile")
set_carrier_profile = chameleon.get("set_carrier_profile")
extract_carrier_hint = chameleon.get("extract_carrier_hint")
process_carrier_hint = chameleon.get("process_carrier_hint")
record_abrupt_disconnect = chameleon.get("record_abrupt_disconnect")
is_ip_jailed = chameleon.get("is_ip_jailed")
record_relay_violation = chameleon.get("record_relay_violation")
clear_jailed_ips = chameleon.get("clear_jailed_ips")
tarpit_client = chameleon.get("tarpit_client")
handle_udpgw_client = chameleon.get("handle_udpgw_client")
is_safe_udpgw_ip = chameleon.get("is_safe_udpgw_ip")
ALLOWED_LOCAL_PORTS = chameleon.get("ALLOWED_LOCAL_PORTS")
FORBIDDEN_LOCAL_PORTS = chameleon.get("FORBIDDEN_LOCAL_PORTS")
_active_tarpits_per_ip = chameleon.get("_active_tarpits_per_ip")
MAX_TARPIT_PER_IP = chameleon.get("MAX_TARPIT_PER_IP")
handle_ssl_client = chameleon.get("handle_ssl_client")
create_ssl_context = chameleon.get("create_ssl_context")


class TestChameleonParsing(unittest.TestCase):
    """Tests for payload extraction and target resolution."""

    def test_standard_connect(self):
        buf = b"CONNECT 127.0.0.1:2222 HTTP/1.1\r\nHost: bug-host.com\r\n\r\n"
        h, p, ic = parse_target(buf)
        self.assertEqual((h, p, ic), ("127.0.0.1", 2222, True))

    def test_connect_default_port_443(self):
        buf = b"CONNECT 127.0.0.1 HTTP/1.1\r\nHost: bug-host.com\r\n\r\n"
        h, p, ic = parse_target(buf)
        self.assertEqual((h, p, ic), ("127.0.0.1", 443, True))

    def test_ipv6_connect(self):
        buf = b"CONNECT [::1]:2222 HTTP/1.1\r\nHost: bug.com\r\n\r\n"
        h, p, ic = parse_target(buf)
        self.assertEqual((h, p, ic), ("::1", 2222, True))

    def test_split_carrier_payload(self):
        split_payload = (
            b"GET http://downloads.vodafone.co.uk/ HTTP/1.1\r\n"
            b"Host: downloads.vodafone.co.uk\r\n\r\n"
            b"CONNECT 127.0.0.1:8448 HTTP/1.1\r\n\r\n"
        )
        h, p, ic = parse_target(split_payload)
        self.assertEqual((h, p, ic), ("127.0.0.1", 8448, True))

    def test_delay_split_with_crlfs(self):
        payload = (
            b"\r\n\r\n"
            b"HEAD / HTTP/1.1\r\nHost: cdn.whatsapp.net\r\n\r\n"
            b"\r\n"
            b"CONNECT 127.0.0.1:18500 HTTP/1.1\r\n\r\n"
        )
        h, p, ic = parse_target(payload)
        self.assertEqual((h, p, ic), ("127.0.0.1", 18500, True))

    def test_front_inject_with_x_online_host(self):
        front_inject = (
            b"CONNECT 127.0.0.1:18500 HTTP/1.1\r\n"
            b"X-Online-Host: downloads.vodafone.co.uk\r\n"
            b"Connection: Keep-Alive\r\n\r\n"
        )
        h, p, ic = parse_target(front_inject)
        self.assertEqual((h, p, ic), ("127.0.0.1", 18500, True))

    def test_explicit_x_target_header(self):
        target_hdr = b"GET / HTTP/1.1\r\nHost: bug.com\r\nX-Target: 127.0.0.1:2222\r\n\r\n"
        h, p, ic = parse_target(target_hdr)
        self.assertEqual((h, p, ic), ("127.0.0.1", 2222, True))

    def test_explicit_x_real_host_header(self):
        hdr = b"POST / HTTP/1.1\r\nHost: bug.com\r\nX-Real-Host: 127.0.0.1:17000\r\n\r\n"
        h, p, ic = parse_target(hdr)
        self.assertEqual((h, p, ic), ("127.0.0.1", 17000, True))

    def test_absolute_http_url(self):
        web_payload = b"GET http://example.com:80/ HTTP/1.1\r\nHost: example.com\r\n\r\n"
        h, p, ic = parse_target(web_payload)
        self.assertEqual((h, p, ic), ("example.com", 80, False))

    def test_host_header_with_explicit_port(self):
        host_port_payload = b"GET / HTTP/1.1\r\nHost: 127.0.0.1:2222\r\n\r\n"
        h, p, ic = parse_target(host_port_payload)
        self.assertEqual((h, p, ic), ("127.0.0.1", 2222, True))

    def test_carrier_bug_host_defaults_to_ssh(self):
        carrier_payload = b"GET / HTTP/1.1\r\nHost: free.facebook.com\r\n\r\n"
        h, p, ic = parse_target(carrier_payload)
        self.assertEqual((h, p, ic), ("127.0.0.1", 2222, True))

    def test_connect_with_at_userinfo_and_host_port(self):
        buf1 = b"CONNECT downloads.vodafone.co.uk@[host_port] HTTP/1.1\r\n\r\n"
        self.assertEqual(parse_target(buf1), ("127.0.0.1", 2222, True))

        buf2 = b"CONNECT [host_port]@downloads.vodafone.co.uk HTTP/1.1\r\n\r\n"
        self.assertEqual(parse_target(buf2), ("127.0.0.1", 2222, True))

        buf3 = b"CONNECT [host_port] HTTP/1.1\r\n\r\n"
        self.assertEqual(parse_target(buf3), ("127.0.0.1", 2222, True))

        buf4 = b"CONNECT bughost.com@127.0.0.1:2222 HTTP/1.1\r\n\r\n"
        self.assertEqual(parse_target(buf4), ("127.0.0.1", 2222, True))

    def test_empty_buffer(self):
        self.assertEqual(parse_target(b""), (None, None, False))
        self.assertEqual(parse_target(b"RANDOM NON HTTP BYTES\x00\x01\x02"), (None, None, False))


class TestLocalTargetAndCaching(unittest.TestCase):
    """Tests for local target determination and identifier caching."""

    def test_local_loopback_variants(self):
        self.assertTrue(is_local_target("127.0.0.1", 443))
        self.assertTrue(is_local_target("localhost", 8080))
        self.assertTrue(is_local_target("::1", 2222))
        self.assertTrue(is_local_target("127.0.0.2", 1194))

    def test_dedicated_local_tunnel_ports(self):
        # Even with custom hostnames, dedicated tunnel ports are recognized as local
        self.assertTrue(is_local_target("my-vps.com", 2222))
        self.assertTrue(is_local_target("carrier-bughost.com", 8448))
        self.assertTrue(is_local_target("whatever.net", 17000))
        self.assertTrue(is_local_target("custom.host", 18500))

    def test_external_target_not_local(self):
        self.assertFalse(is_local_target("example.com", 80))
        self.assertFalse(is_local_target("1.1.1.1", 443))
        self.assertFalse(is_local_target("93.184.216.34", 80))

    def test_identifier_ttl_caching(self):
        ids1 = load_local_identifiers(ttl=10.0)
        self.assertIn("127.0.0.1", ids1)
        ids2 = load_local_identifiers(ttl=10.0)
        self.assertIs(ids1, ids2)  # Should return identical cached object


class TestAuthentication(unittest.TestCase):
    """Tests for HTTP Proxy-Authorization parsing and validation."""

    def test_parse_basic_auth_valid(self):
        token = base64.b64encode(b"alice:secret-uuid-123").decode("ascii")
        buf = f"CONNECT 1.1.1.1:443 HTTP/1.1\r\nProxy-Authorization: Basic {token}\r\n\r\n".encode("utf-8")
        creds = parse_basic_auth(buf)
        self.assertEqual(creds, ("alice", "secret-uuid-123"))

    def test_parse_basic_auth_missing(self):
        buf = b"CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n\r\n"
        self.assertIsNone(parse_basic_auth(buf))

    def test_parse_basic_auth_malformed(self):
        buf = b"CONNECT 1.1.1.1:443 HTTP/1.1\r\nProxy-Authorization: Basic not-base64!#\r\n\r\n"
        self.assertIsNone(parse_basic_auth(buf))

    def test_check_auth_against_store(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump([
                {"name": "admin", "uuid": "admin-uuid-111", "status": "active"},
                {"name": "bob", "uuid": "bob-uuid-222", "status": "active"},
                {"name": "frozen_user", "uuid": "frozen-uuid-333", "status": "frozen"},
            ], tf)
            tf.flush()
            store_path = tf.name

        old_env = os.environ.get("MUBX_USERS_FILE")
        try:
            os.environ["MUBX_USERS_FILE"] = store_path
            load_valid_users(force_refresh=True)

            # Valid user bob
            tok_bob = base64.b64encode(b"bob:bob-uuid-222").decode("ascii")
            req_bob = f"CONNECT 1.1.1.1:443 HTTP/1.1\r\nProxy-Authorization: Basic {tok_bob}\r\n\r\n".encode()
            self.assertTrue(check_auth(req_bob))

            # Invalid password
            tok_bad_pw = base64.b64encode(b"bob:wrong-pw").decode("ascii")
            req_bad_pw = f"CONNECT 1.1.1.1:443 HTTP/1.1\r\nProxy-Authorization: Basic {tok_bad_pw}\r\n\r\n".encode()
            self.assertFalse(check_auth(req_bad_pw))

            # Frozen user
            tok_frozen = base64.b64encode(b"frozen_user:frozen-uuid-333").decode("ascii")
            req_frozen = f"CONNECT 1.1.1.1:443 HTTP/1.1\r\nProxy-Authorization: Basic {tok_frozen}\r\n\r\n".encode()
            self.assertFalse(check_auth(req_frozen))

            # Unknown user
            tok_unknown = base64.b64encode(b"eve:secret").decode("ascii")
            req_unknown = f"CONNECT 1.1.1.1:443 HTTP/1.1\r\nProxy-Authorization: Basic {tok_unknown}\r\n\r\n".encode()
            self.assertFalse(check_auth(req_unknown))
        finally:
            if old_env is not None:
                os.environ["MUBX_USERS_FILE"] = old_env
            else:
                os.environ.pop("MUBX_USERS_FILE", None)
            if os.path.exists(store_path):
                os.remove(store_path)


class TestAsyncClientFlows(unittest.IsolatedAsyncioTestCase):
    """End-to-end async tests of handle_client: open-relay rejection and metrics."""

    async def test_unauthenticated_external_relay_rejected_with_407(self):
        # Mock connection where client asks for external IP 1.1.1.1 without auth
        reader = asyncio.StreamReader()
        writer_transport = asyncio.StreamWriter(
            transport=mock.MagicMock(),
            protocol=asyncio.StreamReaderProtocol(reader),
            reader=reader,
            loop=asyncio.get_running_loop()
        )
        written_data = bytearray()

        def fake_write(data):
            written_data.extend(data)

        writer_transport.write = fake_write
        writer_transport.get_extra_info = lambda info: ("192.168.1.50", 54321) if info == "peername" else None

        # Feed request
        reader.feed_data(b"CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n\r\n")
        reader.feed_eof()

        await handle_client(reader, writer_transport)
        self.assertIn(b"407 Proxy Authentication Required", written_data)
        self.assertIn(b"Proxy-Authenticate: Basic", written_data)

    async def test_loopback_stats_endpoint(self):
        reader = asyncio.StreamReader()
        writer_transport = asyncio.StreamWriter(
            transport=mock.MagicMock(),
            protocol=asyncio.StreamReaderProtocol(reader),
            reader=reader,
            loop=asyncio.get_running_loop()
        )
        written_data = bytearray()
        writer_transport.write = lambda d: written_data.extend(d)
        writer_transport.get_extra_info = lambda info: ("127.0.0.1", 45678) if info == "peername" else None

        reader.feed_data(b"GET /stats HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        reader.feed_eof()

        await handle_client(reader, writer_transport)
        self.assertIn(b"HTTP/1.1 200 OK", written_data)
        self.assertIn(b"application/json", written_data)
        body = written_data.split(b"\r\n\r\n", 1)[1]
        data = json.loads(body.decode("utf-8"))
        self.assertIn("connections_accepted", data)
        self.assertIn("uptime_seconds", data)

    async def test_handshake_with_profile_header(self):
        # Test connecting to an allowed local tunnel with X-MUBX-Profile: ok200
        # Start a local echo server to act as target on loopback
        target_received = bytearray()
        async def echo_server(r, w):
            d = await r.read(1024)
            target_received.extend(d)
            w.write(b"target-ack")
            await w.drain()
            w.close()

        server = await asyncio.start_server(echo_server, "127.0.0.1", 0)
        target_port = server.sockets[0].getsockname()[1]

        # Temporarily allow target_port in LOCAL_TUNNEL_PORTS and ALLOWED_LOCAL_PORTS
        chameleon["LOCAL_TUNNEL_PORTS"].add(target_port)
        chameleon["ALLOWED_LOCAL_PORTS"].add(target_port)

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            written_data = bytearray()
            writer_transport.write = lambda d: written_data.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 55555) if info == "peername" else None

            # Send CONNECT with X-MUBX-Profile: ok200
            req = f"CONNECT 127.0.0.1:{target_port} HTTP/1.1\r\nHost: bug.com\r\nX-MUBX-Profile: ok200\r\n\r\n".encode()
            reader.feed_data(req)
            reader.feed_eof()

            await handle_client(reader, writer_transport)
            self.assertIn(b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n", written_data)
        finally:
            server.close()
            await server.wait_closed()
            chameleon["LOCAL_TUNNEL_PORTS"].discard(target_port)
            chameleon["ALLOWED_LOCAL_PORTS"].discard(target_port)

    async def test_client_abrupt_disconnect_handled_gracefully(self):
        """Simulates remote client closing connection abruptly (ConnectionResetError during drain)."""
        reader = asyncio.StreamReader()
        writer_transport = asyncio.StreamWriter(
            transport=mock.MagicMock(),
            protocol=asyncio.StreamReaderProtocol(reader),
            reader=reader,
            loop=asyncio.get_running_loop()
        )
        writer_transport.get_extra_info = lambda info: ("45.92.29.68", 41714) if info == "peername" else None
        
        async def mock_drain():
            raise ConnectionResetError(104, "Connection reset by peer")
        
        writer_transport.drain = mock_drain

        # Send unauthenticated relay attempt which normally triggers 407 + drain
        reader.feed_data(b"CONNECT 114.66.18.180:10015 HTTP/1.1\r\nHost: 114.66.18.180:10015\r\n\r\n")
        reader.feed_eof()

        # Must not raise ConnectionResetError or unhandled exception
        await handle_client(reader, writer_transport)
        # Verify active connection counter is safely 0
        self.assertEqual(chameleon["_active_connections"], 0)

    async def test_loopback_port_22_fallback_to_dropbear_2222(self):
        """When client targets 127.0.0.1:22 and port 22 is unavailable, fallback to DEFAULT_PORT."""
        target_received = bytearray()
        async def mock_dropbear(r, w):
            d = await r.read(1024)
            target_received.extend(d)
            w.close()

        server = await asyncio.start_server(mock_dropbear, "127.0.0.1", 0)
        dropbear_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_default_port = globals_dict["DEFAULT_PORT"]
        globals_dict["DEFAULT_PORT"] = dropbear_port
        globals_dict["LOCAL_TUNNEL_PORTS"].add(dropbear_port)
        globals_dict["ALLOWED_LOCAL_PORTS"].add(dropbear_port)

        reader = asyncio.StreamReader()
        writer_transport = asyncio.StreamWriter(
            transport=mock.MagicMock(),
            protocol=asyncio.StreamReaderProtocol(reader),
            reader=reader,
            loop=asyncio.get_running_loop()
        )
        written_data = bytearray()
        writer_transport.write = lambda d: written_data.extend(d)
        writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54321) if info == "peername" else None

        # Point port 22 connection attempt at loopback
        orig_open_conn = asyncio.open_connection
        async def mock_open_conn(host, port, **kwargs):
            if port == 22:
                raise ConnectionRefusedError(111, "Connection refused")
            return await orig_open_conn(host, port, **kwargs)

        with mock.patch("asyncio.open_connection", side_effect=mock_open_conn):
            reader.feed_data(b"CONNECT 127.0.0.1:22 HTTP/1.1\r\n\r\n")
            reader.feed_eof()
            try:
                await handle_client(reader, writer_transport)
                self.assertIn(b"200 Connection established", written_data)
            finally:
                server.close()
                await server.wait_closed()
                globals_dict["DEFAULT_PORT"] = orig_default_port
                globals_dict["LOCAL_TUNNEL_PORTS"].discard(dropbear_port)
                globals_dict["ALLOWED_LOCAL_PORTS"].discard(dropbear_port)


class TestResponseProfiles(unittest.TestCase):
    """Tests for Chameleon response profile engine."""

    def test_default_profiles_present(self):
        profiles = load_response_profiles()
        for name in (
            "default", "ws", "legacy10", "ok200", "redirect",
            "302_spoof", "204_nocontent", "206_partial", "chunked_evasion",
            "custom_carrier_a",
        ):
            self.assertIn(name, profiles)

    def test_render_response_default(self):
        raw = render_response(DEFAULT_PROFILES["default"])
        self.assertEqual(raw, b"HTTP/1.1 200 Connection established\r\n\r\n")

    def test_render_response_ws(self):
        raw = render_response(DEFAULT_PROFILES["ws"])
        self.assertIn(b"HTTP/1.1 101 Switching Protocols", raw)
        self.assertIn(b"Upgrade: websocket\r\n", raw)
        self.assertIn(b"Connection: Upgrade\r\n", raw)

    def test_render_response_302_spoof(self):
        raw = render_response(DEFAULT_PROFILES["302_spoof"])
        self.assertIn(b"HTTP/1.1 302 Found\r\n", raw)
        self.assertIn(b"Location: http://example.com/\r\n", raw)
        self.assertIn(b"Connection: Keep-Alive\r\n", raw)
        self.assertIn(b"Content-Length: 0\r\n", raw)

    def test_render_response_204_nocontent(self):
        raw = render_response(DEFAULT_PROFILES["204_nocontent"])
        self.assertIn(b"HTTP/1.1 204 No Content\r\n", raw)
        self.assertIn(b"Connection: Keep-Alive\r\n", raw)

    def test_render_response_206_partial(self):
        raw = render_response(DEFAULT_PROFILES["206_partial"])
        self.assertIn(b"HTTP/1.1 206 Partial Content\r\n", raw)
        self.assertIn(b"Content-Range: bytes 0-0/1\r\n", raw)

    def test_render_response_chunked_evasion(self):
        raw = render_response(DEFAULT_PROFILES["chunked_evasion"])
        self.assertIn(b"HTTP/1.1 200 Connection established\r\n", raw)
        self.assertIn(b"Transfer-Encoding: chunked\r\n", raw)

    def test_render_response_custom(self):
        custom = {
            "status": "HTTP/1.1 007 Custom Carrier",
            "headers": {"X-Note": "test-carrier-header", "Server": "MUB-X"},
        }
        raw = render_response(custom)
        self.assertTrue(raw.startswith(b"HTTP/1.1 007 Custom Carrier\r\n"))
        self.assertIn(b"X-Note: test-carrier-header\r\n", raw)
        self.assertIn(b"Server: MUB-X\r\n", raw)
        self.assertTrue(raw.endswith(b"\r\n\r\n"))

    def test_load_custom_response_file(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as tf:
            json.dump({
                "profile_xyz": {
                    "status": "HTTP/1.1 204 No Content",
                    "headers": {"X-Special": "xyz"}
                }
            }, tf)
            tf.flush()
            temp_path = tf.name

        old_env = os.environ.get("CHAMELEON_RESPONSES_FILE")
        try:
            os.environ["CHAMELEON_RESPONSES_FILE"] = temp_path
            chameleon["_responses_cache"]["ts"] = 0.0  # invalidate cache
            loaded = load_response_profiles()
            self.assertIn("profile_xyz", loaded)
            self.assertEqual(loaded["profile_xyz"]["status"], "HTTP/1.1 204 No Content")
            # Defaults should still be preserved
            self.assertIn("default", loaded)
        finally:
            if old_env is not None:
                os.environ["CHAMELEON_RESPONSES_FILE"] = old_env
            else:
                os.environ.pop("CHAMELEON_RESPONSES_FILE", None)
            chameleon["_responses_cache"]["ts"] = 0.0
            if os.path.exists(temp_path):
                os.remove(temp_path)


class TestProtocolSniffingAndRewriting(unittest.TestCase):
    """Tests for protocol sniffing and HTTP request line rewriting."""

    def test_sniff_tls(self):
        # TLS handshake: 0x16 0x03 0x01 (TLS 1.0 ClientHello record)
        tls_record = b"\x16\x03\x01\x00\xa5\x01\x00\x00\xa1\x03\x03"
        self.assertEqual(sniff_protocol(tls_record), "tls")

    def test_sniff_ssh(self):
        ssh_banner = b"SSH-2.0-OpenSSH_8.9p1 Ubuntu-3ubuntu0.1\r\n"
        self.assertEqual(sniff_protocol(ssh_banner), "ssh")

    def test_sniff_tbrutal(self):
        # T-Brutal protocol magic: 0x54 0x42 0x01 (TB\x01) or TBRU
        self.assertEqual(sniff_protocol(b"TB\x01\x05\x00\x00\x00\x00\x00\x00"), "tbrutal")
        self.assertEqual(sniff_protocol(b"TBRU\x01\x00\x00\x00"), "tbrutal")

    def test_sniff_http_connect(self):
        req = b"CONNECT 127.0.0.1:2222 HTTP/1.1\r\nHost: bug.com\r\n\r\n"
        self.assertEqual(sniff_protocol(req), "http")

    def test_sniff_http_methods(self):
        for method in (b"GET / HTTP/1.1\r\n\r\n", b"POST /api HTTP/1.1\r\n\r\n", b"HEAD / HTTP/1.0\r\n\r\n"):
            self.assertEqual(sniff_protocol(method), "http")

    def test_sniff_unknown(self):
        self.assertEqual(sniff_protocol(b"\x00\x01\x02\x03"), "unknown")
        self.assertEqual(sniff_protocol(b"RANDOM_BYTES"), "unknown")

    def test_rewrite_absolute_uri(self):
        req = b"GET http://downloads.carrier.com:80/file.bin HTTP/1.1\r\nHost: downloads.carrier.com\r\n\r\n"
        rewritten = rewrite_absolute_uri(req)
        self.assertTrue(rewritten.startswith(b"GET /file.bin HTTP/1.1\r\n"))
        self.assertIn(b"Host: downloads.carrier.com\r\n", rewritten)

    def test_rewrite_origin_form_unchanged(self):
        req = b"GET /index.html HTTP/1.1\r\nHost: example.com\r\n\r\n"
        self.assertEqual(rewrite_absolute_uri(req), req)

    def test_build_ack(self):
        if set_carrier_profile:
            set_carrier_profile(None)
        # Default
        ack_def = build_ack(None, is_ws=False)
        self.assertEqual(ack_def, b"HTTP/1.1 200 Connection established\r\n\r\n")

        # WS auto
        ack_ws = build_ack(None, is_ws=True)
        self.assertIn(b"HTTP/1.1 101 Switching Protocols", ack_ws)
        self.assertIn(b"Upgrade: websocket", ack_ws)

        # Explicit profile
        ack_legacy = build_ack("legacy10", is_ws=False)
        self.assertEqual(ack_legacy, b"HTTP/1.0 200 Connection established\r\n\r\n")

        ack_ok = build_ack("ok200", is_ws=False)
        self.assertIn(b"HTTP/1.1 200 OK", ack_ok)
        self.assertIn(b"Content-Length: 0", ack_ok)

        ack_spoof = build_ack("302_spoof", is_ws=False)
        self.assertIn(b"HTTP/1.1 302 Found", ack_spoof)
        self.assertIn(b"Location: http://example.com/", ack_spoof)

        ack_204 = build_ack("204_nocontent", is_ws=False)
        self.assertIn(b"HTTP/1.1 204 No Content", ack_204)


class TestCarrierAutoAdapt(unittest.TestCase):
    """Tests for the probe→chameleon auto-adapt feedback loop."""

    def test_carrier_profile_auto_selected_when_no_explicit_header(self):
        """When no X-MUBX-Profile header is present, _CARRIER_PROFILE should
        be used so the probe result drives the evasion strategy automatically."""
        original = get_carrier_profile() if get_carrier_profile else build_ack.__globals__.get("_CARRIER_PROFILE")
        try:
            # Simulate probe having written '204_nocontent'
            if set_carrier_profile:
                set_carrier_profile("204_nocontent")
            build_ack.__globals__["_CARRIER_PROFILE"] = "204_nocontent"
            ack = build_ack(None, is_ws=False)
            self.assertIn(b"HTTP/1.1 204 No Content", ack,
                          "build_ack should use _CARRIER_PROFILE when no explicit header")
        finally:
            if set_carrier_profile:
                set_carrier_profile(original)
            build_ack.__globals__["_CARRIER_PROFILE"] = original

    def test_explicit_header_overrides_carrier_profile(self):
        """An explicit X-MUBX-Profile from the client payload must always win
        over the auto-selected carrier profile."""
        original = get_carrier_profile() if get_carrier_profile else build_ack.__globals__.get("_CARRIER_PROFILE")
        try:
            if set_carrier_profile:
                set_carrier_profile("204_nocontent")  # probe says anti-302
            build_ack.__globals__["_CARRIER_PROFILE"] = "204_nocontent"
            # Client explicitly requests 302_spoof
            ack = build_ack("302_spoof", is_ws=False)
            self.assertIn(b"HTTP/1.1 302 Found", ack,
                          "Explicit profile header must override _CARRIER_PROFILE")
        finally:
            if set_carrier_profile:
                set_carrier_profile(original)
            build_ack.__globals__["_CARRIER_PROFILE"] = original

    def test_load_carrier_profile_ignores_unknown_profile_name(self):
        """load_carrier_profile must reject a profile name not in the profile
        table so a malformed or tampered carrier-profile file can't inject
        arbitrary strings into build_ack."""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False) as f:
            json.dump({"profile": "totally_fake_profile", "ts": 9999999999}, f)
            fname = f.name
        try:
            with mock.patch.dict(os.environ, {"CHAMELEON_CARRIER_PROFILE": fname}):
                # Force the function to use our temp file path
                result = load_carrier_profile()
            self.assertIsNone(result,
                              "Unknown profile names must be rejected by load_carrier_profile")
        finally:
            os.unlink(fname)

    def test_render_response_strips_crlf_from_status_and_headers(self):
        """CRLF characters embedded in status line or header values must be
        stripped to prevent HTTP response splitting attacks."""
        evil_profile = {
            "status": "HTTP/1.1 200 OK\r\nX-Injected: evil",
            "headers": {"X-Header": "value\r\nX-Extra: injected"},
        }
        rendered = render_response(evil_profile)
        self.assertNotIn(b"X-Injected", rendered,
                         "CRLF-injected header in status line must be stripped")
        self.assertNotIn(b"X-Extra", rendered,
                         "CRLF-injected header in header value must be stripped")
        self.assertIn(b"HTTP/1.1 200 OK", rendered)


class TestStatsEndpointSecurity(unittest.IsolatedAsyncioTestCase):
    """Tests for the loopback-only restriction on /stats and /metrics."""

    async def _call_stats(self, peer_ip: str, path: str = "GET /stats HTTP/1.1\r\n\r\n") -> bytes:
        """Helper: feeds a stats request through _handle_client_inner with a
        given peer IP and returns the complete response bytes."""
        _handle_client_inner = chameleon["_handle_client_inner"]
        data = path.encode() if isinstance(path, str) else path
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        out = bytearray()

        class _FakeWriter:
            def __init__(self):
                self.closed = False
            def get_extra_info(self, key, default=None):
                return None
            def write(self, d):
                out.extend(d)
            async def drain(self): pass
            def close(self): self.closed = True
            async def wait_closed(self): pass

        writer = _FakeWriter()
        await _handle_client_inner(reader, writer, (peer_ip, 12345))
        return bytes(out)

    async def test_stats_blocked_from_public_ip(self):
        resp = await self._call_stats("1.2.3.4")
        self.assertIn(b"403", resp,
                      "/stats must return 403 to non-loopback IPs")
        self.assertNotIn(b"connections_accepted", resp,
                         "Metrics must not be revealed to public IPs")

    async def test_stats_allowed_from_loopback(self):
        resp = await self._call_stats("127.0.0.1")
        self.assertIn(b"200", resp,
                      "/stats must return 200 to loopback callers")
        self.assertIn(b"connections_accepted", resp)

    async def test_metrics_blocked_from_public_ip(self):
        resp = await self._call_stats("203.0.113.99", "GET /metrics HTTP/1.1\r\n\r\n")
        self.assertIn(b"403", resp)


class TestInBandCarrierHintsAndAnomalies(unittest.TestCase):
    """Tests for in-band client feedback headers and passive RST anomaly detection."""

    def setUp(self):
        self.original_profile = get_carrier_profile()
        set_carrier_profile("default")
        chameleon["_abrupt_disconnects"].clear()

    def tearDown(self):
        set_carrier_profile(self.original_profile)
        chameleon["_abrupt_disconnects"].clear()

    def test_extract_carrier_hint(self):
        buf1 = b"CONNECT 127.0.0.1:2222 HTTP/1.1\r\nX-MUBX-Carrier-Hint: 302_seen\r\n\r\n"
        self.assertEqual(extract_carrier_hint(buf1), "302_seen")

        buf2 = b"CONNECT 127.0.0.1:2222 HTTP/1.1\r\nx-mubx-carrier-hint: 410_seen\r\n\r\n"
        self.assertEqual(extract_carrier_hint(buf2), "410_seen")

        buf3 = b"CONNECT 127.0.0.1:2222 HTTP/1.1\r\nHost: bug.com\r\n\r\n"
        self.assertIsNone(extract_carrier_hint(buf3))

    def test_process_carrier_hint_302(self):
        res = process_carrier_hint("302_seen", "198.51.100.1")
        self.assertEqual(res, "204_nocontent")
        self.assertEqual(get_carrier_profile(), "204_nocontent")

    def test_process_carrier_hint_reset(self):
        res = process_carrier_hint("reset", "198.51.100.2")
        self.assertEqual(res, "chunked_evasion")
        self.assertEqual(get_carrier_profile(), "chunked_evasion")

    def test_passive_abrupt_disconnect_anomaly_trigger(self):
        # 1st disconnect: under threshold (threshold is 3)
        triggered1 = record_abrupt_disconnect("198.51.100.10")
        self.assertFalse(triggered1)
        self.assertEqual(get_carrier_profile(), "default")

        # 2nd disconnect: under threshold
        triggered2 = record_abrupt_disconnect("198.51.100.11")
        self.assertFalse(triggered2)

        # 3rd disconnect: triggers anomaly detection & auto-adaptation
        triggered3 = record_abrupt_disconnect("198.51.100.12")
        self.assertTrue(triggered3)
        self.assertEqual(get_carrier_profile(), "204_nocontent")
        self.assertGreater(chameleon["STATS"]["middlebox_anomalies_detected"], 0)


class TestReportEndpoint(unittest.IsolatedAsyncioTestCase):
    """Tests for the client-side /report telemetry endpoint."""

    async def _call_endpoint(self, peer_ip: str, req: str) -> bytes:
        _handle_client_inner = chameleon["_handle_client_inner"]
        data = req.encode() if isinstance(req, str) else req
        reader = asyncio.StreamReader()
        reader.feed_data(data)
        reader.feed_eof()
        out = bytearray()

        class _FakeWriter:
            def __init__(self):
                self.closed = False
            def get_extra_info(self, key, default=None):
                return None
            def write(self, d):
                out.extend(d)
            async def drain(self): pass
            def close(self): self.closed = True
            async def wait_closed(self): pass

        writer = _FakeWriter()
        await _handle_client_inner(reader, writer, (peer_ip, 12345))
        return bytes(out)

    async def test_report_unauthorized_rejected(self):
        # Non-loopback caller without valid token gets 401
        resp = await self._call_endpoint("203.0.113.5", "GET /report?hint=302_seen HTTP/1.1\r\n\r\n")
        self.assertIn(b"401 Unauthorized", resp)

    async def test_report_from_loopback_allowed(self):
        resp = await self._call_endpoint("127.0.0.1", "GET /report?hint=302_seen HTTP/1.1\r\n\r\n")
        self.assertIn(b"200 OK", resp)
        self.assertIn(b"\"status\": \"ok\"", resp)

    async def test_report_with_valid_token_allowed(self):
        _handle_client_inner = chameleon["_handle_client_inner"]
        orig_tokens = _handle_client_inner.__globals__.get("_AUTH_TOKENS", set())
        test_tok = "test-token-telemetry-12345"
        _handle_client_inner.__globals__["_AUTH_TOKENS"] = {test_tok}
        try:
            resp = await self._call_endpoint(
                "203.0.113.10",
                f"GET /report?hint=302_seen&token={test_tok} HTTP/1.1\r\n\r\n"
            )
            self.assertIn(b"200 OK", resp)
            self.assertIn(b"\"status\": \"ok\"", resp)
        finally:
            _handle_client_inner.__globals__["_AUTH_TOKENS"] = orig_tokens


class TestTarpitAndAutoJail(unittest.IsolatedAsyncioTestCase):
    """Tests for scanner tarpitting and automatic IP jailing."""

    def setUp(self):
        clear_jailed_ips()

    def tearDown(self):
        clear_jailed_ips()

    def test_relay_violations_trigger_autojail(self):
        test_ip = "198.51.100.77"
        self.assertFalse(is_ip_jailed(test_ip))
        # Send 4 violations - below default threshold of 5
        for _ in range(4):
            newly_jailed = record_relay_violation(test_ip)
            self.assertFalse(newly_jailed)
        self.assertFalse(is_ip_jailed(test_ip))

        # 5th violation crosses threshold
        newly_jailed = record_relay_violation(test_ip)
        self.assertTrue(newly_jailed)
        self.assertTrue(is_ip_jailed(test_ip))

        # Loopback IPs never get jailed
        for _ in range(10):
            self.assertFalse(record_relay_violation("127.0.0.1"))
        self.assertFalse(is_ip_jailed("127.0.0.1"))

    async def test_jailed_ip_tarpitted_on_connect(self):
        test_ip = "198.51.100.88"
        _globals = chameleon["handle_client"].__globals__
        orig_delay = _globals["CHAMELEON_TARPIT_DELAY"]
        _globals["CHAMELEON_TARPIT_DELAY"] = 0.05  # fast for testing

        try:
            # Mark as jailed
            for _ in range(5):
                record_relay_violation(test_ip)
            self.assertTrue(is_ip_jailed(test_ip))

            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            written_data = bytearray()
            writer_transport.write = lambda d: written_data.extend(d)
            writer_transport.get_extra_info = lambda info: (test_ip, 45678) if info == "peername" else None

            tarpits_before = _globals["STATS"]["tarpitted_connections"]
            await handle_client(reader, writer_transport)
            self.assertGreater(_globals["STATS"]["tarpitted_connections"], tarpits_before)
            self.assertIn(b"407 Proxy Authentication Required", written_data)
        finally:
            _globals["CHAMELEON_TARPIT_DELAY"] = orig_delay


class TestUDPGWBridge(unittest.IsolatedAsyncioTestCase):
    """Tests for BadVPN UDPGW binary framing and forwarding."""

    async def test_udpgw_ipv4_forwarding(self):
        # Start a local UDP echo server
        received_udp = bytearray()
        loop = asyncio.get_running_loop()

        class EchoServerProtocol(asyncio.DatagramProtocol):
            def __init__(self):
                self.transport = None
            def connection_made(self, transport):
                self.transport = transport
            def datagram_received(self, data, addr):
                received_udp.extend(data)
                # Echo packet back with prefix
                self.transport.sendto(b"ECHO:" + data, addr)

        udp_transport, _ = await loop.create_datagram_endpoint(
            EchoServerProtocol, local_addr=("127.0.0.1", 0)
        )
        udp_port = udp_transport.get_extra_info("sockname")[1]

        reader = asyncio.StreamReader()
        writer_transport = asyncio.StreamWriter(
            transport=mock.MagicMock(),
            protocol=asyncio.StreamReaderProtocol(reader),
            reader=reader,
            loop=loop
        )
        written_tcp = bytearray()
        writer_transport.write = lambda d: written_tcp.extend(d)
        writer_transport.get_extra_info = lambda info: ("127.0.0.1", 55555) if info == "peername" else None

        # Craft UDPGW frame:
        # flags(1B=0x00 IPv4) + con_id(2B) + ip(4B) + port(2B) + payload
        con_id = b"\x00\x2a"
        dest_ip_bytes = socket.inet_aton("127.0.0.1")
        dest_port_bytes = udp_port.to_bytes(2, "big")
        payload = b"GAME_TEST_PACKET_123"
        pkt_body = b"\x00" + con_id + dest_ip_bytes + dest_port_bytes + payload
        frame = len(pkt_body).to_bytes(2, "big") + pkt_body

        # Feed frame into reader
        reader.feed_data(frame)

        # Allow loopback destination for this local unit test
        os.environ["CHAMELEON_UDPGW_ALLOW_LOOPBACK"] = "1"

        try:
            udpgw_task = asyncio.create_task(handle_udpgw_client(reader, writer_transport))
            await asyncio.sleep(0.15)
            self.assertEqual(bytes(received_udp), payload)

            # Check that client TCP writer received UDPGW framed reply:
            # [2B len][1B flags][2B con_id][ECHO:GAME_TEST_PACKET_123]
            self.assertIn(b"ECHO:GAME_TEST_PACKET_123", written_tcp)
            self.assertIn(con_id, written_tcp)
            reader.feed_eof()
            udpgw_task.cancel()
            try:
                await udpgw_task
            except asyncio.CancelledError:
                pass
        finally:
            os.environ.pop("CHAMELEON_UDPGW_ALLOW_LOOPBACK", None)
            udp_transport.close()

    def test_udpgw_ssrf_safety(self):
        """Verifies SSRF prevention blocks cloud metadata, loopback, and multicast."""
        os.environ.pop("CHAMELEON_UDPGW_ALLOW_LOOPBACK", None)
        self.assertFalse(is_safe_udpgw_ip("127.0.0.1"))
        self.assertFalse(is_safe_udpgw_ip("169.254.169.254"))  # AWS / GCP / Cloud metadata
        self.assertFalse(is_safe_udpgw_ip("224.0.0.1"))        # Multicast
        self.assertFalse(is_safe_udpgw_ip("0.0.0.0"))          # Unspecified
        self.assertFalse(is_safe_udpgw_ip("invalid_ip"))       # Malformed
        self.assertTrue(is_safe_udpgw_ip("8.8.8.8"))           # Valid public IP
        self.assertTrue(is_safe_udpgw_ip("1.1.1.1"))           # Valid public IP


class TestSecurityHardening(unittest.IsolatedAsyncioTestCase):
    """Tests for multi-tenant and VPS security hardening."""

    def test_ports_policy(self):
        """Verifies forbidden internal ports and UDPGW exclusion from unauthenticated proxying."""
        self.assertIn(18088, FORBIDDEN_LOCAL_PORTS)
        self.assertNotIn(7300, ALLOWED_LOCAL_PORTS)
        self.assertIn(2222, ALLOWED_LOCAL_PORTS)
        self.assertIn(8448, ALLOWED_LOCAL_PORTS)

    async def test_tarpit_throttling_per_ip(self):
        """Verifies excess connections from a single IP bypass sleep and close immediately."""
        fake_writer = mock.MagicMock()
        fake_writer.wait_closed = mock.AsyncMock()

        test_ip = "198.51.100.99"
        _active_tarpits_per_ip[test_ip] = MAX_TARPIT_PER_IP

        t0 = time.time()
        await tarpit_client(fake_writer, test_ip, "test_overflow")
        elapsed = time.time() - t0

        # Should return almost instantaneously (< 0.1s) without triggering CHAMELEON_TARPIT_DELAY (10s)
        self.assertLess(elapsed, 0.5)
        fake_writer.close.assert_called()
        _active_tarpits_per_ip.pop(test_ip, None)

    async def test_ssh_carrier_split_decoy_filtered(self):
        """Verifies that when an injector sends a carrier split decoy (e.g. GET http://bug...)
        after CONNECT to an SSH port, the HTTP decoy is swallowed and Dropbear only receives
        the legitimate SSH identification banner (SSH-2.0-...)."""
        received_by_dropbear = bytearray()
        async def mock_dropbear(r, w):
            # Dropbear sends its banner
            w.write(b"SSH-2.0-dropbear_2020.81\r\n")
            await w.drain()
            d = await r.read(1024)
            received_by_dropbear.extend(d)
            w.close()

        server = await asyncio.start_server(mock_dropbear, "127.0.0.1", 0)
        ssh_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_default_port = globals_dict["DEFAULT_PORT"]
        globals_dict["DEFAULT_PORT"] = ssh_port
        chameleon["LOCAL_TUNNEL_PORTS"].add(ssh_port)
        chameleon["ALLOWED_LOCAL_PORTS"].add(ssh_port)

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            client_written = bytearray()
            writer_transport.write = lambda d: client_written.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54321) if info == "peername" else None

            # Feed CONNECT payload with carrier split HTTP GET decoy in leftover
            split_payload = (
                f"CONNECT 127.0.0.1:{ssh_port} HTTP/1.1\r\n"
                f"Host: filter.ncnd.jazz.com.pk/nc\r\n"
                f"Connection: Keep-Alive\r\n\r\n"
                f"GET http://filter.ncnd.jazz.com.pk/nc HTTP/1.1\r\n"
                f"Host: filter.ncnd.jazz.com.pk/nc\r\n\r\n"
            ).encode()
            reader.feed_data(split_payload)

            # Schedule client sending real SSH banner shortly after
            async def feed_ssh_client():
                await asyncio.sleep(0.05)
                reader.feed_data(b"SSH-2.0-JSCH-0.1.55\r\n")
                reader.feed_eof()

            asyncio.create_task(feed_ssh_client())

            await handle_client(reader, writer_transport)

            # Dropbear MUST receive SSH-2.0-JSCH... and MUST NOT receive GET http://...
            self.assertTrue(received_by_dropbear.startswith(b"SSH-2.0-JSCH"),
                            f"Dropbear must receive SSH identification banner first, got: {bytes(received_by_dropbear)}")
            self.assertNotIn(b"GET http://", bytes(received_by_dropbear),
                             "HTTP carrier split decoy must not be forwarded to Dropbear")
        finally:
            server.close()
            await server.wait_closed()
            globals_dict["DEFAULT_PORT"] = orig_default_port
            chameleon["LOCAL_TUNNEL_PORTS"].discard(ssh_port)

class TestChameleonSSLMultiplexer(unittest.IsolatedAsyncioTestCase):
    """Tests for Chameleon's SSL/TLS termination and multiplexing (SSH SSL SNI, Payload Proxy, Decrypted Web)."""

    def setUp(self):
        if set_carrier_profile:
            self.orig_p = get_carrier_profile()
            set_carrier_profile(None)

    def tearDown(self):
        if set_carrier_profile:
            set_carrier_profile(getattr(self, "orig_p", None))

    def test_create_ssl_context_self_signed_fallback(self):
        """Verifies create_ssl_context returns a valid SSLContext even without Let's Encrypt."""
        ctx = create_ssl_context()
        self.assertIsNotNone(ctx)

    async def test_ssl_direct_ssh_stunnel(self):
        """When client connects inside TLS with raw SSH banner (Stunnel / SSH Direct SNI),
        it routes directly to Dropbear SSH without expecting HTTP headers."""
        received_by_dropbear = bytearray()

        async def dropbear_server_handler(d_reader, d_writer):
            d_writer.write(b"SSH-2.0-dropbear_2022.82\r\n")
            await d_writer.drain()
            while True:
                chunk = await d_reader.read(4096)
                if not chunk:
                    break
                received_by_dropbear.extend(chunk)
            d_writer.close()
            await d_writer.wait_closed()

        server = await asyncio.start_server(dropbear_server_handler, "127.0.0.1", 0)
        ssh_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_default_port = globals_dict["DEFAULT_PORT"]
        globals_dict["DEFAULT_PORT"] = ssh_port

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            client_written = bytearray()
            writer_transport.write = lambda d: client_written.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54321) if info == "peername" else None

            # Feed SSH identification string
            reader.feed_data(b"SSH-2.0-TestStunnel\r\n")

            async def feed_eof_later():
                await asyncio.sleep(0.08)
                reader.feed_eof()

            asyncio.create_task(feed_eof_later())

            await handle_ssl_client(reader, writer_transport)

            # Dropbear should receive the raw SSH banner
            self.assertTrue(received_by_dropbear.startswith(b"SSH-2.0-TestStunnel"))
            # Client should receive Dropbear's banner
            self.assertIn(b"SSH-2.0-dropbear", bytes(client_written))
        finally:
            server.close()
            await server.wait_closed()
            globals_dict["DEFAULT_PORT"] = orig_default_port

    async def test_ssl_payload_proxy_ssh(self):
        """When client connects inside TLS with HTTP CONNECT + split carrier payload,
        Chameleon sends 200 Connection Established and filters split decoys from Dropbear."""
        received_by_dropbear = bytearray()

        async def dropbear_server_handler(d_reader, d_writer):
            d_writer.write(b"SSH-2.0-dropbear_2022.82\r\n")
            await d_writer.drain()
            while True:
                chunk = await d_reader.read(4096)
                if not chunk:
                    break
                received_by_dropbear.extend(chunk)
            d_writer.close()
            await d_writer.wait_closed()

        server = await asyncio.start_server(dropbear_server_handler, "127.0.0.1", 0)
        ssh_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_default_port = globals_dict["DEFAULT_PORT"]
        globals_dict["DEFAULT_PORT"] = ssh_port
        chameleon["LOCAL_TUNNEL_PORTS"].add(ssh_port)
        chameleon["ALLOWED_LOCAL_PORTS"].add(ssh_port)

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            client_written = bytearray()
            writer_transport.write = lambda d: client_written.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54322) if info == "peername" else None

            # Feed SSL proxy payload with carrier split decoy
            payload = (
                f"CONNECT 127.0.0.1:{ssh_port} HTTP/1.1\r\n"
                f"Host: portal.ncnd.jazz.com.pk\r\n"
                f"Connection: Keep-Alive\r\n\r\n"
                f"GET http://filter.ncnd.jazz.com.pk/nc HTTP/1.1\r\n"
                f"Host: filter.ncnd.jazz.com.pk\r\n\r\n"
            ).encode()
            reader.feed_data(payload)

            async def feed_ssh():
                await asyncio.sleep(0.05)
                reader.feed_data(b"SSH-2.0-InjectorSSH\r\n")
                await asyncio.sleep(0.08)
                reader.feed_eof()

            asyncio.create_task(feed_ssh())

            await handle_ssl_client(reader, writer_transport)

            # Client must receive 200 Connection Established (or profile ACK)
            self.assertIn(b"200 ", bytes(client_written))
            # Dropbear must receive the real SSH banner and NOT the split HTTP decoy
            self.assertTrue(received_by_dropbear.startswith(b"SSH-2.0-InjectorSSH"))
            self.assertNotIn(b"GET http://", bytes(received_by_dropbear))
        finally:
            server.close()
            await server.wait_closed()
            globals_dict["DEFAULT_PORT"] = orig_default_port
            chameleon["LOCAL_TUNNEL_PORTS"].discard(ssh_port)
            chameleon["ALLOWED_LOCAL_PORTS"].discard(ssh_port)

    async def test_ssl_web_forwarded_to_nginx(self):
        """When client connects inside TLS with standard HTTP GET (Web or WebSocket),
        Chameleon proxies the decrypted HTTP stream to local Nginx port."""
        received_by_nginx = bytearray()

        async def nginx_mock_handler(n_reader, n_writer):
            chunk = await n_reader.read(4096)
            received_by_nginx.extend(chunk)
            n_writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
            await n_writer.drain()
            n_writer.close()
            await n_writer.wait_closed()

        server = await asyncio.start_server(nginx_mock_handler, "127.0.0.1", 0)
        nginx_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_nginx_port = globals_dict["CHAMELEON_NGINX_PORT"]
        globals_dict["CHAMELEON_NGINX_PORT"] = nginx_port

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            client_written = bytearray()
            writer_transport.write = lambda d: client_written.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54323) if info == "peername" else None

            reader.feed_data(b"GET / HTTP/1.1\r\nHost: portal.ncnd.jazz.com.pk\r\n\r\n")

            async def feed_eof_later():
                await asyncio.sleep(0.15)
                reader.feed_eof()

            asyncio.create_task(feed_eof_later())

            await handle_ssl_client(reader, writer_transport)

            # Nginx should receive the HTTP GET request
            self.assertIn(b"GET / HTTP/1.1", bytes(received_by_nginx))
            # Client should receive Nginx's HTTP response
            self.assertIn(b"HTTP/1.1 200 OK", bytes(client_written))
        finally:
            server.close()
            await server.wait_closed()
            globals_dict["CHAMELEON_NGINX_PORT"] = orig_nginx_port

    async def test_raw_passthrough_half_close_xhttp_streaming(self):
        """When an XHTTP client sends request and closes upload side (half-close),
        Chameleon must NOT cancel the download pipe and must deliver the full response."""
        received_by_backend = bytearray()

        async def xhttp_backend_handler(b_reader, b_writer):
            while True:
                chunk = await b_reader.read(4096)
                if not chunk:
                    break
                received_by_backend.extend(chunk)
            # Send response after client has half-closed its upload
            await asyncio.sleep(0.05)
            b_writer.write(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\npong\r\n0\r\n\r\n")
            await b_writer.drain()
            b_writer.close()
            await b_writer.wait_closed()

        server = await asyncio.start_server(xhttp_backend_handler, "127.0.0.1", 0)
        backend_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_nginx_port = globals_dict["CHAMELEON_NGINX_PORT"]
        globals_dict["CHAMELEON_NGINX_PORT"] = backend_port

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            client_written = bytearray()
            writer_transport.write = lambda d: client_written.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.can_write_eof = lambda: True
            writer_transport.write_eof = mock.MagicMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54324) if info == "peername" else None

            # Feed request and immediately close upload side (half-close)
            reader.feed_data(b"POST /vless-xhttp HTTP/1.1\r\nHost: downloads.vodafone.co.uk\r\n\r\nping")
            reader.feed_eof()

            await handle_ssl_client(reader, writer_transport)

            # Backend should have received the client request
            self.assertIn(b"POST /vless-xhttp", bytes(received_by_backend))
            # Client must have received the full 200 OK response despite upload half-close
            self.assertIn(b"HTTP/1.1 200 OK", bytes(client_written))
            self.assertIn(b"pong", bytes(client_written))
        finally:
            server.close()
            await server.wait_closed()
            globals_dict["CHAMELEON_NGINX_PORT"] = orig_nginx_port

    async def test_ssl_direct_tbrutal(self):
        """When client connects with T-Brutal binary framing (TB\\x01...),
        handle_ssl_client routes raw stream directly to CHAMELEON_TBRUTAL_PORT."""
        received_by_tbrutal = bytearray()

        async def tbrutal_daemon_handler(t_reader, t_writer):
            # Send mock pong
            t_writer.write(b"TB\x01\x06\x00\x00\x00\x00\x00\x00")
            await t_writer.drain()
            while True:
                chunk = await t_reader.read(4096)
                if not chunk:
                    break
                received_by_tbrutal.extend(chunk)
            t_writer.close()
            await t_writer.wait_closed()

        server = await asyncio.start_server(tbrutal_daemon_handler, "127.0.0.1", 0)
        tbrutal_port = server.sockets[0].getsockname()[1]
        globals_dict = chameleon["handle_client"].__globals__
        orig_tbrutal_port = globals_dict["CHAMELEON_TBRUTAL_PORT"]
        globals_dict["CHAMELEON_TBRUTAL_PORT"] = tbrutal_port

        try:
            reader = asyncio.StreamReader()
            writer_transport = asyncio.StreamWriter(
                transport=mock.MagicMock(),
                protocol=asyncio.StreamReaderProtocol(reader),
                reader=reader,
                loop=asyncio.get_running_loop()
            )
            client_written = bytearray()
            writer_transport.write = lambda d: client_written.extend(d)
            writer_transport.wait_closed = mock.AsyncMock()
            writer_transport.can_write_eof = lambda: True
            writer_transport.write_eof = mock.MagicMock()
            writer_transport.get_extra_info = lambda info: ("127.0.0.1", 54325) if info == "peername" else None

            # Feed raw T-Brutal ping frame (TB\x01\x05...)
            reader.feed_data(b"TB\x01\x05\x00\x00\x00\x00\x00\x00")

            async def feed_eof_later():
                await asyncio.sleep(0.08)
                reader.feed_eof()

            asyncio.create_task(feed_eof_later())

            await handle_ssl_client(reader, writer_transport)

            # T-Brutal daemon should receive the raw ping frame
            self.assertTrue(received_by_tbrutal.startswith(b"TB\x01\x05"))
            # Client should receive T-Brutal's pong
            self.assertTrue(bytes(client_written).startswith(b"TB\x01\x06"))
        finally:
            server.close()
            await server.wait_closed()
            globals_dict["CHAMELEON_TBRUTAL_PORT"] = orig_tbrutal_port


if __name__ == "__main__":
    unittest.main()
