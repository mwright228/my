package bridge

import (
	"os"
	"path/filepath"
	"regexp"
	"testing"
)

// This file enforces the contract the app depends on: every protocol the
// installer publishes a link for, and every protocol identity the Android app
// can send over JNI, must be claimed by exactly one engine. It is the
// regression lock for the bug class where a protocol silently came up as the
// wrong engine (SSH_PAYLOAD built a VLESS outbound because it contains "SS"),
// or as no engine at all (AMNEZIA_WG fell through to T-Brutal).

// scriptSchemes maps a link scheme printed by lib/subscribe.sh to the protocol
// identities that must be able to speak it.
var scriptSchemes = map[string][]string{
	"hysteria2": {"HYSTERIA_2"},
	"vless":     {"VLESS_WS", "VLESS_HTTPUPGRADE", "VLESS_XHTTP", "VLESS_GRPC"},
	"vmess":     {"VMESS_WS"},
	"trojan":    {"TROJAN_WS"},
	"ss":        {"SHADOWSOCKS_2022", "SHADOWTLS_V3"},
	"tuic":      {"TUIC"},
	"zivpn":     {"ZIVPN_UDP"},
	"tbrutal":   {"T_BRUTAL"},
	"http":      {"CHAMELEON_HTTP"},
}

// uncoveredSchemes are link schemes the installer publishes that no client
// engine can speak yet. Each entry is a known, tracked gap; the test fails for
// any scheme that is neither in scriptSchemes nor listed here, so this list can
// only shrink deliberately and a new script protocol cannot ship unhandled.
var uncoveredSchemes = map[string]string{
	"socks5": "MUBX-T-Brutal-BugHost prints a loopback SOCKS address with no dialable endpoint",
}

// uncoveredProtocols are app-side protocol identities with no engine. The test
// fails for any new identity that is not dispatchable and not listed here.
var uncoveredProtocols = map[string]string{
	"AMNEZIA_WG": "AmneziaWG has no client engine; the identity is present in the app enum only",
}

// nonLinkSchemes are printf literals in lib/subscribe.sh that are outputs rather
// than dialable client links.
var nonLinkSchemes = map[string]string{
	"https": "the subscription URL printed by mubx_sub_generate, not a client link",
}

func repoFile(t *testing.T, parts ...string) string {
	t.Helper()
	path := filepath.Join(append([]string{"..", "..", ".."}, parts...)...)
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("expected repository file %s: %v", path, err)
	}
	return path
}

// TestEngineDispatchIsExact pins the protocol identity -> engine mapping.
func TestEngineDispatchIsExact(t *testing.T) {
	cases := []struct {
		protocol string
		want     engineKind
	}{
		{"ZIVPN_UDP", engineZiVPN},
		{"zivpn", engineZiVPN},
		{"SSH_PAYLOAD", engineUniversal},
		{"T_BRUTAL", engineTBrutal},
		{"", engineTBrutal},
		{"CHAMELEON_HTTP", engineUniversal},
		{"VLESS_WS", engineSingBox},
		{"VLESS_TCP", engineSingBox},
		{"VLESS_HTTPUPGRADE", engineSingBox},
		{"VLESS_XHTTP", engineSingBox},
		{"VLESS_GRPC", engineSingBox},
		{"VMESS_WS", engineSingBox},
		{"TROJAN_WS", engineSingBox},
		{"TUIC", engineSingBox},
		{"HYSTERIA_2", engineSingBox},
		{"SHADOWSOCKS_2022", engineSingBox},
		{"SHADOWTLS_V3", engineSingBox},
	}

	for _, tc := range cases {
		got, err := engineFor(tc.protocol)
		if err != nil {
			t.Errorf("engineFor(%q) returned error: %v", tc.protocol, err)
			continue
		}
		if got != tc.want {
			t.Errorf("engineFor(%q) = %d, want %d", tc.protocol, got, tc.want)
		}
	}

	// SSH must never be captured by the Shadowsocks/SS-matchable branch.
	if got, err := engineFor("SSH_PAYLOAD"); err != nil || got != engineUniversal {
		t.Errorf("SSH_PAYLOAD must select the SSH engine, got engine=%d err=%v", got, err)
	}
}

// TestUnsupportedProtocolIsRejected locks in that an unknown protocol fails
// loudly instead of silently forming a T-Brutal tunnel.
func TestUnsupportedProtocolIsRejected(t *testing.T) {
	for _, protocol := range []string{"AMNEZIA_WG", "WIREGUARD", "OPENVPN", "REALITY", "NOT_A_PROTOCOL"} {
		if _, err := engineFor(protocol); err == nil {
			t.Errorf("engineFor(%q) must be rejected, got no error", protocol)
		}
	}
}

var printfSchemeRe = regexp.MustCompile(`printf '([a-z0-9]+)://`)

// TestScriptSchemesAreAccountedFor reads lib/subscribe.sh and requires every
// link scheme it can print to be either dispatchable or a documented gap.
func TestScriptSchemesAreAccountedFor(t *testing.T) {
	source, err := os.ReadFile(repoFile(t, "lib", "subscribe.sh"))
	if err != nil {
		t.Fatalf("read subscribe.sh: %v", err)
	}

	found := map[string]bool{}
	for _, m := range printfSchemeRe.FindAllStringSubmatch(string(source), -1) {
		found[m[1]] = true
	}
	if len(found) == 0 {
		t.Fatal("no link schemes found in lib/subscribe.sh; the parser or the script layout changed")
	}

	for scheme := range found {
		if _, ok := nonLinkSchemes[scheme]; ok {
			continue
		}
		if _, ok := uncoveredSchemes[scheme]; ok {
			continue
		}
		identities, ok := scriptSchemes[scheme]
		if !ok {
			t.Errorf("lib/subscribe.sh publishes %s:// links but no engine mapping is declared for that scheme", scheme)
			continue
		}
		for _, identity := range identities {
			if _, err := engineFor(identity); err != nil {
				t.Errorf("%s:// links need protocol %q, but engineFor rejects it: %v", scheme, identity, err)
			}
		}
	}

	for scheme := range uncoveredSchemes {
		if !found[scheme] {
			t.Errorf("uncoveredSchemes lists %q but lib/subscribe.sh no longer prints it; remove the entry", scheme)
		}
	}
	for scheme := range scriptSchemes {
		if !found[scheme] {
			t.Errorf("scriptSchemes maps %q but lib/subscribe.sh no longer prints it; remove the entry", scheme)
		}
	}
}

var protocolEnumRe = regexp.MustCompile(`(?m)^\s{4}([A-Z][A-Z0-9_]*)\("`)

// TestAppProtocolEnumIsDispatchable reads the app's ProtocolType enum and
// requires every identity the app can send over JNI to reach an engine. A new
// enum member without an engine fails here instead of failing on a user's phone.
func TestAppProtocolEnumIsDispatchable(t *testing.T) {
	source, err := os.ReadFile(repoFile(t,
		"android", "app", "src", "main", "java", "id", "my", "mub", "data", "Models.kt"))
	if err != nil {
		t.Fatalf("read Models.kt: %v", err)
	}

	members := protocolEnumRe.FindAllStringSubmatch(string(source), -1)
	if len(members) == 0 {
		t.Fatal("no ProtocolType members found in Models.kt; the enum layout changed")
	}

	for _, m := range members {
		name := m[1]
		if _, ok := uncoveredProtocols[name]; ok {
			continue
		}
		if _, err := engineFor(name); err != nil {
			t.Errorf("ProtocolType.%s has no engine; add one or document it in uncoveredProtocols: %v", name, err)
		}
	}
}
