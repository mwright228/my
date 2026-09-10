package main

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/mwright228/my/src/tbrutal/server"
)

func main() {
	listenAddr := flag.String("listen", "127.0.0.1:18999", "Listen address for T-Brutal HTTP upgrade")
	usersFile := flag.String("users", "/etc/mubx/users.json", "Path to users.json credentials file")
	rateMbps := flag.Int("rate", 0, "Userspace Brutal pacing rate in Mbps (0 = uncapped wire speed)")
	flag.Parse()

	log.Printf("[*] Starting MUB-X T-Brutal Engine...")
	srv := server.NewServer(server.Config{
		ListenAddr: *listenAddr,
		UsersFile:  *usersFile,
		RateMbps:   *rateMbps,
	})

	stopChan := make(chan os.Signal, 1)
	signal.Notify(stopChan, os.Interrupt, syscall.SIGTERM)

	go func() {
		if err := srv.Start(); err != nil {
			log.Fatalf("[!] Server exited with error: %v", err)
		}
	}()

	<-stopChan
	log.Printf("[*] Shutting down T-Brutal server gracefully...")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Stop(ctx)
	log.Printf("[*] T-Brutal server stopped.")
}
