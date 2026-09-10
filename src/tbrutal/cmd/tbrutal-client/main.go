package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/mwright228/my/src/tbrutal/client"
)

func main() {
	serverAddr := flag.String("s", "", "Server address host:port (e.g. 212.60.151.69:443)")
	sni := flag.String("sni", "images.vodafone.co.uk", "Carrier bug-host SNI (e.g. images.vodafone.co.uk)")
	hostHeader := flag.String("host", "", "HTTP Host header (defaults to server host or SNI)")
	path := flag.String("path", "/tbrutal", "HTTP upgrade path (e.g. /tbrutal; empty string enables direct raw SNI mode)")
	raw := flag.Bool("raw", false, "Use direct raw TCP/TLS SNI mode without HTTP upgrade (like SSH SSL SNI)")
	token := flag.String("token", "", "User authentication UUID / token")
	localSocks := flag.String("l", "127.0.0.1:10808", "Local SOCKS5 listen address")
	numConns := flag.Int("p", 1, "Number of concurrent pooled TCP connections (default: 1 for mobile stability)")
	rateMbps := flag.Int("rate", 0, "Brutal pacing rate in Mbps (0 = native unthrottled wire-speed with BBR)")
	useTLS := flag.Bool("tls", true, "Use TLS for server connection (set to false for plaintext/port 80)")
	insecure := flag.Bool("insecure", true, "Skip TLS verification for carrier bug-host SNIs")
	flag.Parse()

	if *serverAddr == "" || *token == "" {
		fmt.Println("Usage: tbrutal-client -s <SERVER:PORT> -token <UUID> [options]")
		fmt.Println("\nOptions:")
		flag.PrintDefaults()
		os.Exit(1)
	}

	isRaw := *raw || *path == ""

	cli := client.NewClient(client.Config{
		ServerAddr:     *serverAddr,
		SNI:            *sni,
		HostHeader:     *hostHeader,
		Path:           *path,
		Token:          *token,
		LocalSocksAddr: *localSocks,
		NumConns:       *numConns,
		RateMbps:       *rateMbps,
		UseTLS:         *useTLS,
		InsecureTLS:    *insecure,
		RawMode:        isRaw,
	})

	log.Printf("[*] Launching T-Brutal Client...")
	if err := cli.Start(); err != nil {
		log.Fatalf("[!] Failed to start T-Brutal client: %v", err)
	}

	log.Printf("[+] SOCKS5 ready at %s -> %s (SNI: %s, %d pooled connections, %d Mbps Brutal rate)",
		*localSocks, *serverAddr, *sni, *numConns, *rateMbps)

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	<-stopChan
	log.Printf("[*] Stopping T-Brutal client...")
	cli.Stop()
	log.Printf("[*] T-Brutal client stopped.")
}
