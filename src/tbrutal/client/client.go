package client

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"time"

	"github.com/mwright228/my/src/tbrutal/pacer"
	"github.com/mwright228/my/src/tbrutal/protocol"
)

type Config struct {
	ServerAddr     string
	SNI            string
	HostHeader     string
	Path           string
	Token          string
	LocalSocksAddr string
	NumConns       int
	RateMbps       int
	UseTLS         bool
	InsecureTLS    bool
	RawMode        bool
}

type Client struct {
	cfg      Config
	pacer    *pacer.Pacer
	pool     *Pool
	listener net.Listener
}

func NewClient(cfg Config) *Client {
	if cfg.LocalSocksAddr == "" {
		cfg.LocalSocksAddr = "127.0.0.1:10808"
	}
	if cfg.NumConns <= 0 {
		cfg.NumConns = 4
	}
	if cfg.Path == "" && !cfg.RawMode {
		cfg.Path = "/tbrutal"
	}

	p := pacer.NewPacer(cfg.RateMbps)
	pool := NewPool(cfg.ServerAddr, cfg.SNI, cfg.HostHeader, cfg.Path, cfg.Token, cfg.NumConns, cfg.UseTLS, cfg.InsecureTLS, cfg.RawMode, p)

	return &Client{
		cfg:   cfg,
		pacer: p,
		pool:  pool,
	}
}

func (c *Client) Start() error {
	log.Printf("[*] Connecting T-Brutal pool to %s (SNI: %s, Conns: %d, Rate: %d Mbps)...",
		c.cfg.ServerAddr, c.cfg.SNI, c.cfg.NumConns, c.cfg.RateMbps)

	if err := c.pool.Start(); err != nil {
		return fmt.Errorf("failed to initialize connection pool: %w", err)
	}

	ln, err := net.Listen("tcp", c.cfg.LocalSocksAddr)
	if err != nil {
		c.pool.Close()
		return fmt.Errorf("failed to listen on local socks5 address %s: %w", c.cfg.LocalSocksAddr, err)
	}
	c.listener = ln

	log.Printf("[*] T-Brutal SOCKS5 proxy listening on %s (Ready for Android / VPN clients)", c.cfg.LocalSocksAddr)

	go func() {
		for {
			conn, err := c.listener.Accept()
			if err != nil {
				return
			}
			go c.handleSocks5(conn)
		}
	}()

	return nil
}

func (c *Client) ListenerAddr() string {
	if c.listener != nil {
		return c.listener.Addr().String()
	}
	return c.cfg.LocalSocksAddr
}

func (c *Client) Stop() {
	if c.listener != nil {
		_ = c.listener.Close()
	}
	if c.pool != nil {
		c.pool.Close()
	}
}

func (c *Client) handleSocks5(conn net.Conn) {
	defer conn.Close()

	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}

	// 1. Negotiate SOCKS5 version and auth methods
	var verAuth [2]byte
	if _, err := io.ReadFull(conn, verAuth[:]); err != nil {
		return
	}
	if verAuth[0] != 0x05 {
		return // SOCKS5 only
	}

	nMethods := int(verAuth[1])
	methods := make([]byte, nMethods)
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}

	// Reply: No authentication required (0x00)
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	// 2. Read SOCKS5 Request
	var reqHdr [4]byte
	if _, err := io.ReadFull(conn, reqHdr[:]); err != nil {
		return
	}
	if reqHdr[0] != 0x05 || reqHdr[1] != 0x01 {
		// Only CONNECT (0x01) supported
		_, _ = conn.Write([]byte{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Command not supported
		return
	}

	atyp := reqHdr[3]
	var targetHost string
	switch atyp {
	case 0x01: // IPv4
		var ip [4]byte
		if _, err := io.ReadFull(conn, ip[:]); err != nil {
			return
		}
		targetHost = net.IP(ip[:]).String()
	case 0x03: // Domain
		var dLen [1]byte
		if _, err := io.ReadFull(conn, dLen[:]); err != nil {
			return
		}
		domainBytes := make([]byte, dLen[0])
		if _, err := io.ReadFull(conn, domainBytes); err != nil {
			return
		}
		targetHost = string(domainBytes)
	case 0x04: // IPv6
		var ip [16]byte
		if _, err := io.ReadFull(conn, ip[:]); err != nil {
			return
		}
		targetHost = net.IP(ip[:]).String()
	default:
		_, _ = conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Address type not supported
		return
	}

	var portBuf [2]byte
	if _, err := io.ReadFull(conn, portBuf[:]); err != nil {
		return
	}
	targetPort := binary.BigEndian.Uint16(portBuf[:])

	// 3. Register stream in pool
	st, err := c.pool.RegisterStream(conn)
	if err != nil {
		_, _ = conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // General SOCKS server failure
		return
	}
	defer c.pool.UnregisterStream(st.id)

	// 4. Send CmdConnect frame to server
	connectPayload, err := protocol.EncodeConnectPayload(c.cfg.Token, atyp, targetHost, targetPort)
	if err != nil {
		_, _ = conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	connectFrame, err := protocol.NewFrame(protocol.CmdConnect, st.id, connectPayload)
	if err != nil {
		_, _ = conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	if err := st.pConn.sendFrame(connectFrame); err != nil {
		_, _ = conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Host unreachable
		return
	}

	// 5. Wait for Connect Response
	select {
	case status := <-st.respChan:
		if status != protocol.RespSuccess {
			_, _ = conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Connection refused
			return
		}
	case <-time.After(12 * time.Second):
		_, _ = conn.Write([]byte{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}) // Host unreachable
		return
	}

	// 6. Send SOCKS5 Success Response
	if _, err := conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); err != nil {
		return
	}

	// 7. Pipe client data to server connection with Brutal pacing
	buf := make([]byte, 32*1024)
	for {
		if st.closed.Load() {
			return
		}

		n, err := conn.Read(buf)
		if n > 0 {
			c.pacer.Wait(n)
			dataFrame, fErr := protocol.NewFrame(protocol.CmdData, st.id, buf[:n])
			if fErr != nil {
				return
			}
			if err := st.pConn.sendFrame(dataFrame); err != nil {
				return
			}
		}

		if err != nil {
			return
		}
	}
}
